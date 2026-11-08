# Remaining implementation, validation and delivery work

Current source checkpoint: the preceding local main commit is 02a2aae; its publication remains pending, with the last observed GitHub main at 6f76999. The standalone consumer, private workflow/native text history, independent World Editor selection and ID text-preservation follow-up are **uncompiled and untested** under the owner's testing pause. The last observed baseline remains **922 tests / 120 suites**. The [A01–A36 acceptance matrix](REQUIREMENTS_AND_TESTS.md) retains its existing evidence; this register does not promote any gate.

The principal implementation tracks below cover specification Trees 0–22 exactly once. They are a finite audit queue, not eight claims of missing features or a completion percentage. Each track must reconcile its applicable numbered leaves with source and deferred verification, record concrete gaps as child items, and close those gaps before its source audit can be marked complete. Many features already have implementation and earlier evidence. Pending execution is recorded separately and cannot be treated as missing source or passing evidence.

## Implementation audit queue

| ID | Specification scope | Current source entry points | Work needed to close the source audit |
| --- | --- | --- | --- |
| W01 | Trees 0–3: architecture, configuration, startup, files, resources, clipboard and history | [Desktop](../editor-ui/src/main/java/io/voxelcreator/editor/Desktop.java), [EditorState](../editor-ui/src/main/java/io/voxelcreator/editor/EditorState.java), [DocumentSession](../project-model/src/main/java/io/voxelcreator/model/DocumentSession.java), [ResourcePanel](../editor-ui/src/main/java/io/voxelcreator/editor/ResourcePanel.java) | Trace every project/user/app boundary and New/Open/Save/Exit, copy/cut/paste, dependency/delete, import/export and Undo/Redo path. Record any missing user workflow, explicit-save boundary or transaction ownership case. |
| W02 | Tree 4: control conventions, shortcuts, scaling and responsiveness | [UiFields](../editor-ui/src/main/java/io/voxelcreator/editor/UiFields.java), [ScalarInputs](../editor-ui/src/main/java/io/voxelcreator/editor/ScalarInputs.java), [Shortcuts](../editor-ui/src/main/java/io/voxelcreator/editor/Shortcuts.java), [numeric policy](NUMERIC_EDITOR_PRECISION.md) | Finish the caller inventory for paired numeric controls, compact display versus exact editing, retained invalid input, metadata tooltips, bounded layouts, search debounce and responsive work. Account for every caller instead of assuming shared helpers cover it. |
| W03 | Trees 5–8: noise authoring, typed processing, expressions, curves and remapping | [PropertyPanel](../editor-ui/src/main/java/io/voxelcreator/editor/PropertyPanel.java), [NoiseCompiler](../engine-core/src/main/java/io/voxelcreator/core/NoiseCompiler.java), [Expression](../engine-core/src/main/java/io/voxelcreator/core/Expression.java), [ScalarCurve](../engine-core/src/main/java/io/voxelcreator/core/ScalarCurve.java) | Reconcile the required registry/function inventory and real authoring controls, exact input/output eligibility, curve gestures/local history, private Apply/Cancel and dependency resolution. Link independent mathematical expectations for each semantic behavior. |
| W04 | Trees 9–10: voxel identities, groups/sets, ordered world stages and inheritance | [ProjectData](../project-model/src/main/java/io/voxelcreator/model/ProjectData.java), [SettingsResolver](../project-model/src/main/java/io/voxelcreator/model/SettingsResolver.java), [WorldListEdits](../editor-ui/src/main/java/io/voxelcreator/editor/WorldListEdits.java), [PropertyPanel](../editor-ui/src/main/java/io/voxelcreator/editor/PropertyPanel.java) | Walk type/group/set workflows and every interpretation/action/predicate/driver. Confirm visible inheritance/override/reset/source navigation, independent placements and execution-order controls against each leaf. |
| W05 | Trees 11–14: coordinates, exact chunks, public engine, scheduling, caching and archives | [NoiseGeneratorEngine](../engine-api/src/main/java/io/voxelcreator/api/NoiseGeneratorEngine.java), [WorldContext](../engine-api/src/main/java/io/voxelcreator/api/WorldContext.java), [VoxelArchive](../voxel-storage/src/main/java/io/voxelcreator/storage/VoxelArchive.java), [build](../build.gradle) | Reconcile all API responsibilities/overloads and exception/ownership behavior, region partitioning, exactness, bounded scheduling/cache policy, archive coverage/corruption and external dependency use. W05-C01 below addresses one confirmed handoff gap. |
| W06 | Trees 15–17: three visualizers, cameras, Inspector selection, rendering and fallback | [Viewports](../editor-ui/src/main/java/io/voxelcreator/editor/Viewports.java), [render module](../editor-render/src/main/java/io/voxelcreator/render/), [requirements matrix](REQUIREMENTS_AND_TESTS.md) | Trace each required representation/control/gesture and accepted-preview lifetime, camera-relative/fixed modes, exact hover/pick provenance, streaming/LOD and mandatory fallback order. Separate optional renderer extensions from required behavior. |
| W07 | Trees 18–19: shell, Find, settings, Help, diagnostics, updates and profiling | [Desktop](../editor-ui/src/main/java/io/voxelcreator/editor/Desktop.java), [AppMetadata](../editor-ui/src/main/java/io/voxelcreator/editor/AppMetadata.java), [PerformancePanel](../editor-ui/src/main/java/io/voxelcreator/editor/PerformancePanel.java), [manual checks](COMPLETED_MANUAL_CHECKS.md) | Reconcile menu and utility entry points with actual services, metadata/help consistency, preference persistence, trace/profiler attribution, actionable recovery and background-work ownership. New failure fixes must identify the leaf/workflow they close. |
| W08 | Trees 20–22: schemas/defaults, fixture design, acceptance and release contracts | [schemas](../schema/), [fixtures](../fixtures/), [build/run](BUILD_AND_RUN.md), [packager](../scripts/package_release.py), [packaging contract](DETERMINISTIC_PACKAGING.md) | Reconcile every normative format/default, required fixture family and A01–A36 evidence command. Finish release input/output/notice documentation and the final evidence plan; actual execution and artifact certification belong to V01–V04. |

