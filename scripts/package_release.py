"""Deterministic Development distribution; assembly does not execute or certify inputs."""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
import zipfile


STAGE_NAME = "ProceduralVoxelCreator-Development"
ARCHIVE_NAME = STAGE_NAME + "-Windows-x64.zip"
REPORT_MODULES = (
    "project-model", "engine-core", "engine-api", "voxel-storage", "editor-render", "editor-ui"
)
EXAMPLE_SUPPORT_FILES = ("build.gradle", "settings.gradle", "README.md")
EXAMPLE_COMMAND = (
    '@echo off\r\nsetlocal\r\npushd "%~dp0" || exit /b 1\r\n'
    'if not exist classes mkdir classes\r\n'
    'if errorlevel 1 (popd & exit /b 1)\r\n'
    'javac -cp "..\\..\\jars\\noise-generator-engine.jar" -d classes *.java\r\n'
    'if errorlevel 1 (popd & exit /b 1)\r\n'
    'java -cp "classes;..\\..\\jars\\noise-generator-engine.jar" '
    'io.voxelcreator.example.Example output\r\n'
    'set "PVC_EXAMPLE_EXIT=%ERRORLEVEL%"\r\npopd\r\nexit /b %PVC_EXAMPLE_EXIT%\r\n'
).encode("ascii")


class PackageRecoveryError(RuntimeError):
    """A failed publication could not completely restore the previous output."""


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def regular_file(path: Path) -> None:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"Expected a regular file, without a symbolic link: {path}")


def ordered_files(directory: Path) -> list[Path]:
    """One explicit POSIX string order on every host; never follow directory links."""
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError(f"Expected a directory, without a symbolic link: {directory}")
    def traversal_failed(error: OSError) -> None:
        raise error

    files = []
    for parent, directories, names in os.walk(
            directory, followlinks=False, onerror=traversal_failed):
        parent = Path(parent)
        for name in directories:
            if (parent / name).is_symlink():
                raise ValueError(f"Symbolic directory link is not a package input: {parent / name}")
        for name in names:
            path = parent / name
            regular_file(path)
            files.append(path)
    return sorted(files, key=lambda path: path.relative_to(directory).as_posix())


