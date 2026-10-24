# Requirements and test evidence

Status vocabulary: **PASS** means the stated automated scope passed; **PARTIAL** means some implementation/evidence exists but the entire acceptance item is not certified; **PENDING** means the required environment or scenario has not been verified. This development build is not a final acceptance release.

Local observed toolchains: Eclipse Temurin 25.0.4+7 and 25.0.5+8 x64, Gradle 9.2.1, Linux container. `clean test build releaseJars` passed with 219 JUnit tests. The independent javac/java engine consumer also passed. Native Linux and Windows Server 2025 smoke passed in Actions run 40045036266; screenshots and logs provide narrower native launch evidence, not full Windows 11 GUI acceptance.

| Acceptance | Status | Evidence / remaining scope |
|---|---|---|
| A01 empty startup | PARTIAL | Empty ProjectData defaults; normal GUI startup visual check remains |
| A02 transactional open | PASS | EngineContractTest.transactionalLoadUndoAndAtomicSave |
| A03 noise validation | PARTIAL | Candidate/innermost compiler locations, UUID navigation and rejected-draft preservation tested; full control GUI coverage remains |
| A04 disabled drafts | PASS | EngineContractTest.rejectsCyclesBadBindingsAndAllowsDisabledDraft |
| A05 save/transitions | PARTIAL | Atomic-save/document tests; all dirty dialog actions need GUI certification |
| A06 UUID clipboard | PARTIAL | Sibling/folder clipboard, internal/external reference and live cut identity tests; complete gesture GUI matrix remains |
| A07 import/export | PARTIAL | Dependency checkbox export and COPY/compatible REUSE/SKIP preview; conservative semantic reuse and dependent-closure tests |
| A08 undo/redo | PARTIAL | Structural delta transactions, bounded history, array reorder, undo/redo, curve gesture coalescing tests; full GUI audit remains |
| A09 noise families | PARTIAL | All adapters finite/repeatable; independent bit-vector matrix remains |
| A10 manipulations | PARTIAL | Typed math/logic/vectors/remap/curves/sampling; prefix resampling, six types, conversions and typed coordinate stages tested; broader registry/GUI acceptance remains |
| A11 canonical panel | PARTIAL | One definition and immutable compiler used by preview/API; pin/source navigation breadth remains |
| A12 expression language | PASS | MathContractTest and ExpressionPlanTest: every function, operators, types, lazy branches/spans, folding/sharing, nonrecursive flat plans, signed zero, nesting/threads/batches and limits |
| A13 curve editor | PARTIAL | Auto/Aligned/Free coefficient and identity tests; group snapping/tangent/local history; native curve render; complete GUI gesture matrix remains |
| A14 voxel identities | PARTIAL | Registry/archive remapping tested; manager interactions remain |
| A15 nested conditions | PASS | Separate recursive field/voxel evaluators; all seven base predicates including EMPTY behavior and nested groups tested |
| A16 inheritance/stages | PARTIAL | SET/CLEAR/NO_CHANGE stages, interval endpoints/gaps/overlap rejection, mapped probability/strength, signed axes/ordered transforms, live scalar/atomic list inheritance; ordered threshold/height mappings tested; full GUI acceptance and broader destinations remain |
| A17 coordinates/Y | PASS | EngineContractTest and StorageContractTest negative/inclusive/clipping cases |
| A18 invariance | PARTIAL | Actual chunks at every root 2..256; sync/async/cache/seed and scalar/batch/thread bitwise tests; 32/64 golden scene hashes; broader scheduling matrix remains |
| A19 adaptive storage | PASS | Dense random oracle, sparse 256³, uniform/coalescence, occupied iteration; local/chunk palettes, bit boundaries, dense fallback and immutable fills against a dense oracle |
| A20 255/256 | PASS | Sparse final local 255 plus RendererContractTest.meshBoundary256DoesNotWrap |
| A21 archives | PASS | Semantic remap, coverage, random read, unresolved IDs, invalid index and aborted write; independent v1/v2 vectors and packed corruption/termination checks |
| A22 independent engine | PASS | External javac/java with engine JAR only; Example prints PASS |
| A23 contexts | PASS | Context seed/cache isolation and independent closure test |
| A24 cancellation | PARTIAL | Immediate explicit-token queued cleanup, independent sharing, running abandonment/rejoin and async collection lease cleanup tested; broader mid-stage stress remains |
| A25 backpressure | PASS | Consumer future holds max-in-flight capacity; bounded scheduling assertion |
| A26 failures/closure | PARTIAL | Generation/delivery policies, bounded fair priorities, overload/closure cleanup, global cache reclamation and protected user-managed policy tested; broader stress remains |
| A27 2D viewport | PARTIAL | Engine image sampling and modes implemented; tile cache/UI acceptance remains |
| A28 3D viewport | PARTIAL | Threshold, marching tetrahedra, points, ray-marched volume and X/Y/Z slices; numerical native volume probe; streamed threshold/point display implemented; volume/isosurface streaming remains |
| A29 world inspector | PARTIAL | Exact stage pairs and four preview modes, removal ghosts, richer snapshot-correct trace/links; camera-driven stage/final streaming implemented; full GUI scenarios remain |
| A30 bookmarks | PARTIAL | Independent coordinate lists and navigation; rename/full gesture audit remains |
| A31 rendering LOD | PARTIAL | Exact shell seams, majority/tie aggregation, nearest-AABB hysteresis, AO, frustum, packed arena uploads, indirect/fallback parity and pressure tests; hardware/full GUI acceptance remains |
| A32 appearance/workspace | PARTIAL | Theme/docking/scales/shortcuts persistence implemented; restart and DPI matrix remain |
| A33 utilities | PARTIAL | Structured grouped logs, filters, validation results and notification actions; UUID source navigation tested; broader trace/profiling/manual recovery scope remains |
| A34 desktop JAR | PARTIAL | Linux and Windows Server native JAR smoke passed; complete Windows 11 GUI acceptance remains |
| A35 Windows bundle | PARTIAL | Windows bundled runtime smoke and package passed; packaged launcher smoke passed in run 40047610767; full Windows 11 acceptance remains a separate gate |
| A36 final delivery | PARTIAL | Source/build/tests/fixtures/docs/checksum packaging; full release gates remain |