- [ ] W01 source audit reconciled; all discovered child gaps closed in source.
- [ ] W02 source audit reconciled; all discovered child gaps closed in source.
- [ ] W03 source audit reconciled; all discovered child gaps closed in source.
- [ ] W04 source audit reconciled; all discovered child gaps closed in source.
- [ ] W05 source audit reconciled; all discovered child gaps closed in source.
- [ ] W06 source audit reconciled; all discovered child gaps closed in source.
- [ ] W07 source audit reconciled; all discovered child gaps closed in source.
- [ ] W08 source audit reconciled; all discovered child gaps closed in source.

An audit closes only with a checked leaf inventory and a recorded disposition for each applicable leaf. These eight source-audit boxes remain open. A deferred test definition alone does not close a missing implementation; an unexecuted source audit does not certify runtime behavior. Avoid creating additional feature scope from a general limitation paragraph when the numbered specification does not require it.

## W01 resource-workflow audit progress

The first W01 slice traced the existing resource browser, clipboard and membership editor. “Source reviewed” records inspected implementation paths, not passing evidence or closure of the encompassing specification tree.

| Leaves | Source disposition | Remaining work |
| --- | --- | --- |
| 1.3.8 | Source reviewed: Gradle module dependencies flow from editor/render toward API/core/storage/model; source import scan found no reverse editor/render imports in the four headless modules. | Compile independent artifacts and execute dependency/notice isolation checks when validation resumes. |
| 2.1.1–2.1.3 | Source reviewed: ProjectData is the authored UUID/ordered-resource/world/bookmark document; explicit ProjectIO/DocumentSession writes exclude editor preferences, local histories and generated/runtime objects. | Execute schema/save/Undo and complete project-boundary scenarios. |
| 2.2.1–2.2.4 | Source reviewed: UserConfig owns layout/theme/scales, input/editor/visualization/performance preferences; Desktop exposes all six User Settings categories and saves preferences through its separate writer. | Execute full preference persistence/private-project invariance and platform layout scenarios. |
| 2.3.1–2.3.4 | Source reviewed: AppMetadata owns presentation and future-item defaults; strict read/validation rejects incompatible metadata and load falls back to built-ins. CreationRegistry signatures/ranges/options remain authoritative; defaults apply through editor creation. | Finish per-control coverage in W02/W03/W07 and execute malformed-metadata/future-item/unchanged-engine scenarios. |
| 2.4.1–2.4.5 | Source reviewed: settings menu/pages and compact status access reach the existing project/app/user/profiler owners; Project Settings publishes only seed/bounds transactionally and display/profiler links share their backing owners. | Execute all entry points and follow the remaining temporary-editor audit, including C05 below. |
| 2.5.1–2.5.4 | Source reviewed: Desktop initializes services hidden, then EditorPresentation requires a complete first frame before showing it. New ProjectData starts with no user resources; protected roots and default/persisted theme/docking are presented without automatic recent-project reopening. Explicit launch-file/smoke paths remain separate. | Execute startup, empty-world, first-frame, preference/fallback and real Windows 11 launch scenarios. |
| 2.6.1–2.6.2 | Source reviewed: Desktop File actions route to project/package workflows; Save uses the remappable Ctrl+S binding and DocumentSession checkpoint tracking. | Execute menu/remap/dirty-state scenarios for the current revision. |
| 2.6.3 | Source reviewed: draft decisions precede Save/Don't Save/Cancel; New/Open/Exit and GLFW close share the workflow. Failed required Save clears the pending transition; retry is standalone Save. | Execute the complete transition/physical window-close matrix. |
| 2.6.4 | Source reviewed: owned Save prepares ProjectIO sibling staging, flushes and publishes through its atomic writer after exact owner checks. | Execute I/O/failure/durability/platform cases. |
| 2.6.5–2.6.6 | Source reviewed: prepared Open parses/validates a separate candidate before publication; rejection retains the active document/checkpoint. | Finish detailed diagnostic caller inventory and execute failed/stale/cancelled Open cases. |
| 2.6.7 | Source reviewed: normal project writes use explicit Save paths; the other Desktop save is explicit smoke-fixture output. Periodic preferences use a separate writer. | Keep prohibited recent/reopen/autosave/repair behavior excluded during remaining startup/config audit. |
| 2.7.1–2.7.5 | Source reviewed: ProjectData/schema preserve formatVersion 2, the required top-level authoring collections, one World object, explicit layer/operation arrays and three tab bookmark arrays. ProjectIO preflights required top-level shapes. | Execute schema/file round trips and all malformed shape/ordering cases. |
| 2.7.6 | Source reviewed: ProjectIO rejects unsupported versions, trailing/duplicate JSON, incompatible typed fields/coercions and malformed UUID text; Validator checks identity/reference integrity. No migration/repair path is used. | Execute strict admission and exact-pointer definitions for every nested family. |
| 2.7.7–2.7.9 | Source reviewed: EditorState supplies NoiseCompiler validation to DocumentSession, and ProjectLoader applies it after ProjectIO structural admission. Compiler validation visits every noise; disabled processing retains structural/identity/reference checks while enabled committed items require full semantics. | Execute disabled/enable/dependency and unreferenced-item cases; complete wider diagnostic caller reconciliation. |
| 2.8.1–2.8.3 | Source reviewed: ProjectIO handles whole authoring JSON, ResourcePackageFiles requires its packageVersion/resources envelope and validates its resource payload, and VoxelArchive owns a separate binary dictionary/chunk format without editor-project dependency. | Execute cross-format rejection and standalone project/package/archive readers and artifact handoff. |
| 3.1.1 | Source reviewed: ProjectData identities and Validator's global UUID set cover resources, noise items/parameters and embedded curve/point identities; absent/duplicate identities reject. | Execute each resource family, malformed identity and nested-copy case. |
| 3.1.2 | Source reviewed: Properties/creation/F2 expose one editable primary ID; hidden UUIDs remain reference authority. Long-ID buffer preservation is W01-C06 below. | Execute all resource-kind controls, long Unicode input and disclosure layouts. |
| 3.1.3, 3.1.5 | Source reviewed: Validator owns case-sensitive namespaces, including separate project-wide 2D/3D collections, world layers, per-layer operations, noise items/parameters, sibling-root folders and each voxel/group/set kind. ResourceGraph scopes move/copy collisions accordingly. | Execute complete namespace/collision/reorder/reference combinations; no runtime closure. |
| 3.1.4 | Source reviewed: rename changes ID only; ResourceTransactions/clipboard duplicate identities and remap internal UUID references, while collision naming uses -copy/-copy-2 succession. | Execute recursive copies, external references and atomic rename/Undo/Redo, including W01-C06 long IDs. |
| 3.1.6 | Source reviewed: ResourceIds/Validator require nonempty IDs, reject edge-whitespace/control/NFC violations without repair, compare exact case and reserve voxel EMPTY. Text editing now preserves input for validation (W01-C06). | Execute malformed incoming files and full editor correction/commit scenarios. |
| 3.2.1–3.2.3 | Source reviewed: fixed root menus omit destructive actions; ResourceGraph validates root/type/parent/sibling constraints and folder cycles. Folder/tree order is separate from computational world-stage order. | Execute physical cross-root/descendant/reorder gestures and compare sampling before/after organizational moves. |
| 3.2.4 | Source reviewed: ResourcePanel context actions, inline F2 rename, hierarchy/drop/reorder entry points and ResourceBrowserEdits transaction handoff are present. Confirmed long-ID buffer gap is W01-C06 below. | Execute real gestures and recovery cases when validation resumes. |
| 3.2.5 | Source reviewed: sibling-scope selection guards and consolidated Properties summary are present. | Execute selection transitions; independent World Editor source is recorded below. |
| 3.2.6, 10.9.2 | Confirmed missing independent World Editor selection; implemented as W01-C02 below. | Compile/execute deferred selection/navigation/native checks and complete remaining Tree 10 presentation audit. |
| 3.2.7 | Source reviewed: Resources and Properties use PanelDockControls; WorkspaceDocking and Desktop/WorkspaceLayout capture dock IDs/INI through UserConfig, including reset ownership. | Execute undock/redock, floating windows, restart/reset and unchanged project/history checks on required displays. |
| 3.2.8 | Source reviewed: subtree dependents, Cancel/replacement review and validated atomic delete are present. | Execute dependency/replacement cases; retain all-dependents inventory in the broader audit. |
| 3.3.1 | Source reviewed: ResourcePanel resets clipboard on the document lifetime token, including same-UUID replacement; ordinary edits/history retain the session clipboard. | Execute reopen/close and pending-command transitions. |
| 3.3.2–3.3.5 | Source reviewed: captured copy subtree, internal UUID remapping, collision-safe names and live UUID-preserving cut/move pass through one owned project transaction. | Execute complete copy/folder/cut/rejection scenarios. |
| 3.3.6 | Source reviewed: ShortcutKeys/Shortcuts pass text/widget/modal ownership into dispatch; resource Copy/Cut/Paste do not intercept active native text input. The project clipboard is separate ResourcePanel state. | Execute actual OS clipboard Copy/Cut/Paste and configured resource bindings with active/inactive text in all hosts. |
| 3.3.6 | Source reviewed: application command suppression preserves native text clipboard ownership; project resource clipboard is separate. | Physical system clipboard/input verification remains pending. |
| 3.4.1 | Source reviewed: ExportTree/ResourceExportPanel expose explicit checkboxes, folder/layer context, included badges and exact dependency coverage; Included alone acknowledges the current frozen preview. | Execute all tree/closure/gesture combinations and representative package-size cases. |
| 3.4.2 | Source reviewed: ResourceImport plan rows show conflict, compatibility, incoming/existing/target UUID/ID and COPY/REUSE/SKIP resolution in the owned review. | Execute the full conflict/type/namespace matrix. |
| 3.4.3 | Source reviewed: ResourceImport forks retain COPY mappings, validate the complete candidate and publish through one DocumentSession transaction; stale/failed/cancelled work has no authority. | Execute whole-payload/identity/failure/concurrency cases for the current revision. |
| 3.4.4 | Source reviewed: ResourcePackageFiles requires the version-1 portable envelope, distinct from ProjectIO's project entry and voxel-storage archives; Import appends resources without replacing the active project. | Execute cross-format rejection and payload round trips. |
| 3.4.5 | Source reviewed: portable voxel/group JSON passes structural, color/member/UUID and full candidate validation before publication. | Execute the full voxel/group/color/member/conflict matrix. |
| 3.4.6 | Source reviewed: semantic compatibility gates REUSE, reverse dependency closure propagates SKIP, COPY remaps identities/collisions, and the candidate preserves existing definitions. | Execute every resolution/dependency/namespace combination and independent replay. |
| 3.5.3, membership editor only | Confirmed missing local history; implemented as W01-C01 below. | Compile/execute deferred checks and audit every other temporary editor. |
| 3.5.3, Import choices only | Confirmed missing resolution history; implemented as W01-C03 below. | Compile/execute deferred history/planning/review cases and continue other temporary editors. |
| 3.5.3, Export selection only | Confirmed missing local selection history; implemented as W01-C04 below. | Compile/execute deferred selection/review/publication cases and continue other temporary editors. |
| 3.5.3, Project Settings only | Confirmed missing local raw-input history; implemented as W01-C05 below. | Compile/execute deferred gesture/history/Apply cases and continue other temporary editors. |
| 3.5.3, bookmark forms only | Confirmed missing cross-field raw-input history; implemented as W01-C07 below. | Compile/execute deferred Add/Rename/Teleport history and native/layout cases; continue other temporary editors. |
| 3.5.3, noise dependency choices only | Confirmed missing local resolution/replacement history; implemented as W01-C08 below. | Compile/execute deferred choice/history/worker/decision and native/layout cases; continue other temporary editors. |
| 3.5.3, expanded expression/F2 native text | Source reviewed: native text supplies local Undo/Redo, but its input identity was reused across openings. Per-opening text scopes implement W01-C09 below. | Execute same-text close/reopen history, same-opening reflow, error selection and existing publication/lifetime suites. |
| 3.5.2/3.5.3, curve gesture history only | Source reviewed: CurveEditSession has bounded local curve states, gesture begin/preview/end/cancel and Undo/Redo; CurveDialog coordinates pointer/numeric gesture cancellation and private publication. | Execute pointer/numeric/native history definitions; finish broader control/navigation caller reconciliation in W01/W03. |
| 3.5.1, 3.5.4, 3.5.6 | Source reviewed: DocumentSession retains ProjectDelta value/splice changes with bounded history; dirty state compares the explicit saved checkpoint to current authoring values. ProjectData and portable payloads do not serialize DocumentSession/local stacks. | Execute current-revision delta/dirty/save/Undo/Redo/payload checks and finish 3.5.2/3.5.5 gesture/navigation caller inventory. |

