# Streamed volume pressure recovery

Implementation: `cb213fb2aa0d1071bd8ca6903c56d4346bec3a34`.

[Source run 40614176072](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40614176072) passed the full pinned Linux and Windows builds, native/reference families, Windows packaging/launcher checks and cross-platform JAR reproducibility with Temurin 25.0.4+7 / Gradle 9.2.1. Raw XML contains **886 tests across 115 suites, zero failures/errors/skips**; see [JUnit summary](volume-pressure-junit.json). Both platforms record VOLUME_PRESSURE_NATIVE_PASS / VOLUME_PRESSURE_PASS. The completed source build supplies compiler, native and packaging evidence; the final follow-up changes documentation/reports only.

Eight new VolumePressureTest contracts cover effective extent/cache ordering, CPU peak/cache arithmetic, accepted+candidate GPU admission at exact/one-byte-short boundaries, aligned dimension/cell limits, zero/invalid limits, queued/worker/upload ownership accounting, critical pause/Resume/gradual restoration, and exact canonical affine samples after cache release without recompilation. Existing engine, volume encoding, transfer, GPU overlap, streaming, storage/hash and isolation checks remain passing.

The production Viewports native fixture records twelve accepted-display observations. It uses a controlled monotonic clock for escalation/recovery, real background workers, real GL textures, actual memory-limit changes and the actual Resume Volume widget. It does not wait for wall-clock performance degradation or claim representative frame timing. The observations cover:

- Configured 64³ visibility and level-1 retention of the canonical unit grid without another sampling request.
- Level-2 complete 32³ replacement with the old size/origin/version retained throughout its partial upload.
- Level-3 release of optional chunk history without recompiling/resampling the accepted window.
- Level-4 camera/source deferral, retaining the manual accepted source after an explicit committed 1-to-0 edit.
- Critical cancellation of an incomplete upload; neither a twenty-second low-pressure clock advance nor Regenerate bypasses Resume.
- An actual Resume button click followed by adoption of the explicitly requested new source at reduced quality.
- Gradual restoration to the configured 64³ extent, then CPU admission recovery at 32³ under a tightened 1 MiB limit.
- A GPU limit reduced to 1 MiB while new windows are deferred. The admitted candidate is canceled, rejection sustains automatic escalation to critical pause, and a raised limit plus actual Resume recovers.
- A real queued window behind two occupied workers. Its peak is reserved before execution; critical pause cancels/purges the queue slot, clears the UI reservation and rejects late publication. Actual Resume starts a legal successor.

[Raw native report](volume-pressure-native.json) records exact accepted versions `1,1,2,2,2,2,3,4,5,8,8,9`. Source generation/compilation remains 1 until explicit regeneration is admitted after Resume, then remains 2 through restoration, budget recovery and queued cancellation. Configured extent stays 64. Project revision/history/data and configured performance preferences remain unchanged by every navigation/pressure/recovery action; the one committed field edit is separately acknowledged by the fixture.

Each observation reads every GPU texel and its accepted encoding. Uniform canonical 1 and 0 both encode as positive float zero; exact offset/min/max metadata distinguishes their canonical values. Independent Python checks **1,540,096 float texels** from raw binaries, encoding metadata, signed origins, publication versions, pressure levels, cache bounds, queue reservation and analytical Beer–Lambert center pixels. Deferred/paused views deliberately point outside the retained old window and correctly render the background. Complete-window opacity remains based on the accepted extent. Uploads remain at most **16,384 destination bytes per advance**.

The pure admission peaks are 3,473,536 bytes for 64³ and 721,024 bytes for 32³, including sampling/encoding, one tile and bounded staging. Under the 1 MiB CPU limit, the accepted reduced window retains one 262,272-byte cache tile, within its remaining CPU allowance. The queued fixture reports a 3,473,536-byte peak reservation before the worker starts; aggregate profiler CPU/reserved bytes retain their disjoint accounting. These are conservative ownership estimates, not OS RSS or driver allocation measurements.

The [Linux evidence artifact](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40614176072/artifacts/12784567296) retains all twelve binaries, saved project, raw XML and the complete previous native evidence family. Downloaded World reference replay also passes locally. The existing resident-copy probe continues to pass normal and actual strict OpenGL 3.0 contexts; the new pressure interaction probe runs in the ordinary native context.

Initial run 40613151939 failed compilation due to a missing ProcessingRegistry import in the new fixture. Run 40613338800 then caught a fixture expectation that confused uniform encoded texels with canonical values. The correction checks exact positive-zero texel bits, exact accepted offset/scale/min/max and canonical optical output separately. Run 40613858581 passed the original Linux native/independent pressure workflow before cancellation for the strengthened deferred-budget rejection case. Review found and fixed rejection tracking during deferred uploads, and the final source run exercises its automatic escalation. No texel/optical tolerance was widened.

Required local Gradle was attempted and failed to load libjli.so (exit 127). Local formatting, schemas, Python syntax, diff checks and downloaded reference replay passed; pinned CI supplies compiler/native evidence. Final follow-up changes documentation/raw reports only.

This validates bounded functional policy and ownership. It adds no sparse atlas or coarser scalar sampling/isosurface LOD, changes no engine/project/archive numerical semantics, and does not certify representative hardware throughput/latency, arbitrary device faults, full GUI/DPI or Windows 11 hardware. A28/A31 remain PARTIAL.
