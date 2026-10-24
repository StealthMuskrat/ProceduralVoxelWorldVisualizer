# PROCEDURAL NOISE & VOXEL WORLD CREATOR
## Complete Implementation Tree — ChatGPT Work / GPT-6.1 Sol / High Thinking

> **Assignment:** Engineer, build, test, package, and deliver the fully functioning procedural-noise and voxel-world creation application defined below, including its independent Java Noise Generator Engine. This document is the complete authoritative work assignment. Treat it as a product specification, implementation plan, contract register, and release acceptance standard. Implement the software; do not stop after outlining a design or building a demonstrator.
>
> **Layer assignment and inheritance:** Noise generators can be added to World Generator layers. Each added noise generator is represented by a layer-local operation that references the reusable noise generator resource. It inherits the containing layer's applicable settings by default and can explicitly override individual settings for that placement. Overrides take precedence over layer defaults; resetting an override restores live inheritance. Placement settings never mutate the shared noise generator resource or other placements. See Tree 10 for the binding contract.
>
> **Execution model:** Work through the numbered tree in dependency order, maintaining a coherent, compiling repository. Every leaf requirement describes real functionality. Use the hierarchy as the work breakdown structure, not as a request to expose private reasoning. Use your own engineering judgment on internal optimizations, keeping observable contracts and determinism intact.
>
> **Platform and deliverables:** Java 25; one source repository; compiled, launchable **Windows desktop application JAR**; Windows launcher/distribution with the required Java runtime and native dependencies; separately compiled, importable **headless Noise Generator Engine JAR**; runnable independent Java consumer; source, Gradle scripts, tests, fixtures, licenses and instructions. Verify the final artifacts outside the IDE. The application is one complete standalone program.

---

# TREE 0 — AUTHORITY, WORK METHOD, AND DEFINITION OF DONE

- **0.1. Authority and reconciliation**
  - **0.1.1.** The functional and user-experience rules in this document are binding. Implement every applicable leaf rather than merely creating classes, menu entries, or decorative controls.
  - **0.1.2.** Where two clauses overlap, prefer the more specific, later-listed invariant; choose the smallest coherent clarification if necessary. Preserve the essential user-facing interaction and exact engine output.
  - **0.1.3.** Record actual technical conflicts, trade-offs, contract corrections, measurable optimizations, and their verification in `DECISIONS.md`. Keep source, schema, API and tests synchronized.
  - **0.1.4.** Make reasonable in-scope performance and implementation decisions autonomously: storage layout, memory arenas, noise optimization, scheduler structure, compiler passes, GPU allocation, safe threading, dependency/library choices, error reporting and tuning. Correctness and stable generation semantics override micro-optimizations.
  - **0.1.5.** Do not invent a new resource classification, incompatible persistence model, or duplicated engine implementation to make the UI easier.
  - **0.1.6.** Treat negative requirements here as enforceable invariants; avoid implementing silent behavior that the specification explicitly rules out.
- **0.2. Execution process**
  - **0.2.1.** Establish a multi-module Gradle repository, baseline models, versioned persistence and automated contract tests.
  - **0.2.2.** Build the deterministic headless Noise generator/compiler and exact world representation before wiring up graphical editors that depend upon them.
  - **0.2.3.** Implement renderer and UI with real engine data; demonstrate complete end-to-end workflows as they become available.
  - **0.2.4.** Compile and run automated tests frequently; repair failures before expanding dependencies on the broken subsystem.
  - **0.2.5.** Maintain a requirements-to-test matrix (`REQUIREMENTS_AND_TESTS.md`) mapping every principal tree branch to automated tests and/or reproducible GUI checks.
  - **0.2.6.** Work toward a finished Windows application rather than interpreting the tree as a list of independent examples.
  - **0.2.7.** Build a new repository and establish the collapsible right-side property-panel appearance from this written specification; no external existing UI must be matched. References throughout this document to the existing panel, layout or control style mean the consistent baseline established within this new application.
- **0.3. Completion gates**
  - **0.3.1.** `gradlew clean test build` succeeds on the repository.
  - **0.3.2.** Actual generated worlds and direct noise samples are reproducible across input partitioning, caching, worker count, and root-chunk size.
  - **0.3.3.** The three viewports, editors, project lifecycle, importer/exporter, inspector, profiler, settings and accessibility controls are working integrated features.
  - **0.3.4.** The desktop JAR launches using `java -jar` with the specified Windows environment, and the Windows distribution launches with its bundled runtime; native dependencies are available.
  - **0.3.5.** The engine JAR works from an independent Java client without the desktop, rendering, OpenGL, or ImGui modules.
  - **0.3.6.** Acceptance checks in Tree 22 pass with evidence; the release directory contains the tested files and a manifest of their checksums.

# TREE 1 — PRODUCT, TECHNOLOGY, AND MODULE OWNERSHIP

- **1.1. Product responsibility**
  - **1.1.1.** Author 2D and 3D procedural Scalar noise generators, edit their source settings, ordered noise manipulations and curves in the Noise Editor panel, combine them into an ordered world-generation pipeline, and inspect a deterministic voxel world.
  - **1.1.2.** Support projects with user-created reusable noise generators and voxel definitions, without tying the noise generator library to a game or editor process.
  - **1.1.3.** Separate noise generator *field evaluation* from voxel *interpretation and modification*.
  - **1.1.4.** Noise generators are ordinary resources regardless of usage as selection fields; no special Mask, Template or Preset resource classes, resource flags or protected child folders. Resource classification and organizational folders introduce no inheritance; noise generators added to World Generator layers do inherit applicable layer settings and may override them as defined in Tree 10.
- **1.2. Technology choices**
  - **1.2.1.** Java 25, Gradle multi-module builds, JUnit 5 and regression fixtures.
  - **1.2.2.** LWJGL 3 including GLFW, OpenGL and STB; JOML for spatial math; custom lightweight renderer.
  - **1.2.3.** Dear ImGui Java binding supporting docking and multi-viewport windows; standard property-panel controls for all noise authoring features.
  - **1.2.4.** Jackson for JSON; FastUtil or other primitive collections in hot paths.
  - **1.2.5.** FastNoise Lite behind a stable noise-algorithm adapter; additional algorithms implemented through the same contract.
  - **1.2.6.** Java concurrency, bounded executors/queues, primitive arrays and explicit memory budgets.
- **1.3. Modular architecture**
  - **1.3.1. `project-model` / `schema`:** Editable projects, IDs/UUIDs, folders, settings metadata, validators, codecs and resource transactions; no GPU requirements.
  - **1.3.2. `engine-core`:** Scalar noise, expressions, curves, noise-processing compiler, world operations and immutable compiled execution plans.
  - **1.3.3. `voxel-storage`:** Exact adaptive chunks, immutable snapshots, caches, binary archives and spatial utilities.
  - **1.3.4. `engine-api`:** Supported public Java types, contexts, requests, scheduling, diagnostics and samplers.
  - **1.3.5. `editor-render`:** OpenGL context, camera systems, 2D previews, voxel/volume visualizations, meshing and display LOD.
  - **1.3.6. `editor-ui`:** Window shell, resource tree, property editors, Noise Editor panel, managers, viewport integration, dialogs, preferences, logs and help.
  - **1.3.7. `example-client`, `tests`, `desktop-package`:** Independent API example, fixture/GUI/integration tests, Windows launch and distribution.
  - **1.3.8.** Exact module names may vary; enforce the dependency direction `editor -> engine-api/core/storage`, never `engine -> editor/render`.
- **1.4. Thread ownership**
  - **1.4.1.** UI and OpenGL state are controlled by their owning threads.
  - **1.4.2.** Worker threads evaluate immutable compiled snapshots, schedule CPU jobs and pass immutable completed data to the render thread.
  - **1.4.3.** Never mutate published chunk data; keep mutable editor settings and processing items out of per-voxel runtime evaluation.

# TREE 2 — THREE CONFIGURATION DOMAINS AND PROJECT LIFECYCLE

- **2.1. Project Config (explicit-save document)**
  - **2.1.1.** Holds project UUID, project seed, inclusive vertical limits, noise generators and complete editable noise-panel definitions, the one World Generator with ordered layers, their default settings, and ordered noise generator placements/operations with explicit per-placement overrides, voxel definitions/groups/sets, ordinary folders and per-tab coordinate bookmarks.
  - **2.1.2.** Persists only on explicit Save/Save As or user-approved Save during a document transition.
  - **2.1.3.** Never embeds user theme/layout/window scales/shortcuts, compiled bytecode, undo stacks, live chunks or binary world archives.
- **2.2. User Config (preferences, independent persistence)**
  - **2.2.1.** Stores appearance/theme colors, dock layouts, window position/docking/visibility and per-window scale overrides.
  - **2.2.2.** Stores keyboard shortcuts and conflict-free bindings, editor/navigation/viewport display preferences, preview settings, worker/render budgets and performance-related controls.
  - **2.2.3.** Organize the User Settings window into Appearance, Workspace, Input, Editor, Visualization and Performance categories.
  - **2.2.4.** User preferences may be persisted as they change, without triggering project dirty state or a project save.
- **2.3. App Config (central UI metadata)**
  - **2.3.1.** Centralize control IDs, labels, short tooltips, descriptions, option lists and default UI control values; expose the supported application metadata controls in Application Settings.
  - **2.3.2.** Keep help metadata consistent with the UI; user-editable display descriptions must not modify procedural computation.
  - **2.3.3.** Validate App Config and safely use built-in definitions on malformed UI metadata; never silently change deterministic engine behavior.
  - **2.3.4.** Application Settings may edit presentation labels, tooltips, help text and presentation grouping/order, and explicit new-item control defaults validated against immutable registry signatures/ranges. Apply creation defaults only to future user-created items and persist concrete created values in the project. Keep computational operation IDs, valid options, signatures and ranges authoritative in the versioned registries; metadata/default edits do not change existing definitions or load/evaluation semantics.
- **2.4. Settings navigation**
  - **2.4.1.** Settings menu: `Project Settings`, `Application Settings`, `User Settings`, `Debug & Profiling`.
  - **2.4.2.** Project Settings edits project seed, Y bounds and other world-generation settings transactionally.
  - **2.4.3.** Application Settings edits centralized application metadata and control presentation; User Settings is the only authoritative editor of persisted user preferences.
  - **2.4.4.** Debug & Profiling controls diagnostics and instrumentation; duplicate entry points must link to one backing preference or profiler state, not conflicting copies.
  - **2.4.5.** Provide convenient compact settings access near the bottom-right area as well as the menu.
- **2.5. Startup and initial project**
  - **2.5.1.** Initialize required services before presenting the application window; show the ready editor directly, without intermediate startup UI.
  - **2.5.2.** Open one new, unsaved, empty project with the three protected root tree items only: `2D Noise`, `3D Noise`, `World Generator`.
  - **2.5.3.** Create no initial user folders, noise generators, layers, voxel types, sets or bookmarks; the empty World Generator is legal and produces EMPTY.
  - **2.5.4.** Start in the default dark theme and normal dock layout, using persisted User Config when available.
- **2.6. File menu and saving**
  - **2.6.1.** File menu exact primary actions: `New`, `Open Project`, `Save`, `Save As`, `Import`, `Export`, `Exit`.
  - **2.6.2.** Save defaults to Ctrl+S, remappable in User Settings; record project dirty state precisely.
  - **2.6.3.** Before New/Open/Exit/window close with unsaved project changes, show `Save`, `Don't Save`, `Cancel`; canceled or failed saving aborts the transition.
  - **2.6.4.** Save writes a temporary sibling file, flushes appropriately, and replaces the destination safely/atomically where supported; preserve the previous valid destination on failure.
  - **2.6.5.** Opening a project is transactional: parse and validate a separate candidate model first; replace the active project only when the candidate fully passes required validation.
  - **2.6.6.** Display precise errors including JSON paths, resources/processing items/parameters and compatibility information when opening fails; preserve the current project and its dirty state.
  - **2.6.7.** No recent-project history, last-project automatic reopening, project autosave, crash-recovery file workflow, migration/repair wizard or automatic project backup workflow.
- **2.7. Project format contract**
  - **2.7.1.** Use an explicit `formatVersion` to identify the project file structure.
  - **2.7.2.** Use arrays of objects containing UUID fields for resources and explicit ordering for layers/operations, rather than deriving semantic execution order from maps.
  - **2.7.3.** Canonical top-level structure contains `formatVersion`, `project`, `voxelTypes`, `voxelGroups`, `voxelSets`, `noise2D`, `noise3D`, `worldGenerator`, `folders`, `bookmarks`.
  - **2.7.4.** `worldGenerator` is a single object, never a list of user-selectable World Generators.
  - **2.7.5.** `bookmarks` has `noise2D`, `noise3D`, `world` arrays, independent of source noise generator selection.
  - **2.7.6.** Reject unsupported project format versions and unknown incompatible fields, malformed UUIDs, duplicate identities and inconsistent references. Do not silently migrate or repair documents.
  - **2.7.7.** A committed noise definition must be valid when applying an editor transaction and when loading a project. Provisional panel inputs may contain invalid values until commit; invalid commits are rejected while preserving the existing committed definition.
  - **2.7.8.** Structurally valid disabled experimental processing items can remain serialized and are never executed while disabled; unrelated unreferenced noise generators remain valid. Enabled items cannot depend on disabled results.
  - **2.7.9.** Permit incomplete expression text and missing execution-only inputs inside disabled items if their serialized structure and identity/reference integrity are valid. Supported item kinds, serialized shape, UUID/reference integrity, acyclic references and the prohibition on enabled dependencies on disabled results still apply. Validate full expression/type/required-input semantics transactionally before enabling; validate every enabled committed item even if not selected as output. Apply the broad committed-definition and expression-validation clauses using this explicit disabled-item boundary.
- **2.8. Independent file formats**
  - **2.8.1.** Editable Project JSON stores the whole authoring configuration.
  - **2.8.2.** Selected-resource Import/Export packages store only chosen portable resources and necessary dependencies, with transactional identity resolution.
  - **2.8.3.** Binary voxel archives store exact generated semantic voxel results and can be read without the original editor project.

# TREE 3 — RESOURCE IDENTITY, TREE, CLIPBOARD, IMPORT/EXPORT, AND UNDO