## Tree-to-test mapping

| Principal trees | Tests / checks |
|---|---|
| 0–4, 20 | EngineContractTest transactional persistence, validation, IDs; document and schema review |
| 5–8 | MathContractTest expression inventory, noise adapter repetition, curve policies; TypedProcessingTest, FieldSamplingTest, CoordinateTransformTest, ExpressionPlanTest, CompilerOptimizationTest and SamplingBatchTest typed/lazy/prefix/folding/sharing/buffer contracts |
| 9–10 | EngineContractTest, WorldSettingsTest, WorldUseTest and WorldResourceTest: intervals, transformations, drivers, short-circuit gates, independent probability vectors, root/worker/stage invariance, imports and atomic replacement |
| 11–12 | StorageContractTest, PaletteStorageTest and PackedArchiveTest: coordinates, dense oracle, coalescence, sparse 256³, primitive palettes, bulk replacement and independent v1/v2 binary vectors |
| 13–14 | EngineContractTest async/contexts/backpressure; independent Example; StorageContractTest archives |
| 15–16 | Native `--smoke` visits all tabs; RendererContractTest camera; remaining manual scenarios |
| 17 | RendererContractTest, StreamingContractTest, ChunkSceneTest and native StreamingRenderProbe; representative hardware/full GUI performance pending |
| 18–19 | Editor service tests and native smoke; manual full UI routing pending |
| 21–22 | `clean test build releaseJars`, external javac/java, platform smoke, deterministic package checks |

## Reproducible GUI checks still required

1. Normal launch: only protected roots; create noise, edit and Apply; reject invalid expression without changing the document.
2. Save/Don't Save/Cancel across New/Open/Exit; failed save aborts transition; failed open retains document and dirty state.
3. Create voxel types/sets, layer and repeated placements; override/reset settings; reorder actions and inspect exact EMPTY and occupied coordinates.
4. Pan/zoom and all display modes; orbit/free-fly/Inspector focus; independent coordinate bookmarks across tabs.
5. Copy/cut/folder moves, import dependency conflicts, Undo/Redo, rename/delete dependencies.
6. Restart after theme/scale/layout/shortcut changes; verify preference changes never dirty project.
7. Complete every outstanding requirement in DECISIONS.md, rerun the full A01–A36 list, then perform Windows 11 hardware certification before production release.

Latest local raw evidence: [docs/evidence](evidence/README.md). Formal project/user/app schemas and positive/negative fixtures were validated with jsonschema 4.23.0.

The typed-field extension passed 56 local JUnit tests, formal schemas including sampled-fields.json, the external engine-only consumer, unchanged exact voxel regression hashes, and a native Linux three-viewport/save-open smoke run. The previous baseline Actions run 40048968302 passed both platform builds, bundled launcher and cross-platform identical JAR hashes. The typed-field extension subsequently passed the same cross-platform pipeline in run 40050919102.