Configuration/startup, lifecycle, format, identity/hierarchy/docking, clipboard and the listed history paths have source dispositions without new runtime evidence. W01 remains open for the complete Tree 0/1 authority/ownership inventory, wider diagnostic caller reconciliation, 3.5.2/3.5.5 gesture/navigation callers and remaining temporary editors. Detailed control/engine/render functionality retains its respective W02–W08 audit. Continue these remaining leaf inventories before marking any parent audit complete.

## Confirmed gaps and source changes

**W01-C01 — membership-local Undo/Redo (3.5.3, 9.4.3): implementation added, execution pending.** The searchable membership modal had private Apply/Cancel choices but no local history. [Local membership history](MEMBERSHIP_LOCAL_HISTORY.md) now records ordered add/remove deltas, groups bulk actions, provides persistent Undo/Redo and follows existing remapped chords while text/widgets retain their keys. A local history change cancels pending Apply; Apply/Cancel disposes of that history. Final Apply remains one member-only project transaction and preserves separate private Properties input.

- [x] Implement bounded local deltas, exact order restoration, no-op/Redo behavior and owner/closure checks.
- [x] Add modal actions/shortcut routing and deferred unit/native/independent-reader definitions.
- [ ] Compile and execute the ten new unit definitions, existing membership suites and 30-/18-case native/readback scenarios.
- [ ] Verify text/focus ownership, remapped shortcuts and physical input at required Windows 11/DPI layouts.

