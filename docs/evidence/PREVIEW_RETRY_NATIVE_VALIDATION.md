# Native production viewport admission and retry

Validated implementation: `21b1004bf76b3226d527ce3547646121d0c66221`.

[Actions run 40423624991](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40423624991) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 734 tests across 92 suites with zero failures, errors or skips; see [JUnit summary](preview-retry-junit.json). This batch adds native integration evidence rather than duplicate admission unit tests.

PreviewAdmissionProbe creates a hidden GLFW/OpenGL context and native ImGui context, then draws the production Viewports controls. It creates a constant 0.25 field and waits for real async image publication. It lowers the cell budget below 65,536, clicks toolbar Regenerate, observes budget rejection and verifies the same accepted image remains. Raising the budget alone stays blocked. It right-clicks the image, selects context-menu Regenerate and waits for a fresh accepted image.

The probe then occupies both real editor executor workers with latch-controlled tasks and fills all eight queue slots. A toolbar click reaches actual submission rejection; the queue message, retained image and other views' unblocked admission state are asserted. After releasing/draining those tasks, context-menu retry publishes another fresh image. A second budget rejection initiated through the context menu recovers using the toolbar. Both retry paths therefore execute production controls, mark/request logic, admission and async publication.

Project JSON, document revision and history size remain unchanged; final center sampling is exactly 0.25. Python checks the structured report, real configured worker/queue counts, observed failure reasons and analytical constant output. See [raw native report](preview-retry-native.json). PREVIEW_ADMISSION_NATIVE_PASS and PREVIEW_ADMISSION_PYTHON_PASS identify the checks. Both platform workflows run the probe; Linux uses software GL under Xvfb, Windows uses its bundled runtime/software GL setup.

Other views' admission flags are observed, not simultaneous 3D generation under load. These are injected native ImGui controls and a bounded controlled queue scenario, not physical OS-window/mouse interaction, arbitrary scheduler load/DPI transitions or Windows 11 hardware certification. Complete acceptance and production sign-off remain pending.