The volume/slice extension passes 60 local JUnit tests. Native Linux smoke now asserts volume and all three slice planes completed; VolumeRenderProbe separately matches rendered center pixels to analytical absorption values. This adds renderer evidence without certifying the complete Tree 17 streaming/LOD requirements.

Authoring/stage batch validation adds CurveContractTest, CurveGestureTest, ResourceTransactionTest and StagePreviewTest. Native smoke visits Curve Editor and Final/Before/After/Only Changes in addition to volume and X/Y/Z slices. See the raw evidence for this batch; these scoped checks do not certify every A01–A36 scenario.

The streaming/runtime batch passes 95 JUnit tests. Added coverage includes same-context sync/async sharing, independent cancellation, collection ownership/admission and coordinate/count overflow; display aggregation/ties/boundary shells, packed 256 coordinates/material IDs, AO/seams, arena coalescing, hysteresis, pressure order, camera travel and stale-source publication. Native StreamingRenderProbe verifies bounded upload publication, frustum counts, multirow palettes, palette-only updates and pixel-identical indirect/fallback rendering. Desktop smoke additionally pans the streamed world. See [streaming design and limits](STREAMING_AND_LOD.md).

World-use validation passes 112 JUnit tests. New independent expectations cover interval inclusivity/singletons/gaps, transformation order/pivots/axis permutations, 2D-to-world-Y sampling and projection, driver endpoint math/composition, fixed probability vectors, gate short-circuiting, failed-publication safety, historical format-2 loading, root/worker/stage invariance and nested resource references/undo. The external engine-only consumer also exercises the portable world-use fixture and owned region results. These scoped checks do not certify every interactive Tree 10 scenario.

The compiler/batch extension passes 138 JUnit tests. Independent expectations cover folded arithmetic/branches, signed zero and strict operation order, precise executing spans after sharing, flat-expression stack safety, nested and concurrent frames, pure-item sharing with correct failing item UUID, primitive grid/point layouts and padding, coordinate/count overflow, alias rejection, first-failure prefixes/cause chains and bounded cancellation. ExpressionFeedbackTest covers cache invalidation/capacity and non-dirty display feedback. SIMD and representative hardware throughput are not certified by these checks.

The exact-storage extension passes 156 JUnit tests. New coverage includes all local palette widths and cross-word indices, high runtime IDs, large primitive interner growth, palette ownership, every root size, random inclusive fill/clear vs a dense oracle, concurrent readers and cancellation without snapshot mutation. Independent Python vectors exercise v1/v2 wire compatibility, semantic remaps and exact writer bytes; malformed headers, dictionaries, packed indices/padding/unused entries, compressed truncation/checksums/trailing bytes and writer cleanup reject without publication. The engine-only consumer, unchanged voxel hashes and schema family also pass. See [EXACT_STORAGE.md](EXACT_STORAGE.md).

The diagnostics/recovery extension passes 176 JUnit tests. New checks cover candidate path/UUID enrichment, escaped keys, nested operations and curve points, innermost referenced/transform/parser compiler errors, bounded concurrent grouped publication, independent read/resolve/dismiss state, long messages, composed filters, stale/deleted targets and rejected-draft/document/history invariance. Native smoke renders logs, notifications and validation results, groups an actual rejected expression error and navigates without discarding its draft or changing the document. See [DIAGNOSTICS_AND_RECOVERY.md](DIAGNOSTICS_AND_RECOVERY.md). These checks do not certify the entire Tree 19 GUI matrix.

The numeric-parameter extension passes 195 JUnit tests. New independent expectations cover finite signed/descending/constant/extreme mappings, threshold ties, ordered repeated destinations, height rounding, per-resource dimensionality and transformed-Y sampling, secondary-field cache safety, disabled/irrelevant/out-of-Y laziness, overflow without publication, snapshot immutability, worker/root/trace agreement, historical trace constructors and dependency closure/replacement/undo. Schema generation and the independent consumer exercise the portable parameter-drivers fixture. Native smoke renders the ordered numeric controls; complete interactive Tree 10 acceptance remains separate.

The scheduling/cache extension passes 219 JUnit tests. New contracts cover weighted lane service and per-scope FIFO/round robin, shared promotion, bounds/removal/draining/timeouts, cancellation callbacks and registration races, queued capacity release, running abandonment and later requests, parent-supplier lock isolation/failure, asynchronous collection cleanup, all cache policies, LRU/pins, cross-world reclamation, immutable snapshots, concurrent retention and balanced accounting. The independent engine consumer exercises priorities/region metadata and queue stats. Native smoke renders the real Profiler and all previous scenarios. These checks do not certify every scheduling race or representative hardware throughput.