This source change closes only the membership-specific gap; it does not close W01, the all-dialog requirement or an acceptance gate.

**W01-C02 — independent World Editor selection (3.2.6, 10.9.2): implementation added, execution pending.** [The World Editor panel](WORLD_EDITOR_SELECTION.md) now has a separate execution-order catalog, UUID-based sibling selection, Ctrl/Shift ranges, retained search context and a consolidated summary. It never silently replaces Resources/Properties ownership. Single-item Edit Properties uses the existing guarded navigation and a selection/document ticket; Find References uses the exact UUID. New/Open clears transient selection, and moved/deleted items are pruned. Docking, Appearance scaling and context Help include the new panel.

- [x] Add the independent selection/catalog, clipped dockable list, summary and guarded single-item actions.
- [x] Add eight unit definitions, six native layout/input case definitions and an independent project/geometry reader; synchronize the twenty-window scale catalog and deferred expectations.
- [ ] Compile and execute selection/guard/scale suites and the new native/readback scenario.
- [ ] Verify actual dock/undock/redock, focus/text/modifier behavior and Windows 11/DPI interaction.

This source change closes only the independent-selection gap. Other World presentation actions still use Resources/Properties, and W01/W04 and all runtime acceptance gates remain open.

**W01-C03 — local Import choice history (3.5.3, 3.4): implementation added, execution pending.** [Import choice history](IMPORT_CHOICE_HISTORY.md) records bounded resolution deltas, exposes Undo Choice/Redo Choice and shares existing remapped private-modal bindings. Restored choices keep COPY identities, rebuild their preview and require fresh Resources review. Queued/ready planning is superseded; final Import locks choices, validates and commits one project transaction. Reload starts a new identity/history baseline; accepted Import/Cancel consumes local history.

