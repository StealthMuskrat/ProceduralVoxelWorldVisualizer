# Bookmark/navigation batch validation

Observed 2026-10-25, implementation head `ea9fa25bcd2ecfa7650ed5ce2196eebaeeb09657`. Documentation/evidence was recorded after the implementation passed.

[GitHub Actions run 40225218645](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40225218645) completed successfully: Linux, Windows and binary reproducibility jobs. Pinned toolchain: Temurin 25.0.4+7, Gradle 9.2.1. Raw test reports and native output are in that run's platform artifacts. The Linux XML reports were independently counted: **428 tests / 62 suites / 0 failures / 0 errors / 0 skipped**.

- `clean test build dependencyNotices releaseJars`: PASS on both platforms.
- Formal schema family and new positive/negative bookmark coordinate fixtures: PASS locally and in CI.
- Existing independent engine consumers, exact voxel hashes and native desktop/render/input references: PASS.
- `BookmarkReferenceProbe`: PASS on both platforms, using actual mouse/text/Escape events against the production toolbar and dialogs. Checks include duplicate-name UUID selection, picked Add, Rename, Update, Delete, one-transaction Undo/Redo, tab-owned dialogs, navigation invariance and save/reopen.
- `BookmarkConsumer`: PASS with only the engine JAR. Independent persisted coordinate checks, constant .625 samples and EMPTY negative chunks at roots 2, 4 and 8 remain exact after bookmark edits.
- `verify_bookmarks.py`: PASS for schema, coordinate-only shape, separate tab membership, globally unique UUIDs and exact expected coordinates.
- Windows development package and packaged launcher smoke: PASS. Full Windows 11 hardware certification remains pending.
- Linux/Windows desktop and engine JAR comparison: PASS, byte-identical.

The explicit 2,000-bookmark native fixture submitted 11 unfiltered rows and one filtered row; duplicate-name search retained two distinct UUID rows. [Raw native report](bookmarks-reference.json). The existing desktop smoke screenshot was inspected and the added toolbar remained readable and reachable.

Local Java restoration encountered truncated archive data, so the full Java/native checks above were executed in CI. Initial validation found and repaired a test generic-type error and the selector's missing popup-close action; the final run passed all dependent checks. This evidence covers the stated scope, not the entire A01-A36 GUI/hardware acceptance matrix. See [bookmark contracts](../BOOKMARKS_AND_NAVIGATION.md).
