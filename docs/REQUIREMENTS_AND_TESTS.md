# Requirements and test evidence

Status vocabulary: **PASS** means the stated automated scope passed; **PARTIAL** means some implementation/evidence exists but the entire acceptance item is not certified; **PENDING** means the required environment or scenario has not been verified. This development build is not a final acceptance release.

Local observed toolchains: Eclipse Temurin 25.0.4+7 and 25.0.5+8 x64, Gradle 9.2.1, Linux container. `clean test build releaseJars` passed with 60 JUnit tests. The independent javac/java engine consumer also passed. Native Linux and Windows Server 2025 smoke passed in Actions run 40045036266; screenshots and logs provide narrower native launch evidence, not full Windows 11 GUI acceptance.

| Acceptance | Status | Evidence / remaining scope |
|---|---|---|
| A01 empty startup | PARTIAL | Empty ProjectData defaults; normal GUI startup visual check remains |
| A02 transactional open | PASS | EngineContractTest.transactionalLoadUndoAndAtomicSave |
| A03 noise validation | PARTIAL | Enabled compiler validation and rejection tests; full control navigation remains |
| A04 disabled drafts | PASS | EngineContractTest.rejectsCyclesBadBindingsAndAllowsDisabledDraft |
| A05 save/transitions | PARTIAL | Atomic-save/document tests; all dirty dialog actions need GUI certification |
| A06 UUID clipboard | PARTIAL | ResourceTransactions remaps internal identities; folder/multi-selection scenarios remain |
| A07 import/export | PARTIAL | Dependency package + validated copy import; conflict policy breadth remains |
| A08 undo/redo | PARTIAL | Document snapshot transactions tested; full gesture audit remains |
| A09 noise families | PARTIAL | All adapters finite/repeatable; independent bit-vector matrix remains |
| A10 manipulations | PARTIAL | Typed math/logic/vectors/remap/curves/sampling; prefix resampling, six types, conversions and typed coordinate stages tested; broader registry/GUI acceptance remains |
| A11 canonical panel | PARTIAL | One definition and immutable compiler used by preview/API; pin/source navigation breadth remains |
| A12 expression language | PASS | MathContractTest: every function, operators, precedence, types, short-circuiting, source spans, limits |
| A13 curve editor | PARTIAL | Curve evaluator tests; complete tangent/multi-point GUI scenarios remain |
| A14 voxel identities | PARTIAL | Registry/archive remapping tested; manager interactions remain |
| A15 nested conditions | PASS | Separate recursive field/voxel evaluators; all seven base predicates including EMPTY behavior and nested groups tested |
| A16 inheritance/stages | PARTIAL | Three staged operations, zero override/live reset tests; complete operation inventory remains |
| A17 coordinates/Y | PASS | EngineContractTest and StorageContractTest negative/inclusive/clipping cases |
| A18 invariance | PARTIAL | Actual chunks at every root 2..256; sync/async/cache/seed tests; 32/64 golden scene hashes; broader scheduling matrix remains |
| A19 adaptive storage | PASS | Dense random oracle, sparse 256³, uniform/coalescence and occupied iteration |
| A20 255/256 | PASS | Sparse final local 255 plus RendererContractTest.meshBoundary256DoesNotWrap |
| A21 archives | PASS | Semantic remap, coverage, random read, unresolved IDs, invalid index and aborted write |
| A22 independent engine | PASS | External javac/java with engine JAR only; Example prints PASS |
| A23 contexts | PASS | Context seed/cache isolation and independent closure test |
| A24 cancellation | PARTIAL | Slow-consumer region cancellation tested; broader mid-stage cancellation stress remains |
| A25 backpressure | PASS | Consumer future holds max-in-flight capacity; bounded scheduling assertion |
| A26 failures/closure | PARTIAL | Generation and delivery CONTINUE/FAIL_FAST tested, plus context isolation; overload stress remains |
| A27 2D viewport | PARTIAL | Engine image sampling and modes implemented; tile cache/UI acceptance remains |
| A28 3D viewport | PARTIAL | Threshold, marching tetrahedra, points, ray-marched volume and X/Y/Z slices; numerical native volume probe; full streaming remains |
| A29 world inspector | PARTIAL | Engine stage replay and links; stage preview UI/richer provenance remains |
| A30 bookmarks | PARTIAL | Independent coordinate lists and navigation; rename/full gesture audit remains |
| A31 rendering LOD | PENDING | Exact data separate from display; production LOD/fallback not implemented |
| A32 appearance/workspace | PARTIAL | Theme/docking/scales/shortcuts persistence implemented; restart and DPI matrix remain |
| A33 utilities | PARTIAL | Real Find/Validate/Logs/Help/metrics; full filters/navigation/profiling scope remains |
| A34 desktop JAR | PARTIAL | Linux and Windows Server native JAR smoke passed; complete Windows 11 GUI acceptance remains |
| A35 Windows bundle | PARTIAL | Windows bundled runtime smoke and package passed; packaged launcher smoke passed in run 40047610767; full Windows 11 acceptance remains a separate gate |
| A36 final delivery | PARTIAL | Source/build/tests/fixtures/docs/checksum packaging; full release gates remain |

## Tree-to-test mapping

| Principal trees | Tests / checks |
|---|---|
| 0–4, 20 | EngineContractTest transactional persistence, validation, IDs; document and schema review |
| 5–8 | MathContractTest expression inventory, noise adapter repetition, curve policies; TypedProcessingTest, FieldSamplingTest and CoordinateTransformTest typed/lazy/prefix/immutable contracts |
| 9–10 | EngineContractTest layered fixtures and explicit override resolution |
| 11–12 | StorageContractTest coordinates, dense oracle, coalescence, sparse 256³ |
| 13–14 | EngineContractTest async/contexts/backpressure; independent Example; StorageContractTest archives |
| 15–16 | Native `--smoke` visits all tabs; RendererContractTest camera; remaining manual scenarios |
| 17 | RendererContractTest greedy faces and vertex 256; full LOD/performance pending |
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
