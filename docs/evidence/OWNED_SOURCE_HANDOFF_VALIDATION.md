# Owned streaming source handoff validation

Validated implementation: `e187aa0f91f190df854aa3c2596de9b2eec2f1d5`.

[Actions run 40498744868](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40498744868) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit contains 742 tests across 93 suites with zero failures, errors or skips; see [JUnit summary](owned-source-junit.json). Seven OwnedPreviewMailboxTest cases check ownership transfer, replacement/invalidation cleanup, stale results after a consumed current slot, late completion after close without any UI drain, cleanup outside the monitor, contained cleanup failure and repeated close/invalidation without duplicate disposal.

Streaming compilation no longer captures owned source objects in UI-queue callbacks. A one-slot versioned handoff owns pending SceneSetup values. Beginning/invalidation/close detaches and disposes pending values; rejected late publication disposes on the publishing worker. Taking a value transfers ownership to the render-thread streaming scene. Queued obsolete work checks its version before compilation. Palette preparation precedes source construction, keeping that potential failure before source ownership begins.

The close race test blocks a worker before publication, closes the handoff and releases the worker; the source receives exactly one close without draining any UI queue. The cleanup-lock test blocks a disposer and concurrently begins/publishes/takes a new source, demonstrating cleanup does not retain the mailbox monitor. Already taken sources are not closed by mailbox shutdown: the scene/caller owns them. Ordinary close exceptions are contained so a discarded resource cannot strand a newer source; interruption from a close is preserved. This does not guarantee successful cleanup from a resource whose own close fails or forcibly preempt arbitrary compilation.

Production stop invalidates the handoff before cancelling compiler work, and viewport close closes it before scene cleanup. Fixed-preview ownership and unrelated UI task queues are unchanged. The existing native probe still passes real streaming generation, clear/delete CPU-GPU release and reselect/Undo recovery, alongside World/editor/rendering/input/engine references and cross-platform packaging checks.

These are deterministic ownership/race tests plus native regression evidence, not every live WorldContext shutdown interleaving, operating-system scheduling/load/DPI scenario or Windows 11 hardware certification. Complete specification acceptance and production sign-off remain separate.
