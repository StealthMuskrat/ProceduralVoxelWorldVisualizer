# Resident scalar texture overlap

Validated implementation: `7c6f9c2b7fc2fe6b298394239c8599094ac1d081`.

[Actions run 40602416230](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40602416230) passed Linux, Windows and cross-platform JAR reproducibility with Temurin 25.0.4+7 and Gradle 9.2.1. Both builds passed `clean test build dependencyNotices releaseJars`, prior native/reference workflows and packaged launchers. Raw Linux JUnit contains **878 tests across 114 suites, zero failures/errors/skips**; see [summary](volume-overlap-junit.json). Both platforms record VOLUME_OVERLAP_NATIVE_PASS / VOLUME_OVERLAP_PASS in normal and actual strict OpenGL 3.0 contexts.

Compatible scalar-window overlap now copies from the accepted texture into a separate admitted candidate. Exposed slabs upload through bounded packed staging. One source/encoding proof gates reuse, and one destination-write budget covers both paths. Complete-only publication retains accepted texture/origin ownership through cancellation, rejection and copy/write failure. See [contract and renderer API compatibility notes](../VOLUME_GPU_REUSE.md).

Eight new memory-backend contracts reconstruct every 3D destination write with independent coverage bits. They cover signed XYZ shifts, diagonal overlap, contained/expanded extents, arbitrary sub-row budgets, exactly-once coverage, unchanged accepted data, source/encoding/capability/identity fallback, disjoint signed domain ends, rejection without ownership mutation, cancellation, copy failure after zero/one successful regions, successful-byte accounting, legacy full-host retry, bounded gather validation/immutability and retained historical constructors/default backend behavior. Existing complete-texture, admission, field/cache and engine/hash contracts remain passing.

Twelve native scenarios execute with the ordinary context and an actual OpenGL 3.0 capability set on each platform. The Linux reports identify Mesa 4.5 compatibility and Mesa 3.0 with OpenGL31=false. Each context compares all **2,916,352 encoded float texels** across its scenarios against CPU data. The fields include signed values below zero/above one and an ordinary-to-extreme piecewise range. Independent Python reconstructs every global cell center, finite encoding and float32 bit pattern from raw binaries.

Representative measured transfer partitions are:

| Candidate change | Host bytes | GPU copied bytes |
|---|---:|---:|
| Half-window X/Y/Z move, 64³ | 524,288 | 524,288 |
| Diagonal move by 16 on all axes | 606,208 | 442,368 |
| Same 64³ window | 0 | 1,048,576 |
| Contained 32³ window | 0 | 131,072 |
| Expand back to 64³ | 917,504 | 131,072 |
| Disjoint/source/encoding fallback, 64³ | 1,048,576 | 0 |

All host+copy writes sum to exactly the candidate size, with at most 16,384 destination bytes per advance. Copying and full-host upload produce byte-identical complete RGBA framebuffers. Every incomplete advance retains the prior resident and framebuffer, even when a different render size is requested. The fixture binds distinct incomplete caller read/draw FBOs, enables an empty scissor, disables color masks, enables read clamping and binds a pixel-unpack buffer; copy/upload operations restore owned state and do not corrupt signed texels. Temporary copy attachments are detached before retirement.

See [normal raw report](volume-overlap-native.json) and [strict-3.0 raw report](volume-overlap-gl30-native.json). The [Linux evidence artifact](https://github.com/StealthMuskrat/ProceduralVoxelWorldVisualizer/actions/runs/40602416230/artifacts/12778608649) retains all per-case texel binaries, XML and the complete prior native report family. Local replay of both contexts and every downloaded World-picking reference passed.

The production Viewports fixture also records [resident-copy counters](volume-overlap-streaming-native.json) through signed travel, manual-source retention, explicit regeneration, canceled uploads, GPU/queue recovery and fixed return. CPU history and GPU residency differ: the two-chunk negative jump can reuse CPU chunks while its GPU windows are disjoint. A manual authored edit revalidates the unchanged accepted-source window with one full resident copy. Explicit source regeneration copies no old-source bytes. Existing project/history, source-epoch, optics and cancellation assertions remain unchanged.

Initial run 40602093207 failed compilation because the new fixture omitted its GL capability import. That import was restored. Review also corrected the transfer-counter expectation to reflect only the current resident GPU window, rather than the larger CPU chunk cache. The successful source run passes the original texel/framebuffer/ownership assertions, exact corrected transfer counts and strict-context independent replay. No rendering assertion or tolerance was weakened. The final evidence commit changes documentation/reports only.

Required local Gradle was attempted but Java cannot load libjli.so (exit 127). Local formatting, schemas/fixtures, Python syntax, diff checks and downloaded-report replay passed; pinned CI supplies compiler/native/package evidence.

This proves scoped transfer reuse, not a latency or throughput improvement. Separate full candidate allocation and total destination writes remain. There is no sparse atlas or coarser scalar LOD. Uploaded/last counters include all successful destination writes; host/copy accessors and profiler series split the paths. Renderer records gained components with legacy constructors retained; reflection/record-pattern clients must update. Canonical field samples, headless engine APIs, project schemas and archive formats are unchanged. Broader pressure/performance, arbitrary device faults, complete GUI/DPI and Windows 11 hardware acceptance remain separate; A28/A31 stay PARTIAL.
