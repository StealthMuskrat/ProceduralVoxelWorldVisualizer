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

## Placement world-use settings

Layer defaults and placement overrides now expose signed sample axes, component scales/offsets, an atomic ordered `samplingTransforms` list, FIXED/INTERVALS type sources and separate mapped `probabilityDriver`/`strengthDriver` objects. `WorldSemantics` and `registries/world-operation-registry.json` publish the vocabulary. TYPE_SELECTION requires interval types; ALL supports a parameter-only primary input. Existing defaults preserve the previous generation semantics. The mathematical contracts and format-2 compatibility policy are in [WORLD_USE.md](WORLD_USE.md); `Example.worldUseDemo()` is an engine-only authoring example.

GenerationTrace.Details adds `sampleCoordinates()`, `matchedInterval()` (nullable, zero-based), `candidateType()` and immutable `drivers()`. Each driver report includes sample, mapped factor, constant and effective constant×factor. GateEvaluation adds nullable `random()` for its independent probability draw. Existing constructors remain available; clients destructuring the records must update to the extended components. Unexecuted gates/drivers are omitted, and skipped gates/predicates retain null outcomes.

## Compiled expressions and bulk scalar sampling

Expression.Program now exposes immutable `inputs()` order, `statistics()`, `constantValue()`, lazy `scalarIndexed`/`boolIndexed`, and primitive `scalars`/`booleans` batches. Providers must return stable values during one evaluation; each successful input can be read once and reused. Full typed parsing precedes branch removal. No floating-point reassociation or invalid-boundary suppression occurs. NoiseCompiler.statistics(UUID) reports compilation counts and conservative work. The development-branch [semantic input-sharing extension](SEMANTIC_INPUT_SHARING.md) can increase shared-item counts while preserving this record shape; it is unvalidated. See [compiler contracts](COMPILED_EXPRESSIONS.md).

NoiseSampler2D/3D retain their functional `sample` and existing uniform-step grid methods. New grid overloads accept per-axis steps, destination offset, long row/plane strides and CancellationToken. New `samplePoints` overloads accept coordinate arrays, source offset/count and destination offset/token. 3D retains X-fast Z rows in Y planes. For example:

```java
double[] values = new double[48];
noise2D.sampleGrid(-2, 3, 4, 3, .5, -1, values, 2, 8, token);
noise2D.samplePoints(xs, zs, 0, xs.length, pointValues, 0, token);
```

Bounds, address products and coordinate extents preflight before writes. Coordinate/output overlap is rejected; padding and untouched tails are preserved. Cancellation checks occur at rows and at most every 256 samples. BatchSamplingException identifies the first failed traversal index and XYZ, with the original cause; a wrapped EvaluationFailure retains the underlying Expression.Problem span. Expression.BatchFailure provides analogous sample/cause detail for direct program batches. Successful prefix writes remain valid, while the failed sample and tail are untouched. Callers must keep coordinate arrays unchanged throughout the call. The authored project format and expression grammar did not change.

## Exact storage and archives

`VoxelChunk.withVoxel` and `withFilledBox(minX,minY,minZ,maxX,maxY,maxZ,type)` return immutable exact replacements; boxes are inclusive local coordinates and type 0 clears. Fully covered adaptive subtrees become uniform immediately. Retained storage is copied, so these are snapshot operations rather than a mutable per-cell staging API. `paletteRuntimeIds()` returns a defensive dictionary copy; `storageStatistics()` reports reachable storage and occupied cells.

Archives default to packed version 2. Readers accept versions 1 and 2 and expose `version()`. Use `openWorldWriter(path,registry,VoxelArchive.Format.LEGACY_V1)` for older readers. Existing raw `writeTree` remains v1; `writePackedTree` adds compact leaves and `readTree` accepts both. The project JSON version is unchanged. See [exact storage and wire contracts](EXACT_STORAGE.md) for layout, bounds, ownership and resource limits.

## Structured validation and compilation failures

