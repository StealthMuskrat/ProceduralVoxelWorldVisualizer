# Streaming source removal and GPU cleanup validation

Validated implementation: `6d01f42f257471eb21ce0d34d8aa897e8a3b4cb0`.

[Actions run 40429186190](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40429186190) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit remains 735 tests across 92 suites with zero failures, errors or skips; see [JUnit summary](stream-recovery-junit.json). This batch expands native integration evidence.

Previously, missing/empty source handling stopped the CPU streaming scene but retained a hidden GPU scene renderer. GlViewport.releaseScene now disables and closes that renderer, drops its arena/budget and wanted-set references, and is called only when there is no live source. Visible last-valid transitions and fixed accepted observations retain their existing behavior. All GPU lifecycle work stays on the render thread.

The native production viewport probe selects the 3D field, enables Threshold Voxels streaming with root size 32, horizontal/vertical radius zero and a 27-data-chunk ceiling, then waits for generated data and an allocated GPU scene renderer. The constant 0.5 source and 0.75 threshold produce an empty field, keeping this lifecycle probe bounded. The observed Linux report records one generated chunk. Native picker actions clear and reselect the source. The clear state requires scene/compiler/pending setup and GPU renderer references all absent. Reselect waits for another generated streaming scene.

A separate document transaction deletes the selected 3D field. The missing UUID/status and the same CPU/GPU cleanup assertions must hold. Document Undo plus normal revision invalidation restores the project and another generated streaming scene. The probe drains the real editor UI queue each frame to apply source-compilation completions. Existing 2D selection, image retention, queue/budget retry and authored-state checks remain.

See [raw format-4 report](stream-recovery-native.json). Python checks positive generated count and the native clear/delete/reselect/Undo flags alongside earlier exact field and input observations. VIEWPORT_STREAM_RECOVERY_PASS identifies this scope. The workflow also retains native/editor/rendering/input/engine references and reproducibility checks.

This is a bounded empty-field Threshold Voxels lifecycle test, not a measured VRAM reclamation benchmark or every Point Cloud/World/visible-mesh/focus/DPI/load transition. Physical OS-window/pointer and Windows 11 hardware acceptance remain separate, as do complete specification acceptance and production sign-off.
