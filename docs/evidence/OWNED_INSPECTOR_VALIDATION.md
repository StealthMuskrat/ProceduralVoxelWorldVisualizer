# One owned Inspector job for World picks and coordinate traces

Validated implementation: `59066fc62f9a135bb778d99acc37b61fb8346e1a`.

[Actions run 40532386268](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40532386268) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Raw Linux JUnit reports **771 tests across 97 suites, zero failures/errors/skips**; see [summary](owned-inspector-junit.json). The renamed task suite retains its previous cases and adds two request-kind/late-running-completion regressions.

The [raw format-4 report](owned-inspector-native.json) records bounded replacement, Cancel Pick, actual queue rejection/retry at (3,0,0) with occupied final type 1, camera/tab cancellation and retained Logs. Existing proxy, coordinate and draft workflows still pass; the [saved fixture](owned-inspector-project.json) is retained for independent replay. Both platform logs include INSPECTOR_WORLD_TASK_PASS.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schema families/fixtures, Python syntax, independent replay of downloaded native reports and diff checks passed.

World picks previously used untracked editor Runnables. Publication gates rejected obsolete results, but superseded picks could fill the queue and continue evaluation. Both World picks and explicit coordinate traces now use the same replaceable InspectorTask Future slot. New requests cancel/purge queued predecessors across request kinds. The Inspector shows pending pick feedback and Cancel Pick, while coordinate requests keep their existing X/Y/Z and Cancel Trace feedback.

XYZ edits, restore, Inspector closure and viewport closure reclaim either request kind. Camera/tab/preview changes cancel view-bound picks; explicit coordinates remain navigation independent. A validity check before and after viewport drawing also cancels picks whose CPU/GPU scene or drawn-version map changed. Completion first releases only its owning slot, then verifies the captured publication guards; stale completion cannot clear a newer request. Validity closures are released on completion, cancellation and closure. Queue/evaluation failures retain the accepted trace and provide an explicit retry.

The package-private CoordinateTraceTask helper/test have been renamed InspectorTask/InspectorTaskTest and generalized to allow unknown or known World-pick targets. Existing lifecycle tests remain; two additional cases cover cross-kind replacement and a running predecessor that ignores interruption but cannot finish the newer slot. Running cancellation remains best-effort interruption, not hard preemption of engine work.

The native World/LOD/coordinate fixture keeps all prior semantic and retry checks. Its draft/close cases now assert queued World Futures are reclaimed instead of deliberately allowing untracked work to run. Additional actual image/button/wheel interactions with held editor workers check:

- Five repeated World clicks retain exactly one queued successor.
- Pending pick feedback and the actual Cancel Pick button release the queue without replacing the trace or Logs.
- A genuinely full 8-slot queue rejects a pick without leaving pending state; an explicit retry selects (3,0,0) with occupied final type 1.
- Camera wheel and active-tab changes reclaim queued pick work; no canceled request publishes another trace or Logs payload.

The existing coordinate workflow still verifies navigation independence, edited-input cancellation, bounded replacement, real evaluation failure, full-queue recovery and authored-state invariance. Python independently checks the reported coordinate/type and all lifecycle observations alongside the saved project's proxy/stage arithmetic.

This is scoped native GL/ImGui/editor-executor and engine evidence. Arbitrary scheduling/pressure/closure timing, hard preemption, physical input, all GUI/DPI/load cases and Windows 11 hardware acceptance remain separate. No engine API, schema, archive, project format or exact generation semantics change.
