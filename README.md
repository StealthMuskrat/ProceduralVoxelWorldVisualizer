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

Bounded opt-in timing/metric captures, percentile inspection and explicit JSON/CSV exports are documented in [PROFILING.md](docs/PROFILING.md).

Fragmentation recovery and explicit GPU maintenance are documented in [GPU_ARENA_COMPACTION.md](docs/GPU_ARENA_COMPACTION.md).