`Validator.validate` now enriches available Diagnostic source/item UUIDs and parameter keys against the validated candidate. `DiagnosticLocations.enrich(project, diagnostics)` supports existing index and UUID-style paths without mutating the model. `CompilationFailure` extends IllegalArgumentException, exposes `diagnostic()` and retains the original cause, including Expression.Problem spans. Referenced failures preserve the innermost definition's identity. The public Diagnostic record shape remains unchanged. Editor event retention and source-navigation scope are documented in [DIAGNOSTICS_AND_RECOVERY.md](DIAGNOSTICS_AND_RECOVERY.md).

## Numeric parameter drivers

`Settings.parameterDrivers` is an ordered atomic-inheritance list of `NumericDriver` values. Set `enabled`, `destination` (threshold/baseHeight/heightScale), `combination` (REPLACE/ADD/MULTIPLY), optional `noiseGeneratorUuid` and mapping endpoints. Numeric output endpoints can be any finite signed doubles. `WorldSemantics` publishes the whitelist and mapping/composition helpers. FieldDriver is now sealed with NumericDriver as its canonical subtype; probability/strength properties retain their base shape and [0,1] output constraints.

`GenerationTrace.Details.parameters()` returns immutable ParameterEvaluation records containing row, destination, combination, source UUID, sample, mapped, previous and effective values. Previous seven- and eleven-argument Details constructors remain available and supply an empty list; record patterns/reflection require updating for the new component. The exact ordering, projection/cache behavior, failure policy and format-2 defaults are in [NUMERIC_PARAMETER_DRIVERS.md](NUMERIC_PARAMETER_DRIVERS.md).

## Queued priorities and cache admission

`generateChunkAsync(position, GenerationPriority)` and `generateChunkAsync(position, token, priority)` add INTERACTIVE/NORMAL/BACKGROUND queued service. Existing overloads default to NORMAL. RegionRequest adds priority as its fifth component and retains its four-argument constructor. Shared queued jobs can promote without duplication; admitted contexts take turns within weighted priority lanes. `schedulerStats()` exposes queue counts by priority, scopes and counters.

`CancellationToken.onCancel` returns a disposable registration; explicit cancel completes its subscribers and frees abandoned queued capacity immediately. Parent suppliers remain cooperative. Cancelling collectRegionAsync also cancels its internal stream and releases the collection lease after cleanup.

MEMORY_CACHE now uses the global budget and ignores cacheBytes. BOUNDED_MEMORY_CACHE retains its local LRU bound and can be reclaimed globally. USER_MANAGED never evicts implicitly, even when unpinned; unretained complete results still return and pinChunk returns false for them. CacheStats adds automaticEvictions and retentionRejected while preserving its prior constructor; its bytes now include a 128-byte per-entry allowance. Record-pattern/reflection clients must account for the new RegionRequest/CacheStats shapes. The ordering, ownership, reclamation and concurrency contracts are in [SCHEDULING_AND_CACHE_ADMISSION.md](SCHEDULING_AND_CACHE_ADMISSION.md).

## Bounded profiling

`engine.profiler()` is a standalone, headless capture owner. It starts disabled; `start(Profiler.Options)` replaces the previous capture, `stop()` retains it, `clear()` releases the owner's reference, and engine close stops it. Previously obtained immutable snapshots remain valid. `WorldContext.contextId()` is unique within that engine; `CompiledProject.projectUuid()` identifies the compiled snapshot.

```java
var capture = engine.profiler().start(new Profiler.Options(10000, 8192, 128, 256));
world.generateChunk(new ChunkPos(0, 0, 0));
engine.profiler().stop();
var snapshot = capture.snapshot();
ProfileIO.writeJSON(Path.of("profile-capture.json"), snapshot);
ProfileIO.writeCSV(Path.of("profile-capture.csv"), snapshot);
```

