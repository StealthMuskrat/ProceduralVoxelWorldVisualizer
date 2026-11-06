"""Deferred packaging contracts. Do not run during the owner testing pause."""
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import package_release as packaging


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "repository"
        self.runtime = self.base / "runtime"
        self.output = self.base / "output"
        self.tracked = []
        for name, content in {
            "build/release/jars/ProceduralVoxelCreator.jar": b"desktop-binary\0",
            "build/release/jars/noise-generator-engine.jar": b"engine-binary\0",
            "desktop-package/windows/launcher/ProceduralVoxelCreator.cmd": b"@echo off\n",
            "docs/README.md": b"development\n",
            "docs/z.txt": b"lowercase\n",
            "docs/caf\u00e9.txt": b"unicode-path\n",
            "docs/A.txt": b"uppercase\n",
            "fixtures/example.json": b"{}\n",
            "example-client/src/main/java/io/voxelcreator/example/Example.java": b"class Example {}\n",
            "example-client/standalone/build.gradle": b"standalone-build\n",
            "example-client/standalone/settings.gradle": b"standalone-settings\n",
            "example-client/standalone/README.md": b"standalone-instructions\n",
            "gradlew": b"#!/bin/sh\n",
        }.items():
            target = self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            if not name.startswith("build/"):
                self.tracked.append(target)
        self.write_runtime("bin/java.exe", b"supplied-java\0")
        self.write_runtime("bin/Z.dll", b"native-Z\0")
        self.write_runtime("bin/a.dll", b"native-a\0")

    def write_runtime(self, name, content):
        target = self.runtime / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)

    def build(self):
        with patch.object(packaging, "tracked_files", return_value=list(reversed(self.tracked))):
            return packaging.package(self.root, self.runtime, self.output)

    def snapshot(self):
        return {p.relative_to(self.output).as_posix(): p.read_bytes()
                for p in self.output.rglob("*") if p.is_file()}

    def test_repeated_assembly_is_byte_identical_despite_input_times_and_modes(self):
        archive = self.build()
        original = archive.read_bytes()
        for root in (self.root, self.runtime):
            for path in root.rglob("*"):
                if path.is_file():
                    os.utime(path, (1_700_000_000, 1_700_000_000))
                    path.chmod(0o700)
        self.assertEqual(original, self.build().read_bytes())

    def test_manifest_uses_exact_utf8_lf_and_posix_lexical_order(self):
        self.build()
        stage = self.output / packaging.STAGE_NAME
        manifest = (stage / "SHA256SUMS.txt").read_bytes()
        self.assertNotIn(b"\r", manifest)
        entries = [line.split("  ", 1) for line in manifest.decode("utf-8").splitlines()]
        names = [name for _, name in entries]
        self.assertEqual(sorted(names), names)
        self.assertLess(names.index("docs/A.txt"), names.index("docs/z.txt"))
        self.assertNotIn("SHA256SUMS.txt", names)
        self.assertEqual(set(names), {p.relative_to(stage).as_posix()
            for p in stage.rglob("*") if p.is_file()} - {"SHA256SUMS.txt"})
        for checksum, name in entries:
            self.assertEqual(hashlib.sha256((stage / name).read_bytes()).hexdigest(), checksum)

    def test_zip_metadata_is_host_independent_and_members_are_stored(self):
        archive = self.build()
        with zipfile.ZipFile(archive) as package:
            members = package.infolist()
            self.assertEqual(sorted(m.filename for m in members), [m.filename for m in members])
            for member in members:
                self.assertEqual(3, member.create_system)
                self.assertEqual((2020, 1, 1, 0, 0, 0), member.date_time)
                self.assertEqual(zipfile.ZIP_STORED, member.compress_type)
                mode = 0o100755 if member.filename.endswith("/gradlew") else 0o100644
                self.assertEqual(mode, member.external_attr >> 16)
            stage = self.output / packaging.STAGE_NAME
            for member in members:
                relative = member.filename.removeprefix(packaging.STAGE_NAME + "/")
                self.assertEqual((stage / relative).read_bytes(), package.read(member))

    def test_archive_checksum_matches_exact_binary(self):
        archive = self.build()
        expected = hashlib.sha256(archive.read_bytes()).hexdigest() + "  " + archive.name + "\n"
        self.assertEqual(expected.encode("ascii"), archive.with_suffix(".zip.sha256").read_bytes())

    def test_binary_inventory_is_separate_and_complete(self):
        self.build()
        stage = self.output / packaging.STAGE_NAME
        entries = (stage / "BUNDLED_BINARIES_SHA256SUMS.txt").read_text().splitlines()
        self.assertEqual(["windows/runtime/bin/Z.dll", "windows/runtime/bin/a.dll",
                          "windows/runtime/bin/java.exe"], [s.split("  ", 1)[1] for s in entries])
        for line in entries:
            checksum, name = line.split("  ", 1)
            self.assertEqual(packaging.digest(stage / name), checksum)

    def test_example_command_owns_its_directory_and_uses_single_crlf(self):
        self.build()
        command = (self.output / packaging.STAGE_NAME /
                   "examples/independent-engine-client/run.cmd").read_bytes()
        self.assertIn(b'pushd "%~dp0"', command)
        self.assertNotIn(b"\r\r\n", command)
        self.assertNotIn(b"\n", command.replace(b"\r\n", b""))
        self.assertIn(b"exit /b %PVC_EXAMPLE_EXIT%\r\n", command)

    def test_project_model_and_nested_smoke_reports_are_preserved(self):
        reports = self.root / "project-model/build/test-results/test/TEST-owned.xml"
        reports.parent.mkdir(parents=True)
        reports.write_bytes(b"observed-report")
        smoke = self.root / "build/smoke/nested/frame.png"
        smoke.parent.mkdir(parents=True)
        smoke.write_bytes(b"observed-frame")
        self.build()
        stage = self.output / packaging.STAGE_NAME
        self.assertEqual(b"observed-report", (stage / "test-reports/project-model/TEST-owned.xml").read_bytes())
        self.assertEqual(b"observed-frame", (stage / "test-reports/native-smoke/nested/frame.png").read_bytes())

    def test_standalone_example_support_is_copied_exactly_beside_its_java_sources(self):
        archive = self.build()
        relative = Path("examples/independent-engine-client")
        stage = self.output / packaging.STAGE_NAME
        with zipfile.ZipFile(archive) as package:
            for name in ("build.gradle", "settings.gradle", "README.md"):
                expected = (self.root / "example-client/standalone" / name).read_bytes()
                self.assertEqual(expected, (stage / relative / name).read_bytes())
                member = f"{packaging.STAGE_NAME}/{relative.as_posix()}/{name}"
                self.assertEqual(expected, package.read(member))
        self.assertEqual(b"class Example {}\n", (stage / relative / "Example.java").read_bytes())

    def test_each_missing_example_support_file_preserves_previous_outputs(self):
        self.build()
        before = self.snapshot()
        for name in ("build.gradle", "settings.gradle", "README.md"):
            source = self.root / "example-client/standalone" / name
            content = source.read_bytes()
            source.unlink()
            try:
                with self.assertRaises(ValueError):
                    self.build()
                self.assertEqual(before, self.snapshot(), name)
            finally:
                source.write_bytes(content)

    @unittest.skipUnless(hasattr(os, "symlink"), "Symbolic links unavailable")
    def test_symlinked_example_support_is_rejected_before_replacing_outputs(self):
        self.build()
        before = self.snapshot()
        source = self.root / "example-client/standalone/build.gradle"
        content = source.read_bytes()
        source.unlink()
        outside = self.base / "outside.gradle"
        outside.write_bytes(b"outside-build\n")
        try:
            try:
                source.symlink_to(outside)
            except OSError as unavailable:
                self.skipTest(f"Symbolic links unavailable: {unavailable}")
            with self.assertRaises(ValueError):
                self.build()
            self.assertEqual(before, self.snapshot())
        finally:
            if source.is_symlink():
                source.unlink()
            source.write_bytes(content)

    def test_missing_input_keeps_all_previous_outputs(self):
        self.build()
        before = self.snapshot()
        (self.root / "build/release/jars/noise-generator-engine.jar").unlink()
        with self.assertRaises(ValueError):
            self.build()
        self.assertEqual(before, self.snapshot())

    def test_assembly_failure_keeps_previous_outputs_and_cleans_private_files(self):
        self.build()
        before = self.snapshot()
        with patch.object(packaging, "write_archive", side_effect=OSError("archive failed")):
            with self.assertRaisesRegex(OSError, "archive failed"):
                self.build()
        self.assertEqual(before, self.snapshot())
        self.assertFalse(list(self.output.glob(".package-release*")))

    def test_each_publication_rename_failure_restores_previous_outputs(self):
        self.build()
        before = self.snapshot()
        original_replace = os.replace
        for fail_at in range(1, 7):
            calls = 0

            def replace(source, target):
                nonlocal calls
                calls += 1
                if calls == fail_at:
                    raise OSError("publication failed")
                return original_replace(source, target)

            with patch.object(packaging.os, "replace", side_effect=replace):
                with self.assertRaisesRegex(OSError, "publication failed"):
                    self.build()
            self.assertEqual(before, self.snapshot(), fail_at)
            self.assertFalse(list(self.output.glob(".package-release*")))

    def test_failed_restoration_retains_recovery_files_and_primary_cause(self):
        self.build()
        original_replace = os.replace
        calls = 0

        def replace(source, target):
            nonlocal calls
            calls += 1
            if calls >= 5:
                raise OSError("rename fault")
            return original_replace(source, target)

        with patch.object(packaging.os, "replace", side_effect=replace):
            with self.assertRaises(packaging.PackageRecoveryError) as raised:
                self.build()
        self.assertIsInstance(raised.exception.__cause__, OSError)
        work = list(self.output.glob(".package-release-*"))
        self.assertEqual(1, len(work))
        self.assertTrue(list(work[0].glob("*.previous")))
        self.assertIn(str(work[0]), str(raised.exception))
        self.assertFalse((self.output / ".package-release.lock").exists())

    def test_existing_assembler_lock_is_never_removed_or_overridden(self):
        self.output.mkdir()
        lock = self.output / ".package-release.lock"
        lock.write_bytes(b"other owner")
        with self.assertRaises(FileExistsError):
            self.build()
        self.assertEqual(b"other owner", lock.read_bytes())

    def test_output_inside_runtime_rejects_before_recursive_copy(self):
        self.output = self.runtime / "packages"
        with self.assertRaisesRegex(ValueError, "inside its input"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_runtime_under_replaced_stage_is_rejected(self):
        self.runtime = self.output / packaging.STAGE_NAME / "windows/runtime"
        self.write_runtime("bin/java.exe", b"previous")
        with self.assertRaisesRegex(ValueError, "replaced by its output"):
            self.build()
        self.assertEqual(b"previous", (self.runtime / "bin/java.exe").read_bytes())

    def test_source_inventory_rejects_missing_tracked_file_and_excludes_untracked(self):
        existing = self.root / "gradlew"
        with patch.object(packaging.subprocess, "check_output", return_value=b"gradlew\0"):
            self.assertEqual([existing], packaging.tracked_files(self.root))
            existing.unlink()
            with self.assertRaises(ValueError):
                packaging.tracked_files(self.root)

    def test_runtime_file_link_is_rejected_without_copying_its_target(self):
        outside = self.base / "outside-secret"
        outside.write_bytes(b"must not package")
        try:
            (self.runtime / "bin/linked.dll").symlink_to(outside)
        except OSError as error:
            self.skipTest(f"Host does not permit creating symbolic links: {error}")
        with self.assertRaisesRegex(ValueError, "symbolic link"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_runtime_directory_link_is_rejected_without_traversal(self):
        outside = self.base / "outside-directory"
        outside.mkdir()
        (outside / "secret").write_bytes(b"must not package")
        try:
            (self.runtime / "linked").symlink_to(outside, target_is_directory=True)
        except OSError as error:
            self.skipTest(f"Host does not permit creating symbolic links: {error}")
        with self.assertRaisesRegex(ValueError, "directory link"):
            self.build()
        self.assertFalse(self.output.exists())

    def test_source_inventory_rejects_escaping_path(self):
        for name in (b"../outside\0", b"/outside\0", b"folder\\outside\0"):
            with patch.object(packaging.subprocess, "check_output", return_value=name):
                with self.assertRaises(ValueError):
                    packaging.tracked_files(self.root)


if __name__ == "__main__":
    unittest.main()