- **3.1. Identities**
  - **3.1.1.** Every project resource (noise generator, folder, voxel type, group, set, layer, operation, noise-processing item, shared parameter, curve point as applicable) has a hidden stable UUID.
  - **3.1.2.** A resource has only one visible primary `ID`; do not duplicate it as `Display Name` or a second `User-Facing ID`.
  - **3.1.3.** References use UUID, never mutable visible IDs. Validate uniqueness in the appropriate resource/parent namespace; normalize ID comparisons consistently.
  - **3.1.4.** Renames preserve UUID. Duplicates create new UUIDs and generated collision-safe IDs `name-copy`, `name-copy-2`, etc.
  - **3.1.5.** Make 2D noise IDs unique project-wide within 2D noise and 3D IDs unique project-wide within 3D noise; scope layer IDs to the world, operation IDs to their layer, item/parameter IDs to their noise definition, folder IDs to sibling folders within a root, and group/set IDs project-wide within each kind. Keep voxel-type IDs project-wide unique.
  - **3.1.6.** Require nonempty IDs without leading/trailing whitespace or control characters; require NFC form at edit commit and reject noncanonical incoming project IDs instead of silently repairing them; compare case-sensitively and reserve the exact voxel ID EMPTY.
- **3.2. Tree and hierarchy**
  - **3.2.1.** Three protected roots are fixed; they cannot be renamed, deleted or relocated.
  - **3.2.2.** User-created folders are purely organizational. Their position in the tree does not change noise generator semantics or introduce category-specific behavior.
  - **3.2.3.** Drag/drop supports hierarchy and reordering within a valid root; prevent moving incompatible resource types across roots or creating folder cycles.
  - **3.2.4.** Tree context menus support Create, Rename, Duplicate, Copy, Cut, Paste, Delete, and reorder/move actions; F2 starts inline rename.
  - **3.2.5.** Tree multi-selection permits only siblings sharing the same immediate parent; show a consolidated selection summary rather than mixed-value batch editing.
  - **3.2.6.** The World Editor can independently multi-select related objects within its own context, without general batch property editing.
  - **3.2.7.** Resource tree and Properties are dockable and individually undockable/redockable; docking layout persists in User Config.
  - **3.2.8.** Show all dependents; allow cancel or a validated replacement reference/explicit dependent edit committed atomically with deletion; otherwise block deletion.
- **3.3. Project-session clipboard**
  - **3.3.1.** Clipboard is scoped to the current project session; clearing/replacing/closing the project clears resource clipboard state.
  - **3.3.2.** Copy/paste duplicates resources with new UUIDs; copied internal dependencies point to copied resources, while external dependencies continue to point at original UUIDs.
  - **3.3.3.** Copying a folder recursively duplicates its subtree and remaps its internal references as one undoable transaction.
  - **3.3.4.** Cut/paste moves the existing resource, preserving UUID; source removal occurs only after the destination operation succeeds.
  - **3.3.5.** Standard same-project paste resolves trivial visible-ID collisions automatically with `-copy-N` naming.
  - **3.3.6.** System text clipboard continues to work for text inputs independently of the project resource clipboard.
- **3.4. Import/Export**
  - **3.4.1.** Export dialog shows a dependency-aware, checkbox-selectable resource tree and communicates precisely which dependencies will be included.
  - **3.4.2.** Import presents an explicit preview of resource conflicts, semantic compatibility, UUID and ID collisions, and proposed resolution/remapping choices.
  - **3.4.3.** Import validates the entire payload before commit, remaps inter-resource UUID references deterministically, and commits atomically or not at all.
  - **3.4.4.** A portable exported resource set is never treated as a complete editable project or exact voxel archive.
  - **3.4.5.** Import of voxel definitions/group JSON validates colors, members and conflicts before changing the project.
  - **3.4.6.** Allow reuse only after semantic compatibility is validated; otherwise skip an incoming resource and its now-invalid dependents, or import remapped copies with collision-safe IDs. Preview the complete valid transaction and never replace existing resource definitions.
- **3.5. Undo/Redo**
  - **3.5.1.** Maintain transaction-based delta history, not serialized full-project snapshots.
  - **3.5.2.** Coalesce slider dragging, processing-item reordering, input-binding edits, curve gestures, resource moves and folder copies into logical transactions.
  - **3.5.3.** Each temporary editor/dialog has private provisional state and local history; Apply commits one parent project transaction, Cancel discards it, closing discards temporary history.
  - **3.5.4.** Save state/dirty tracking must reflect effective project changes, including undoing back to the saved state.
  - **3.5.5.** Navigation, focus, camera movement, viewport pan/zoom, dock position and other User Config state do not enter project Undo history.
  - **3.5.6.** Undo/Redo stacks are never written into projects, exported resources or other persistent project data.

# TREE 4 — NUMERIC CONTROLS, EDITING CONVENTIONS, AND GLOBAL USABILITY

- **4.1. Universal control language**
  - **4.1.1.** Use checkboxes for Boolean properties; searchable dropdowns for large choice sets; collapsible sections for related settings.
  - **4.1.2.** Numeric properties present a slider with appropiate min/max ranges plus editable numeric text field to the right of it; retain engine values as double and display up to five decimal places without repeatedly rounding stored data.
  - **4.1.3.** Controls use context-relevant short tooltips drawn from centralized UI metadata.
  - **4.1.4.** Responsive popovers, menus, tooltips and dialogs clamp to available monitor work areas, including secondary monitors and detached windows.
- **4.2. Keyboard/mouse preferences**
  - **4.2.1.** Frequent actions have editable keyboard shortcuts with conflict detection and safe reset defaults.
  - **4.2.2.** Defined camera and editor mouse gestures remain consistent and are not exposed as a general custom mouse-binding system.
- **4.3. UI scaling and DPI**
  - **4.3.1.** Offer a global UI scale defaulting to 100%, with per-window overrides, approximately 50–250%, persisted in User Config.
  - **4.3.2.** Combine user scale with per-monitor DPI sensitively, avoiding illegible fonts and inaccessible dialogs.
  - **4.3.3.** Window resizing and scaling preserve navigable layouts and reachable critical controls.
  - **4.3.4.** Accessibility acceptance scope: none beyond the explicit source provisions in this specification, including scaling, contrast feedback, remappable shortcuts and non-color error indicators.
- **4.4. UI performance**
  - **4.4.1.** Virtualize large tree/list/table datasets and debounce costly searches.
  - **4.4.2.** Keep drawing the current valid preview while rebuilding or compiling a changed resource asynchronously.
  - **4.4.3.** Never block the UI thread on region generation or GPU upload queues.

# TREE 5 — NOISE GENERATOR RESOURCE DEFINITIONS AND NOISE ALGORITHMS

- **5.1. Noise Generator semantics**
  - **5.1.1.** A noise generator evaluates a mathematical Scalar field at 2D or 3D coordinates; it does not directly create, replace or delete voxels.
  - **5.1.2.** Each noise generator owns UUID, visible ID, dimension, algorithm/source parameters, seed offset/configuration, ordered coordinate transforms, ordered processing items, shared parameters, optional pinned controls and a Scalar output selection.
  - **5.1.3.** Each 2D/3D noise generator has its own complete panel-authored noise definition. Noise generators can be added to World Generator layers through layer-local operations that reference this definition by UUID and inherit/override applicable layer settings. The reusable definition retains its source algorithm, algorithm parameters, internal transforms and processing; layer inheritance applies to its world-use settings, not implicit rewriting of the shared source definition. Noise generator relationships are explicit UUID references selected through property controls; there is no implicit project-wide signal routing.
  - **5.1.4.** An unused or unreferenced noise generator remains fully editable and previewable, is omitted from world compilation if unreachable, and never triggers an unused-resource warning.
- **5.2. Noise Editor panel**
  - **5.2.1.** Right-side Properties contains the Noise Editor panel: ID, dimension/type, searchable noise algorithm chooser, seed settings, algorithm-specific parameters and collapsible manipulation sections described in Tree 6. Keep the existing property-panel layout and control style throughout.
  - **5.2.2.** Noise generator creation requests a unique visible ID and offers an optional initial noise-algorithm choice; default is an OpenSimplex-family source, seed offset 0, no coordinate transforms or processing items, and the raw source Scalar selected as output.
  - **5.2.3.** Display only parameters supported by the selected algorithm or manipulation; use numeric slider/text pairs, checkboxes and appropriate searchable dropdowns. Vector and coordinate controls use labeled component rows in the same style.
  - **5.2.4.** Source settings and all manipulation sections operate on **one shared definition** compiled by the same headless engine; never create a copied secondary evaluation pipeline.
- **5.3. Required algorithm families**
  - **5.3.1.** Gradient noise: OpenSimplex2-style and Perlin, dimension-appropriate.
  - **5.3.2.** Value noise and Value Cubic.
  - **5.3.3.** Cellular/Worley noise, with documented distance/return variants.
  - **5.3.4.** White/Hash deterministic coordinate noise.
  - **5.3.5.** Fractal processing: fBm, ridged, billow and ping-pong, with octaves/frequency/lacunarity/gain where applicable.
  - **5.3.6.** Domain warping, transforms, gradients, constants, coordinate functions, procedural shapes and distance fields.
  - **5.3.7.** Algorithm registry declares stable IDs, supported dimensions, parameter types/defaults/ranges and deterministic sampling semantics.
- **5.4. Seed and consistency**
  - **5.4.1.** Define explicit stable 64-bit mixing of runtime/project seed, noise generator UUID, optional noise generator seed offset and coordinates where random sampling is required.
  - **5.4.2.** Do not seed from mutable resource-tree order, collapsed section state, panel scroll position, worker ID, chunk size, processing order or display LOD.
  - **5.4.3.** Preserve algorithm identity and normalization settings in diagnostics and fixtures; noise values are raw signed doubles unless a specific mapping is explicitly defined.
  - **5.4.4.** For a fixed project/algorithm format and engine version, require identical raw double bits and exact voxel results across every certified JVM/CPU configuration in the supported matrix; document and test the matrix.
- **5.5. Direct use**
  - **5.5.1.** Expose both single-point `sample` and batch/grid sampling to the headless API.
  - **5.5.2.** Cache or precompute dimensional constants where possible, including 2D-on-XZ reuse through Y, without affecting numerical results.

# TREE 6 — NOISE EDITOR PANEL MANIPULATIONS AND COMPILER

- **6.1. Panel layout and interaction**
  - **6.1.1.** All noise generator authoring lives in the existing right-side Noise Editor/Properties panel. Retain ID, dimension/type, algorithm chooser, seed controls and algorithm-specific settings at the top; add manipulation features below in collapsible sections using the same spacing, label/control alignment and styling.
  - **6.1.2.** Use labeled slider plus numeric-text pairs for numeric properties, checkboxes for Boolean properties, searchable dropdowns for algorithms/modes/resources, and labeled component rows for vectors/coordinates. Show only relevant controls for the selected operation and dimension; obtain tooltips from App Config.
  - **6.1.3.** Coordinate transforms and post-source processing each use ordered lists of compact, collapsible property items. Item headers show ID, operation, enable checkbox, evaluation index and compact move/duplicate/delete actions. `Add Transform` and `Add Processing` use searchable, categorized menus filtered by valid input/result types.
  - **6.1.4.** Keep one panel and one canonical noise generator definition. Do not introduce a separate advanced signal-routing workspace, movable processing boxes, connection wires, sockets, canvas navigation, frames or reroute controls. Curve plots remain available through the existing Curve Editor.
  - **6.1.5.** Duplicate a processing item with a new UUID and collision-safe ID; its external/shared input references remain unchanged. Noise generator duplication and resource clipboard operations remap copied internal item/parameter UUIDs transactionally. Item reorder/delete actions must reject invalid dependent bindings or present an explicit resolution before commit.
  - **6.1.6.** Disabled items remain editable and serializable but do not execute. Section collapse, panel scrolling and optional presentation labels do not alter evaluation. Changes to committed parameters/order/enable state invalidate affected previews through the existing change-driven regeneration rules.
  - **6.1.7.** Category sections organize available features and their property controls; they do not impose an additional execution order. Show the stored global processing index on each item and provide a compact ordered summary for cross-category move actions within the same panel.
- **6.2. Typed input controls and evaluation order**
  - **6.2.1.** Preserve six computational types: `Scalar` (IEEE 754 double), `Boolean`, `Vector2`, `Vector3`, `Coordinates2D` and `Coordinates3D`. Coordinates represent sample locations and remain semantically distinct from value/direction/displacement vectors.
  - **6.2.2.** Each operation input is a labeled property row with an explicit source selector: `Constant`, `Shared Parameter`, `Ambient Value`, `Source Result`, `Earlier Result` or `Noise Generator Reference`, restricted to valid choices for that stage. Edit constants inline; select other sources with searchable dropdowns that resolve stable UUIDs.
  - **6.2.3.** Start with the current evaluation coordinates, apply enabled coordinate-transform items in stored order, evaluate the configured source at the resulting coordinates, then evaluate enabled processing items in stored order. The Output section selects the source Scalar or an enabled Scalar result; it defaults to the last enabled Scalar result when adding a Scalar processing item, and any output change is explicit and undoable.
  - **6.2.4.** A coordinate-transform item may use ambient coordinates, constants, shared parameters, earlier coordinate-stage results or noise generator samples; it cannot reference the source result or later processing. Post-source items may reference the source or enabled earlier processing results. Reject forward/self references and cross-noise-generator dependency cycles, with a complete dependency path in diagnostics.
  - **6.2.5.** Multiple input rows may reference the same earlier result or shared parameter without duplicating its value. Required inputs must be supplied; optional inputs use documented typed constant defaults. Bindings display their resolved source and provide navigation to the originating control.
  - **6.2.6.** Arithmetic operations use documented signatures such as Scalar×Scalar, Vector×Scalar and matching Vector+Vector. Scalar→Vector, Boolean↔Scalar, Coordinates↔Vector, Vector2↔Vector3 and Coordinates2D↔Coordinates3D require an explicitly configured conversion, projection or slice. Projections identify dropped axes; dimensional expansion identifies the injected value and axis.
  - **6.2.7.** A vector/coordinate/Boolean intermediate result is permitted, but the noise generator's selected final output must be Scalar. Type-incompatible choices show a precise explanation; never silently convert values or change procedural semantics.
  - **6.2.8.** Distinguish a numeric value input from a sampled-field input in the operation registry and UI. A sampled-field selector chooses the source field, an enabled earlier item’s field prefix or a noise generator reference; persist its stable target and explicit coordinate/sampling settings. Prefixes include their declared upstream transforms and processing, never the requesting item or later results.
  - **6.2.9.** Require selection of the source Scalar or another enabled valid Scalar before applying the delete/disable; commit the output change and item edit atomically as one Undo transaction.