Each capture stops at its duration, sample or new-series limit. Operation stride is per calling thread and capture. `Profiler.Scope` binds custom editor/host timings and gauges to one capture; it cannot publish into a replacement capture. A zero begin stamp means disabled; callers can skip label construction for unsampled operations. There is no automatic file output or engine dependency on graphics/UI. On the unvalidated development branch, ProfileIO requires atomic destination replacement; unsupported filesystems fail without a non-atomic fallback. Callers own destination selection, including avoiding their application documents. Public method signatures and version-1 export formats are unchanged. See [capture semantics and limitations](PROFILING.md) and the v1 [JSON schema](../schema/profile-capture.schema.json).

Resource hierarchy extension (project format 2): `Project.rootOrder` is an optional sparse map for the three protected roots, and `Layer.parentFolderUuid` optionally names a World folder. These fields affect authoring presentation only. `ResourceGraph.children`, `shift` and `reorderBefore` use mixed presentation order; World-layer execution edits now use `ResourceGraph.shiftExecution`. The `worldGenerator.layers` array remains authoritative for execution. See [RESOURCE_HIERARCHY.md](RESOURCE_HIERARCHY.md) and `registries/resource-hierarchy.json` for migration, ownership, clipboard/export and historical-default details.

## Portable import drafts

`ResourceImport.Session(destination, incoming)` owns its inputs and caches COPY/REUSE/SKIP previews with stable copied UUIDs. `choose` invalidates the preview; `preview` returns an immutable plan whose `candidate()` accessor returns an owned copy. Use `document.edit(session::applyTo)` with the complete application validator. Publication rejects a changed destination before mutation. Plan's existing constructor/accessors remain available; mutating a returned candidate no longer changes the plan. See [import transaction rules](IMPORT_TRANSACTIONS.md).

## Coordinate bookmarks

`BookmarkTransactions` exposes three `Tab` scopes and immutable `Coordinates`. `capture(tab, x, y, z)` floors used axes and rejects nonfinite/out-of-range cells. Use `Draft.add` or `Draft.rename`, edit their provisional values, and commit with `document.edit(draft::applyTo)`. Rename rejects changed/deleted/cross-tab targets while merging unrelated edits; `update` and `delete` remain explicit document transactions. Names may repeat; UUID and tab identify the target. No helper changes generation or camera state. See [bookmark contracts](BOOKMARKS_AND_NAVIGATION.md).

## Private noise authoring plans

`NoiseAuthoring.plan(project, draft, Request)` prepares an owned compiler-validated structural edit. Requests cover move, duplicate, enable/disable, item delete and parameter duplicate/delete. Explicit resolutions retain a replacement reference, select Raw Source, or inline a parameter's current value. Invalid plans preserve the draft; `applyTo` rejects a changed draft base. Use the owning document transaction with the complete compiler validator for parent publication. `candidate()` returns an owned copy. No GUI classes are required. See [authoring workflows](NOISE_AUTHORING_WORKFLOWS.md).

## Sampled calculus and distance operations

`ProcessingRegistry.create(key, id, dimension)` validates dimension eligibility and creates the matching result signature. The existing two-argument factory retains its 3D default. `ProcessingExtensions.DETAILS` exposes immutable dimensions, per-dimension result types, control signatures and descriptions for GRADIENT, LAPLACIAN, MIN_FILTER, MAX_FILTER, MEDIAN_FILTER, PLANE, CAPSULE, CYLINDER, TORUS, ROUNDED_BOX, SMOOTH_INTERSECTION and SMOOTH_SUBTRACTION. Numeric values and Coordinates are distinct; gradients require an explicit Scalar consumer for final output.

See [field semantics](FIELD_CALCULUS_AND_DISTANCE.md) for exact stencils, shape formulas, median order, bounded scratch/work, parameter validation and compatibility. Format 2 remains unchanged; processing registry version 3 describes the added signatures.

## Noise explanations and trace provenance