- [x] Add bounded history, no-op/Redo/review rules, planner ownership and closure/reload disposal.
- [x] Add version-owned actions, shared bindings, ten unit definitions and expanded native/independent-reader definitions.
- [ ] Compile and execute history, Import/shortcut suites and the updated 30-case native/readback scenario.
- [ ] Verify remapped shortcuts, text/focus ownership and required Windows 11/DPI/physical input.

This closes Import's local-choice-history source gap only. Other temporary editors and all execution/delivery gates remain open; Export's source change and portable-package dispositions are recorded in this follow-up.

**W01-C04 — local Export selection history (3.5.3, 3.4.1): implementation added, execution pending.** [Export selection history](EXPORT_SELECTION_HISTORY.md) records bounded explicit-UUID deltas, restores the owning selection set, rebuilds dependency coverage and requires fresh Included review. History supersedes queued/ready planning, preserves missing UUIDs explicitly, and starts a new baseline on Reload. The package dialog locks history during file staging/publication. Cancel/reset and successful export consume it; failure retains it. Export does not create a project transaction.

- [x] Add bounded selection deltas, no-op/Redo/review rules, planner ownership and closure/reload disposal.
- [x] Add version-owned modal/standalone actions, shared bindings, ten unit definitions and expanded existing native/independent-reader definitions.
- [ ] Compile and execute history, Export/shortcut/Help suites and updated package/hierarchy/lifetime native/readback scenarios.
- [ ] Verify remapped shortcuts, text/focus ownership and Windows 11/DPI/physical input.

