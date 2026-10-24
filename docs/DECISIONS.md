# Engineering decisions and current limitations

## Fixed implementation contracts

- Project format 2 is explicit. Model objects serialize ordered arrays, stable UUID references, and exactly one World Generator. Noncanonical visible IDs are rejected, with case-sensitive NFC comparisons. Unknown JSON fields, duplicate object keys, malformed UUIDs and unsupported versions are rejected. Enabled noise definitions receive compiler validation before the editor or public ProjectLoader accepts them.
- `project-model`, `engine-core`, `voxel-storage`, and `engine-api` contain no desktop/OpenGL dependency. The separately assembled engine JAR is exercised by an external javac/java invocation.
- New layer defaults are NO_CHANGE. Placement overrides use map presence; zero remains an override, null is invalid, reset removes the key. A placement never mutates its source resource.
- Same-cell world predicates allow per-cell execution of immutable stages without observing partial writes. A voxel sees earlier operations only. Field-selection gates and voxel predicates have separate types and evaluators.
- Root storage is an immutable int arena: EMPTY and UNIFORM are immediate encodings, branches have eight child indices, and heterogeneous leaves up to 4³ store primitive values. This avoids a root-sized dense array for sparse 256³ chunks. Per-leaf palette/bit packing is not implemented yet; PACKED currently means a primitive dense leaf, not bit-packed palette indices.
- CoordinateHash uses SplitMix64 finalization. Operation probability includes stable operation identity and effective seeds, never traversal or worker identity. 2D placement fields are cached per X/Z during chunk generation.
- FastNoiseLite 1.1.1 was vendored with its original notice. Upstream's marked coordinate declarations use double; outputs remain the reference float values widened to double. Exact cross-architecture certification has not been completed.
- Expression operators/functions use immutable compiled nodes and per-thread scratch. Numeric domain errors fail at the executed boundary. Short-circuiting is real; invalid unselected ternary/Boolean branches are not evaluated. Power is right-associative and binds above unary minus. Enabled items are validated even when not selected; execution prunes unused processing results. Constant folding and broader dead-branch optimization still need expansion.
- Curve Smooth uses monotone-limited Hermite slopes; Bezier uses explicit endpoint slopes and one-third X handles. Step is left-valued within a segment and exact knots use that knot's value. Outside-domain policies are explicit; raw outputs are never implicitly clamped.
- Generation queues and region in-flight windows are bounded. Separate virtual coordinators may wait for async consumers without occupying generation workers. Completed immutable snapshots survive cancellation, cache eviction and context closure.
- Archive v1 stores a semantic string dictionary, compressed adaptive roots, explicit sparse coverage and a checked random-access index. EMPTY coverage and absent coverage differ. Decoding rejects unresolved required IDs without mutating the registry.
- Properties use an isolated draft and Apply/Revert. Valid Apply is one document transaction. Undo/Redo uses a bounded snapshot history; navigation and preferences are outside it. Saves stage a sibling file, force contents, then use atomic replacement where available.
- The renderer uses camera-relative float matrices derived from double navigation, greedy exposed-face meshes, and a GPU palette. Color edits currently also invalidate editor previews; avoiding unnecessary mesh rebuilds remains an optimization requirement.
- Editor previews currently use bounded camera-centered cubes. This is not yet the complete multi-chunk renderer/streaming LOD system from Tree 17. The internal editor runtime uses fixed size 32 after the recorded sparse/dense/mixed/high-frequency comparison in docs/evidence/BENCHMARK.md. This local measurement is not a universal hardware certification.

## Outstanding requirements preventing production sign-off

The original specification is much broader than the current tested development implementation. Do not mark A01–A36 complete from a compiling build. Remaining work includes:

- Complete sampled-field prefixes, six-type polymorphic operation signatures, all explicit conversions/projections and transform input bindings; constant folding, dependency-closure execution and compiler optimizations.
- Complete per-algorithm independent reference vectors and certified JVM/CPU matrix.
- Full resource tree ordering/multi-selection clipboard behavior, inline F2 rename, atomic dependent replacement during deletion, and comprehensive portable import conflict choices.
- Full curve multi-point/tangent gesture handling and Auto/Aligned/Free handle validation.
- Type-selection interval mappings, field-driven strength/probability, full inheritable ordered transforms/axes and richer field gate registry.
- Arbitrarily large region counters beyond signed long capacity need a documented rejection policy; generation/shared-request cache deduplication, priority scheduling, stronger global collection accounting and pin policy need more coverage.
- Fully interactive camera-relative chunk streaming; all orthogonal slice axes; a true volume renderer; stage Before/After/Only Changes UI; richer trace values/navigation. Exact DDA picking is implemented and tested.
- Renderer AO, neighbor-aware chunk seams, display LOD, hysteresis/stitching, frustum culling, shared GPU arenas, indirect drawing and the prescribed measured pressure fallback.
- Complete settings metadata creation-default controls, shortcut grammar, search filters/virtualization, notification action routing, diagnostics source navigation, theme contrast feedback, and detailed bounded profiler captures.
- Measured representative Windows hardware, full interactive GUI acceptance, complete third-party distribution review and owner-approved project license/redistribution notice.
- Final binary reproducibility verification, Windows 11 acceptance, and production release packaging sign-off.

Development artifacts and CI smoke tests are useful evidence but are not a claim of complete specification conformance.

## Observed native evidence

Linux local software OpenGL smoke completed all three engine-backed viewports, save/reload and clean exit. Screenshots were inspected. GitHub Actions run 40045036266 also passed Linux and Windows Server 2025 native smoke with Temurin 25.0.4+7 and bundled Windows runtime/Mesa. This is not a Windows 11 hardware certification. The later pipeline adds packaged-launcher and cross-platform JAR hash verification.

The imgui-java 1.89 GLFW backend can attempt to destroy an unavailable X11 cursor (0). Desktop shutdown contains that specific failure and still disposes callbacks, contexts and GLFW. Other NullPointerExceptions are not suppressed. Multi-release JAR metadata is retained for LWJGL 3.4.1 Java 25 support.

Generation admission reserves a conservative 16 bytes per root voxel plus 2D field-cache storage, accounting for arena growth and immutable publication copies rather than only the final snapshot size. This may reject large concurrent roots before allocation even when the eventual sparse chunk would be small.

The GUI binding publishes additional Kotlin support dependencies through Gradle module metadata. kotlin-stdlib-jdk8 1.8.20 is explicit in the desktop runtime graph so POM-based local mirrors and online Gradle resolution produce the same complete runtime; its transitive artifacts are checksum verified. The headless engine graph remains free of GUI/Kotlin dependencies.

The first cross-platform comparison found identical per-file JAR hashes but a different manifest listing order: pathlib uses case-insensitive path ordering on Windows. Hash manifests now sort explicit filename strings, and fat-JAR input archives sort by name. The check compares artifact bytes, not platform-specific filesystem ordering.