`NoiseCompiler.explain(uuid,x,y,z[,limit])` returns an owned `NoiseTrace` with the exact sampled value, immutable typed events and omitted-observation count. Limits are 0..4096 (default 256); limits affect retention, never computation. `CompiledProject.explainNoise(uuid,seed,x,y,z,limit)` exposes the same low-volume diagnostic path. Ordinary compiled sampling has no observation hooks.

`GenerationTrace.Details` adds `settingTargets()` (actual layer/override UUID and logical key), `comparisons()` (effective reached comparisons) and `primaryField()` (bounded canonical primary-noise observations). Existing 7-, 11- and 12-argument constructors supply empty metadata. Record-pattern/reflection clients must migrate. World trace shares a 1024-event allowance across primary samples; other sampled fields retain their existing gate/driver/parameter values and UUID links. Top-level trace/step collections are now defensively copied.

See [trace semantics and limits](TRACE_EXPLANATIONS_AND_NAVIGATION.md) and `registries/trace-navigation.json`. Project/user/app schema shapes remain unchanged.

## Canonical source algorithm signatures

`NoiseAlgorithmRegistry.ITEMS`, `KEYS`, `parameters(key,dimension)` and `parameter(key)` expose immutable source signatures. Each parameter includes engine and narrower creation-preset ranges, default, integer/choice indices, dimensionality, visibility and help. `validate()` raises `NoiseAlgorithmRegistry.Problem` with the exact parameter key. `NoiseAlgorithms.compile` uses this contract; source formulas and 64-bit seed mixing are unchanged. Source compiler issues use `algorithm/parameters/<key>` for control navigation.

Known inactive values remain stored, with legacy global frequency and FastNoiseLite Cellular-index checks preserved. Negative active SDF sizes and nonfinite float narrowing now reject. Named choices remain numeric in serialized projects/profiles. See [compatibility and reference-vector policy](SOURCE_ALGORITHM_CONTRACTS.md). Project format remains 2; source registry version is 2.

Development addition (uncompiled and untested): indexed sampling-transform driver components are specified in [TRANSFORM_PARAMETER_DRIVERS.md](TRANSFORM_PARAMETER_DRIVERS.md). `NumericDriver.transformIndex` uses a resolved, zero-based authored transform position, defaulting to -1 for historical placement-wide targets. `ParameterEvaluation` adds nullable transformIndex and preserves its eight- and ten-argument constructors; record-pattern/reflection clients must account for the added component. World-operation registry v5 exposes six new component targets and their index metadata. Project format remains 2.

Editor-only development addition (uncompiled and untested): [property control focus](PROPERTY_CONTROL_FOCUS.md) routes existing UUID/logical-key destinations to production widgets and specialized warp bindings. Public diagnostic/event records, project formats and the headless API are unchanged.

Renderer development addition (uncompiled and untested): [volume display detail](VOLUME_DISPLAY_DETAIL.md) separates texel size from physical extent using sampleStep metadata in VolumeData, streaming plans/statistics and texture Region/Resident records. Historical constructors and unit sample methods remain; record-pattern/reflection clients must account for the added components. Project/user schemas and the headless engine API are unchanged.

Development addition (uncompiled and untested): [attributed work captures](WORLD_WORK_CONTRIBUTIONS.md) add Profiler.Options.worldContributions and OPERATION_WORK/LAYER_WORK kinds. The historical four-argument Options constructor defaults false. Record-pattern/reflection clients must account for the new option component. Capture JSON format remains 1 with an optional Boolean option and extended kind enums; raw Sample/Series shapes and CSV columns are unchanged. Opted-in generation reserves a bounded ledger before allocation; see the linked computation/outcome and admission contract.

Development-phase model behavior (uncompiled/untested): `DocumentSession.undo()` / `redo()` reconstruct and run the current session validator before publishing or moving history stacks. Nonempty diagnostics reject with `IllegalStateException`; validator/reconstruction runtime failures preserve active identity, deltas and accounting. Void signatures and persisted formats remain unchanged. Clients replacing validators must account for validation on history movement; see [history ownership](PROJECT_HISTORY_OWNERSHIP.md).
