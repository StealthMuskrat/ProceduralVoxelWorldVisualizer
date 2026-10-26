# Accepted 2D image geometry validation

Validated implementation: `40381d1feb2722384334a62004345e1a5119bb86`.

[Actions run 40413253411](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40413253411) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Local execution was attempted but Java lacks `libjli.so`; local schemas and diff checks passed.

Raw Linux JUnit contains 717 tests across 89 suites with zero failures, errors or skips; see [JUnit summary](image-view-junit.json). Four new NoiseImageViewTest cases check every one of 65,536 pixel coordinates against original grid arithmetic, continuous pointer coordinates, independent requested/accepted sampling programs, unclamped raw and clamped display values, failure recovery and malformed/overflowing pointers before sampling.

The accepted preview now carries immutable geometry with its compiled sampler through the existing epoch-checked publication path. Hover, picked bookmarks and grid alignment read this accepted geometry. Requested navigation does not relabel an older displayed image, and unsuccessful jobs cannot replace its geometry. Before first publication, hover reports waiting and picking supplies no coordinate. Runtime sampling failure reports unavailable while retaining the geometry.

Native smoke requests a pan after a valid 2D image, then immediately checks the accepted geometry object and its center/sample program remain attached to the old image. It reports `ACCEPTED_2D_IMAGE_GEOMETRY_PASS pending pan retains hover program and center`. Subsequent native row/column work-count checks wait for the replacement image to complete. Existing independent Python recomputes eight exact raw/color/histogram digests, and the engine-only consumer replays 524,288 exact samples.

The native assertion is scoped pending-navigation evidence, not fault injection of every failed/cancelled GUI job or a complete mouse-hover/bookmark-click audit. Full manual GUI/DPI, Windows 11 hardware, full world tiling and complete A27 acceptance remain separate.
