# Headless API

Public types are under `io.voxelcreator.api`, with coordinate/storage values under `io.voxelcreator.storage` and editable data under `io.voxelcreator.model`.

```java
var load = new ProjectLoader().load(Path.of("terrain.pvproj.json"));
if (!load.success()) throw new IllegalArgumentException(load.diagnostics().toString());
var result = new ProjectCompiler().compileWorld(load.project());
if (!result.success()) throw new IllegalArgumentException(result.diagnostics().toString());
var compiled = result.compiled();
try (var engine = new NoiseGeneratorEngine();
     var world = engine.openWorld(compiled, WorldOptions.defaults())) {
    var noise = compiled.noise2D("terrain", 42);
    double value = noise.sample(-1, 12);
    VoxelChunk chunk = world.generateChunk(new ChunkPos(-1, 0, 0));
    var task = world.streamRegion(RegionRequest.of(bounds), completed -> {
        consume(completed);
        return CompletableFuture.completedFuture(null);
    });
    RegionSummary summary = task.completion().join();
}
```

See `example-client/src/main/java/io/voxelcreator/example/Example.java` for a complete runnable consumer, including cancellation and archive IO. Engine and contexts implement AutoCloseable. A context owns its seed, cache, futures and region tasks. Completed snapshots survive eviction and closure.

Coordinates and region bounds are signed int; region corners and project Y limits are inclusive. Roots are exactly 2,4,8,16,32,64,128,256, default 64. Negative chunk addressing uses floorDiv/floorMod. Region streaming delivers complete intersecting chunks and reports requested and Y-clipped effective bounds.

Callbacks run on a separate region coordinator, never a generation worker. A callback's CompletionStage holds in-flight capacity until acknowledged. Cancellation stops scheduling and discards incomplete generation. Consumer failures are delivery failures and are never retried automatically. Failure detail retention is capped at 1024 entries; failure counts remain exact. Collection is a bounded convenience; streaming is the primary large-region interface.

Runtime voxel IDs are context-local: 0 is EMPTY. Project references use UUIDs. Archives use semantic voxel string IDs, explicit chunk coverage, independently compressed adaptive trees, and a checked random-access index. An omitted payload inside coverage is EMPTY; a position outside coverage returns Optional.empty(). Reader mappings never mutate the supplied registry.

Numeric semantics: source noise uses vendored FastNoiseLite 1.1.1 with double coordinate declarations and float outputs widened to double. StrictMath is used in authored expressions/curves. Hashing uses SplitMix64 finalization and high 53 bits divided by 2^53 for [0,1). Division by zero, invalid domains and executed nonfinite values raise failures; there is no implicit clamp. Expression power is right associative and binds above unary minus. Booleans have no implicit numeric coercion. `normalizedY` is `(y-minY)/(maxY-minY)`, or 0 for a degenerate interval; worldHeight is maxY-minY+1. Seed expression variables convert signed long to double; CoordinateHash retains all seed bits.

## Stage analysis

`WorldContext.stages()` lists stable layer/operation UUIDs and enabled before/after boundaries. `generateStageChunk(ChunkPos, UUID[, CancellationToken])` returns an immutable `StageChunk` containing exact `before()` and `after()` chunks; `changed(x,y,z)` includes removal to EMPTY. This does not change the final generation cache. Both snapshots finish before publication and remain usable after context closure. Stage memory uses bounded engine staging admission.

`GenerationTrace.Step.details()` exposes effective setting values, evaluated field-gate branches, nullable gate/predicate outcomes (null means not reached), probability/draw and selection state. The previous Step constructor remains available. See [authoring and stage semantics](AUTHORING_AND_STAGES.md).

## Shared requests and collected ownership

Concurrent sync/async requests for the same chunk share generation within one WorldContext. Futures and CancellationTokens remain per subscriber. Cancelling one subscriber preserves other requests; abandoning every subscriber cancels the shared computation at its cancellation boundaries. `sharedRequests()` and `inFlightChunks()` expose diagnostics. A synchronous call can run queued shared work inline, including from an engine worker. Context/seed boundaries never share requests.

`collectRegion` reserves from a shared engine collection budget (at most half the engine budget across all live results), alongside generation/cache accounting. Use owned results in a try-with-resources block:

```java
try (RegionResult result = world.collectRegion(RegionRequest.of(bounds))) {
    consume(result.chunks());
}
```

Closing releases admission; immutable snapshots remain valid. A Cleaner releases abandoned result leases as a fallback. RegionResult retains its two-argument constructor, accessors and value equality, but is now a class rather than a record: record patterns/reflection require migration. `EngineMetrics.collectionBytes()` reports these reservations. Prefer streaming for large regions. Contexts admit at most 16 region streams and 8192 pending chunk subscribers. Region counts beyond signed-long capacity fail with REGION_TOO_LARGE; chunk generation rejects block-coordinate overflow with COORDINATE_RANGE.