- **6.3. Noise Generators section**
  - **6.3.1.** Provide additional dimension-appropriate algorithm-source sampling and Noise generator Reference controls, plus fBm, ridged, billow and ping-pong fractal processing. Expose algorithm, seed offset, frequency, octaves, lacunarity, gain and other supported parameters using the existing algorithm-panel control style.
  - **6.3.2.** Provide noise blending and combination with explicitly selected Scalar inputs, arithmetic/min/max modes, blend factor or field-driven weight, and documented normalization/clamping policies. Extra sources use the same algorithm registry and engine implementation as the primary source.
  - **6.3.3.** Fractal and warp settings must identify the sampled source/reference or upstream field prefix, octave seed derivation and coordinate domain. Never approximate resampling by manipulating only a previously sampled numeric value.
- **6.4. Math section**
  - **6.4.1.** Provide add, subtract, multiply, divide, powers, modulo, absolute value, min/max, rounding, floor/ceil, trigonometry and thresholds, with named operand rows and documented valid types.
  - **6.4.2.** Expose operation-specific parameters only when applicable; define division/modulo by zero, invalid domains and nonfinite results consistently with Tree 7. Retain raw signed doubles unless an explicit manipulation changes their range.
- **6.5. Logic & Selection section**
  - **6.5.1.** Provide comparisons, AND/OR/NOT, branch/select and compound conditions through compact nested condition rows and searchable input selectors. Expose true/false Scalar or matching typed alternatives for Select; evaluate only the selected alternative where the operation requires short-circuit behavior.
  - **6.5.2.** Conditions use Boolean results; conversions between Boolean and Scalar require an explicit operation. These field conditions remain separate from the World Generator's voxel-state predicates.
- **6.6. Remapping & Curves section**
  - **6.6.1.** Provide `Remap Range` with Input Min/Max, Output Min/Max, Clamp Output and a compact visual preview; Curve/ramp with `Edit Curve`; terrace with editable levels and transition controls; and falloff with explicit domain, extent and shape controls.
  - **6.6.2.** Reuse Tree 8's exact curve model, interpolation, tangent, endpoint and out-of-range contracts. Terrace and falloff expose documented interval/tie-breaking behavior and preserve values outside 0..1 unless explicitly clamped.
- **6.7. Coordinates & Transforms section**
  - **6.7.1.** Provide translate, rotate, scale, axis selection, dimensional projection/slice and domain warp. Show X/Z controls for 2D and X/Y/Z controls for 3D, explicit angle units, pivot and axis ordering, and explicit inserted slice coordinate where required.
  - **6.7.2.** Domain warp exposes warp algorithm or noise generator sources, displacement components, amplitude, frequency, applicable fractal controls, seed offset and warp order/mode. Apply displacement to sampling coordinates and evaluate the selected field at the displaced coordinates.
  - **6.7.3.** Domain-changing post-source operations explicitly choose the upstream field prefix or referenced noise generator to resample; they cannot resample themselves or a later result. Sampling a prefix reevaluates its compiled field definition at the requested coordinates, preserving declared transform order.
- **6.8. Vectors section**
  - **6.8.1.** Provide compose/decompose, component selection, dot, 3D cross, length, normalize and value transforms, with labeled components and typed input selectors. Define zero-length normalization and dimension-specific signatures explicitly.
  - **6.8.2.** Explicit conversion controls handle Scalar replication, Boolean/Scalar mapping, Coordinates/Vector conversion and vector dimension changes without concealing their effect.
- **6.9. Shapes & Distance section**
  - **6.9.1.** Provide dimension-appropriate SDF primitives, distance fields, gradients and spatial fields with center/position, radius/extents, axis and other relevant property rows. Include explicit selection of primitive/field type and sign convention.
  - **6.9.2.** Provide union, intersection, subtraction and smooth combinations with typed field selectors and smoothness controls. Document distance/combination semantics and expose a Scalar result suitable for later manipulation or output.
- **6.10. Sampling & Filters section**
  - **6.10.1.** Provide offset sampling, deterministic derivatives, neighborhood field filters and smoothing. Expose sampled field/reference or upstream prefix, axis/offset, sample spacing, radius/kernel, weighting and strength only where relevant.
  - **6.10.2.** Define derivative stencils, filter kernels, normalization, bounded work limits and sampling order. Evaluate actual neighboring procedural coordinates with the same deterministic engine, including across chunk boundaries; do not derive values from preview pixels, mesh resolution or display LOD.
- **6.11. Random & Utility, Expression, and Parameters sections**
  - **6.11.1.** Provide deterministic Coordinate Hash with stable seed/coordinate controls and Constants for Scalar, Boolean, Vector2 and Vector3. Coordinate functions expose applicable axes/context values through ordinary property selectors.
  - **6.11.2.** Provide Expression items with an editable expression field, expandable typed named-input rows, selected result type, inline parser/type feedback and optional expanded text editing; use the complete deterministic language in Tree 7.
  - **6.11.3.** A `Parameters` subsection manages shared Scalar, Boolean, Vector2 and Vector3 values with stable UUIDs, editable IDs and the existing numeric/checkbox/component controls. Multiple processing inputs may reference the same shared value.
  - **6.11.4.** `Pin Control` optionally places a frequently used parameter in a compact panel group, referencing the original parameter identity rather than copying its value. Editable presentation labels/grouping do not change computation; input-bound controls show the resolved source and `Go To Control` instead of a misleading editable constant.
- **6.12. Noise generator references and navigation**
  - **6.12.1.** Store referenced noise generator UUID, not visible ID; show the current visible ID resolved live. Default sampling uses current evaluation coordinates; alternate sampling explicitly selects transformed coordinates, offsets or a supplied coordinate value.
  - **6.12.2.** A 3D noise generator may sample a 2D noise generator through explicit X/Z or another chosen projection. A 2D noise generator may sample a 3D noise generator through an explicit slice with a supplied Y or other missing axis.
  - **6.12.3.** References participate in dependency invalidation and compiler cycle detection. Provide `Open Referenced Noise Generator`, `Find References` and `Select Resource in Tree` beside the relevant resource picker or in its context menu.
- **6.13. Validation presentation**
  - **6.13.1.** Display compact error/warning counts near the panel header and a navigable issue list. Mark affected sections/items/controls using icons and inline messages; optional type badges supplement labels and never rely on color alone.
  - **6.13.2.** `Go To Control` opens the affected noise generator, expands the relevant section/item, scrolls to and focuses the failing field. Explain missing inputs, incompatible types, invalid ordering, noise generator cycles and expression spans with corrective context.
  - **6.13.3.** Distinguish Error (blocks affected active execution), Warning (valid but risky/expensive) and Information (helpful, nonblocking). Unused noise generators are not warned about solely for being unused.
- **6.14. Compilation and evaluation**
  - **6.14.1.** Compile the canonical panel-authored definition into an immutable typed execution plan on model changes or validated committed snapshots.
  - **6.14.2.** Perform parser/type checking, parameter/UUID resolution, ordered-input validation, constant folding, dead-branch elimination, safe common-subexpression elimination, dependency analysis, dimensionality analysis and primitive-friendly batch scheduling.
  - **6.14.3.** Preserve valid disabled experimental items in editable JSON but exclude them from compiled runtime. Compile only the dependency closure of the selected Scalar output and requested direct samplers or enabled world operations; independent valid noise generators remain usable.
  - **6.14.4.** Do not evaluate runtime values through per-voxel traversal of mutable editor objects or heap allocations per cell. Reuse typed intermediate buffers and compiled field prefixes for repeated sampling.
  - **6.14.5.** Report invalid/nonfinite numeric results at defined processing/operation boundaries with resource UUID, item UUID, parameter and coordinates in diagnostics, using a deterministic explicit policy.
  - **6.14.6.** Raise a structured evaluation failure at the first executed invalid/nonfinite boundary with resource/item/input/coordinate context; fail that sample or staged chunk and apply the existing request failure policy.

# TREE 7 — DETERMINISTIC EXPRESSION LANGUAGE AND PANEL CONTROLS

- **7.1. Language purpose**
  - **7.1.1.** Provide a restricted, safe, stateless mathematical DSL for fine-grained Scalar and Boolean transformations in the Noise Editor panel’s Expression section.
  - **7.1.2.** Each Expression processing item has explicitly declared typed named inputs configured as property rows using the input-binding controls in Tree 6; its result type is inferred/checked against its selected result type.
  - **7.1.3.** Compile once through tokenize → parse → AST → type check → constant fold → immutable evaluator/batch plan; never reparse per sample.
- **7.2. Named ambient read-only variables**
  - **7.2.1.** Coordinate/context variables, only where meaningful: `x`, `y`, `z`, `worldMinY`, `worldMaxY`, `worldHeight`, `normalizedY`, `projectSeed`, `noiseGeneratorSeed`.
  - **7.2.2.** Define `worldHeight` from inclusive vertical bounds consistently and `normalizedY` from the declared interval, handling degenerate bounds without nonfinite values.
  - **7.2.3.** Document whether variables are 2D/3D applicable and how numeric seeds are converted into Scalar expression values; keep deterministic use of the full seed in the Coordinate Hash controls.
- **7.3. Grammar and operators**
  - **7.3.1.** Numeric literals, typed named-input identifiers and constants `pi`, `e`, `tau`.
  - **7.3.2.** Unary `+`, `-`, `!`; binary arithmetic `+`, `-`, `*`, `/`, `%`, `^` (power, with documented associativity).
  - **7.3.3.** Comparisons `<`, `<=`, `>`, `>=`, `==`, `!=` with a documented type policy; Boolean `&&`, `||`, `!`; ternary `condition ? trueValue : falseValue`.
  - **7.3.4.** Parenthesized precedence. Short-circuit Boolean and ternary expressions to prevent evaluating branches not selected.
  - **7.3.5.** Explicit Scalar/Boolean typing; disallow truthy implicit Scalar→Boolean or Boolean→Scalar coercion.
- **7.4. Full function inventory and defined signatures**
  - **7.4.1.** Trigonometric/hyperbolic: `sin`, `cos`, `tan`, `asin`, `atan`, `atan2`, `sinh`, `cosh`, `tanh`.
  - **7.4.2.** Numeric/rounding: `abs`, `min`, `max`, `floor`, `ceil`, `round`, `fract`, `sqrt`, `cbrt`, `pow`, `exp`, `log`, `log10`, `sign`.
  - **7.4.3.** Mapping/ranges: `clamp`, `lerp`, `inverseLerp`, `step`, `smoothstep`, `mod`, `saturate`, `remap`, `wrap`, `pingPong`.
  - **7.4.4.** Create a function registry specifying each arity, valid types, argument order, edge-case semantics, limits and deterministic reference vectors.
  - **7.4.5.** Define division/modulo by zero, logarithm/sqrt of invalid inputs, overflow and nonfinite results explicitly. Provide precise diagnostics and, if included, a clearly named safe-division utility with documented behavior.
- **7.5. Language safety and limits**
  - **7.5.1.** No assignment, mutation, executable Java, reflection, file/network/system access, I/O, clock access, arbitrary external calls, custom function definitions, recursion or loops.
  - **7.5.2.** Use the dedicated deterministic Coordinate Hash controls rather than an unseeded/random-time function; noise generator lookup uses the panel’s Noise Generator Reference selectors and typed named-input bindings.
  - **7.5.3.** Enforce bounded token count, expression length, AST depth/size and execution complexity, with user-visible diagnostics.
  - **7.5.4.** Validate expressions against the supported grammar, types and function signatures when loading a project.
  - **7.5.5.** Include source-span-aware parser/type messages and matching project/panel validation entries, with navigation to the Expression item and offending input or text span.

# TREE 8 — CURVE, GRADIENT-LIKE SCALAR RAMPS, AND REMAPPING EDITORS

- **8.1. Shared curve representation**
  - **8.1.1.** Use one reusable Scalar Curve data model and editor for nonlinear remapping, falls-offs and scalar gradient/ramp transformations.
  - **8.1.2.** Store curve UUID, configured input domain, output display range, control points with stable identities, per-segment interpolation and tangent information, and outside-domain policy.
  - **8.1.3.** Default two endpoints `(0,0)` and `(1,1)`, Linear, Clamp; visible grid enabled, snapping disabled.
  - **8.1.4.** Ordinary noise generator outputs remain Scalar; UI RGB color controls are separate from procedural Scalar ramps.
- **8.2. Point and domain invariants**
  - **8.2.1.** At least two points, sorted strictly by X; duplicate X values are invalid.
  - **8.2.2.** Endpoint X positions are locked to the configurable domain min/max; endpoint Y is freely editable.
  - **8.2.3.** Interior X values may not cross their neighbors; validate multi-point transforms for ordering and spacing before commit.
  - **8.2.4.** Permit negative outputs and values above 1 when the output range/curve requires them; never apply hidden output clamping.
  - **8.2.5.** Keep exact double values for storage/evaluation; UI shows at most five decimals.
- **8.3. Interpolation per segment**
  - **8.3.1. `Step`:** Explicit left/right knot behavior; deterministic handling at the exact boundary.
  - **8.3.2. `Linear`:** Standard straight line between successive points.
  - **8.3.3. `Smooth`:** Shape-preserving monotone cubic or equivalent, with no unintended overshoot.
  - **8.3.4. `Bezier`:** Controlled tangents/handles respecting single-valued X→Y evaluation; selectable Auto, Aligned, Free handle modes with validation.
  - **8.3.5.** Mix interpolation choices segment-by-segment in the same curve.
- **8.4. Out-of-range input semantics**
  - **8.4.1. `Clamp`:** Use nearest endpoint output; default mode.
  - **8.4.2. `Extend`:** Continue the endpoint slope/tangent under a documented rule.
  - **8.4.3. `Repeat`:** Wrap into configured input domain with defined endpoint seam behavior.
  - **8.4.4. `Mirror`:** Repeat alternating travel direction across domain intervals.
- **8.5. Curve Editor UI**
  - **8.5.1.** Resizable dedicated window/dialog with plot area, grid/axes, pan (middle drag), zoom (wheel), selected point inspector and numeric X/Y fields.
  - **8.5.2.** Click selects; drag moves; double-click line inserts a point; Delete removes selected interior points; right-click opens actions.
  - **8.5.3.** Support multi-select and group move/delete, respecting mandatory endpoints and X ordering.
  - **8.5.4.** Independent Snap X, Snap Y, snap increments and grid visibility; modifier such as Alt temporarily bypasses snapping.
  - **8.5.5.** Tangent handles visible and draggable for Bezier; render active interpolation accurately.
  - **8.5.6.** Opening the dialog creates an isolated edit context; Apply validates and commits one parent undoable change, Cancel discards.
