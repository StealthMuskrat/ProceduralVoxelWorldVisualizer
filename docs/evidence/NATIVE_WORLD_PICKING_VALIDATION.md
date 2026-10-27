# Native World picking and inspector publication lifetime

Validated implementation: `de54f954ccb9e4c46811a65cafe2c70c3b6683d1`.

[Actions run 40510374553](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40510374553) passed Linux and Windows builds/native workflows, packaged Windows launcher and cross-platform reproducibility using Temurin 25.0.4+7 and Gradle 9.2.1. Both jobs execute `clean test build dependencyNotices releaseJars`. Raw Linux JUnit totals are **755 tests across 95 suites, zero failures/errors/skips**; see [JUnit summary](world-picking-junit.json) and [native observations](world-picking-native.json). Both platform logs contain WORLD_PICKING_NATIVE_PASS and WORLD_PICKING_INDEPENDENT_PASS.

Local schema families/fixtures, Python syntax, independent checks against the downloaded native report and `git diff --check` passed. Required local Gradle execution was attempted but Java cannot load `libjli.so` (exit 127); pinned CI is the build/native authority.

World picks previously guarded only the latest inspector request and project revision. A mode, camera or tab change could let a queued hit, miss or error reopen the Inspector for an obsolete view. View-bound tickets now expire when the World preview epoch, active tab or camera pose changes. World request changes also clear the previous picked bookmark. Closing Viewports rejects all later inspector publications. Explicit coordinate traces stay independent of camera/tab movement; a newer inspector request, document revision or closure still supersedes them. Current EMPTY misses clear the picked bookmark.

InspectorRequestsTest adds five regressions: newest-intent ordering across both request kinds; irreversible view invalidation; coordinate tracing across view changes; document revision rejection; and idempotent closure rejecting queued publications/new work.

WorldPickingProbe runs the production Viewports World tab in native GLFW/OpenGL/ImGui. It injects actual image mouse press/release events into a fixture with a constant 3D field and SET followed by CLEAR. The engine generates and picks the selected stage, while the resulting full trace correctly reports final EMPTY.

- After SET and Before CLEAR: an 8-cube centered at X=0.25 picks the visible positive-X face at (3,0,0), rather than an occupied cell near the camera.
- After CLEAR: the actual World image click misses and clears the picked bookmark.
- Only Changes at CLEAR: a 3-cube centered at X=-0.25 picks its removal ghost at (0,0,0), checking odd extents and fractional/negative lower bounds.
- Renderer startup failure: a World stream switches to its fixed fallback, releases its streaming resources and an image click picks the fixed displayed face at (3,0,0).
- Busy-worker mode and zoom changes: both editor workers are held by latches, a real image click queues a pick, then the view changes before workers resume. The obsolete result cannot replace the retained trace or reopen the Inspector.
- Closure: a similarly queued pick finishes after Viewports.close and a UI-queue drain; no Inspector publication occurs.

The probe verifies unchanged project JSON, revision and history. Python independently computes each positive-X cube face from floor(centerX-side/2)+side-1 and checks observed coordinates, final EMPTY values and the lifecycle flags. The workflow runs this fixture on Linux and Windows and retains its raw report.

This is scoped native ImGui event and production engine evidence under CI/software GL. It does not certify physical OS-window input, every camera path, DPI/load cases, streamed picks against unloaded/display-LOD regions, every Inspector control, Windows 11 hardware or full A01–A36 acceptance.