This closes Export's selection-history source gap only. The all-dialog audit, remaining W01 inventory and all execution/delivery gates remain open.

**W01-C05 — Project Settings local raw-input history (3.5.3, 2.4.2): implementation added, execution pending.** [Project Settings history](PROJECT_SETTINGS_LOCAL_HISTORY.md) retains bounded exact raw-field deltas, coalesces continuous typing/slider gestures and exposes Undo Settings/Redo Settings. Restoration synchronizes native buffers/controllers, clears local feedback and retires pending Apply. Final Apply remains one metadata-only project transaction; closure/reload consumes local history and stale owners cannot use it. Native text Undo and Document shortcut routing retain their existing ownership.

- [x] Add bounded raw history, gesture/control boundaries, net-zero/Redo/cache rules and lifecycle/Apply ownership.
- [x] Add persistent version-owned actions, eight unit definitions and expanded existing native/readback/Help definitions.
- [ ] Compile and execute local history, Project Settings/Help suites and updated 24-/12-case native/readback scenarios.
- [ ] Verify actual text/drag/focus behavior and Windows 11/DPI/physical input.

This closes Project Settings' history source gap only. Other temporary editors, remaining W01 leaves and all execution/delivery gates remain open.

**W05-C01 — standalone build-tool consumer (13.8.2, 13.8.3, 22.8.3): implementation added, execution pending.** The release assembler copied Java consumers and generated run.cmd, but supplied no independent Gradle project. [The standalone consumer](../example-client/standalone/README.md) now has its own settings/build, one engine-JAR file dependency, Java 25 toolchain and the existing Example entry point. Explicit source/JAR properties support a checkout; default paths support the extracted distribution. A precompile check requires the engine API and rejects known editor/render/native packages. Release assembly includes these exact support files beside the existing Java sources, using the same safe file-copy and deterministic manifest/archive path.

