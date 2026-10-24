# Verification evidence

- `clean test build dependencyNotices releaseJars`: PASS with 219 JUnit tests on Temurin 25.0.4+7 x64.
- Independent javac/java consumer using only `noise-generator-engine.jar`: PASS.
- Local native smoke: all three engine-backed viewports, explicit save/reload, screenshots with nonblank-frame checks, and exit: PASS on Temurin 25.0.5+8; OpenGL 4.5 Mesa llvmpipe.
- Draft 2020-12 project/user/app schema verification and fixtures: PASS using jsonschema 4.23.0.
- Exact scene hashes at 32³ and 64³: PASS. See [benchmark](BENCHMARK.md).
- Earlier CI run [40045036266](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40045036266): Linux and Windows Server 2025 build, independent consumer, native smoke and Windows packaging passed.
- CI run 40047610767 passed Linux/Windows builds, all 35 tests of that revision, native smoke, exact voxel hashes, and the packaged Windows launcher. Its independent JAR SHA-256 values match across both platforms; the manifest comparison needed a platform-independent listing order, fixed in the following workflow revision.

Raw JUnit results are under `junit/`. `linux-native-smoke.log` and `smoke-project.json` accompany the inspected World screenshot below. These tests do not certify the full A01–A36 specification or Windows 11 hardware.

![Engine-backed world viewport](world.png)

Typed-field extension CI [40050919102](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40050919102) passed Linux and Windows builds, native desktop/packaged launcher, exact voxel hashes and identical JAR bytes. The later ray-marched volume extension adds four CPU tests, X/Y/Z slice smoke scenarios, and the analytic native volume probe (linux-volume-probe.log). The following image belongs to volume-smoke-project.json.

![Ray-marched scalar volume](volume.png)

The authoring/stage batch passes 79 JUnit tests, strict schema/curve fixtures, unchanged exact voxel regression hashes, the engine-only consumer including exact stage snapshots, the native numerical volume probe, and native curve/four-stage/three-viewport rendering. linux-authoring-smoke.log and authoring-smoke-project.json accompany these inspected images. The curve dialog uses its isolated demonstration definition and is cancelled during smoke.

![Curve editor with selected points and tangent handles](curve-editor.png)

![Only Changes preview showing removed cells](stage-changes.png)

The streaming/runtime batch passes 95 JUnit tests on pinned Temurin 25.0.4+7. `streaming-build.log`, `streaming-artifacts.log` and `streaming-independent-consumer.log` record the final build, exact regression hashes, schemas, engine isolation and external consumer. `linux-streaming-probe.log` records bounded packed uploads, culling, multirow palette updates and indirect/fallback pixel parity. `linux-streaming-smoke.log` includes streamed camera movement, stage/volume/slice modes and save/reload on Mesa llvmpipe OpenGL 4.5. The paired input for the image is `streaming-smoke-project.json`. Pressure controls reduced effective distance during this software-rendered run; this is functional evidence, not representative hardware performance certification.

![Camera-driven streamed world after panning](streaming-world.png)

The world-use batch passes 112 JUnit tests on pinned Temurin 25.0.4+7. `world-use-build.log`, `world-use-artifacts.log` and `world-use-independent-consumer.log` record the clean build, schemas, unchanged exact voxel regression hashes, engine isolation and external world-use/owned-collection consumer. `linux-world-use-smoke.log` records the native controls, stage previews, ray-marched volume/slices, streamed camera movement and save/reload. The isolated smoke controls use a copy of the saved `world-use-smoke-project.json` defaults; they exercise the actual property widgets without committing those demonstration edits. Screenshots were inspected and action-row clipping was corrected before the final run. This is scoped native rendering evidence, not a full gesture or Windows 11 hardware certification.

![Ordered placement sampling transforms](world-use-transforms.png)

![Interval type selection with explicit endpoints](world-use-types.png)

![Mapped field driver and NOT/probability gate](world-use-drivers.png)

The compiler/batch extension passes 138 JUnit tests on pinned Temurin 25.0.4+7. `compiler-build.log`, `compiler-artifacts.log` and `compiler-independent-consumer.log` record the clean build, schema/fixture checks, unchanged exact voxel hashes, engine isolation and external compiled-expression/strided-grid/point-array consumer. `linux-compiler-smoke.log` adds native expression feedback to the curve, world-use, stage, streaming, volume and slice checks. The inspected screenshot is paired with `compiler-smoke-project.json`. The panel shows constant/branch removal and shared calculations from the actual parser plan. Functional checks do not certify SIMD, hardware throughput or the complete GUI interaction matrix.

![Compiled expression feedback and evaluation details](compiled-expression.png)

The exact-storage extension passes 156 JUnit tests on pinned Temurin 25.0.4+7. `storage-build.log` and the latest raw JUnit XML record the clean build. `storage-independent-consumer.log` exercises immutable bulk replacement and default v2/explicit v1 archives using only the engine JAR. `storage-artifacts.log` records schema family, unchanged exact voxel hashes and engine isolation. `storage-benchmark.csv` measures current palette storage and v1/v2 raw-tree bytes; `storage-world-benchmark.csv` retains exact generation hashes. See [storage design and scope](../EXACT_STORAGE.md).

For the measured 64³ checkerboard, the current snapshot estimate is 119,456 bytes; raw v1/v2 trees are 1,053,257 / 74,313 bytes respectively. The many-type fixture has 4,097 chunk palette entries and still uses one-bit local leaves. These raw-tree comparisons exclude zlib compression and archive headers. Timing rows are local container observations, not a representative hardware certification or a prior implementation speed comparison.

