# Native shared 2D image input validation

Validated implementation: `e9aaf08bcfd4851b6a3250f45d0e7d271e1a13a4`.

[Actions run 40416295127](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40416295127) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 722 tests across 90 suites with zero failures, errors or skips; see [JUnit summary](image-input-junit.json). This batch adds a native event probe rather than duplicating the existing navigation arithmetic tests.

NoiseImageInput is the shared production ImGui routing for the viewport and probe. The probe creates an actual native ImGui context with an image-sized surface, injects pointer/wheel/button events, verifies off-center cursor anchoring, reverse zoom, exact 32/-18 pixel pan, release termination, ignored outside wheel input and no dirty-work count at minimum scale. Project JSON, revision and history size remain unchanged. The shared handler uses double normalized coordinates/deltas. See [raw native observations](image-input-native.json).

Independent Python recomputes zoom scale, both world-anchor equations and both pan distances from the recorded poses with 1e-10 absolute coordinate tolerance (1e-12 for scale). The native and Python markers are IMAGE_INPUT_NATIVE_PASS and IMAGE_INPUT_PYTHON_PASS. Both platform workflows run the probe and retain reports. Existing viewport GL/native smokes, exact row/overlap digests and 524,288 engine-only replay remain in the same workflow.

This is production shared-handler evidence on an ImGui surface. It is not a complete viewport texture/OS-window interaction audit, physical mouse test, outside-boundary captured drag, arbitrary DPI matrix or Windows 11 hardware acceptance. Complete A27 and production sign-off remain separate.
