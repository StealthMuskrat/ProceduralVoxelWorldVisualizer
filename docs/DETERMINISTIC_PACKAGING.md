# Deterministic development packaging — unvalidated source

This implementation supports specification 22.8.1–22.8.8 and A34–A36. It does not build, launch, test or certify a distribution. The owner execution pause remains active; no assembler, contract test, checker, compiler, launcher or workflow was run for this batch. The last observed validated baseline remains 922 tests / 120 suites.

## Assembly and integrity

The existing command and Development output names remain:

```sh
python scripts/package_release.py --runtime build/windows-runtime --mesa-notices build/mesa-notices --output build/packages
```

Supply the already verified Windows runtime/native inputs and built desktop/engine JARs when final validation resumes. The assembler does not download anything or infer that supplied reports certify those binaries. The package remains named ProceduralVoxelCreator-Development, with the existing Windows-x64 ZIP and adjacent .zip.sha256 file. The required final Release designation still depends on complete acceptance and owner notice review.

All archive member and checksum ordering uses explicit relative POSIX strings, including mixed case and Unicode names. ZIP timestamps, Unix creator metadata, regular/executable permission bits and member format are fixed independently of filesystem timestamps or host permissions. ZIP members use ZIP_STORED, deliberately trading a larger download for deterministic bytes without a platform-dependent zlib compression stream. Binary copying, ZIP writing and hashing stream bounded blocks rather than retaining entire JAR/runtime files in memory. Identical complete input file bytes/names produce identical package content; changed supplied reports or binaries are different inputs. No cross-platform byte comparison has been observed for this change.

Generated checksum files are exact UTF-8/ASCII with LF bytes. The independent example's generated run.cmd has exactly one CRLF per line, owns its directory through pushd, quotes its engine classpath and propagates the Java exit status after restoring the caller directory. A JDK remains necessary to compile this separate example; the bundled desktop runtime is not presented as a compiler. Repository text attributes now request LF on every host, avoiding checkout-dependent source/document bytes; this does not rewrite opaque binary inputs.

SHA256SUMS.txt covers every staged file except itself, including the separate BUNDLED_BINARIES_SHA256SUMS.txt. The latter inventories each actual supplied windows/runtime file, including native DLLs placed there by the existing pipeline. It records bundled file checksums, not upstream download provenance or platform acceptance. The ZIP's adjacent checksum covers the completed archive. Existing upstream dependency/download checksums remain separately authoritative.

The package contains one tracked source tree. Missing tracked files, escaping names and symbolic links below copied input trees reject instead of silently omitting source or following outside files. Optional supplied test evidence is copied byte-for-byte: project-model now joins engine/core/storage/render/UI reports, nested smoke output is retained, and the Windows smoke log remains optional. No passing report is synthesized. docs/README.md supplies a documentation entry point with direct relative links and clear development status.

## Publication and recovery

Mandatory inputs and incompatible output/input nesting are checked before replacing a previous successful package. Assembly, manifests and the complete ZIP/checksum are prepared in a private sibling directory. An exclusive .package-release.lock prevents two cooperative assemblers from publishing into the same output concurrently. An existing lock is never silently removed by a new invocation. After an interrupted process, establish that its owner has exited before removing a stale lock.

Publication first saves previous stage/archive/checksum paths in the private directory, then installs the completed replacements. Ordinary rename failures attempt to restore every previous output. If restoration also fails, PackageRecoveryError preserves the publication failure as its cause and identifies the retained private directory; its .previous copies must remain available for manual recovery. Successful publication ignores best-effort temporary/lock cleanup errors. A retained lock may therefore require the same explicit owner check before another attempt.

The stage, ZIP and checksum are three filesystem entries, so this is not a crash-atomic multi-file filesystem transaction. Sudden power loss or termination can leave the named entries between rename steps; inspect the private recovery directory and checksums before using them. Network/shared filesystems, independent noncooperating writers and files changing during assembly remain outside this local assembler protocol. The original input directories are never chosen as replacement targets.

## Deferred definitions

Eighteen standard-library unittest definitions in scripts/test_package_release.py cover repeated byte identity despite source timestamps/permissions, exact manifest coverage/order/UTF-8/LF, ZIP metadata and entry bytes, archive checksum, separate runtime inventory, exact CRLF/example directory ownership, project-model/nested evidence, absent inputs, failed ZIP assembly, all six publication rename failure positions, failed restoration retaining backups/cause, competing locks, recursive/replaced input paths, tracked inventory completeness, escaping names and symbolic file/directory rejection. Link cases explicitly skip on a host lacking link-creation permission; a skip is not evidence of link acceptance.

At the final execution stage, run from the repository root:

```sh
python -m unittest discover -s scripts -p test_package_release.py
```

Then assemble twice from identical complete inputs and compare ZIP/checksum bytes on the pinned Linux and Windows environments, inspect checksums, run the packaged Windows launcher and separate engine example, and complete the full required Windows 11/hardware/release acceptance. Existing manual-only packaging/smoke commands still reference the same output paths; no workflow trigger or dispatch is added. All new definitions remain unexecuted.
