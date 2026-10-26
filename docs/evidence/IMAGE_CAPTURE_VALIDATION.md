# Captured 2D image pan validation

Validated implementation: `9a287c633478251713d4ecec70989f7ab835aea9`.

[Actions run 40417843740](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40417843740) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; schemas and diff checks passed locally.

Raw Linux JUnit remains 722 tests across 90 suites with zero failures, errors or skips; see [JUnit summary](image-capture-junit.json). This batch expands native event contracts rather than duplicating arithmetic unit tests.

The production handler now owns a per-view gesture starting only with an image press. Movement is computed from the original pose and pointer, continues outside the image, and ends on release. Escape restores the baseline and does not adopt a still-held button. Wheel events during capture and foreign drags entering the image are ignored. External pose replacement, tab departure and selected-source removal reset capture. Input routing executes outside hover only for the 2D handler; hover sampling stays within the image.

NoiseImageInputProbe injects native ImGui events through that same handler. In addition to previous anchor/pan/release/saturation checks, it presses outside and enters the image, captures an inside press and moves beyond the top/right boundary, injects a held wheel event, restores with Escape, moves while still held, and starts a fresh drag after release. Native assertions verify each transition and unchanged project/revision/history. See [raw format-2 observations](image-capture-native.json). Independent Python recomputes both outside-pan coordinates from the recorded baseline, pointer displacement and image dimensions with 1e-10 absolute tolerance. IMAGE_CAPTURE_PYTHON_PASS and the extended IMAGE_INPUT_NATIVE_PASS identify the checks.

These are shared-handler/native-ImGui surface observations, alongside existing viewport GL smokes and exact image/engine references. Physical mouse/OS-window capture, all popup/focus/DPI transitions, Windows 11 hardware and complete A27 acceptance remain pending.
