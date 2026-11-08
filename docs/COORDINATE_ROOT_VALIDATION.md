# Coordinate root validation — unvalidated

W05-C08 covers the public coordinate/root boundary in specification 11.1.5, 11.2.1–11.2.3, 13.2.1 and 13.8.1. `ChunkPos.fromBlock` previously accepted unsupported dimensions, including 3 and 512, while zero leaked an arithmetic division exception. It now calls the existing `VoxelChunk.validateSize` before reading coordinates. The supported root sizes remain exactly **2, 4, 8, 16, 32, 64, 128 and 256**, shared with `WorldOptions`, chunk factories/decoding and `RegionBounds.chunkCount`.

An unsupported size throws `IllegalArgumentException`, including when the point is null. A supported size with a null point keeps `NullPointerException`. Valid points retain independent `Math.floorDiv` on each signed axis; local coordinates continue to use `Math.floorMod` in the engine. For example, block −1 at root 256 maps to chunk −1 and local 255. The sole production helper caller is `WorldContext.getVoxelTypeId`, whose immutable `WorldOptions` already validates its root. There is no public signature, schema, registry, authored format, numerical or ordering change.

The production helper gains boundary validation only. Arbitrary-precision arithmetic belongs to deferred expectations, not voxel hot paths. Engine request admission retains its existing long origin/endpoint check. Region bounds still use inclusive endpoints, checked long chunk counts and optional Y intersections; this follow-up does not change their implementation.

## Deferred verification definitions

Four `CoordinateBoundaryTest` definitions cover all eight roots with 128 mixed-axis vectors / 384 axis reconstructions, unsupported roots across five storage owners before callbacks or input consumption, root-before-null priority, valid-root null rejection, inclusive counts and clipping. Independent `BigInteger.divideAndRemainder` expectations adjust negative remainders to a floor quotient and nonnegative local index. Reconstruction also requires the mapped root's full extent to fit signed block coordinates.

The engine-JAR-only `CoordinateBoundaryConsumer` joins the existing `ProjectParseConsumer` command. It uses public APIs and JDK arithmetic without editor/render/native dependencies. Its deferred dimensions are:

| Boundary | Defined expectations |
| --- | --- |
| Root admission | 16 unsupported sizes across six public owners: 96 typed rejections, untouched input and zero sampler/mapper callbacks |
| Null ordering | 16 unsupported-root/null-point rejections; eight supported-root/null-point rejections |
| Signed mapping | 128 mixed-axis vectors / 384 exact axis reconstructions, including both signed extremes and negative/exact root boundaries |
| Inclusive regions | Seven regions per root: 56 cases, 47 fitting long counts and nine overflow rejections, plus 24 Y-clip controls |
| Independent fields | 256 raw-bit observations of coordinate expressions using exact integer-and-quarter expectations |
| World requests | 128 point lookups and 128 async EMPTY chunks across alternating interactive/background requests |
| Retained storage | Eight EMPTY chunks after world/engine closure, 64 extreme-local corners, empty occupied iteration/statistics and exact EMPTY palettes |

The region oracle includes full signed-axis spans, full signed-volume overflow, a root-2 count of 2^63 and an adjacent count below the signed-long maximum. Full signed project Y bounds ensure every point lookup reaches coordinate mapping. Empty world plans allow all eight roots to be exercised without dense root-256 generation. These definitions do not certify nonempty all-root generation, complete region streaming, concurrency/performance or renderer behavior. Caller JSON must remain exact after compilation and all requests.

Future successful execution writes `project-parse/coordinate-boundaries/coordinate-boundary-report.json` through the existing manual-only consumer workflow. No report has been generated and no workflow changed or dispatched. Existing consumer report dimensions remain separate.

## Status and remaining work

All additions are **uncompiled and untested** under the owner's testing pause. No compiler, Gradle, tests, native probe, consumer, checker, package, update request or workflow ran. Source review and Git whitespace/content/metadata checks add no runtime evidence. The observed baseline stays **922 tests / 120 suites**; no A01–A36, Windows 11 or release gate advances. Compile and execute the module and engine-JAR definitions on Linux/Windows after the pause, then complete the wider W05 caller, nonempty-root, scheduler, region and performance inventories. Publication remains pending on local main.
