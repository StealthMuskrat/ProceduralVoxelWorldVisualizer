# Chunk-size benchmark

Environment: Temurin 25.0.4+7, Linux amd64 container, 9 reported processors, 1 GiB Java heap. CPU model and physical memory are unavailable in this sandbox. GPU performance was not measured.

Single caller, NO_CACHE, same [0,63]^3 cells, 2 warmups and 5 measured runs per scene/root. Times include context creation and generation. Meshing is measured separately in the raw CSV. Each scene produced identical voxel hashes for 32³ and 64³ roots.

| Scene | 32³ median generation ms | 64³ median generation ms |
|---|---:|---:|
| empty | 0.171 | 0.178 |
| sparse | 8.048 | 9.089 |
| dense | 13.265 | 10.718 |
| mixed | 7.623 | 5.866 |
| high_frequency | 66.435 | 70.508 |

The editor retains a fixed logical size of 32 based on these measurements and its smaller per-job staging footprint. This is a local tuning decision, not a universal performance claim. Windows hardware, GPU upload, frame-time, GC and pressure-fallback certification remain pending.

Raw data: [chunk-benchmark.csv](chunk-benchmark.csv). Reproduce with `java -Xmx1g -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.render.Benchmark`.
