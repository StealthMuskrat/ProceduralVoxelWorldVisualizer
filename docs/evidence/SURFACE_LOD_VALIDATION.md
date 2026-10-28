# Scalar surface LOD and shared faces

Implementation: `38a7f3da061acae1b8fdbd9a8b60fb318389f911`.

[Source run 40674865793](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40674865793) passed the full pinned Linux and Windows builds/native/reference families, Windows packaging/launcher and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Raw XML contains **896 tests across 117 suites, zero failures/errors/skips**; see [summary](surface-lod-junit.json). Both platforms record SURFACE_LOD_NATIVE_PASS / SURFACE_LOD_PASS in ordinary and actual strict OpenGL 3.0 contexts. The final follow-up is documentation/reports only.

Ten new contracts verify byte-identical LOD0 extraction, affine area and outward normals, all 96 combinations of neighboring levels/axes/affine-or-nonlinear faces on signed chunks, root-256 packed precision and boundary contours, closed-sphere two-triangle edge incidence, concurrent immutable remeshing without source calls, extreme/tiny finite values, output-byte boundaries, row cancellation and unchanged legacy admission. Scene tests check camera/quality/pressure detail selection within one source epoch, exact cached-grid sampling counts, return to the original mesh, and an 8 MiB scene that rejects finest meshing but admits a distant coarse replacement without resampling corners.

The normal and actual strict OpenGL 3.0 probes each export six packed 32³ horizontal planes and twelve signed nonlinear neighbor meshes. Independent Python reconstructs triangle attributes, exact packed area, canonical quadratic face intersections and expected triangle counts. All twelve nonlinear meshes share the same **95 canonical boundary segments**, giving identical contours for any pairing of the six levels. Raw reports: [normal context](surface-lod-native.json), [strict 3.0 context](surface-lod-gl30-native.json).

The measured horizontal-plane geometry is:

| Display level | Coarse step | Triangles | Packed bytes |
|---|---:|---:|---:|
| 0 | 1 | 8,192 | 393,216 |
| 1 | 2 | 8,192 | 393,216 |
| 2 | 4 | 5,120 | 245,760 |
| 3 | 8 | 3,584 | 172,032 |
| 4 | 16 | 2,816 | 135,168 |
| 5 | 32 | 6,272 | 301,056 |

All areas remain exactly 1,024 square blocks at the declared packed position precision. This illustrates why actual counts need not decrease at every level: center/face sign layout changes the crossing set, and unit face detail is deliberately retained. Worst-case output bounds decrease after level 1. These are geometry/admission observations, not latency or throughput claims. See [algorithm, bounds and API notes](../SURFACE_LOD.md).

Native rendering mixes levels 0, 2 and 5 across three adjacent chunks. Every incomplete upload retains the previously drawn level. All complete and partial frames match the original full-grid plane framebuffer byte for byte, including indirect/fallback submission and every compaction step. Both contexts relocate **694,272 bytes**, within **4,096 bytes per advance**, preserving position scale and detail metadata. The two Linux context reports also have the same full framebuffer SHA-256. Removal clears the retired chunk's drawn metadata.

The production Viewports fixture records camera/quality levels `0,3,2,4,3,0`, then negative travel at 0 and a fixed-analysis/streaming return at 0. The first seven observations keep one source epoch; return creates a fresh epoch. All accepted center pixels match the canonical plane's independently computed palette/ambient lighting. Manual mode permits navigation/quality remeshing without adopting a new source. Project data, revision/history and configured performance preferences remain unchanged after the controlled preference round trip. This tests the production backing controls and camera state; it is not full physical gesture/DPI certification.

Publishing fixed images and meshes now retires the unused packed arena and its drawn metadata. The fixed-image GPU path and production fixed-mesh return assert that retirement; incomplete fixed requests continue retaining the previous display. No shader changes were needed. Historical Upload constructors and surfaceMesh(token) remain; Upload record-pattern/reflection clients must account for its added detail component.

[Linux evidence artifact](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40674865793/artifacts/12809583654) retains XML, all normal/strict packed binaries, saved project and the complete previous native evidence family. Local downloaded World replay and strict scalar-surface replay also pass. Previous volume pressure/overlap/encoding, fixed and streamed surfaces, picking, authoring, schemas, exact voxel/hash and isolated engine consumer checks remain passing.

Initial run 40673423809 found one packed root-256 test mismatch: the oracle treated an internal edge of a triangle rounded onto a chunk face as an open boundary edge. The corrected oracle compares one-triangle-incidence boundary contours, still checks incidence and exact quantized coordinates, and retains all precision/topology assertions. Run 40674030011 then caught an unused streaming arena retained after fixed publication. The runtime now retires that arena on completed mesh/image publication; explicit native cleanup assertions pass. No geometric or pixel tolerance was widened.

Required local Gradle was attempted but cannot load libjli.so (exit 127). Local formatting, schema fixtures, Python syntax, diff checks and downloaded replay passed; pinned CI supplies compiler/native evidence. The final follow-up changes documentation/reports only.

Canonical corner grids and engine/project/archive semantics are unchanged. Coarse interiors are display approximations and per-triangle normals may differ across detail levels; shared contour geometry is the scoped seam contract. Sparse volume residency, coarser volume sampling, shadows, representative hardware/stress/performance, full GUI/DPI and Windows 11 hardware acceptance remain separate. A28/A31 stay PARTIAL.