- **8.6. Related controls and execution**
  - **8.6.1.** A collapsible `Remap Range` processing item in the Noise Editor panel exposes Input Min/Max, Output Min/Max and Clamp Output with a compact visual preview; nonlinear processing uses a Curve item with `Edit Curve` access to the existing Curve Editor.
  - **8.6.2.** Compile immutable sorted point arrays and interpolation coefficients; use deterministic direct evaluation or validated acceleration that preserves reference behavior.
  - **8.6.3.** Preview changes respect change-driven generation limits and never derive numeric results from plot resolution or renderer LOD.

# TREE 9 — VOXEL TYPES, GROUPS, SETS, AND IDENTITIES

- **9.1. Voxel identity levels**
  - **9.1.1.** Project resource UUID: hidden identity that references a Voxel Type in editable configurations.
  - **9.1.2.** User-facing `ID`: editable semantic name (for example `stone`); must be unique among voxel types.
  - **9.1.3.** Runtime integer ID: compact context-local integer, with `0 = EMPTY`, occupied types strictly positive.
  - **9.1.4.** Binary archive ID: the semantic string ID, stored through a file-level dictionary, never a transient runtime integer or project UUID.
  - **9.1.5.** `EMPTY` means there is no occupied voxel; it is not a user-created Voxel Type.
- **9.2. Voxel Type data and editing**
  - **9.2.1.** Every Voxel Type has `{uuid, id, color}`; color uses opaque RGB components 0..255.
  - **9.2.2.** Default type color is light gray (suggested RGB 200,200,200).
  - **9.2.3.** Color edits update the renderer GPU palette/lookup table; they should not force geometry remeshing.
  - **9.2.4.** Type deletion and semantic-ID changes display existing dependencies and require safe reference-handling choices.
- **9.3. Voxel Groups**
  - **9.3.1.** Organize voxel types and group-related display metadata/colors; do not affect voxel-generation selection logic by membership alone.
  - **9.3.2.** Store group UUID/ID and references to member type UUIDs with stable round-trip serialization.
- **9.4. Voxel Sets**
  - **9.4.1.** Define reusable semantic membership lists of Voxel Type UUIDs, with their own UUID and visible ID.
  - **9.4.2.** Set membership changes invalidate dependent world operations and preview caches.
  - **9.4.3.** Open a temporary searchable multi-checkbox membership editor with Apply/Cancel and one Undo transaction on Apply.
  - **9.4.4.** Voxel-set references are validated on load, copy, import, compile and deletion.
- **9.5. Voxel Manager**
  - **9.5.1.** Provide `Voxel Types` and `Voxel Sets` tabs and appropriate group organization controls.
  - **9.5.2.** Search/filter and virtualize lists to handle large projects; show color swatches, IDs and dependency navigation.
  - **9.5.3.** Type/Group/Set import validates the entire JSON, conflicts, color ranges and references before a single project transaction.

# TREE 10 — WORLD GENERATOR LAYERS, OPERATIONS, AND SELECTION SEMANTICS

- **10.1. World Generator invariant**
  - **10.1.1.** The editor project contains exactly one inherent World Generator.
  - **10.1.2.** Its visible layers execute top to bottom; within a layer, added noise generators execute through their layer-local operations in list order.
  - **10.1.3.** Generation begins with a completely EMPTY world; the empty layer list is valid.
  - **10.1.4.** Each layer/operation has stable UUID and editable ID; enable/disable and ordering changes invalidate relevant generation.
  - **10.1.5.** Users can add one or more 2D or 3D noise generators to a layer. Each addition creates a layer-local operation/placement with its own UUID, a `noiseGeneratorUuid` reference, and initially empty overrides; this is the same operation model used throughout this document, not a second pipeline or resource class.
  - **10.1.6.** The same reusable noise generator may be added to multiple layers or more than once to the same layer. Each placement independently inherits its containing layer settings and owns its overrides; adding it does not move or duplicate the source resource in the 2D/3D Noise resource tree.
  - **10.1.7.** Initialize new layer defaults with NO_CHANGE and no required type target; newly added placements inherit those defaults and remain valid. Require a valid type before committing a switch to SET.
- **10.2. Layer default values and inheritance**
  - **10.2.1.** Layer default properties include base height, min/max Y, sample coordinate transforms, axes/scale/offset, thresholds or selection conditions, default action/type, existing-state conditions, strength and seed offset where applicable.
  - **10.2.2.** Each noise generator added to a layer inherits that layer's applicable settings through its operation/placement. For each inheritable property, resolve `effectiveValue = placement override if present, otherwise containing layer default`. Explicit overrides take precedence even when their value is `0`, `false`, or equal to the current layer default; absence of an override means live inheritance.
  - **10.2.3.** Show inheritance clearly on every added noise generator's layer-local settings, with the resolved value, its layer/override source, and per-property `Override` and `Reset to Layer Default` actions. Reset removes the stored override. An override changes only that placement; it does not mutate the layer defaults, reusable noise generator definition or other placements.
  - **10.2.4.** Project Min Y/Max Y remains the authoritative outer vertical interval. Resolve each placement's min/max Y from layer defaults and any per-property overrides, then intersect the resolved interval with the project bounds; an override can replace a layer default range value but cannot extend generation outside project bounds.
  - **10.2.5.** Editing a layer default updates all placements inheriting that property; placements with an explicit override retain their override. Moving a placement to another layer resolves its inherited values from the new layer and preserves its explicit overrides. A disabled layer never executes its placements.
  - **10.2.6.** Inheritance covers applicable world-use settings listed in 10.2.1, including sampling transforms, axes/scale/offset, selection thresholds, action/type, previous-state conditions, strength and operation seed offset. These settings control how the referenced field is sampled and interpreted; the resource's own algorithm parameters, internal transform/processing order and resource seed offset remain part of its canonical field definition.
  - **10.2.7.** Example: with layer strength `0.5`, two newly added noise generators both use `0.5`. Overriding the first placement to `0.8` changes only that placement. Changing the layer strength to `0.6` updates the second to `0.6` while the first stays `0.8`; resetting the first override makes it inherit `0.6`.
  - **10.2.8.** Publish a fixed inheritable-property registry. Override scalar/component properties individually, including minY/maxY and declared axis/scale/offset components; replace each ordered transform list, field-gate tree and voxel-predicate tree atomically. Missing properties inherit live and null is not an override-removal sentinel.
- **10.3. Operation inputs**
  - **10.3.1.** Each added noise generator's operation references a 2D or 3D resource by UUID and uses its Scalar field under the placement's resolved inherited/overridden settings. Optional additional ordinary noise generator references supply field-selection inputs; they do not create additional layer placements implicitly.
  - **10.3.2.** Transform sampling coordinates before evaluating input fields.
  - **10.3.3.** Define explicit field interpretation, selection/threshold gates, previous voxel-state condition, requested action and Voxel Type source.
  - **10.3.4.** Expose stable seed/probability/strength controls only with clearly documented effects.
  - **10.3.5.** Reusable mask-like selection is achieved by referencing normal noise generators as field inputs, never by assigning special metadata to the noise generator resource.
- **10.4. Accepted field interpretations**
  - **10.4.1. 2D Height Field:** Interpret X/Z sample with configured base height/scale/rounding against Y; specify solid-above, solid-below or boundary selection deliberately in operation settings.
  - **10.4.2. 2D Selection:** Turn X/Z Scalar into a conditional selection field under explicit comparisons, thresholds or intervals.
  - **10.4.3. 2D Type Selection:** Use scalar intervals or a defined mapping to choose a voxel type from configured candidates.
  - **10.4.4. 2D Strength/Parameter Driver:** Supply a continuously varying control for operation thresholds, probability or other documented numeric parameters.
  - **10.4.5. 3D Density:** Compare volume sample against an explicit density threshold to select occupied/selected cells.
  - **10.4.6. 3D Volume/Selection:** Evaluate volumetric scalar criteria directly at XYZ; expose explicit comparison/region behavior.
  - **10.4.7. 3D Type Selection and parameter roles:** Resolve type/strength consistently using documented mappings and field sources.
  - **10.4.8.** Make interpretation names, thresholds, rounding, tie-breaking, intervals and `NO_CHANGE` behavior exact and inspectable rather than leaving ambiguous implicit logic.
  - **10.4.9.** Allow an explicitly bound field to drive placement probability or strength with an explicit [0,1] mapping and documented composition with constant settings.
  - **10.4.10.** Reject intervals that overlap after applying their explicit endpoint inclusivity; a sample matching no interval produces NO_CHANGE.
- **10.5. Existing-voxel predicates**
  - **10.5.1. `Any`:** Match every cell.
  - **10.5.2. `Empty`:** Match only absence of an occupied voxel.
  - **10.5.3. `Occupied`:** Match only cells with runtime ID >0.
  - **10.5.4. `Voxel Type Is` / `Is Not`:** Test UUID-equivalent identity on **occupied** voxels only; EMPTY matches neither predicate, with explicit documented semantics.
  - **10.5.5. `In Set` / `Not In Set`:** Test occupied voxel membership in a Voxel Set; **EMPTY matches neither predicate**.
  - **10.5.6.** Build recursive finite `AND` / `OR` groups of voxel-state predicates, separate from compound AND/OR **field-selection** gates.
  - **10.5.7.** Validation and editor controls preserve exact predicate tree grouping and prevent invalid empty logical groups.
- **10.6. Fundamental actions**
  - **10.6.1. `SET`:** Write a chosen occupied Voxel Type runtime ID to selected cells.
  - **10.6.2. `CLEAR`:** Make selected cells EMPTY.
  - **10.6.3. `NO_CHANGE`:** Leave the currently committed voxel state unchanged.
  - **10.6.4.** Replacement, recoloring by type, carving and conditional filling are expressed by these actions plus predicates and mappings, rather than as overlapping operation semantics.
- **10.7. Mandatory deterministic execution pipeline**
  - **10.7.1.** Resolve every added noise generator placement's effective settings from its containing layer defaults and explicit per-placement overrides before sampling or voxel interpretation.
  - **10.7.2.** Transform world/sample coordinates and evaluate field inputs through the compiled noise engine.
  - **10.7.3.** Interpret field values and compute selection/strength/type candidates.
  - **10.7.4.** Evaluate compound field-selection gates.
  - **10.7.5.** Read *previously committed* voxel state and evaluate the compound voxel-state predicates.
  - **10.7.6.** Resolve deterministic probability, type choice and `SET` / `CLEAR` / `NO_CHANGE` action.
  - **10.7.7.** Commit this operation's changes as a logical stage; then execute the next operation.
  - **10.7.8.** An operation sees earlier completed operations' writes but never reads its **own partial writes** during its own evaluation.
  - **10.7.9.** Operation writes and type decisions cannot depend on chunk traversal, job completion, worker thread, region slicing, cache hit/miss or visualization quality.
- **10.8. Deterministic probability and dependency resolution**
  - **10.8.1.** Define a stable coordinate hash from effective world/noise generator/operation seed inputs, stable operation UUID and integer XYZ; map to a well-defined probability interval.
  - **10.8.2.** Define comparison and probability-edge behavior at 0 and 1; fix bit-level test vectors.
  - **10.8.3.** Ordinary noise generator field sampling can read transformed/neighboring coordinates using deterministic evaluation. Voxel-state predicates operate on same-cell previously committed state under these defined operations.
  - **10.8.4.** Maintain coherent world-coordinate semantics at chunk boundaries; operations cannot accidentally sample a different result at the left/right face of a chunk.
  - **10.8.5.** Incremental updates invalidate only dependents when safe, with epoch-based rejection of stale published results.
  - **10.8.6.** Treat strength as a dimensionless value in [0,1] multiplying the resolved placement probability after field gates and prior-state predicates; strength 0 never commits an action and strength 1 preserves the base probability. Reject out-of-range configured strength rather than silently clamp it.
- **10.9. World Editor presentation**
  - **10.9.1.** Ordered layers and their nested added noise generators/operations have clear enable, move, duplicate, delete and property editing actions. Provide `Add Noise Generator` on each layer with a searchable 2D/3D resource picker; selecting a resource creates an inheriting placement in that layer.
  - **10.9.2.** Multi-select of related layer/operation items can present a consolidated summary but cannot silently batch-edit mixed fields.
  - **10.9.3.** Show noise generator resources and voxel sets through searchable pickers, referencing hidden UUIDs, plus `Find References` navigation.
  - **10.9.4.** Selecting an added noise generator shows its containing layer, referenced resource, effective layer-local settings, and a visible per-property distinction between inherited values and explicit overrides. Provide `Override` and `Reset to Layer Default`, navigation to the source Noise Editor, and trace diagnostics explaining resolved settings and selection failures.

# TREE 11 — SPATIAL COORDINATES, REGIONS, CHUNKS AND BOUNDARIES

- **11.1. World coordinate conventions**
  - **11.1.1.** X/Z are horizontal, Y is vertical and positive upward.
  - **11.1.2.** World voxel/block coordinates, logical chunk coordinates, and integer region bounds use signed Java `int`.
  - **11.1.3.** X and Z have no configurable finite world borders beyond signed-int coordinate semantics; Min Y and Max Y are user-defined **inclusive** bounds, negative Y valid.
  - **11.1.4.** Noise arithmetic and continuous transforms use double; GPU positions use camera-relative float coordinate representations to retain useful precision at distant world positions.
  - **11.1.5.** Caller is responsible for invalid near-`int`-limit arithmetic; no pervasive `long` checked coordinate conversion in voxel hot paths. Validate obviously bad dimensions/ranges at public API boundaries, and use `long` for counters/byte sizes.
- **11.2. Chunk grid**
  - **11.2.1.** Chunk coordinates are computed using `Math.floorDiv(worldCoord, S)` for root size `S` and local voxel index with `Math.floorMod(worldCoord, S)`; this applies independently on X/Y/Z.
  - **11.2.2.** Example: X = −1 and S = 64 gives chunkX = −1 and localX = 63.
  - **11.2.3.** External root chunk sizes allowed: exactly `2,4,8,16,32,64,128,256`; reject all other values.
  - **11.2.4.** Editor uses one fixed optimized internal logical chunk size selected after 64³ vs 32³ benchmarks; no project-exposed adjustable internal chunk-size control.
  - **11.2.5.** World contexts with different allowed chunk sizes must yield the same exact voxel type at every valid world coordinate.
