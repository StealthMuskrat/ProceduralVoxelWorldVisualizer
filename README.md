# Procedural Voxel Creator

A native Java 25 procedural-noise and voxel-world editor, with a separately usable headless engine.

![World viewport](docs/evidence/world.png)

This is a **development implementation** of the supplied specification, not a completed A01–A36 release. See [acceptance status](docs/REQUIREMENTS_AND_TESTS.md) for verified behavior and outstanding work.

## Build and run

Install Eclipse Temurin **25.0.4+7** (x64). Gradle 9.2.1 is pinned by the wrapper.

```sh
./gradlew clean test build releaseJars
java --enable-native-access=ALL-UNNAMED -jar build/release/jars/ProceduralVoxelCreator.jar
```

On Windows use `gradlew.bat`. The application needs OpenGL 3.0 or newer. CI also exercises software OpenGL. Startup creates an empty, unsaved project with only the protected 2D Noise, 3D Noise and World Generator roots. Right-click a root to create content.

The Windows workflow packages a runtime and native dependencies as a downloadable Actions artifact after its smoke test succeeds. It does not publish a production release automatically.

## Independent headless engine

`build/release/jars/noise-generator-engine.jar` contains the engine and its non-UI dependencies. It includes no ImGui, OpenGL or LWJGL classes.

```sh
./gradlew :example-client:run
```

See [compiled expressions and batches](docs/COMPILED_EXPRESSIONS.md), [placement world-use controls](docs/WORLD_USE.md), [streaming and display LOD](docs/STREAMING_AND_LOD.md), [authoring and stage analysis](docs/AUTHORING_AND_STAGES.md), [typed field processing](docs/FIELD_PROCESSING.md), [API](docs/API.md), [build instructions](docs/BUILD_AND_RUN.md), [decisions](docs/DECISIONS.md), and the [original assignment](docs/SPECIFICATION.md).

Project-owned source has no open-source license grant. Third-party dependency notices are listed in [LICENSES.md](docs/LICENSES.md).

Exact palette storage, immutable bulk edits and v1/v2 archive compatibility are documented in [EXACT_STORAGE.md](docs/EXACT_STORAGE.md).

Structured validation, grouped logs and actionable notifications are documented in [DIAGNOSTICS_AND_RECOVERY.md](docs/DIAGNOSTICS_AND_RECOVERY.md).

Field-driven thresholds, base height and height scale are documented in [NUMERIC_PARAMETER_DRIVERS.md](docs/NUMERIC_PARAMETER_DRIVERS.md).

Queued priorities, cancellation cleanup and cache ownership are documented in [SCHEDULING_AND_CACHE_ADMISSION.md](docs/SCHEDULING_AND_CACHE_ADMISSION.md).

Bounded opt-in timing/metric captures, percentile inspection and explicit JSON/CSV exports are documented in [PROFILING.md](docs/PROFILING.md), including optional [GPU draw timestamps](docs/GPU_DRAW_TIMING.md).

Fragmentation recovery and explicit GPU maintenance are documented in [GPU_ARENA_COMPACTION.md](docs/GPU_ARENA_COMPACTION.md).

Context-aware remapping, transactional binding drafts and atomic preference persistence are documented in [KEYBOARD_INPUT.md](docs/KEYBOARD_INPUT.md).

Future-item defaults and transactional presentation/help settings are documented in [APPLICATION_PROFILES.md](docs/APPLICATION_PROFILES.md).

Theme preview, persistence and contrast feedback: [THEMES_AND_CONTRAST.md](docs/THEMES_AND_CONTRAST.md).

Large resource browsing, filtering and selection: [RESOURCE_BROWSING.md](docs/RESOURCE_BROWSING.md).

Resource pickers, membership editing and export previews: [RESOURCE_SELECTION_WORKFLOWS.md](docs/RESOURCE_SELECTION_WORKFLOWS.md).

Unified Find, precise references and protected navigation: [UNIFIED_FIND.md](docs/UNIFIED_FIND.md).

Mixed resource ordering and World folders: [RESOURCE_HIERARCHY.md](docs/RESOURCE_HIERARCHY.md).

Stable portable import previews and guarded publication: [IMPORT_TRANSACTIONS.md](docs/IMPORT_TRANSACTIONS.md).

Searchable coordinate bookmarks and tab-owned navigation dialogs: [BOOKMARKS_AND_NAVIGATION.md](docs/BOOKMARKS_AND_NAVIGATION.md).

Typed noise authoring, bounded controls and explicit dependency resolution: [NOISE_AUTHORING_WORKFLOWS.md](docs/NOISE_AUTHORING_WORKFLOWS.md).

The sampled-calculus and distance-shape extension adds twelve dimension-aware operations, canonical control metadata and creation defaults. See [field operation semantics](docs/FIELD_CALCULUS_AND_DISTANCE.md). Validation passed after implementation: 502 tests, native workflows, independent mathematical consumers and cross-platform packaging. Full GUI/hardware acceptance remains separate.

The trace-explanation extension adds bounded canonical noise observations, actual comparison/provenance records, one clipped Inspector/Logs view, bounded retained payloads and guarded UUID/control navigation. See [trace contracts](docs/TRACE_EXPLANATIONS_AND_NAVIGATION.md). Validation passed after implementation: 544 tests, native workflows, independent engine-only replay and cross-platform packaging. Full GUI/hardware certification remains separate.

The source-algorithm contract batch centralizes dimensional parameter signatures, named enum authoring, precise validation and creation metadata, with independent upstream C++/Python vectors and JVM raw-bit comparisons. See [source algorithm contracts](docs/SOURCE_ALGORITHM_CONTRACTS.md). Validation passed after implementation: 570 tests, native workflows, independent C++/Python vectors, matching pinned-JVM bit digests and cross-platform packaging. Hardware and full GUI certification remain separate.

The scalar-remapping batch adds weighted noise combinations, explicit falloff domains/metrics/shapes, signed terraces, descending remap clamping and canonical transfer previews. Validation passed after implementation: 599 tests, 548 independent vectors, native Apply/Undo workflows and cross-platform packaging. See [scalar controls](docs/SCALAR_REMAPPING_AND_BLENDING.md) and [evidence](docs/evidence/SCALAR_REMAPPING_VALIDATION.md). Full warp controls and GUI/hardware certification remain separate.

The shared-warp batch adds canonical algorithm/source controls, sampled displacement fields and independent/progressive/sequential octaves to both coordinate and processing stages. Validation passed after implementation: 633 tests, 432 independent vectors across 864 stage cases, native editing and cross-platform packaging. See [warp contracts](docs/WARP_FIELDS_AND_MODES.md) and [evidence](docs/evidence/WARP_CONTROLS_VALIDATION.md). Complete GUI/hardware acceptance remains separate.

Phased placement drivers now control sampling scale/offset and strength/probability with fixed baseline queries, explicit source validation and trace coordinates. Post-implementation validation passed 655 tests, native editing, independent replay and platform packaging. See [driver contracts](docs/NUMERIC_PARAMETER_DRIVERS.md) and [evidence](docs/evidence/PLACEMENT_DRIVER_PHASE_VALIDATION.md). Full GUI/hardware acceptance remains separate.
