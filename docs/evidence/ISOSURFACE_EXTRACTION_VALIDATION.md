# Bounded, cancellable isosurface extraction

Validated implementation: `1a4a84355396d2bc75295a5e797c26053827fec2`.

[Actions run 40566839735](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40566839735) passed Linux, Windows and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Both platform builds passed `clean test build dependencyNotices releaseJars`, prior native workflows, packaged launcher checks and ISOSURFACE_NATIVE_PASS / ISOSURFACE_PASS. Raw Linux JUnit contains **827 tests across 106 suites, zero failures/errors/skips**; see [summary](isosurface-junit.json).

The previous isosurface loop built temporary lists for every tetrahedron, had no extraction cancellation or output cap, interpolated overflowing finite differences directly and emitted inconsistent triangle winding. The replacement uses primitive scratch, one sampled corner grid, canonical edge ordering, overflow-safe interpolation, outward winding/normals and bounded complete-triangle output. Fixed Viewports passes its owned cancellation token, omits the unused voxel array and admits grid plus peak mesh-buffer storage. See [extraction contract](../ISOSURFACE_EXTRACTION.md).

Ten mesher unit cases and one preview-admission case check:

- Plane `x=.25` has area 4 in a two-cell cube, normal (-1,0,0) and consistent winding. The unit-cube threshold-corner plane `x+y+z=1` has independent area sqrt(3)/2 and normal (-1,-1,-1)/sqrt(3).
- Negating field and threshold preserves the vertex set and surface area and reverses normals. Extreme finite ±Double.MAX_VALUE endpoints interpolate at .5 (and .75 for a shifted threshold); normal and minimum subnormal magnitudes remain valid.
- Adjacent origins -2 and 0 retain identical shared-face vertices for an oblique affine field. A closed sphere has exactly two incident triangles per edge and outward unit normals.
- Every grid corner is sampled once. Uniform positive, negative and exactly-threshold fields create no artificial cube caps. Invalid dimensions/thresholds reject before evaluation; nonfinite samples identify the local node.
- Pre-canceled tokens, thread interruption, cancellation during sampling and cancellation during extraction return no partial result and leave a previous mesh untouched.
- Exact output bytes admit inclusively; one byte less rejects. A zero output cap admits an empty mesh. Working-memory estimates include worst-case triangles, peak buffer growth, sampled grid and scratch, using long arithmetic through extent 256.

The native fixture compiles the [saved canonical project](isosurface-project.json) with three fields: `z - 2.25`, `2.25 - z`, and finite extreme values selected on either side of z=3. It builds four-cell isosurfaces and renders them through the production GlViewport, with image-to-mesh framebuffer transitions. Each field samples 125 nodes exactly once. Twenty-five interior pixels per field match analytical shader lighting: the negative-Z normals produce red 89, complemented positive-Z normals red 134, and extreme interpolation again red 89; green/blue are 0 and alpha 255. Both platform logs report all 75 pixel checks passing.

The [raw geometry/pixel report](isosurface-native.json) retains all six exported meshes: positive/complement/extreme planes, adjacent negative/positive-origin affine pieces, and a closed sphere. Independent Python verifies finite vertices, complete-triangle byte counts, area 16 for the native planes, every triangle's winding against its normal, every pixel against the light equation, oblique affine residuals, exact shared-face vertices and two faces per sphere edge. Local replay of the downloaded report and all previous picking/navigation/project-file/input-binding reports passed.

The source run passed all checks on its first validation attempt; no assertion or numerical tolerance was weakened.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local formatting, schema/fixture validation, Python syntax, diff checks and downloaded-report replay passed. Pinned CI supplies compilation and automated/native/package evidence.

This is renderer/backend and bounded fixed-analysis evidence. The native fixture invokes the production mesher/GlViewport on compiled fields; it does not certify the full Isosurface toolbar, arbitrary cancellation timing, representative CPU/GPU throughput, physical input/DPI or Windows 11 hardware. Streaming isosurfaces/volumes remain outstanding. Exact procedural computation, voxel data, engine API and project/archive formats are unchanged. A28 remains PARTIAL.