- **11.3. Vertical bounds**
  - **11.3.1.** Direct voxel read outside `[minY,maxY]` returns EMPTY.
  - **11.3.2.** A chunk fully outside the Y range returns a complete logical, immutable empty chunk.
  - **11.3.3.** A chunk partially intersecting Y contains valid generation inside the interval and EMPTY in cells outside it.
  - **11.3.4.** Region requests partially outside Y automatically report both requested region and Y-clipped effective region.
  - **11.3.5.** A region entirely outside valid Y completes successfully with an empty effective region and no chunk deliveries.
- **11.4. Region shape and delivery**
  - **11.4.1.** Region min/max corners are inclusive in X/Y/Z; validate `min <= max` for each axis.
  - **11.4.2.** Streaming delivers **complete intersecting logical chunks**, with requested/effective region metadata so clients can clip edge-cell consumption themselves.
  - **11.4.3.** Region completion order is unordered by default; optional coordinate order buffers within bounded resources while still allowing parallel generation.
  - **11.4.4.** Streaming has no arbitrary maximum total requested region volume; scheduling, memory, queue and callback backpressure remain bounded.
  - **11.4.5.** Collected region convenience may reject requests exceeding configured memory budgets before allocating enormous results.

# TREE 12 — EXACT ADAPTIVE VOXEL STORAGE AND IMMUTABLE OUTPUT

- **12.1. Tree-based exact representation**
  - **12.1.1.** Support exact adaptive subdivision from each configured root down to 1 voxel; for a 256³ root the levels are `256 → 128 → 64 → 32 → 16 → 8 → 4 → 2 → 1`.
  - **12.1.2. `EMPTY`:** Entire subtree contains no occupied voxels; no dense allocation.
  - **12.1.3. `UNIFORM`:** Entire subtree contains one occupied Voxel Type.
  - **12.1.4. `BRANCH`:** Subdivided children with sparse, indexed representation.
  - **12.1.5. `PACKED`:** Compact dense/palette-indexed leaf for heterogeneous content where subdivision isn't efficient.
  - **12.1.6.** Use per-chunk palette and primitive-backed arrays/arenas; avoid a Java object for every voxel or node in dense areas.
  - **12.1.7.** Coalesce children back to EMPTY/UNIFORM when possible; bulk fill/clear should operate over adaptive ranges rather than iterating every voxel when semantics permit.
  - **12.1.8.** Do not allocate a full dense 256³ voxel array simply because a root is 256 wide.
- **12.2. Local coordinates and packing**
  - **12.2.1.** Voxel **cell-local** positions are 0..255 at maximum root size; store as `uint8`-equivalent Java bytes interpreted unsigned when beneficial (`value & 0xFF` or `Byte.toUnsignedInt(value)`).
  - **12.2.2.** Use ordinary `int` for hot loop counters and address arithmetic when faster; choose compact representation based on benchmarked benefit.
  - **12.2.3.** Mesh boundary **vertex** coordinates may be 256. Encode as `(cell coordinate, face/corner bit)` or use a wider field, and test that 256 never wraps to zero.
- **12.3. Published chunk semantics**
  - **12.3.1.** Generation constructs private scratch/staging data and publishes a `VoxelChunk` only after all layers/operations succeed.
  - **12.3.2.** Published chunks are immutable read-only snapshots, with local accessor and efficient primitive occupied-voxel iteration.
  - **12.3.3.** Runtime Voxel Type lookup is integer-backed; 0 denotes EMPTY, positive IDs correspond to a particular compiled registry/context.
  - **12.3.4.** Cache eviction, duplicate requests and chunk rebuilds must not mutate snapshots already handed to callers.
  - **12.3.5.** Cancellation/failure discards incomplete staged chunk data; already successfully published chunks remain valid.
- **12.4. Exactness tests**
  - **12.4.1.** Fill/clear, branches, packed leaves, palette updates, mutation during staging and auto-coalescence match a dense reference oracle exactly.
  - **12.4.2.** Randomized sparse/uniform/checkerboard chunk fixtures cover every allowed root size.
  - **12.4.3.** Verify negatives, the final local voxel at 255 and 256 boundary vertex encoding.

# TREE 13 — PUBLIC HEADLESS JAVA NOISE GENERATOR ENGINE API

- **13.1. Loading, compilation and world creation**
  - **13.1.1. `ProjectLoader`:** Parse project JSON, validate supported project format version/schema/IDs/UUIDs/references/noise definitions, and report `LoadResult` containing model plus diagnostics or rejection. Do not replace editor document as part of library loading.
  - **13.1.2. `ProjectData`:** Editable serializable project representation; compilation must not mutate it.
  - **13.1.3. `ProjectCompiler`:** Resolve references and each noise generator placement's inherited/overridden settings, run validation/optimization, compile immutable field and layer/operation plans; expose world compiler and selected-noise-generator compiler. Reuse canonical field definitions without sharing mutable placement settings; sampling/cache reuse must distinguish effective settings that change results.
  - **13.1.4. `CompiledProject`:** Immutable thread-safe project-wide executable snapshot, including bounds, default seed, compiled dependencies and Voxel Registry.
  - **13.1.5. `NoiseGeneratorEngine`:** Runtime owner of scheduler, queue and memory budgets, diagnostic listeners, metrics and context lifecycle; implements AutoCloseable.
  - **13.1.6. `WorldContext`:** Independent runtime world using a compiled definition and optional seed override, root chunk size and cache policy; implements AutoCloseable.
  - **13.1.7.** Multiple contexts may share immutable compiled plans but never mix caches, context seeds, cancellation state or mutable storage.
- **13.2. Value types and registries**
  - **13.2.1.** `BlockPos(int x,int y,int z)` and `ChunkPos(int x,int y,int z)` are public stable value types.
  - **13.2.2.** `RegionBounds(min,max)` uses inclusive positions and supports an optional empty effective region after vertical clipping.
  - **13.2.3.** `VoxelRegistry` maps semantic string Voxel IDs ↔ context-local positive runtime IDs (0 is EMPTY).
  - **13.2.4.** `VoxelChunk` exposes read-only `position`, logical size, `getVoxelTypeId(localX,localY,localZ)`, `isEmpty` and efficient occupied iteration.
- **13.3. Direct sampler API**
  - **13.3.1.** `NoiseSampler2D.sample(double x,double z)` and `sampleGrid(originX,originZ,width,depth,step,destination)`.
  - **13.3.2.** `NoiseSampler3D.sample(double x,double y,double z)` and an efficient 3D grid/batch overload.
  - **13.3.3.** Support lookup by visible ID as a convenience while storing/resolving stable UUID internally; direct samplers are usable independently of whole-world generation.
  - **13.3.4.** Sampling results and batch methods are thread-safe with isolated work buffers, deterministic across worker count.
- **13.4. Chunk requests**
  - **13.4.1.** Synchronous `generateChunk(ChunkPos)` returns a complete immutable result.
  - **13.4.2.** Asynchronous `generateChunkAsync(ChunkPos, CancellationToken)` uses `CompletableFuture`, plus a convenient overload without token.
  - **13.4.3.** `getVoxelTypeId(BlockPos)` is a convenient, potentially generating lookup; recommend batch/chunk paths to high-throughput callers.
- **13.5. Region requests**
  - **13.5.1.** `RegionRequest` contains requested inclusive bounds, failure policy, completion/coordinate order, explicit `maxInFlightChunks` budget and applicable consumer/scheduling controls.
  - **13.5.2.** Streaming first: `RegionTask streamRegion(request, ChunkConsumer)` returns immediately with cancellation, progress and completion summary.
  - **13.5.3.** `ChunkConsumer` may return a `CompletionStage<Void>`; do not release bounded in-flight capacity until consumer signals completion, enabling real callback backpressure.
  - **13.5.4.** `collectRegion(request)` is a memory-budgeted convenience returning completed chunks and diagnostics; sync/async variants must use the same semantics.
  - **13.5.5.** Default `FailurePolicy.CONTINUE`; alternate `FAIL_FAST`. Default delivery is unordered/completion order.
  - **13.5.6.** `RegionTask` reports state and `long` counts for pending/completed/failed/cancelled; summary includes requested/effective region and per-chunk failures.
  - **13.5.7.** Cancellation prevents new scheduling and cooperatively checks at safe checkpoints between layers, operations and finite work batches. Completed chunks remain valid; incomplete results are never published.
  - **13.5.8.** If several callers share an in-flight chunk request, cancellation of one caller does not invalidate work still needed by another.
  - **13.5.9.** Record a delivery-phase failure for that chunk, release its in-flight capacity, and apply the region CONTINUE/FAIL_FAST policy. Distinguish generation success from delivery success; never automatically retry a side-effecting consumer.
- **13.6. Cache policy**
  - **13.6.1. `NO_CACHE`:** No retained completed chunks except in-flight request ownership.
  - **13.6.2. `MEMORY_CACHE`:** Retain generated chunks subject to global engine memory safety.
  - **13.6.3. `BOUNDED_MEMORY_CACHE`:** Default; automatically evict within configured memory budget.
  - **13.6.4. `USER_MANAGED`:** Caller controls pin/unpin/evict within enforced global safety limits.
  - **13.6.5.** Expose `cacheStats`, `evictChunk`, `clearCache`, and pin/unpin methods for applicable policies.
  - **13.6.6.** Cache keys are context-aware and include stable compiled generation identity, seed and chunk position/size as needed; cache hit/miss never affects numeric results.
- **13.7. Diagnostics and introspection**
  - **13.7.1.** Structured `Diagnostic` includes stable code, severity, readable message, optional source UUID/processing-item UUID/parameter key/JSON pointer/chunk and suggested action.
  - **13.7.2.** Engine supports listeners and metrics without invoking ImGui/OpenGL or UI callbacks from worker threads.
  - **13.7.3.** Expose generation timings, queue depth, cache metrics, task progress, memory budgets and bounded optional profiling data.
  - **13.7.4.** Engine/context closure stops accepting work, requests cooperative cancellation, releases owned resources and leaves independent contexts unaffected.
- **13.8. Contract sketch (actual API must compile)**

```java
public interface ProjectLoader {
    LoadResult load(Path path) throws IOException;
}
public interface ProjectCompiler {
    CompileResult compileWorld(ProjectData editable);
    NoiseSampler2D compileNoise2D(ProjectData editable, UUID noiseGeneratorId, long seed);
    NoiseSampler3D compileNoise3D(ProjectData editable, UUID noiseGeneratorId, long seed);
}
public interface NoiseGeneratorEngine extends AutoCloseable {
    WorldContext openWorld(CompiledProject compiled, WorldOptions options);
    EngineMetrics metrics();
    void addDiagnosticListener(DiagnosticListener listener);
}
public interface WorldContext extends AutoCloseable {
    VoxelChunk generateChunk(ChunkPos pos);
    CompletableFuture<VoxelChunk> generateChunkAsync(ChunkPos pos, CancellationToken token);
    RegionTask streamRegion(RegionRequest request, ChunkConsumer consumer);
    RegionResult collectRegion(RegionRequest request);
    int getVoxelTypeId(BlockPos position);
    CacheStats cacheStats();
    void evictChunk(ChunkPos position);
    void clearCache();
}
public interface RegionTask {
    boolean cancel();
    TaskProgress progress();
    CompletableFuture<RegionSummary> completion();
}
@FunctionalInterface
public interface ChunkConsumer {
    CompletionStage<Void> accept(VoxelChunk completed);
}
```

- **13.8.1.** Implement full value types/option defaults, overloads, typed exception policy, nonblocking ownership and Javadoc consistently; the sketch identifies required responsibilities rather than a complete Java source file.
- **13.8.2.** The public library must be usable in its own Gradle/Maven consumer dependency with no editor/native UI dependency pulled in transitively.
- **13.8.3.** Release separately compiled engine JAR and an independent Java example proving loading, direct sampling, world contexts, chunks, streaming/cancellation and archive IO.

# TREE 14 — SCHEDULER, CACHING, GENERATION PIPELINE AND SERIALIZATION

- **14.1. Central scheduling**
  - **14.1.1.** Bounded priority queues coordinate chunk generation, mesh construction, LOD, direct previews and background preloading.
  - **14.1.2.** Prioritize visible/near-camera work and direct user actions over distant preload/background work without changing final numeric output.
  - **14.1.3.** Use finite worker threads, memory-aware admission control, queue limits, cooperative cancellation and overload feedback.
  - **14.1.4.** Never block a generation worker waiting for another job scheduled on the same saturated worker pool.
  - **14.1.5.** GPU buffer creation/uploads are deferred to the owning render thread through a bounded upload queue.
  - **14.1.6.** Use generation epochs/revisions so stale results from older settings never replace newer completed displays.
- **14.2. Performance implementation**
  - **14.2.1.** Dimensionality analysis caches 2D field samples once per X/Z across relevant Y cells.
  - **14.2.2.** Constant-fold and share noise-processing expressions when semantically safe; use primitive arrays and thread-local scratch buffers, not per-voxel mutable editor objects.
  - **14.2.3.** Use conservative field range analysis and uniform-region/bulk-fill optimizations only when they provably preserve exact output.
  - **14.2.4.** Reuse immutable compiled snapshots, batch noise sampling, bound cache usage and profile before selecting memory/performance trade-offs.
- **14.3. Binary voxel storage format**
  - **14.3.1.** Keep binary voxel serialization separate from editable project JSON and selected-resource export.
  - **14.3.2.** Include file signature/magic, archive version, coordinate/chunk size metadata, compression identifiers, file-level semantic Voxel ID string table and random-access index.
  - **14.3.3.** Represent independently compressed exact root chunks; omit sparse empty roots where possible while preserving logical EMPTY semantics.
  - **14.3.4.** Store semantic Voxel Type **string IDs**, not UUIDs/colors/editor groups or context-local runtime integers; correctly remap through a `VoxelRegistry` on read.
  - **14.3.5.** Support single-chunk serialization and multi-chunk archive readers/writers, streaming append/finish, random `contains/read`, version checks, index bounds and corrupted-file rejection.
  - **14.3.6.** Never encode approximate display LOD as the authoritative persisted world.
  - **14.3.7.** Validate archive headers, lengths, dictionary indices, chunk coordinate uniqueness, compression payloads and resource limits before allocating.
  - **14.3.8.** Record exact covered chunk ranges or an equivalent compact coverage set. Within declared coverage an omitted payload means a complete EMPTY chunk; outside coverage report not archived. Define separate coverage and stored-payload queries and document contains/read behavior.
  - **14.3.9.** Leave the supplied registry unchanged and reject decoding before exposing any chunk that needs unresolved semantic IDs; list the missing IDs so the caller can construct a compatible registry and retry.