def copy_file(source: Path, target: Path) -> None:
    regular_file(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def copy_tree(source: Path, target: Path) -> None:
    if target.resolve().is_relative_to(source.resolve()):
        raise ValueError(f"Package output cannot be inside its input: {source}")
    for path in ordered_files(source):
        copy_file(path, target / path.relative_to(source))


def tracked_files(root: Path) -> list[Path]:
    names = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode("utf-8").split("\0")
    result = []
    for name in sorted(filter(None, names)):
        relative = PurePosixPath(name)
        if relative.is_absolute() or ".." in relative.parts or "\\" in name:
            raise ValueError(f"Unsafe tracked source path: {name}")
        path = root.joinpath(*relative.parts)
        # A regular file below a symlinked ancestor is also outside the exact source tree.
        if path.resolve() != path.absolute():
            raise ValueError(f"Symbolic source path is not a package input: {name}")
        regular_file(path)
        result.append(path)
    return result


def write_manifest(stage: Path, target: Path, files: list[Path]) -> None:
    lines = [f"{digest(path)}  {path.relative_to(stage).as_posix()}\n" for path in files]
    target.write_bytes("".join(lines).encode("utf-8"))


def assemble(root: Path, runtime: Path, notices: Path | None, stage: Path) -> None:
    for name in ("ProceduralVoxelCreator.jar", "noise-generator-engine.jar"):
        copy_file(root / "build/release/jars" / name, stage / "jars" / name)
    copy_tree(runtime, stage / "windows/runtime")
    copy_tree(root / "desktop-package/windows/launcher", stage / "windows/launcher")
    for name in ("docs", "fixtures"):
        copy_tree(root / name, stage / name)
    if notices is not None:
        copy_tree(notices, stage / "docs/mesa-notices")
    # Only tracked source; no checkout caches, build outputs or untracked local files.
    for source in tracked_files(root):
        copy_file(source, stage / "source" / source.relative_to(root))
    example = stage / "examples/independent-engine-client"
    example.mkdir(parents=True)
    sources = sorted((root / "example-client/src/main/java/io/voxelcreator/example").glob("*.java"),
                     key=lambda path: path.name)
    if not sources:
        raise ValueError("Independent engine example sources are required")
    for source in sources:
        copy_file(source, example / source.name)
    for name in EXAMPLE_SUPPORT_FILES:
        copy_file(root / "example-client/standalone" / name, example / name)
    (example / "run.cmd").write_bytes(EXAMPLE_COMMAND)
    for module in REPORT_MODULES:
        reports = root / module / "build/test-results/test"
        if reports.exists():
            copy_tree(reports, stage / "test-reports" / module)
    smoke = root / "build/smoke"
    if smoke.exists():
        # Retain supplied nested smoke evidence as well as top-level screenshots/logs.
        copy_tree(smoke, stage / "test-reports/native-smoke")
    windows_log = root / "build/windows-smoke.log"
    if windows_log.exists():
        copy_file(windows_log, stage / "test-reports/windows-smoke.log")
    binaries = ordered_files(stage / "windows/runtime")
    write_manifest(stage, stage / "BUNDLED_BINARIES_SHA256SUMS.txt", binaries)
    write_manifest(stage, stage / "SHA256SUMS.txt", ordered_files(stage))


def write_archive(stage: Path, target: Path) -> None:
    # Stored entries avoid a host/zlib-version-dependent compression stream. This deliberately
    # trades ZIP size for deterministic bytes without requiring an external compressor.
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for path in ordered_files(stage):
            name = stage.name + "/" + path.relative_to(stage).as_posix()
            info = zipfile.ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.create_version = info.extract_version = 20
            info.external_attr = (0o100755 if path.name == "gradlew" else 0o100644) << 16
            info.compress_type = zipfile.ZIP_STORED
            # Stream binaries instead of holding a runtime/JAR and compressed copy in memory.
            info.file_size = path.stat().st_size
            with path.open("rb") as source, archive.open(info, "w") as member:
                shutil.copyfileobj(source, member, length=1024 * 1024)


def publish(output: Path, work: Path, names: tuple[str, ...]) -> None:
    """Rollback ordinary rename failures; retain backups if restoration itself fails."""
    backed_up, installed = [], []
    try:
        for name in names:
            target = output / name
            if target.exists():
                os.replace(target, work / (name + ".previous"))
                backed_up.append(name)
        for name in names:
            os.replace(work / name, output / name)
            installed.append(name)
    except BaseException as failure:
        restoration_errors = []
        for name in reversed(installed):
            try:
                os.replace(output / name, work / name)
            except OSError as error:
                restoration_errors.append(error)
        for name in reversed(backed_up):
            try:
                os.replace(work / (name + ".previous"), output / name)
            except OSError as error:
                restoration_errors.append(error)
        if restoration_errors:
            recovery = PackageRecoveryError(
                f"Package restoration incomplete; recovery files retained at {work}")
            for error in restoration_errors:
                recovery.add_note(str(error))
            raise recovery from failure
        raise


def package(root: Path, runtime: Path, output: Path, notices: Path | None = None) -> Path:
    root, runtime, output = root.resolve(), runtime.resolve(), output.resolve()
    notices = notices.resolve() if notices is not None else None
    # Missing mandatory inputs fail before touching any prior successful output.
    regular_file(runtime / "bin/java.exe")
    for name in ("ProceduralVoxelCreator.jar", "noise-generator-engine.jar"):
        regular_file(root / "build/release/jars" / name)
    for directory in (runtime, root / "docs", root / "fixtures",
                      root / "desktop-package/windows/launcher", *((notices,) if notices else ())):
        if output.is_relative_to(directory):
            raise ValueError(f"Package output cannot be inside its input: {directory}")
        ordered_files(directory)
    names = (STAGE_NAME, ARCHIVE_NAME, ARCHIVE_NAME + ".sha256")
    for name in names:
        target = output / name
        expected_type = target.is_dir() if name == STAGE_NAME else target.is_file()
        if target.is_symlink() or (target.exists() and not expected_type):
            raise ValueError(f"Unexpected existing package output: {target}")
        if (root.is_relative_to(target) or runtime.is_relative_to(target)
                or (notices is not None and notices.is_relative_to(target))):
            raise ValueError(f"Package input cannot be replaced by its output: {target}")
    output.mkdir(parents=True, exist_ok=True)
    lock = output / ".package-release.lock"
    descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    work = None
    preserve_work = False
    try:
        work = Path(tempfile.mkdtemp(prefix=".package-release-", dir=output))
        stage = work / STAGE_NAME
        stage.mkdir()
        assemble(root, runtime, notices, stage)
        archive = work / ARCHIVE_NAME
        write_archive(stage, archive)
        (work / (ARCHIVE_NAME + ".sha256")).write_bytes(
            f"{digest(archive)}  {ARCHIVE_NAME}\n".encode("ascii"))
        publish(output, work, names)
        return output / ARCHIVE_NAME
    except PackageRecoveryError:
        preserve_work = True
        raise
    finally:
        # Once publication succeeds, cleanup cannot turn the completed package into failure.
        # On failed rollback, preserve every recovery copy rather than deleting the old package.
        if work is not None and not preserve_work:
            shutil.rmtree(work, ignore_errors=True)
        try:
            lock.unlink()
        except OSError:
            pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, required=True)
    parser.add_argument("--mesa-notices", type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/packages"))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    print(package(root, args.runtime, root / args.output, args.mesa_notices))


if __name__ == "__main__":
    main()
