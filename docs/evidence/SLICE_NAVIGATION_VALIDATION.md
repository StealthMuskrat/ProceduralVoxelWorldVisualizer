# Owned slice navigation and accepted coordinate geometry

Validated implementation: `6a56b907639f12c48136b7a2a942439a0edf3d63`.

[Actions run 40551367182](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40551367182) passed Linux/Windows builds, native workflows, packaged launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Both platform logs contain SLICE_INPUT_NATIVE_PASS and SLICE_INPUT_PASS. Raw Linux JUnit reports **793 tests across 102 suites, zero failures/errors/skips**; see [summary](slice-input-junit.json). Seven new unit cases cover all plane/extent wheel anchors, pan/inverse expectations, zero/saturated input, finite atomic rejection, immutable accepted mapping, odd/fractional geometry and invalid coordinates/samples.

Slices previously used hover-only incremental pans, center-only zoom and hover/bookmark calculations combining accepted image data with the latest requested extent. Sampling outside fixed-bounds mode could also use an unrelated orbit/free-fly Y center. Left/middle pans now retain press-time navigation and dimensions, continue outside the image, cancel on superseding input and restore on Escape. Wheel zoom anchors the requested plane-local world point. Accepted pixels carry their own immutable plane, coordinate, origin, extent, spacing and sampler. The displayed label identifies that geometry. See [navigation contract](../SLICE_NAVIGATION.md).

The [raw native report](slice-input-native.json) and [saved affine project](slice-project.json) use the actual compiled expression `x + 10*y + 100*z`. Production image/key input, native ImGui focus requests, controlled tab/plane/extent changes and actual Regenerate input check:

- All X/Y/Z slices use plane-local centers (-7.25,12.5), fixed coordinate -3, extent 8 and spacing 0.75 outside fixed-bounds mode. Origins are (-10.25,9.5). First/last generated pixels and continuous hover samples agree with independent affine expectations in each world-axis mapping.
- Wheel +2 gives spacing 0.5556136655112884 while preserving the procedural point under the measured pointer. Wheel -2 recovers the original request within rounding.
- Left and middle pans continue outside the image from the original request/dimensions. Wheel input cannot change an owned pan. Escape restores the baseline, and subsequent held motion/releases cannot resume it.
- A foreign held button is never adopted. Focus loss/return and tab changes keep the latest valid request without resuming held input. Changing plane/extent while held supersedes the old pan rather than being overwritten by it.
- In manual mode, a request for Z slice 9 / extent 16 leaves the accepted X slice -3 / extent 8 and submission stamp unchanged. Actual hover still reports the old X image's point and affine sample, and a short left click captures bookmark coordinate (-3,-9,14).
- Actual Regenerate accepts the new Z metadata and affine samples without modifying the old image snapshot. Authored JSON, revision and Undo history remain unchanged through the workflow.

Independent Python reconstructs each world point and first/last pixel from captured geometry, evaluates the affine expression, recomputes wheel-anchor/pan math and bookmark floor coordinates, and checks all lifecycle flags. Existing World fixed/streamed/LOD picking, owned Inspector jobs, coordinate draft/freshness, orbit and free-fly fixtures remain passing.

Initial validation caught a missing fixture import. The native pending-image assertion then exposed float pointer normalization against double captured geometry; slice hover and bookmark normalization now use double precision. The exact assertion was retained.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local schemas/fixtures, formatting, Python syntax, diff checks and independent replay of downloaded native reports passed. Pinned CI supplies compilation, automated/native tests and packaging evidence.

This is scoped native GL/ImGui/editor and mathematical evidence. Controlled focus/tab/request transitions are not a physical OS input/focus/DPI matrix; continuous procedural hover is not a GPU-filtered pixel readback. Full GUI and Windows 11 hardware acceptance remain separate. No engine API, procedural generation semantics, schema, archive or project-format changes.