- **14.4. Serialization API**
  - **14.4.1.** `writeSingleChunk(Path, VoxelChunk, VoxelRegistry)` / `readSingleChunk(Path, VoxelRegistry)`.
  - **14.4.2.** `openWorldWriter(Path)` returns an AutoCloseable append/finish writer.
  - **14.4.3.** `openWorldReader(Path, VoxelRegistry)` returns indexed random access and dictionary inspection.
  - **14.4.4.** Reader/writer operations are deterministic and safe for large sparse archives without whole-world dense allocation.

# TREE 15 — THREE INTERACTIVE VISUALIZATION TABS

- **15.1. Shared viewport shell**
  - **15.1.1.** The center main region has three tabs: `2D Noise`, `3D Noise`, `World`, all built on the same authoritative Noise Generator Engine outputs.
  - **15.1.2.** Display tabs' own toolbar, effective view controls, refresh/rebuild state, coordinates and context-sensitive menu actions.
  - **15.1.3.** Changing visualization mode never changes underlying procedural field output or exact stored voxel results.
  - **15.1.4.** Empty newly created projects display valid empty world/selection states and helpful actionable UI without inventing world resources.
- **15.2. 2D Noise visualizer**
  - **15.2.1.** Searchable selector chooses a 2D noise generator by ID; selection does not affect independent 3D tab or project noise generator definitions.
  - **15.2.2.** Left-drag pans 2D world coordinates; wheel zooms; render tiled or cached async samples for the visible area with seam-safe tile addressing.
  - **15.2.3.** Viewport context menu switches grayscale, heatmap, contour, threshold, histogram and grid overlay options.
  - **15.2.4.** Bottom-of-viewport status shows hovered world X/Z, raw sampled Scalar and normalized display-mapped value.
  - **15.2.5.** Pan/zoom changes trigger only necessary visible tile work; cache invalidates when selected field/dependency changes.
  - **15.2.6.** Coordinate teleport/bookmark actions move the 2D view center without changing selected noise generator.
- **15.3. 3D Noise visualizer**
  - **15.3.1.** Searchable selector chooses a 3D noise generator; expose editable X/Y/Z preview bounds/extents with User Config maximum dimension and total-cell budget.
  - **15.3.2.** Render Threshold Voxels, Isosurface, Volume, Point Cloud and orthogonal Slices with meaningful independent controls for threshold/density/transfer/axis.
  - **15.3.3.** Sample static infinite procedural coordinates as camera travels; schedule nearby chunks/previews incrementally, cull stale distant work and keep current valid display during regeneration.
  - **15.3.4.** Share camera navigation code with World visualizer while keeping preview interpretation separate from procedural voxel generation.
  - **15.3.5.** Provide camera-relative streaming as the normal mode plus an explicitly selected fixed-world bounds mode for analysis; preserve camera travel and allow an explicit return to streaming.
- **15.4. World visualizer**
  - **15.4.1.** Visualize the one project World Generator through exact generated voxels and the shared voxel renderer.
  - **15.4.2.** Layer preview modes: `Before`, `After`, `Only Changes`, `Final` at the selected operation/layer stage; faithfully reflect committed pipeline semantics.
  - **15.4.3.** The viewport is strictly procedural/read-only: interactions navigate, inspect, visualize and link back to configuration editors.
  - **15.4.4.** Raycast left-click chooses the actual voxel/cell and opens the dedicated Voxel Inspector.
  - **15.4.5.** Inspector presents integer XYZ, final occupied/EMPTY state, semantic Voxel ID and runtime ID, last deciding layer/operation, previous state and SET/CLEAR, plus collapsible field values, effective settings, selection gates, voxel-state condition and Generation Trace.
  - **15.4.6.** Provide `Go To Resource` links for referenced noise generator, layer, operation and type/set, plus `Go To Control` links for noise-processing items and parameters where available.
  - **15.4.7.** Voxel Inspector is an information-only window; its opening/closing/focus changes do not dirty the project or create Undo entries.
  - **15.4.8.** Report accurate provenance via engine instrumentation or deterministic recomputation rather than inferring it from the rendered mesh color.
  - **15.4.9.** Provide an integer XYZ entry or Inspect Coordinate action in the existing Inspector, alongside occupied-cell raycast selection, so any EMPTY coordinate can be traced.
- **15.5. Live preview regeneration**
  - **15.5.1.** Auto mode offers 1–20 regeneration *opportunities* per second, default suggested 10; regenerate only when the selected data or dependencies are dirty.
  - **15.5.2.** Manual mode offers per-viewport `Regenerate` control; both modes show pending/active/failed states concisely.
  - **15.5.3.** Cancel/obsolete epochs do not publish stale results; retain previous valid preview until a current result commits atomically.
  - **15.5.4.** Preview budgets affect responsiveness/workload, never persisted exact world values.

# TREE 16 — ORBIT, FREE-FLY, TELEPORT AND BOOKMARK CONTRACTS

- **16.1. Orbit mode (default in 3D and World)**
  - **16.1.1.** Left drag rotates around the current focus/pivot.
  - **16.1.2.** Right drag pans the rotation center relative to camera orientation.
  - **16.1.3.** Middle drag moves center along world X/Z; Ctrl + middle drag moves it along world Y.
  - **16.1.4.** Mouse wheel zooms in/out relative to pivot.
  - **16.1.5.** Right-click context menu opens on a click, not a drag; distinguish by a movement/time threshold so camera pan and menus do not conflict.
- **16.2. Free-fly mode**
  - **16.2.1.** Ctrl + left-click enters free-fly from an appropriate viewport context.
  - **16.2.2.** Capture mouse for yaw/pitch; WASD moves horizontally relative to view, Space ascends and Shift descends; Escape exits free-fly.
  - **16.2.3.** Free-fly movement never changes procedural generation coordinates or persisted project data.
- **16.3. Inspector focus transfer**
  - **16.3.1.** On World voxel click while free-flying, release/suspend mouse capture to permit Inspector interaction.
  - **16.3.2.** Preserve camera position/orientation and movement state while Inspector is active.
  - **16.3.3.** On Inspector close, recapture only when viewport is appropriate and focused; never unexpectedly steal mouse when another window is active.
  - **16.3.4.** Escape, click, window focus and Inspector lifecycle must have deterministic non-conflicting transitions.
- **16.4. Bookmarks and teleport**
  - **16.4.1.** Exactly three separate project-stored collections corresponding to the three tabs.
  - **16.4.2.** 2D bookmark stores name + X/Z; 3D and World bookmark stores name + X/Y/Z.
  - **16.4.3.** Bookmarks store **coordinates only**, with no noise generator, layer, camera orientation, rendering mode or navigation-mode association.
  - **16.4.4.** Toolbar offers searchable bookmark dropdown, Add Bookmark, Rename, Update Coordinates, Delete and Teleport.
  - **16.4.5.** Clicking/choosing an inspectable coordinate then Add opens a naming dialog displaying the picked coordinates.
  - **16.4.6.** Separate Teleport dialog directly accepts X/Z or X/Y/Z integer coordinates.
  - **16.4.7.** Teleport in orbit moves the focus point while preserving camera offset/orientation; in free-fly moves the camera position while retaining orientation.
  - **16.4.8.** Bookmark create/rename/update/delete is an undoable dirty project edit; merely teleporting is navigation and does not dirty project.

# TREE 17 — RENDERING, LOD, AND PERFORMANCE FALLBACK

- **17.1. Voxel meshing**
  - **17.1.1.** Cull internal faces between adjacent opaque occupied voxels even when the two voxels have different display colors.
  - **17.1.2.** Greedy-merge exposed opaque faces with correct type-color lookup, normals and AO constraints.
  - **17.1.3.** Respect chunk boundaries and neighbor visibility; avoid visible seams/hole creation during LOD changes.
  - **17.1.4.** Pack mesh vertex data compactly with correct 256 boundary encoding, use shared GPU arenas/buffers and bounded uploads on render thread.
  - **17.1.5.** Use a GPU Voxel Type RGB lookup/palette so color changes can update palette data without rebuilding geometry.
  - **17.1.6.** Use frustum culling, camera-relative origins and Multi-Draw Indirect when supported; provide a correct fallback path where the GPU feature is unavailable.
  - **17.1.7.** Provide correct versioned GLSL shader sources, build/runtime validation and recoverable diagnostics for GPU capability/shader compilation failures.
- **17.2. Visual appearance**
  - **17.2.1.** Directional light, ambient light and mesh ambient occlusion provide readable 3D voxel shape.
  - **17.2.2.** User-adjustable shadows/fog/unlit and quality levels may be selected where supported by the defined renderer pipeline.
  - **17.2.3.** Keep visual settings in User Config rather than affecting compiled world output.
- **17.3. Strictly separate lossless exact storage and lossy render LOD**
  - **17.3.1.** LOD0 displays exact cells. Coarser display levels aggregate 2×2×2 and larger groups (starting through 64³ as needed, and coherently covering the full 256 root size).
  - **17.3.2.** Occupancy aggregation uses documented threshold (initial 50%) and representative dominant occupied voxel type; tie-breaking is deterministic.
  - **17.3.3.** Choose LOD using nearest distance to chunk AABB, distance rings, hysteresis, `LOD Start Distance` measured in chunks and user-selectable quality level.
  - **17.3.4.** Balance neighboring LOD choices or stitch using skirts/edge handling; avoid cracks.
  - **17.3.5.** LOD is a renderer-only derived cache, not part of the authoritative generated data, noise generator API or binary world archive.
- **17.4. Performance fallback order — mandatory priority**
  - **17.4.1.** First increase aggressiveness of distant visual LOD.
  - **17.4.2.** Then reduce effective render distance.
  - **17.4.3.** Then reduce background preview and preload work.
  - **17.4.4.** Then reject newly requested expensive previews, preserving the last valid display and explaining the reason.
  - **17.4.5.** Finally, if pressure remains critical, pause generation visibly and require explicit manual Resume.
  - **17.4.6.** Gradually restore effective visual quality after pressure recedes; never overwrite configured user preference values or change exact generation results.
- **17.5. Benchmarks and resource controls**
  - **17.5.1.** Benchmark 32³ and 64³ editor chunk sizes for representative sparse, dense, mixed and high-frequency scenes before selecting the fixed size.
  - **17.5.2.** Measure CPU generation, meshing, cache sizes, GPU uploads, draw calls, frame times, memory/GC and responsiveness on documented hardware.
  - **17.5.3.** Use measurements and correct data dependencies to select safe memory/throughput optimizations, not assumed universal frame-rate targets.
  - **17.5.4.** Provide one representative minimum Windows machine and one normal test machine. Let measured budgets determine tuning unless a concrete responsiveness acceptance threshold is required.

# TREE 18 — MAIN APPLICATION SHELL, SEARCH, SETTINGS, THEMES, AND HELP

- **18.1. Main window composition**
  - **18.1.1.** Menu bar above dockable main workspace.
  - **18.1.2.** Dockable Resources tree on the left, context-sensitive Properties on the right, three central visualization tabs.
  - **18.1.3.** Both left and right panels offer visible undock/redock controls; docking, sizing, positioning and visibility persist in User Config.
  - **18.1.4.** Resizable windows and dialogs obey independent scale overrides, monitor work areas and DPI.
- **18.2. View menu and project-wide Find**
  - **18.2.1.** `View → Find` (Ctrl+F) opens an advanced unified search window across resources, folders, voxel definitions, noise-processing items/parameters, layers and operations.
  - **18.2.2.** Filters support resource kind, directory/root, references, ID text and breadcrumb paths; results can navigate directly to resources and relevant property-panel sections and controls.
  - **18.2.3.** `Find References` resolves incoming UUID references and offers navigation to each usage.
  - **18.2.4.** Resource picker dialogs remain simple searchable selectors; the advanced search does not need to be duplicated in every menu.
  - **18.2.5.** `View → Profiler` opens live profiler metrics and captures.
  - **18.2.6.** `View → Validate Project` runs a read-only validation pass, showing errors, warnings and information with `Go To Problem` and explicit valid confirmation.
  - **18.2.7.** `View → Logs` opens combined Logs/Error Center window.
  - **18.2.8.** `View → Themes` opens customizable UI color/theme editor.
- **18.3. Status bar**
  - **18.3.1.** Keep a narrow permanent horizontal bar connected at the bottom of the main application.
  - **18.3.2.** The leftmost area shows the currently most relevant concise status (`Ready`, `Project saved`, validation issue, or generation pressure).
  - **18.3.3.** Optional compact generation/chunk/mesh upload progress appears toward the right only when relevant.
  - **18.3.4.** **Far right** holds Notification bell/button and actionable unread count.
  - **18.3.5.** Avoid transforming the status bar into a full performance dashboard.
- **18.4. Notifications**
  - **18.4.1.** Clicking the status-bar notification button opens the notification window/panel.
  - **18.4.2.** Severity: Information, Warning, Error and Action Required; show timestamp and meaningful short explanation.
  - **18.4.3.** Group identical repeated notifications instead of flooding the list; maintain read/unread/action-required state.
  - **18.4.4.** Where context exists, expose `View Error`, `Go To Problem`, `Open Settings`, retry/relevant user action; never show fake inert actions.
  - **18.4.5.** Notifications highlight what needs attention; Logs retain historical events and technical details.
- **18.5. Theme editor**
  - **18.5.1.** `View → Themes` opens a dedicated live-preview color editor, saving selected values in User Config.
  - **18.5.2.** Editable palette groups: window/panel/menu backgrounds; primary/secondary text; borders; selection/hover/active; buttons/inputs; noise-panel section headers and optional value-type badges; warning/error/success statuses.
  - **18.5.3.** Include Dark and Light as starting UI appearances; `Reset to Default` restores documented defaults.
  - **18.5.4.** Prevent illegible text/control contrast through sensible presentation feedback and independent color/state indicators.
