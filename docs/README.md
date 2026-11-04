# Procedural Voxel Creator documentation

This is a development implementation. The current package is named Development until the required acceptance and release checks are completed. Packaging supplied files does not execute or certify them.

Start with [Build and run](BUILD_AND_RUN.md) for the pinned Java toolchain, Windows 11 x64/OpenGL requirements, bundled launcher and independent engine example. In the distribution, the desktop entry point is `windows/launcher/ProceduralVoxelCreator.cmd` relative to the package root; the engine example is `examples/independent-engine-client/run.cmd` and requires the documented JDK.

- [Indexed folder validation](FOLDER_VALIDATION_GRAPH.md) describes hierarchy checks, exact errors and deferred coverage.
- [Indexed resource transfer](INDEXED_RESOURCE_TRANSFER.md) describes dependency selection, conflict lookup and deferred coverage.
- [Resource navigation reveal](RESOURCE_NAVIGATION_REVEAL.md) describes browser reveal after accepted navigation.
- [Application Help](HELP_AND_ABOUT.md) describes editor workflows and controls.
- [Atomic archive publication](ARCHIVE_ATOMIC_PUBLICATION.md) describes replacement requirements and retry/abort ownership.
- [Finite interpolation](FINITE_INTERPOLATION.md) describes guarded BLEND/lerp arithmetic and deferred coverage.
- [Exact regeneration rate](REGENERATION_RATE_CONTROL.md) describes paired live preference input and retained invalid text.
- [Background resource export](BACKGROUND_RESOURCE_EXPORT.md) describes cancellable reviewed staging and atomic publication.
- [Background resource import](BACKGROUND_RESOURCE_IMPORT.md) describes cancellable package preparation and guarded review installation.
- [Project Save ownership](PROJECT_SAVE_OWNERSHIP.md) describes snapshot validation and checkpoint receipt publication.
- [Document transaction ownership](DOCUMENT_TRANSACTION_OWNERSHIP.md) describes stale callback/history publication checks.
- [API](API.md) describes the independent headless engine.
- [Specification](SPECIFICATION.md) defines the complete assignment.
- [Completion roadmap](DEVELOPMENT_ROADMAP.md) lists remaining development and final delivery work.
- [Requirements and evidence](REQUIREMENTS_AND_TESTS.md) distinguishes observed results from deferred definitions.
- [Engineering decisions](DECISIONS.md) records contracts and limitations.
- [Licenses and notices](LICENSES.md) records dependency notices and owner-source licensing status.
- [Deterministic development packaging](DETERMINISTIC_PACKAGING.md) documents checksum files, publication recovery and deferred verification.

The owner's testing pause remains active. Historical test evidence is not certification of subsequent uncompiled changes or Windows 11 hardware acceptance.