- [x] Add the independent build and checkout/distribution instructions without duplicating engine/example implementation.
- [x] Integrate its support files into release assembly and define missing-file/link-rejection preservation checks.
- [ ] Compile/run with the pinned wrapper against the built engine JAR from a checkout.
- [ ] Compile/run from an extracted distribution, including a path containing spaces.
- [ ] Exercise missing/wrong/desktop JAR rejection and retain the independent example's existing full PASS output and arithmetic/archive expectations.

Three new PackageReleaseTest definitions cover exact standalone support bytes in the staged directory/ZIP; each missing support file preserving prior outputs; and symbolic-link rejection preserving prior outputs. Existing Example, engine math, native receipts and independent arithmetic dimensions are unchanged. None of these definitions or Gradle tasks has run. W05-C01's source work does not close the remaining W05 audit or A22/A35/A36 acceptance.

**W01-C06 — lossless ID text editing (3.1.2, 3.1.4, 3.1.6, 3.2.4): implementation added, execution pending.** [Editor text preservation](SEMANTIC_ID_VALIDATION.md#editor-text-preservation-follow-up--unvalidated) removes fixed byte truncation from creation/F2/ordinary Properties buffers. Existing long Unicode IDs load exactly; typing/paste can grow buffers. Semantic validation and explicit project transactions retain authority; expression-specific bounded input keeps its policy.

- [x] Add shared resizing buffer construction before initial assignment and wire creation/rename/ordinary Properties text.
- [x] Add three binding-level definitions and three native long-ID cases with 24 independent whole-project expectations, including unchanged Enter, private staging, Apply and Undo/Redo.
- [ ] Compile and execute buffer, identity and native/readback definitions; inspect other resource-kind/creation input paths.
- [ ] Verify physical paste/edit/cancel and Windows 11/DPI interaction; no runtime gate closes from source review.

**W01-C07 — bookmark/Teleport local field history (3.5.2, 3.5.3, 3.5.5): implementation added, execution pending.** [Bookmark local history](BOOKMARK_LOCAL_HISTORY.md) restores bounded exact name/coordinate deltas in Add/Rename/Teleport. Native typing/slider gestures coalesce, restoration synchronizes buffers/controllers and clears attempt feedback, and focused modal bindings preserve native text ownership. Apply/Cancel/closure consumes local history; running/stale owners cannot restore. Add/Rename remains one project transaction; navigation runs only on explicit Teleport Apply.

- [x] Share raw-field history with Project Settings and bound retained characters for existing long names.
- [x] Add private session actions, persistent version-owned controls, six session definitions, shared-budget/Help definitions and expanded native/layout/readback definitions.
- [ ] Compile and execute bookmark/shared-history/Help suites and the updated input/60-case layout scenarios.
- [ ] Verify actual slider grouping, remapped keys, physical clipboard/input and Windows 11/DPI interaction.

**W01-C08 — noise dependency-resolution choice history (3.5.3, 6.1.5): implementation added, execution pending.** [Noise choice history](NOISE_RESOLUTION_HISTORY.md) restores bounded resolution/replacement deltas, rebuilds private previews, supersedes queued/ready work and requires explicit resolved staging. Choice/work-owned controls and existing remapped modal bindings preserve native input; Cancel/Escape retains priority. Synchronous planner failure/reentrancy cannot consume or overwrite newer history. Apply/Cancel/closure disposes of history; Properties Apply remains the parent project transaction.

- [x] Add 100-step local pair deltas, no-op/Redo/retry rules, preview supersession and synchronous planning ownership.
- [x] Add persistent version-owned actions/picking, seven unit definitions and expanded Help/native/layout/readback definitions.
- [ ] Compile and execute history/session/ready/Help suites and the existing expanded 30-case form/12-case background scenarios.
- [ ] Verify remapped bindings, replacement-picker transitions, physical input and Windows 11/DPI interaction.

**W01-C09 — native text history opening ownership (3.5.3): implementation added, execution pending.** [Native input ownership](NATIVE_TEXT_HISTORY_OWNERSHIP.md) gives Expanded Expression and F2 rename distinct text-widget identities on each opening, retaining native Undo/Redo and stable in-opening reflow. This prevents same-text successor inputs from inheriting discarded native history. Existing window/child identities and authored UUIDs remain unchanged.

- [x] Scope the expanded multiline widget to its dialog UUID and F2 input to each admitted rename opening.
- [x] Correct the six-case layout definition to reflow one opening; add five expression/two rename lifetime definitions and independent identity/text/full-project readback expectations.
- [ ] Compile and execute expanded-expression, identity, pane-lifetime and native/readback definitions.
- [ ] Verify physical Undo/Redo/IME/clipboard, detached/DPI layouts and Windows 11 interaction.

## Validation and delivery queue

| ID | Required work after implementation is complete | Completion evidence |
| --- | --- | --- |
| V01 | Compile the complete current revision; run Gradle unit/integration checks, schema/reference consumers, native scenarios and packaging regressions; repair failures and rerun affected checks. | Actual commands/environments/logs/reports for the current commit, including `clean test build dependencyNotices releaseJars`. No historical count or unexecuted definition substitutes for these results. |
| V02 | Complete deterministic/cross-system, cancellation/backpressure/failure and representative performance/stress validation. | Raw-bit/exact-voxel/archive expectations across required roots, partitions, seeds, cache and worker configurations; recorded JVM/CPU/GPU, workloads, cold/warm cache and measurement method. |
| V03 | Complete Windows 11 x64 interactive/hardware acceptance, startup/close/restart, DPI/multiple-monitor layouts and all required editing/viewport/recovery gestures. | Reproducible A01–A36 scenarios with screenshots/logs and actual hardware/OS details. Linux/Windows Server software-GL evidence retains its narrower scope. |
| V04 | Build and verify the final desktop/engine artifacts, bundled launcher/runtime, independent consumer, notices and named deterministic release ZIP outside Gradle/IDE. | Verified entry points and folder layout, final reports/docs/fixtures/source, SHA256SUMS, upstream runtime/native checksums, reproducible JAR/ZIP comparison and the required owner-approved source notice. |

- [ ] V01 current-revision compilation and automated/native verification complete.
- [ ] V02 deterministic, concurrency and representative performance evidence complete.
- [ ] V03 Windows 11 and full interactive acceptance complete.
- [ ] V04 final verified release package and notice review complete.

The owner's current testing pause remains in force. No compiler, Gradle, JUnit, native probe, consumer, Python/schema/packaging checker, packaging operation, update request or workflow ran for these additions. Source review and git whitespace/content/metadata checks are the available verification. The implementation is not being declared fully developed or release-ready; next development should continue the W01 leaf inventory above and record concrete child gaps here.