- **18.6. Help and About**
  - **18.6.1.** Top-level `Help → Help` opens a searchable in-application feature manual sourced from centralized app/help definitions.
  - **18.6.2.** Help search returns feature name, descriptive guidance, relevant options and related topics.
  - **18.6.3.** Context Help from a control/menu opens the matching Help section directly where available.
  - **18.6.4.** `Help → About` lists application version, engine version, Java version, runtime OpenGL details and licenses.
  - **18.6.5.** `Help → Check for Updates` performs a **manual** release-version check against a configurable trusted release-metadata endpoint; present useful connection/version diagnostics and a verified release-information link, without autonomous download/install/replacement.
  - **18.6.6.** Ship without a fabricated endpoint; manual Check for Updates opens an explicit trusted-endpoint configuration path when unset, and performs a real check once configured. Document/test the metadata schema and trust policy with controlled fixtures.
  - **18.6.7.** Validate HTTPS certificate trust, use explicit configured metadata/release origin allowlists, reject unapproved origin-changing redirects and non-HTTPS release links, and enforce a strict versioned metadata schema with bounded responses/timeouts.
- **18.7. Defaults and persistence**
  - **18.7.1.** Project begins empty/unsaved with defaults documented in Tree 20.
  - **18.7.2.** User preferences update without dirtying the project; project-bound bookmark edits and resource changes do dirty it.
  - **18.7.3.** All actionable UI states (search, inspector links, edit dialogs, errors, profiler, controls) use real underlying models and services.

# TREE 19 — VALIDATION, LOGS, PROFILER, ERROR RECOVERY, AND INSPECTION

- **19.1. Validation types**
  - **19.1.1. `Validation Error`:** Invalid user input/noise definition/schema with inline feedback and location.
  - **19.1.2. `Warning`:** Valid operation with known risks, efficiency or numerical concern.
  - **19.1.3. `Recoverable Error`:** Failed task/IO/preview that preserves application and project state; log in Error Center.
  - **19.1.4. `Critical`:** Fatal failure requiring safe shutdown/stop of affected work and best possible preservation of existing in-memory/project state; never silently corrupt saves.
  - **19.1.5.** Use stable machine-readable codes and consistent severity/category/context through engine and editor.
- **19.2. Validation boundaries**
  - **19.2.1.** On load, reject invalid JSON, unsupported project format version, malformed/duplicate UUIDs/IDs, broken folder hierarchy, unresolved required references, noise generator dependency cycles, invalid processing order/input bindings/types/expressions/output selections and invalid voxel/type/set links.
  - **19.2.2.** Rejected project load must leave prior project instance, resources, selection and dirty state intact; errors identify JSON Pointer/path, resource, processing item and parameter where possible.
  - **19.2.3.** Provisional noise-panel edits and temporary curve/expression editors may show errors before commit or Apply; commit only valid changes into authoritative Project Config.
  - **19.2.4.** Disabled, structurally sound experimental processing items and valid unreferenced noise generator resources remain legal; do not report unused noise generators as a warning/error. Enabled results may not reference disabled items.
  - **19.2.5.** `Validate Project` is read-only, never repairs/reorders/normalizes the project behind the user's back.
  - **19.2.6.** Save operation validates the committed document and preserves the old file on serialization/write errors.
- **19.3. Logs and Error Center**
  - **19.3.1.** View → Logs opens one bounded log/error-center window rather than competing technical-error windows.
  - **19.3.2.** Search and severity/subsystem filters: Generation, Rendering, Project, Import/Export, Scheduler, Engine and Application.
  - **19.3.3.** Group equivalent repeated errors and show occurrence count; keep bounded memory retention and avoid unlimited on-disk output.
  - **19.3.4.** Entries show timestamp, stable code, message, severity, affected resource/JSON path/chunk, and actions `Go To Problem`, `Copy Details` where applicable.
  - **19.3.5.** Verbose logs controlled through Debug & Profiling; low-level engine diagnostic handlers must not depend on editor UI.
- **19.4. Debug & Profiling settings**
  - **19.4.1.** Settings sections/tabs: General, Generation, World-Chunks, Rendering, Memory, Scheduler, LOD and Diagnostics.
  - **19.4.2.** Profiler live views and bounded captures collect generation times, layer/operation contributions, frame/mesh/GPU uploads, queue/cache/memory/GC and thread/workload information.
  - **19.4.3.** Include percentile summaries P50/P95/P99 as well as counts/averages and sample bounds.
  - **19.4.4.** CPU/GPU instrumentation is disabled/low-overhead by default and enables targeted captures without permanently disturbing noise generator output.
  - **19.4.5.** A profiler capture's memory/sample length is bounded and user-inspectable.
- **19.5. Generation Trace and Explain Selection Failure**
  - **19.5.1.** Given world XYZ, explain ordered layers/operations, field samples, interpreted selection, threshold comparisons, inherited settings, voxel-state condition, probability result and final action/state.
  - **19.5.2.** For an unselected cell, `Explain Selection Failure` identifies the first or relevant failing predicate and values rather than merely saying “not selected.”
  - **19.5.3.** Allow trace links to resources, noise-processing items and panel controls, and consistent presentation in Voxel Inspector and Logs.
  - **19.5.4.** Trace results must match actual deterministic generation, using the same compiled semantics or faithful instrumented replay.
- **19.6. Failure containment**
  - **19.6.1.** Failed preview/mesh/chunk request reports an actionable error without replacing an older valid display with broken data.
  - **19.6.2.** Failed import/load/save preserves the existing valid active state.
  - **19.6.3.** Cancellation and overload events have explicit statuses; resume from a critical generation pause requires user action.

# TREE 20 — NORMATIVE DATA CONTRACTS, DEFAULTS, AND SCHEMA REQUIREMENTS

- **20.1. Create one exact, versioned JSON Schema Draft 2020-12 family**
  - **20.1.1. `project.schema.json`:** Top-level project, all resource definitions, folder constraints, bookmark coordinate shapes, noise-panel definitions and typed processing/input bindings, curves, voxel models, World Generator layers and operations.
  - **20.1.2. `user-config.schema.json`:** Theme and palette, global/per-window scale, workspace/docking, keyboard controls, visualization and performance preferences.
  - **20.1.3. `app-config.schema.json`:** Control metadata, labels, short tooltips, help topics, relevant defaults and UI option definitions.
  - **20.1.4.** Maintain `algorithm-registry.json`, `noise-processing-registry.json`, `expression-function-registry.json` and supported interpretation/operation registry; parser/compiler validators agree with them.
  - **20.1.5.** JSON Schema performs shape/local-value checks; a semantic validation pass enforces uniqueness across arrays, valid foreign UUIDs, valid processing order and typed bindings, noise generator dependency acyclicity, conditions and runtime constraints.
  - **20.1.6.** Reject unsupported file-format versions and invalid format fields with precise diagnostics. Do not silently drop unknown project fields.
- **20.2. Canonical project shape (illustrative IDs; use actual UUID strings in files)**

```json
{
  "formatVersion": 2,
  "project": {
    "uuid": "00000000-0000-4000-8000-000000000001",
    "seed": 0,
    "minY": -64,
    "maxY": 319
  },
  "voxelTypes": [],
  "voxelGroups": [],
  "voxelSets": [],
  "noise2D": [],
  "noise3D": [],
  "worldGenerator": {
    "uuid": "00000000-0000-4000-8000-000000000002",
    "layers": []
  },
  "folders": [],
  "bookmarks": {
    "noise2D": [],
    "noise3D": [],
    "world": []
  }
}
```

- **20.2.1.** Project seed `0`, minY `-64`, maxY `319` are coherent initial defaults. Keep them documented and user-editable through Project Settings; preserve inclusive bounds.
- **20.2.2.** Actual generated IDs are random stable UUIDs; sample UUIDs above are fixtures, not hardcoded application constants.
- **20.3. Required resource schemas**
  - **20.3.1. `Resource`:** `{uuid, id, parentFolderUuid?}` plus kind-specific data; enforce ID uniqueness in the resource's appropriate namespace.
  - **20.3.2. `Folder`:** `{uuid, id, rootKind, parentFolderUuid?, child ordering}` with organizational behavior only and no cycles/cross-root movement.
  - **20.3.3. `VoxelType`:** `{uuid, id, color:{r,g,b}}` with RGB bytes.
  - **20.3.4. `VoxelGroup`:** `{uuid, id, voxelTypeUuids:[...]}` plus organizational metadata only.
  - **20.3.5. `VoxelSet`:** `{uuid, id, voxelTypeUuids:[...]}` with meaningful membership evaluation.
  - **20.3.6. `NoiseGenerator`:** `{uuid, id, dimension, algorithm:{key,parameters}, seedOffset, coordinateTransforms:[...], processing:[...], sharedParameters:[...], pinnedControls:[...], outputBinding, organizationalParent}`; the panel edits this canonical noise generator definition.
  - **20.3.7. `NoiseProcessingItem`:** `{uuid, id, kind, enabled, parameters, inputs, resultType}`; ordered arrays define evaluation order. Typed input bindings identify a constant, shared-parameter UUID, ambient value, source result, earlier processing-item UUID or noise generator UUID with explicit sampling/projection settings. Output binding selects the source Scalar or an enabled Scalar result. Coordinate-transform items use the same typed item contract with coordinate results and domain-specific signatures.
  - **20.3.8. `Curve`:** `{uuid, domain:{min,max}, outputRange, outsideDomain, points:[{uuid,x,y,segmentInterpolation,tangents/handleMode}]}`; first and last point are endpoints.
  - **20.3.9. `WorldGenerator`:** `{uuid, layers:[...]}` exactly once.
  - **20.3.10. `Layer`:** `{uuid,id,enabled,defaults,operations:[...]}` and explicit array execution order. `defaults` stores the applicable inheritable world-use settings; `operations` stores added noise generator placements in their execution order.
  - **20.3.11. `Operation` / layer-local noise generator placement:** `{uuid,id,enabled,noiseGeneratorUuid,interpretation,overrides,...}` with explicit optional data matching its interpretation. `overrides` is a sparse map containing only explicitly overridden inheritable properties, including `fieldGates`, `voxelStatePredicate`, `action`, `voxelTypeSource` and operation `seedOffset` where applicable. All absent override properties resolve from the containing layer's `defaults`; do not persist resolved inherited values as overrides or store competing copies of an inheritable setting at the operation top level. Preserve explicit override presence independently of its value, and validate the resolved settings for the chosen dimension/interpretation.
  - **20.3.12. `FieldGate`:** recursively nested `AND`/`OR` clauses over scalar comparisons, intervals, selection fields and deterministic probability.
  - **20.3.13. `VoxelStatePredicate`:** a **separate** recursively nested AST using `AND`, `OR`, `ANY`, `EMPTY`, `OCCUPIED`, `TYPE_IS`, `TYPE_IS_NOT`, `IN_SET`, `NOT_IN_SET`; type/set UUID references must resolve.
  - **20.3.14. `Bookmark2D`:** `{uuid,name,x,z}`; `Bookmark3D`/`BookmarkWorld`: `{uuid,name,x,y,z}`; no attached noise generator/camera reference.
  - **20.3.15. `UserSettings`:** hierarchy of appearance/workspace/input/editor/visualization/performance, global scale 100%, per-window overrides, UI theme, shortcuts, regeneration policy.
  - **20.3.16. `InputBinding`:** Discriminated typed object with `sourceKind` and the applicable constant value, parameter UUID, ambient key, source-result selector, earlier item UUID, or noise generator UUID plus coordinate/projection/slice configuration. Sampled-field inputs additionally identify `Source Field`, `Earlier Field Prefix` or `Noise Generator Field` and their explicit sampling settings. References resolve by UUID; stage order restricts available sources.
  - **20.3.17. `SharedParameter`:** `{uuid,id,type,value}` with supported Scalar/Boolean/Vector2/Vector3 types. `PinnedControl` stores the original item/parameter identity and presentation label/group, never a copied computational value.
  - **20.3.18.** Noise-processing registry entries declare stable kind identifiers, section category, supported dimensions, typed signatures, required/optional inputs, parameter controls/defaults/ranges, sampling semantics and deterministic reference vectors. Persist no canvas positions or connection-link records.
- **20.4. Generation and UI defaults**
  - **20.4.1.** New noise generators: OpenSimplex-family source, seed offset zero, stable algorithm-registry numeric defaults, empty coordinate-transform/processing/shared-parameter/pinned-control arrays and raw source Scalar output; manipulation sections are available but add no effects until configured.
  - **20.4.2.** New Voxel Type: light gray RGB 200,200,200; no built-in prepopulated user types.
  - **20.4.3.** New Curve: points `(0,0),(1,1)`, Linear, Clamp, shown grid, snapping off.
  - **20.4.4.** UI: dark theme, global scale 100%, orbit camera default, change-only auto-regeneration suggested at 10/s adjustable 1–20, bounded cache and profiler disabled until requested.
  - **20.4.5.** External engine: root chunk size default 64, `BOUNDED_MEMORY_CACHE`, unordered region delivery, `CONTINUE` failure policy; worker count and cache budgets chosen by safe hardware-aware defaults.
  - **20.4.6.** LOD/render distance, preview extents and region limits are user preference/performance defaults, never persistent noise generator semantics.
- **20.5. Exactness and file-format metadata**
  - **20.5.1.** Stable algorithm IDs, archive format version and project format version are explicit, inspectable and included in test fixture metadata.
  - **20.5.2.** Do not replace UUID references with visible names in stored editable noise-processing definitions or world operations. Exact voxel archives are explicitly exempt from this UUID-reference rule: their voxel-type dictionaries store semantic string IDs, never project UUIDs or context-local runtime integers, as required by 9.1.4 and 14.3.4.
  - **20.5.3.** Normalize numeric JSON handling and reject nonfinite configured doubles; define rounding, tie-breaking, comparison and interval-edge behavior per operation.
  - **20.5.4.** Make JSON round trips preserve the full user-authored project, including disabled/unused but valid resources, processing order, input bindings, shared parameters, pinned-control presentation and output selection. Panel scroll/collapse state belongs in User Config.
  - **20.5.5.** Keep transient state (live chunks, task queues, last selection, camera orientation, Undo stack) out of project persistence unless explicitly included in the Project Config contract.

# TREE 21 — FIXTURE DESIGN, VALIDATION TESTS, AND CROSS-SYSTEM REGRESSION

- **21.1. Testing architecture**
  - **21.1.1.** Use JUnit 5 for Java modules, Gradle verification tasks, deterministic reference-oracle fixtures and integration tests. A schema-only check does not prove execution behavior.
  - **21.1.2.** Separate tests for project JSON structural validation, semantic cross-resource validation, immutable engine compilation, generated world correctness, exact chunk storage, binary serialization, UI state transitions, rendering interactions and release packaging.
  - **21.1.3.** Generate expected test outputs independently of the exact code under test. Use known mathematical expectations, precomputed reference vectors, simple handwritten voxel worlds, saved hashes with their calculation method, and transparent assertions.
  - **21.1.4.** Each fixture declares applicable file-format versions, algorithm IDs and settings, expression inputs where applicable, seed, relevant dimensions, expected outcome and a precise assertion; deterministic results must be stable across repeated runs.
