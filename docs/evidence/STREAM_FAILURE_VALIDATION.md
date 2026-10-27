# Streaming source failure and explicit retry

Validated implementation: `c897ca38d3e470ff5f00304b84c6794a7f0f41ca`.

[Actions run 40501081230](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40501081230) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 742 tests across 93 suites with zero failures, errors or skips; see [JUnit summary](stream-failure-junit.json). This batch expands native production-viewport failure coverage.

Source compilation and renderer failure now have distinct retry dispositions. A source-factory exception records the failed source key, marks the preview failed/outdated, consumes force, preserves the prior streaming scene and displays explicit Regenerate guidance. The failed key holds without falling into fixed compilation or retrying each frame. A new source key or Regenerate can rearm compilation. Successful source publication clears the failure state. Renderer errors retain the existing fixed fallback.

The native probe starts a bounded empty-field Threshold Voxels stream, injects one exception in the background source-factory path and clicks the production toolbar Regenerate control. It waits for source failure, verifies the old scene epoch survives, then renders frames for at least 150 ms (three configured 50 ms regeneration intervals). The submission timestamp and epoch must remain unchanged, and retry guidance must remain visible. A second toolbar click must produce a new generated streaming scene with a different epoch and cleared failure state.

See [raw format-5 native report](stream-failure-native.json). Python checks retained epoch and the native retention/no-retry/recovery observations alongside previous selector and streaming cleanup assertions. STREAM_SOURCE_FAILURE_PASS identifies this scope. Existing native GL/editor/bundled/packaged smokes, numerical image/engine references and cross-platform reproducibility remain in the workflow.

The exception is a controlled source-factory injection, not a malformed authored project or every possible compiler exception. The retained scene in this probe is an empty field; epoch/ownership is verified rather than visible-pixel equivalence. GPU failure injection, first-ever source failure, arbitrary load/DPI, physical OS-window/mouse and Windows 11 hardware acceptance remain separate, as do complete acceptance and production sign-off.