The diagnostics/recovery extension passes 176 JUnit tests on pinned Temurin 25.0.4+7. `diagnostics-build.log` and the latest raw JUnit XML record the clean build. `diagnostics-artifacts.log` records the external engine-only consumer, unchanged exact voxel hashes, schema family and engine isolation. `linux-diagnostics-smoke.log` records three new native panels, a rejected expression draft, grouped events, source navigation and unchanged document state, plus the existing viewport/stage/volume/slice/streaming and numerical rendering checks. The screenshots below are paired with `diagnostics-smoke-project.json`; the invalid expression is an intentionally rejected unsaved draft and is absent from that valid project file.

Final native checks ran separately from the CPU regression benchmark. One overlapping local run reached the existing critical-pressure pause and failed the complete-preview smoke assertion; the standalone final run passed with the same pressure and explicit-resume policy. This evidence does not certify responsiveness under arbitrary CPU contention, every recovery gesture or Windows 11 hardware.

![Grouped error with source control and preserved draft](diagnostics-logs.png)

![Independent unread and action-required notification state](diagnostics-notifications.png)

![Read-only validation results for a rejected edit](diagnostics-validation.png)

The numeric-parameter extension passes 195 JUnit tests on pinned Temurin 25.0.4+7. `parameters-build.log` and raw JUnit XML record the clean build. `parameters-artifacts.log` records the engine-only numeric mapping/trace/JSON consumer, unchanged exact voxel hashes, schema family and engine isolation. `linux-parameters-smoke.log` adds the actual ordered numeric controls to the existing diagnostics, curve, world-use, stage, streaming, volume and slice scenarios and both renderer probes. The inspected screenshot is paired with `parameters-smoke-project.json`; its isolated control window edits a settings copy. Normal startup creates no demonstration resources. Broader GUI gestures, arbitrary destinations and representative hardware performance remain outside these checks.

![Ordered numeric placement driver controls](numeric-parameter-drivers.png)

The scheduling/cache extension passes 219 JUnit tests on pinned Temurin 25.0.4+7. `scheduler-build.log` and the latest raw JUnit XML record the final clean build. `scheduler-artifacts.log` records the independent engine-only priorities/region/stats consumer, unchanged exact voxel hashes, schemas and engine isolation. `linux-scheduler-smoke.log` adds the real Profiler queue snapshot to the complete existing native smoke and numerical probes. The native/artifact checks used JARs byte-identical to the final build; the last rebuild added only a running-abandonment regression test and strengthened a snapshot assertion. The inspected screenshot is paired with `scheduler-smoke-project.json`. Queue counters are zero in that early preview frame because the initial numerical previews use their separate bounded background executor; no synthetic workload is presented as real metrics. These functional checks do not certify representative hardware performance or every scheduler/consumer race.

![Profiler with bounded scheduler queue statistics](scheduler-profiler.png)

The bounded-profiler extension passes 240 JUnit tests on pinned Temurin 25.0.4+7. `profiler-build.log` and the latest raw JUnit XML record the full build. `profiler-artifacts.log` records the independent engine-only capture/export consumer, unchanged exact voxel hashes, schemas, engine isolation and independent recomputation of exported bounds/means/nearest-rank percentiles. The engine capture contains 36 real samples across four series; its JSON/CSV pair is retained here. `profiler-jar-hashes.txt` identifies the tested JARs. Storage/world timing rows remain local container observations.

`linux-profiler-smoke.log` records all native scenarios and both numerical probes, plus the real opt-in capture panel and exported frame/heap/GC/queue/memory measurements: 64 retained samples across 12 series. The screenshot below is paired with `profiler-smoke-project.json` and `profiler-native-capture.json` / `.csv`. It was taken during recording; the exported pair is the later stopped snapshot. Engine queue/generation counters are zero in this early frame because numerical previews use the separate background executor. GPU completion times are not measured.

`linux-profiler-initial-smoke.log` retains an initial fixed-frame smoke failure: Only Changes had not completed before its next stage. The harness now waits at each stage boundary, with a 30-second timeout, before switching modes; production preview/pressure behavior is unchanged. The final run passed all assertions without relaxing pressure limits. This does not certify every GUI gesture, exclusive layer cost or Windows 11 hardware performance.

![Bounded profiler capture with real frame percentiles and memory series](profiler-capture.png)

The GPU-arena extension passes 249 JUnit tests on pinned Temurin 25.0.4+7; raw XML includes the independent randomized intermediate-layout oracle. `compaction-build.log` records the final full build and `compaction-artifacts.log` records engine-only consumer/export validation, schema family, unchanged exact voxel hashes and the byte-identical headless JAR. `compaction-jar-hashes.txt` identifies the tested artifacts; benchmark rows are local observations rather than certified speedups.

`linux-compaction-smoke.log` records the existing desktop scenarios and volume probe, plus framebuffer equality through overlapping triangle and visible-point moves, both submission paths, deferred retirement/admission retry, explicit requests and partial-epoch replacement. The probe reports actual OpenGL 4.5 and strict 3.0 contexts, with indirect capability true/false respectively. The first probe run deliberately failed its nonempty-point-fixture assertion; `linux-compaction-initial-smoke.log` retains that evidence. The fixture was moved to explicit central coordinates and the final complete run passed, including the same triangle relocation checks.

The inspected screenshot is paired with `compaction-smoke-project.json`. It shows the real late-run streamed scene's empty vertex allocations and retained 32 MiB VBO capacity, plus the stopped earlier frame capture. No synthetic fragmentation or moved-byte values are injected into the normal editor view. The native probe separately forces a fragmented arena and asserts every resulting image. The visible invalid `sin(` text is the intentionally rejected diagnostic draft from the existing smoke scenario; it is absent from the valid saved project. GPU completion timings, device-loss recovery, physical bus accounting and representative hardware throughput are not certified.

![GPU vertex arena capacity, free ranges and explicit compaction controls](gpu-arena-profiler.png)
