# Procedural Voxel Creator documentation

This is a development implementation. The current package is named Development until the required acceptance and release checks are completed. Packaging supplied files does not execute or certify them.

Start with [Build and run](BUILD_AND_RUN.md) for the pinned Java toolchain, Windows 11 x64/OpenGL requirements, bundled launcher and independent engine example. In the distribution, the desktop entry point is `windows/launcher/ProceduralVoxelCreator.cmd` relative to the package root; the engine example is `examples/independent-engine-client/run.cmd` and requires the documented JDK.

- [Indexed folder validation](FOLDER_VALIDATION_GRAPH.md) describes hierarchy checks, exact errors and deferred coverage.
- [Indexed resource transfer](INDEXED_RESOURCE_TRANSFER.md) describes dependency selection, conflict lookup and deferred coverage.
- [Resource navigation reveal](RESOURCE_NAVIGATION_REVEAL.md) describes browser reveal after accepted navigation.
- [Application Help](HELP_AND_ABOUT.md) describes editor workflows and controls.
- [Atomic archive publication](ARCHIVE_ATOMIC_PUBLICATION.md) describes replacement requirements and retry/abort ownership.
- [Finite affine mapping](FINITE_MAPPING.md) describes guarded Remap/expression range recovery and deferred coverage.
- [Finite Linear curves and Extend](CURVE_LINEAR_EXTENSION.md) describes exceptional endpoint/tangent arithmetic and deferred coverage.
- [Finite interpolation](FINITE_INTERPOLATION.md) describes guarded BLEND/lerp arithmetic and deferred coverage.
- [Exact regeneration rate](REGENERATION_RATE_CONTROL.md) describes paired live preference input and retained invalid text.
- [Background resource export](BACKGROUND_RESOURCE_EXPORT.md) describes cancellable reviewed staging and atomic publication.
- [Background export planning](BACKGROUND_EXPORT_PLANNING.md) describes cancellable catalog/closure preparation and fresh Included review.
- [Background resource import](BACKGROUND_RESOURCE_IMPORT.md) describes cancellable package preparation and guarded review installation.
- [Background final Import](BACKGROUND_IMPORT_APPLY.md) describes cancellable candidate validation/Undo preparation, exact reviewed-plan ownership and separate private input.
- [Background import planning](BACKGROUND_IMPORT_PLANNING.md) describes cancellable choice/reload rebuilds and explicit fresh review.
- [Project Save ownership](PROJECT_SAVE_OWNERSHIP.md) describes snapshot validation and checkpoint receipt publication.
- [Document transaction ownership](DOCUMENT_TRANSACTION_OWNERSHIP.md) describes stale callback/history publication checks.
- [API](API.md) describes the independent headless engine.
- [Background Properties Apply](BACKGROUND_PROPERTIES_APPLY.md) describes prepared edits, cancellation and guarded history publication.
- [Background Application Profile Apply](BACKGROUND_APPLICATION_PROFILE.md) describes cancellable metadata validation/staging and atomic activation.
- [Background Curve Apply](BACKGROUND_CURVE_APPLY.md) describes cancellable final curve preparation and completion-ready private publication.
- [Curve Editor reuse](CURVE_EDITOR_REUSE.md) describes bounded canonical definition reuse and exact immutable plot observations.
- [Background structural noise edits](BACKGROUND_NOISE_EDITS.md) describes cancellable initial/choice planning, one-action completion and explicit private resolution.
- [Background Expression feedback](BACKGROUND_EXPRESSION_FEEDBACK.md) describes coalesced parser work, current-source spans, explicit retry and independent private staging.
- [Background Apply Members](BACKGROUND_MEMBERSHIP_APPLY.md) describes cancellable set/group preparation and retained private Properties input.
- [Background resource actions](BACKGROUND_RESOURCE_ACTIONS.md) describes staged private validation, cancellable command preparation and consumed completion.
- [Background browser selection Apply](BACKGROUND_BROWSER_SELECTION.md) describes cancellable tree/range/empty/manager preparation and owned sibling plans.
- [Background Apply before navigation](BACKGROUND_NAVIGATION_APPLY.md) describes cancellable source/Find/reference preparation and guarded destination checks.
- [Background Project Settings Apply](BACKGROUND_PROJECT_SETTINGS.md) describes exact raw input, cancellable preparation and independent Properties retention.
- [Background project history](BACKGROUND_PROJECT_HISTORY.md) describes cancellable Undo/Redo preparation and deferred private discard.
- [Specification](SPECIFICATION.md) defines the complete assignment.
- [Completion roadmap](DEVELOPMENT_ROADMAP.md) lists remaining development and final delivery work.
- [Requirements and evidence](REQUIREMENTS_AND_TESTS.md) distinguishes observed results from deferred definitions.
- [Engineering decisions](DECISIONS.md) records contracts and limitations.
- [Licenses and notices](LICENSES.md) records dependency notices and owner-source licensing status.
- [Deterministic development packaging](DETERMINISTIC_PACKAGING.md) documents checksum files, publication recovery and deferred verification.

The owner's testing pause remains active. Historical test evidence is not certification of subsequent uncompiled changes or Windows 11 hardware acceptance.