- **21.2. Project and schema fixtures**
  - **21.2.1. `valid-empty`:** Three protected roots, no user content; save/open and empty world work.
  - **21.2.2. `valid-layered-three-operations`:** At least three layers/operations exercising prior-state reads, SET, CLEAR and conditional SET.
  - **21.2.3. `valid-disabled-processing`:** Valid disabled experimental processing items preserved in the noise definition while unused by execution.
  - **21.2.4. `invalid-noise-output`:** Active noise definition has a required input/type/expression/output-selection problem; loader rejects and current project survives.
  - **21.2.5. `invalid-format`, `invalid-format-version`, `invalid-duplicate-uuid`, `invalid-duplicate-id`:** Precise failure codes and JSON pointers.
  - **21.2.6. `invalid-reference`, `invalid-noise-generator-cycle`, `invalid-folder-cycle`, `invalid-processing-input`, `invalid-processing-order`:** Cross-reference, noise-generator-cycle, folder-cycle, processing-order and typed input-binding integrity validation.
  - **21.2.7. `invalid-expression`, `invalid-voxel-set`, `invalid-import-conflict`:** Exact parser/member/transaction failures.
- **21.3. Procedural mathematical fixtures**
  - **21.3.1.** Reference point vectors for each required noise family in 2D and 3D, including negative coordinates, transformed seed, repeated seeds and large finite samples.
  - **21.3.2.** Panel processing order, each manipulation family, polymorphic operation signatures, explicit conversion/projection/slice, shared parameters, reference chains, disabled items, resampling-prefix semantics and noise-generator-cycle fixture coverage.
  - **21.3.3.** All DSL functions, operators, precedence, Boolean short circuiting, ternary branches, domain errors, source spans and type errors.
  - **21.3.4.** Curve Step/Linear/Smooth/Bezier, Bezier tangent modes, knot-boundary evaluation and Clamp/Extend/Repeat/Mirror; numeric-precision round trips.
  - **21.3.5.** Stable probability/coordinate hash vector fixtures independent of chunk order and worker count.
- **21.4. World operation fixtures**
  - **21.4.1.** Define a tiny world with known coordinates/field values; compare expected final voxel IDs and provenance after ordered operations.
  - **21.4.2.** Test all seven base previous-voxel predicates including EMPTY behavior of `IN_SET` and `NOT_IN_SET`.
  - **21.4.3.** Test nested `AND` and `OR` voxel-state groups separately from nested field-selection gates.
  - **21.4.4.** Test adding 2D/3D noise generators to layers, initial inheritance with empty overrides, per-property override precedence (including zero/false), reset to live inheritance, layer edits propagating only to inheriting placements, moving placements between layers, and the same resource used in multiple placements without cross-placement or source-resource mutation. Also test resolved Y ranges clipped to project bounds, disabled layers/operations, layer order changes, type selection, density and height interpretations.
  - **21.4.5.** Test that an operation reads prior committed state but cannot observe its own uncommitted intermediate writes.
- **21.5. Spatial metamorphic tests**
  - **21.5.1.** Compare exact voxels at the same world positions using root sizes `2,4,8,16,32,64,128,256`.
  - **21.5.2.** Compare worker counts 1 and several, sync vs async, direct voxel/chunk/region, unordered vs ordered, cold vs warm cache, and differing request partitions.
  - **21.5.3.** Test negative X/Y/Z, −1 at chunk boundaries, 0, both inclusive Y limits and partially/fully out-of-Y results.
  - **21.5.4.** Test adaptive storage EMPTY/UNIFORM/BRANCH/PACKED transitions against a dense reference; sparse 256³ roots do not allocate dense space.
  - **21.5.5.** Test cell-local 255 encoding and render vertex boundary coordinate 256 as distinct values.
  - **21.5.6.** Verify exact world values remain unchanged while toggling display LOD, camera, render distance, visual fallback, theme and all User Config controls.
- **21.6. API and concurrency fixtures**
  - **21.6.1.** Compile/run an independent Java consumer importing only the headless engine and required non-UI libraries.
  - **21.6.2.** Two contexts with different seeds/cache policies remain isolated while sharing immutable compiled resources.
  - **21.6.3.** Cancel a region during active generation; no incomplete chunk is delivered/published, and completed chunks remain valid.
  - **21.6.4.** Slow async `ChunkConsumer` must backpressure delivery and keep memory/in-flight chunk counts within configured limits.
  - **21.6.5.** CONTINUE and FAIL_FAST report correct per-chunk failures, status/counts and proper stop conditions.
  - **21.6.6.** Closing one context cancels its work without shutting down another, and engine closure stops all remaining work safely.
- **21.7. Persistence and archive tests**
  - **21.7.1.** Save/Reopen equivalent editable document (including noise-processing order, bindings, parameters, output selections, UUIDs, references, curves, voxel Sets, bookmarks, layer order, layer defaults, noise generator placement references and explicit override presence).
  - **21.7.2.** Failed Open retains the current document byte-for-byte where applicable, plus selection/dirty state; failed Save leaves old destination valid.
  - **21.7.3.** Copy/paste UUID remaps internal references, cut preserves UUID, recursive folder duplication is one Undo action, cross-session clipboard resets.
  - **21.7.4.** Portable resource Import/Export resolves dependencies and conflicts transactionally.
  - **21.7.5.** Binary chunk/world archive preserves semantic voxel string IDs, negative chunk coordinates and exact cells across compression; verify random access and header/index corruption rejection.
- **21.8. Desktop interaction tests**
  - **21.8.1.** Three true engine-backed viewports, pan/zoom/display modes, 3D region controls, orbit/free-fly gestures and focus handling.
  - **21.8.2.** Voxel Inspector correctly traces the determining operation, previous state, conditions and selected field values.
  - **21.8.3.** Bookmarks are separate per tab, coordinates-only, persist in Project Config, and teleport without dirtying project.
  - **21.8.4.** Themes, per-window scaling, docking, User Config, keyboard mapping/conflict validation and help descriptions function after application restart.
  - **21.8.5.** Find, Find References, Validate Project, Logs, Notifications and Profiler display real results with working navigational actions.
  - **21.8.6.** Generation over-budget fallback follows the prescribed stages and manual Resume works; exact world hash is unchanged.
  - **21.8.7.** Noise Editor panel verifies every manipulation category, consistent collapsible-section/control styling, add/duplicate/reorder/disable/delete workflows, input-source selectors, pinned/shared controls, inline validation/navigation and dependency-driven preview regeneration.

# TREE 22 — RELEASE ACCEPTANCE CHECKLIST AND EVIDENCE

- **22.1. Source, contract and project workflows**
  - **A01:** Clean startup opens only the three protected roots; creates no user resources automatically.
  - **A02:** Rejected project load preserves the existing active project and dirty status.
  - **A03:** An invalid committed noise definition is rejected on load and panel commit/Apply with specific control-level diagnostics.
  - **A04:** Structurally valid disabled experimental processing items and unreferenced noise generators persist unchanged and are not executed unless requested/enabled.
  - **A05:** Explicit Save/Save As, atomic preservation and dirty Save/Don't Save/Cancel transitions work.
  - **A06:** Copy/duplicate changes UUIDs and remaps internal dependencies; cut/move preserves UUID.
  - **A07:** Resource Import/Export is dependency-aware and all-or-nothing with conflict resolution.
  - **A08:** Undo/Redo coalesces logical edit gestures and excludes camera navigation.
- **22.2. Noise panel, manipulations and expression**
  - **A09:** Required 2D/3D noise families match stable independent reference vectors.
  - **A10:** All Tree 6 manipulation families work through panel controls; typed input bindings, processing order, explicit conversions/projections/slices, noise-generator-cycle rejection and reference navigation work.
  - **A11:** Source settings, manipulation sections, shared parameters and pinned controls edit one canonical definition; controls follow the existing collapsible property-panel style, and direct/headless samples match previews.
  - **A12:** Expression grammar, each built-in, all type checks and error source spans pass.
  - **A13:** Curve editor control points, interpolation/tangents, snapping, precision and Apply/Cancel work.
- **22.3. World model, exactness and archive**
  - **A14:** Voxel Types, Groups, Sets, UUIDs, runtime IDs and semantic archive IDs maintain distinct semantics.
  - **A15:** Nested voxel-state AND/OR conditions and field-selection groups are independent and correct.
  - **A16:** Noise generators can be added to layers and inherit the containing layer's applicable settings; per-placement overrides take precedence, resets restore live inheritance, and layer edits update only inheriting properties. Reusing a resource across placements preserves independent settings. Multiple ordered layers/actions produce exact SET/CLEAR/conditional world results and accurate traces identifying each effective setting's layer/override source.
  - **A17:** Signed negative coordinates and inclusive vertical bounds match the spatial contract.
  - **A18:** Root sizes 2–256, worker counts, region order, caches and request partitioning produce identical voxels.
  - **A19:** Exact adaptive EMPTY/UNIFORM/BRANCH/PACKED representations preserve voxel content.
  - **A20:** Unsigned byte cell coordinate 255 and mesh boundary vertex 256 are represented correctly.
  - **A21:** Binary world/chunk serialization round-trips exactly and rejects corrupt inputs reliably.
- **22.4. Engine API and scheduling**
  - **A22:** Independent headless Java client builds/runs without UI/render/game libraries.
  - **A23:** Multiple WorldContexts keep seeds, caches and task state isolated.
  - **A24:** Cancellation never publishes incompletely generated chunks; completed output survives.
  - **A25:** Region streaming remains memory bounded under slow consumer backpressure.
  - **A26:** Failure policies, callback dispatch and context closure work safely.
- **22.5. Editor/visualizer functionality**
  - **A27:** 2D viewport modes/pan/zoom/hovered exact sample status work.
  - **A28:** 3D viewport representations, orbit/free-fly and preview region controls work.
  - **A29:** World layer previews and Voxel Inspector report actual engine provenance.
  - **A30:** Coordinate-only bookmarks persist separately for each tab and teleport as defined.
  - **A31:** LOD and performance fallback do not affect generated or serialized exact results.
  - **A32:** Theme, UI/window scaling, docking and keyboard shortcuts persist correctly in User Config.
  - **A33:** Find, Validate Project, Logs, Notifications, Help and Profiler provide real functionality and actionable navigation.
- **22.6. Finished release artifacts**
  - **A34:** Compiled Windows desktop application JAR launches outside Gradle/IDE and runs a smoke workflow.
  - **A35:** Bundled Windows runtime/launcher launches, and the independent engine JAR works from a separate example program.
  - **A36:** Source, reproducible Gradle build, fixtures, test reports, launch instructions, license notices and SHA-256 artifact manifest are present.
- **22.7. Evidence discipline**
  - **22.7.1.** For each `A01`–`A36`, execute a matching automated test or documented reproducible GUI scenario; record command, environment, date, relevant logs/recording/screenshot and `PASS` or `FAIL` based on observed evidence.
  - **22.7.2.** For benchmarks, record hardware, Java version, GPU, world area, sample sizes, worker count, cold/warm cache, run count and measurement methodology.
  - **22.7.3.** Repair any failed acceptance item and re-run dependent tests; the final release must satisfy the complete list.
  - **22.7.4.** Keep user-visible progress reports focused on results, test evidence, and concrete issues solved rather than repeated design paraphrases.
- **22.8. Required release folder layout**

```text
ProceduralVoxelCreator-Release/
  source/                       # One complete Gradle multi-module repository
  jars/
    ProceduralVoxelCreator.jar  # Finished desktop executable JAR
    noise-generator-engine.jar        # Independent headless engine library JAR
  windows/
    launcher/                   # Windows launchers/native dependency layout
    runtime/                    # Bundled Java runtime
  examples/
    independent-engine-client/  # Compiles/runs against published engine artifact
  fixtures/                     # JSON, reference vectors and expected results
  test-reports/                 # Actual executed test evidence
  docs/
    README.md
    API.md
    BUILD_AND_RUN.md
    DECISIONS.md
    REQUIREMENTS_AND_TESTS.md
    LICENSES.md
  SHA256SUMS.txt
```

- **22.8.1.** Choose JAR packaging and Windows native-library placement so `java -jar jars/ProceduralVoxelCreator.jar` works in the documented Java environment without source checkout or Gradle.
- **22.8.2.** Windows launcher uses the bundled compatible runtime and resolves LWJGL native libraries; package only one source code tree, not redundant platform-specific repositories.
- **22.8.3.** Test the application launch and creation/open/save/2D/3D/World/exit smoke path on Windows; prove the engine JAR independently by compiling/running `examples/independent-engine-client`.
- **22.8.4.** Document required Windows version, OpenGL capability, runtime architecture, memory assumptions and any externally required drivers accurately.
- **22.8.5.** Deliver the release as a named ZIP with integrity check and clearly identified entry points; retain reproducible build scripts and dependency licenses.
- **22.8.6.** Target Windows 11 on x64 for the bundled runtime, native dependencies, launchers and mandatory Windows smoke tests; document any minimum build selected during implementation.
- **22.8.7.** Use an explicit owner-approved notice. Do not infer permission to publish the repository or grant an open-source license from the requirement to deliver source.
- **22.8.8.** Require byte-identical desktop and engine JARs when rebuilt with the documented pinned toolchain/dependency inputs; make the release ZIP deterministic for identical supplied runtime/native binaries and report upstream binary checksums separately.

---

# EXECUTE THE TREE

1. Create the Java 25 multi-module Gradle repository, data models, registries and file-format schemas.
2. Implement strict transactional project validation, persistence and editing invariants.
3. Build the headless noise-processing/expression/curve compiler, deterministic voxel operations and exact adaptive storage.
4. Implement the public Java engine API, bounded async jobs, cache policies, exact binary archives and an independent consumer.
5. Build and integrate real 2D/3D/World visualization, renderer, camera controls, Inspector and LOD.
6. Complete the desktop UI, resource workflows, settings, help, logs, profiler, themes and interaction polish.
7. Run every acceptance case, resolve defects and performance bottlenecks, then create and test the Windows desktop executable JAR, bundled Windows distribution and headless engine JAR.
8. Deliver the complete verified source-and-binary release with test evidence and instructions.

**Release objective:** a fully implemented, deterministic, visually usable, tested, packaged procedural noise and voxel world editor, and a separately usable noise generator library, conforming to this entire tree.
