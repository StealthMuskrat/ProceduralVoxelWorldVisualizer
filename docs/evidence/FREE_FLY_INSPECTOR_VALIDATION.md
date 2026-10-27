# Free-fly entry, Inspector handoff and capture ownership

Validated implementation: `39deddcbe9067f848aa784b062ce0a34aedcfff4`.

[Actions run 40542227385](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40542227385) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Both platform logs contain FREE_FLY_INSPECTOR_NATIVE_PASS and FREE_FLY_INSPECTOR_PASS. Raw Linux JUnit reports **778 tests across 99 suites, zero failures/errors/skips**; see [summary](free-fly-junit.json). Four new state-machine cases cover Inspector suspension, eligible closure, ineligible closure followed by focus return, explicit resume, lost eligibility/tab suspension and exit from every state.

World previously reused its Ctrl-click entry as a pick in the same frame, immediately exiting free-fly. Inspector picking also converted the camera to orbit. Flight intent now remains separate from native cursor ownership. Entry explicitly focuses the clicked viewport and is consumed once. A subsequent World click releases capture, freezes mouse look/movement and opens the Inspector immediately while retaining the free-fly pose/mode. Closure resumes only from an eligible focused viewport; otherwise Ctrl-click explicitly resumes the paused state. Focus/tab/image/popup changes pause capture, and viewport closure releases it. See [interaction contract](../FREE_FLY_AND_INSPECTOR.md).

The [raw native report](free-fly-native.json) and [saved project](free-fly-project.json) record an isolated constant-solid fixed preview. The fixture uses actual ImGui image, key, XYZ and Inspector-close events, native ImGui focus requests, controlled tab selection and GLFW cursor-mode queries:

- Ctrl-click enters from another focused window without opening the Inspector or changing the camera pose. The cursor is disabled and the camera stays in free-fly after button release.
- Four 1/60-second W frames move X from 20.25 to approximately 18.25, preserving Y/Z and orientation. Independent Python recomputes this expected displacement and the occupied center-ray hit at (3,0,0), final type 1.
- The subsequent pick releases the cursor but retains the exact free-fly pose/mode. Held W and actual XYZ editing to (7,0,0) cannot move that camera while the Inspector is active.
- The real Inspector X button closes it without stealing capture. Another native ImGui window's focus request pauses capture, and Escape there does not exit the paused camera.
- Selecting another tab and returning to World retain the paused pose without capture. Ctrl-click resumes explicitly. Escape in the captured viewport returns to orbit with the same eye/orientation.
- Closing Viewports while captured restores the normal native cursor and clears flight mode. All navigation/input leaves authored JSON, revision and Undo history unchanged.

The independent script also verifies the saved SET layer and constant source, finite camera values, exact coordinate observations and all lifecycle flags. Prior fixed/streamed/LOD picking, owned Inspector job, coordinate draft, queue recovery and historical freshness workflows still pass.

Initial validation caught incorrect metadata in the new fixture. The native entry check then exposed ImGui's deferred window focus: a hovered Ctrl-click was valid before Visualizers became focused at frame end. Entry now explicitly transfers that focus. Assertions were retained, with modifier events in separate input frames and diagnostic guard state available on entry failure.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schemas/fixtures, formatting, Python syntax, diff checks and independent replay of downloaded native reports passed. Pinned CI supplies compilation, automated/native tests and packaging evidence.

This is scoped native GL/ImGui/GLFW and editor evidence. Controlled tab/focus transitions are not a physical OS focus-loss or input-device/DPI matrix. Full GUI and Windows 11 hardware acceptance remain separate. No engine API, generation semantics, schema, archive or project-format changes.
