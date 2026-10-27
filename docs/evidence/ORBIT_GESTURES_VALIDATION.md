# Owned orbit gestures and click arbitration

Validated implementation: `6690530b82ba44b4cee97c59d9435391cf65855f`.

[Actions run 40546689221](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40546689221) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Both platform logs contain ORBIT_INPUT_NATIVE_PASS and ORBIT_INPUT_PASS. Raw Linux JUnit reports **786 tests across 100 suites, zero failures/errors/skips**; see [summary](orbit-input-junit.json). Eight new regressions cover independent camera arithmetic, frame-path invariance through pitch clamps, final-release displacement, press-time middle axes, inclusive click bounds, sticky out-and-back classification, Escape rollback, external navigation/reset and free-fly exclusions.

Orbit input previously used hover-only incremental mouse deltas and unowned held buttons. Right panning also shared the automatic context-menu trigger. The production 3D/World image now owns each press as an ImGui item and an OrbitGesture. Movement uses the original pose and total pointer displacement, continues outside the image, and cannot move the containing window. Releases become picks/context menus only within the short-click movement/time bounds. Focus/tab/popup/external-navigation cancellation cannot resume a held predecessor. See [gesture contract](../ORBIT_GESTURES.md).

The [raw native report](orbit-input-native.json) and [saved project](orbit-project.json) retain an isolated constant-solid fixed World fixture. Native image/button/key input, native ImGui focus requests, controlled tab selection and the real editor executor check:

- A left orbit continues beyond the image with total displacement (-490,35), producing yaw 5.4907963267948965 and pitch 0.28. Its release submits no Inspector pick.
- A right pan continues outside with displacement (-490,-25), moving focus from (0.25,0.25,0.25) to approximately (1,0.25,-14.45), with no context menu. A wheel event during the owned pan cannot change distance.
- A short right click opens the real menu; Escape closes it. A 24-frame held right press does not open it. Left and right drags returning to their starting points remain drags and trigger neither pick nor menu.
- A left press begun outside is never adopted. Escape restores an owned orbit's original pose and suppresses movement/release actions until a fresh press.
- Focus loss/return and tab changes retain the last valid pose and cancel held input. Their later releases do not become clicks.
- Middle X/Z movement and Ctrl+middle Y movement match independent expected vectors. Releasing Ctrl during the latter gesture does not switch axes.
- A fresh short left click recovers picking at (3,0,0), occupied final type 1. Authored JSON, revision and Undo history remain unchanged throughout.

Independent Python recomputes all four reported pose transformations using scalar trigonometry and the saved source/layer semantics, then checks exact hit coordinates and lifecycle flags. Existing fixed/streamed/LOD picking, Inspector job ownership, coordinate draft/freshness and free-fly handoff checks still pass. The preview-admission and World native fixtures now show/focus their GLFW windows so production navigation eligibility is tested without bypassing its application-focus guard.

Initial native validation found the viewport menu did not close on Escape with the application's existing ImGui configuration. Production context menus now explicitly close on that key; the assertion was retained.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schemas/fixtures, formatting, Python syntax, diff checks and independent replay of downloaded native reports passed. Pinned CI supplies compilation, automated/native tests and packaging evidence.

This is scoped native GL/ImGui/editor evidence. Controlled focus/tab changes are not a physical OS focus-loss/input-device/DPI matrix. Full GUI and Windows 11 hardware acceptance remain separate. No generation, engine API, schema, archive or project-format changes.
