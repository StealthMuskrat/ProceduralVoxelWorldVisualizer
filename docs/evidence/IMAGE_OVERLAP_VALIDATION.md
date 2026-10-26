# Exact image overlap reuse validation

Validated implementation: `b73088955dbb12525844b8b1b0a37f49045fca3b`.

[Actions run 40408933050](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40408933050) uses Temurin 25.0.4+7 and Gradle 9.2.1, including `clean test build dependencyNotices releaseJars`. Required local execution was attempted but Java lacks `libjli.so`; local schema-family and diff checks passed.

The raw Linux JUnit reports contain 709 tests across 87 suites, with zero failures, errors or skips; see [JUnit summary](image-overlap-junit.json). Four added contracts check horizontal/diagonal work and exact output, zoom intersections, complete row assembly across different sizes/axes, next-representable coordinate misses, negative/positive zero fallback, and cancellation/failure after partial reuse. Existing source/force/generation/LRU/budget and concurrency contracts also pass. The signed-zero fixture was corrected after its first run: a two-pixel negative-zero-centered axis itself contains positive zero, so the final fixture warms only negative zero.

The eight production-cache reference views preserve every raw-double digest, packed-color digest and histogram. Independent Python recomputes each value/map/bin and checks per-row and per-value counts and byte bounds. A separately compiled engine-only consumer reloads the saved source projects and replays 524,288 exact samples. See [raw reference report](image-overlap-reference.json).

| View | Newly sampled values | Reused values |
|---|---:|---:|
| Initial | 65,536 | 0 |
| Recolor | 0 | 65,536 |
| One-step vertical pan | 256 | 65,280 |
| Return | 0 | 65,536 |
| One-step horizontal pan | 256 | 65,280 |
| Matching zoom grid | 49,024 | 16,512 |
| Explicit force | 65,536 | 0 |
| Changed source | 65,536 | 0 |

The native async/GL smoke additionally waits for completed accepted previews and asserts recolor, vertical pan, return and horizontal pan work counts. Linux and Windows bundled/packaged smokes report `EXACT_2D_ROW_CACHE_NATIVE_PASS recolor / one-row pan / return view / one-column pan`.

Exact double comparison and original coordinate evaluation determine reuse; near coordinates are never snapped. Rows remain immutable and bounded by the existing budget. Candidate observations cannot mutate after eviction or source replacement; LRU touches require a current generation. Only complete successful rows are admitted. The historical five-component Statistics constructor is retained; record patterns/reflection must account for the added sampledValues/reusedValues components.

This proves numerical equality and reduced field-call counts for the observed grids, not measured hardware acceleration. Full world tiling, all manual GUI/DPI combinations, Windows 11 hardware acceptance and production sign-off remain pending.
