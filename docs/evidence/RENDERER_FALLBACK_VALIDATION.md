# Streaming renderer failure and fixed fallback

Validated implementation: `b12bcbdac52c7d304817c275f593caec85059bdb`.

[Actions run 40502952205](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40502952205) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 742 tests across 93 suites with zero failures, errors or skips; see [JUnit summary](renderer-fallback-junit.json). This batch extends native production-viewport fault coverage.

Renderer startup and per-frame processing failures now use one cleanup path. It closes the CPU scene, releases the GPU renderer/arena, clears the active scene identity/budget and records the failed source key with fixed-fallback disposition. Previously, disabled GPU resources stayed allocated, and the per-frame catch omitted that disposition. Effective streaming routing now respects fixed fallback for generation, camera invalidation and picking readiness. Fixed preview publication reports explicit fallback/Regenerate guidance. Regenerate changes the request key and retries streaming.

The native probe first starts a bounded Threshold Voxels stream. Startup injection throws after a real GPU renderer is obtained; fallback must complete async fixed publication with scene/compiler/setup/GPU renderer references absent. It checks the fallback status and unchanged submission time over at least 150 ms (three configured regeneration intervals), then clicks the production toolbar Regenerate and requires generated streaming data again.

A second injection throws in per-frame processing after a real GPU renderer is obtained. The same fixed-publication/resource-release assertions apply, followed by another successful production Regenerate. Python checks native flags, ordered fallback submission stamps and all earlier exact field/selector/source lifecycle reports. See [raw format-6 native report](renderer-fallback-native.json). STREAM_RENDERER_FALLBACK_PASS identifies the added scope.

These are controlled RuntimeExceptions at real renderer lifecycle boundaries, not driver/context-loss or allocation-exhaustion simulation. The field is empty, so ownership and fixed publication are verified rather than visible-mesh pixel equivalence. Camera/picking routing changes compile and share production state, but this batch does not inject a full fallback camera/raycast gesture. Arbitrary GPU faults, physical OS-window/mouse/load/DPI and Windows 11 hardware acceptance remain separate, as do complete acceptance and production sign-off.
