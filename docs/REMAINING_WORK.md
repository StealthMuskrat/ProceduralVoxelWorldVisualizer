# Remaining implementation, validation and delivery work

Current source checkpoint: the preceding local main commit is fc27ec1; its publication remains pending, with the last observed GitHub main at 6f76999. The standalone consumer, private workflow/native text/resource-form history, independent World Editor selection, ID preservation, bounded UI dispatch, owned compiled-snapshot admission, world field compilation reuse, Y-bounded column prefetch, parent-poll cancellation observation, archive decoder cancellation, staged snapshot finalization, coordinate-root validation, sampler lookup admission, world-operation cancellation, post-poll shared ownership, cache-policy/region-partition consumer definitions, closed-scene mailbox, scene cancellation ownership, upload acknowledgement boundaries and volume texture cleanup are **uncompiled and untested** under the owner's testing pause. The last observed baseline remains **922 tests / 120 suites**. The [A01–A36 acceptance matrix](REQUIREMENTS_AND_TESTS.md) retains its existing evidence; this register does not promote any gate.

The principal implementation tracks below cover specification Trees 0–22 exactly once. They are a finite audit queue, not eight claims of missing features or a completion percentage. Each track must reconcile its applicable numbered leaves with source and deferred verification, record concrete gaps as child items, and close those gaps before its source audit can be marked complete. Many features already have implementation and earlier evidence. Pending execution is recorded separately and cannot be treated as missing source or passing evidence.

## Implementation audit queue

| ID | Specification scope | Current source entry points | Work needed to close the source audit |
| --- | --- | --- | --- |
| W01 | Trees 0–3: architecture, configuration, startup, files, resources, clipboard and history | [Desktop](../editor-ui/src/main/java/io/voxelcreator/editor/Desktop.java), [EditorState](../editor-ui/src/main/java/io/voxelcreator/editor/EditorState.java), [DocumentSession](../project-model/src/main/java/io/voxelcreator/model/DocumentSession.java), [ResourcePanel](../editor-ui/src/main/java/io/voxelcreator/editor/ResourcePanel.java) | Trace every project/user/app boundary and New/Open/Save/Exit, copy/cut/paste, dependency/delete, import/export and Undo/Redo path. Record any missing user workflow, explicit-save boundary or transaction ownership case. |
| W02 | Tree 4: control conventions, shortcuts, scaling and responsiveness | [UiFields](../editor-ui/src/main/java/io/voxelcreator/editor/UiFields.java), [ScalarInputs](../editor-ui/src/main/java/io/voxelcreator/editor/ScalarInputs.java), [Shortcuts](../editor-ui/src/main/java/io/voxelcreator/editor/Shortcuts.java), [numeric policy](NUMERIC_EDITOR_PRECISION.md) | Finish the caller inventory for paired numeric controls, compact display versus exact editing, retained invalid input, metadata tooltips, bounded layouts, search debounce and responsive work. Account for every caller instead of assuming shared helpers cover it. |
| W03 | Trees 5–8: noise authoring, typed processing, expressions, curves and remapping | [PropertyPanel](../editor-ui/src/main/java/io/voxelcreator/editor/PropertyPanel.java), [NoiseCompiler](../engine-core/src/main/java/io/voxelcreator/core/NoiseCompiler.java), [Expression](../engine-core/src/main/java/io/voxelcreator/core/Expression.java), [ScalarCurve](../engine-core/src/main/java/io/voxelcreator/core/ScalarCurve.java) | Reconcile the required registry/function inventory and real authoring controls, exact input/output eligibility, curve gestures/local history, private Apply/Cancel and dependency resolution. Link independent mathematical expectations for each semantic behavior. |
| W04 | Trees 9–10: voxel identities, groups/sets, ordered world stages and inheritance | [ProjectData](../project-model/src/main/java/io/voxelcreator/model/ProjectData.java), [SettingsResolver](../project-model/src/main/java/io/voxelcreator/model/SettingsResolver.java), [WorldListEdits](../editor-ui/src/main/java/io/voxelcreator/editor/WorldListEdits.java), [PropertyPanel](../editor-ui/src/main/java/io/voxelcreator/editor/PropertyPanel.java) | Walk type/group/set workflows and every interpretation/action/predicate/driver. Confirm visible inheritance/override/reset/source navigation, independent placements and execution-order controls against each leaf. |
| W05 | Trees 11–14: coordinates, exact chunks, public engine, scheduling, caching and archives | [NoiseGeneratorEngine](../engine-api/src/main/java/io/voxelcreator/api/NoiseGeneratorEngine.java), [WorldContext](../engine-api/src/main/java/io/voxelcreator/api/WorldContext.java), [VoxelArchive](../voxel-storage/src/main/java/io/voxelcreator/storage/VoxelArchive.java), [build](../build.gradle) | Reconcile all API responsibilities/overloads and exception/ownership behavior, region partitioning, exactness, bounded scheduling/cache policy, archive coverage/corruption and external dependency use. W05-C01–C11 below address confirmed consumer, snapshot-admission, repeated canonical-compilation, Y-prefetch, cancellation-observation, archive, staged-finalization, coordinate-validation, sampler-lookup, operation-checkpoint and post-poll ownership gaps. |
| W06 | Trees 15–17: three visualizers, cameras, Inspector selection, rendering and fallback | [Viewports](../editor-ui/src/main/java/io/voxelcreator/editor/Viewports.java), [render module](../editor-render/src/main/java/io/voxelcreator/render/), [requirements matrix](REQUIREMENTS_AND_TESTS.md) | Trace each required representation/control/gesture and accepted-preview lifetime, camera-relative/fixed modes, exact hover/pick provenance, streaming/LOD and mandatory fallback order. Separate optional renderer extensions from required behavior. W06-C01–C04 below address confirmed late-receipt, cancellation-callback, upload acknowledgement and texture cleanup ownership gaps. |
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
| 1.2.6, editor handoff only | Confirmed unbounded callback queue/drain and escaping callback failures; W01-C11 below adds bounded strict posting and per-frame delivery. The existing editor worker executor retains its bounded admission policy. | Execute saturation, healthy-peer/failure/retry and measured frame cases; complete remaining queue/budget inventory in W05/W06. |
| 1.4.1–1.4.3, inspected ownership paths | Source reviewed: Desktop owns UI/GL drawing and EditorState drains delivery; PreviewMailbox separates prepared worker data from acceptance. CompiledProject now validates the private retained candidate (W05-C02), and VoxelChunk.Builder finishes with private copied arrays. | Execute thread/publication/immutability definitions and native teardown; complete remaining worker/render caller inventory in W05/W06. |
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
| 3.5.3, browser forms | Confirmed missing ID/algorithm/placement/replacement history; implemented across Create/Add Noise Generator/Delete as W01-C10 below. | Execute private history/ownership, primary handoff, picker and native/layout definitions. |
| 3.5.2/3.5.3, curve gesture history only | Source reviewed: CurveEditSession has bounded local curve states, gesture begin/preview/end/cancel and Undo/Redo; CurveDialog coordinates pointer/numeric gesture cancellation and private publication. | Execute pointer/numeric/native history definitions; finish broader control/navigation caller reconciliation in W01/W03. |
| 3.5.1, 3.5.4, 3.5.6 | Source reviewed: DocumentSession retains ProjectDelta value/splice changes with bounded history; dirty state compares the explicit saved checkpoint to current authoring values. ProjectData and portable payloads do not serialize DocumentSession/local stacks. | Execute current-revision delta/dirty/save/Undo/Redo/payload checks and finish 3.5.2/3.5.5 gesture/navigation caller inventory. |

Configuration/startup, lifecycle, format, identity/hierarchy/docking, clipboard and the listed history paths have source dispositions without new runtime evidence. W01 remains open for the complete Tree 0/1 authority/ownership inventory, wider diagnostic caller reconciliation, 3.5.2/3.5.5 gesture/navigation callers and remaining temporary editors. Detailed control/engine/render functionality retains its respective W02–W08 audit. Continue these remaining leaf inventories before marking any parent audit complete.

## W05 coordinate, storage and engine audit progress

These inspected source paths cover the listed Tree 11 coordinate boundaries, Tree 12 storage paths, 13.1's entry boundaries and specific sampling/cancellation/archive paths below. The broader W05 audit and all runtime verification remain open.

| Specification leaf | Inspected source disposition | Remaining work |
| --- | --- | --- |
| 11.1.2, 11.1.5, 11.2.1–11.2.3; 13.2.1 | BlockPos/ChunkPos retain signed-int axes. W05-C08 adds shared root validation before ChunkPos.fromBlock floor division; WorldContext retains floorMod locals, validated WorldOptions and long request-entry extent checks. | Execute all-root/extreme-axis/null/invalid-size definitions and complete remaining coordinate/renderer callers. EMPTY all-root controls do not certify nonempty generation or hot-path performance. |
| 11.4.1; 11.3.4–11.3.5, bounds/count/Y-intersection helpers only | RegionBounds retains inclusive ordered endpoints, optional Y intersection and checked long chunk counts. Independent BigInteger expectations define 56 counts (47 fitting/nine overflow) and 24 clip controls across all roots. | Execute definitions, finish full region partition/stream/clip/cancellation caller inventory and measure representative large-region behavior. |
| 12.1.1–12.1.8 | VoxelChunk recursively builds exact adaptive branches and at-most-4³ heterogeneous leaves using private primitive arrays/palettes; uniform children coalesce and inclusive replacements use adaptive subranges. | Execute all-root dense-reference, palette, sparse-256 and bulk-update definitions; measure actual allocation/work and complete remaining producer inventory. |
| 12.3.1–12.3.5, 13.2.4 | Private builder finalization copies owned arrays; published cells are read-only and palette access is defensive. W05-C07 adds the missing post-callback cancellation checkpoint before staged finalization. Occupied iteration emits primitive local coordinates/types. | Execute final-callback/thread/retained-output/iteration definitions and finish engine publication/cache/failure paths; immediate paths retain their earlier behavior. |
| 13.1.1 | ProjectLoader delegates format/model validation to ProjectIO and enabled noise compilation to NoiseCompiler; it returns an editable result without editor replacement. | Execute parse/schema/registry/rejection definitions and finish exception/overload inventory. |
| 13.1.2 | CompiledProject preparation preserves caller validation and captures a separate retained definition; W05-C02 adds owned-candidate validation without caller recapture. | Execute mutation, malformed input, diagnostics and serialization-boundary cases. |
| 13.1.3 | SettingsResolver produces separate resolved settings per placement. W05-C03 now reuses a construction-local NoiseCompiler per effective seed, including its UUID/dependency cache; transforms/interpretation/probability remain placement-owned. | Execute shared dependency/seed/placement definitions and finish all driver/override/plan responsibilities. |
| 13.1.4 | CompiledProject privately retains validated source and immutable registry values; direct samplers and context plans build executable fields from it. | Verify thread safety/retained snapshots, compilation cost and all executable-plan lifetime paths; no eager-plan or performance certification is claimed. |
| 13.1.5 | NoiseGeneratorEngine owns bounded workers/queue, budgets, listeners, profiler and registered contexts; openWorld rechecks closure after registration. | Finish engine admission/close/failure caller inventory and execute lifecycle/budget/listener races. |
| 13.1.6 | WorldOptions is immutable; WorldContext resolves the seed override once and owns its plan, cache, futures, regions and cancellation state. | Execute option/default/root/cache/closure matrix and finish all request entry paths. |
| 13.1.7 | Compiler reuse is limited to one WorldPlan construction. Context-local caches/requests/cancellation remain separate; shared registry data is immutable. | Execute simultaneous seeds/workers, independent close/cancel and retained-output cases, including the new independent replay. |
| 13.3.3, 13.8.1, direct-selector overloads only | W05-C09 gives unknown visible IDs the same IllegalArgumentException policy as UUID misses. Exact dimension-specific name matching resolves stable UUIDs; identity/dimension checks precede compiler construction. | Execute typed missing/null/wrong-dimension, exact case/Unicode and retained-sampler definitions; finish batch/overload/error, thread-safety and dependency inventories. |
| 13.4.1, 13.5.4; cross-reference 10.2.4/19.5.4 | W05-C04 corrects column prefetch that bypassed project/resolved-placement Y rejection. Final chunks, stage pairs and region generation now require a nonempty three-way Y intersection before cached field evaluation. | Execute clipping/override/endpoint/domain/overflow/trace/profile definitions; retain the full W04/W05/W07 world-use, API and trace audits as open. |
| 13.3.1–13.3.4, 13.4.2, 13.5.7, parent-poll boundary only | W05-C05 makes a cancellation check observe explicit cancellation during a normally false parent poll, before cached request or empty-grid return. Parent-only observations, callback claims and failure propagation retain their contracts. | Execute direct/cross-thread/cache/grid/failure definitions and complete the remaining sampler/subscriber/region cancellation caller inventory. |
| 13.5.7; 13.4.1–13.4.2, world-operation boundary only | W05-C10 checks the generation scope before each voxel operation/layer transition and before each prefetch operation's allocation/profile start. Existing cell/row/final checks remain; direct point/trace replay keeps no request scope. | Execute profiled phase, held explicit cancellation, fault and retained-output definitions; complete cancellation inside larger field work, shared subscribers, region/scheduler and polling-cost verification. |
| 13.4.2, 13.5.7–13.5.8, 13.7.4, post-poll ownership only | W05-C11 reconciles closure/current subscribers after outside-lock parent queries. Empty/closed owners abandon at that checkpoint; active/new peers preserve work and conditional removal protects replacement map ownership. | Execute all 72 held-poll cases, terminal/profile/accounting and retained-output definitions; finish remaining membership, close, cache/scheduler/region and lock-cost inventories. |
| 13.6.1–13.6.6, headless cache paths | [Six-leaf cache inventory](CACHE_POLICY_SOURCE_AUDIT.md) reconciled: distinct retention/LRU/user policies, local/global reservation/reclamation, pin/control/accounting and implicit per-context source/seed/root identity. No application-code defect found in inspected paths; source semantics/registry unchanged. | Execute existing six module pressure/concurrency definitions and new all-policy/two-seed independent replay. Measure real allocation/throughput and complete all-root/worker races and renderer/scheduler integration. |
| 11.3.1–11.3.5, 11.4.1–11.4.5, 13.5.1–13.5.9, headless region paths | [Nineteen-leaf region inventory](REGION_SOURCE_AUDIT.md) records Y guards, inclusive/complete-chunk partitioning, bounded coordination, metadata/progress, acknowledgements, policies, cancellation/sharing and collection leases. No new application gap recorded in inspected paths. | Execute existing fault/opaque-stage/ownership/concurrency suites plus new independent healthy/empty partition replay. Full limits/stress, all-root/worker/host-stage races, renderer integration and performance remain open. |
| 14.3.1–14.3.4, 14.3.6 | VoxelArchive encodes exact VoxelChunk trees with signature/version, semantic dictionary, per-root zlib payload and sorted coordinate/size index. Storage has no display-LOD/editor input. | Execute independent wire vectors, compatibility/remapping and exact large-root cases; finish limits/performance and producer/consumer inventory. |
| 14.3.5, 14.4.1–14.4.3; cross-reference 19.6.3 | Public factories expose streaming append/finish, indexed random reads and singleton helpers. Earlier cleanup/publication ownership remains; W05-C06 now preserves tree-decoder cancellation through Reader.read instead of labelling it corruption. | Execute reader/writer retry, cancellation, cleanup and publication faults on both platforms; complete all exception entry paths. |
| 14.3.7 | Reader checks header/dictionary/index bounds, root size, coordinate uniqueness, contiguous payload ranges, decoded limit, zlib termination and tree dictionary/palette structure. | Execute the full malformed/truncated/limit matrix and finish every allocation/error boundary; source inspection does not certify hostile-input resource behavior. |
| 14.3.8 | Zero-length indexed entries return complete EMPTY roots; absent entries return Optional.empty. contains and hasStoredPayload separate coverage from storage, with immutable metadata retained after close. | Execute sparse coverage, close, cancellation/non-decoding and mixed-root cases. |
| 14.3.9, 14.4.4 | Semantic remapping uses a separate private array; missing required IDs reject before a decoded chunk escapes. Writer retains bounded-index/per-root encoding rather than a whole-world dense buffer. | Execute missing-ID/registry immutability and large sparse/stress cases; measure actual memory/IO and finish remaining API/producer inventory. |

## W06 scene handoff audit progress

| Specification leaf | Inspected source disposition | Remaining work |
| --- | --- | --- |
| 1.4.1–1.4.3, 14.1.3/14.1.5, CPU receipt lifetime only | ChunkScene workers prepare immutable data/mesh receipts for UI-owned acceptance. W06-C01 replaces clear-only queue shutdown with an atomic close/discard/reject mailbox before entry/source cancellation. Open-scene job/CPU/drain limits and Future/epoch/job acceptance retain authority. | Execute mailbox/real-source closure and existing greedy/point/surface completion/LOD/native definitions; finish open-scene capacity, priority/upload queue, worker lifetime and measured overhead inventory. This does not close W05/W06 scheduling or rendering scope. |
| 1.4.1–1.4.3, 14.1.3/14.1.5, cancellation callbacks only | W06-C02 settles/revokes jobs before token/Future notifications, attempts both independently and preserves primary failure feedback. Snapshot planning/eviction/close plus current identity/window/required guards protect callback reentry. | Execute five new closure/fault/primary-priority/successor definitions and existing completion suites; finish wider callback/upload/caller and measured allocation/latency inventories. |
| 14.1.6, 15.5.3; 14.1.5/17.1.4, acknowledgement metadata only | W06-C03 establishes entry/invalidation minimum versions, caps acknowledgements at the current CPU candidate and requires strict displayed advancement. Valid older in-flight LOD uploads retain eligibility before retirement/invalidation. | Execute five new picking/version/accounting definitions and existing scene/LOD/native upload/resize/recovery suites; finish full caller/GPU/resource/performance inventory. |
| 1.4.1, 14.1.3/14.1.5, 15.5.3, volume cleanup only | W06-C04 preserves pre-publication transfer failures/counters, detaches captured texture ownership before deletion, attempts both clear/close handles and releases the close staging reference in finally. Distinct later cleanup faults are suppressed; ambiguous deletions are not blindly retried. | Execute six new CPU-backend fault definitions and existing upload/copy/native suites. Audit post-publication old-resident deletion failure against GlViewport/Viewports accepted metadata; complete wider native/resource/pressure/performance inventory. |

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

**W01-C10 — private browser-form history (3.5.3, 3.2.4, 3.2.8): implementation added, execution pending.** [Resource form history](RESOURCE_FORM_HISTORY.md) adds bounded exact ID/algorithm and placement/replacement deltas, grouped typing and persistent history controls. Restoration resets input/picker scope and feedback; version-owned primary/history controls reject equal-value round trips. Cancel/reset/panel closure and accepted/pending handoff consume history. Explicit actions retain the existing Properties guard and project transaction.

- [x] Add shared bounded history to all three form owners, exact restoration and current/closed/ticket guards.
- [x] Add native controls/modal bindings, seven unit definitions and expanded Help/30-case form/12-case creation/readback definitions.
- [ ] Compile and execute form/shared-history/creation/Help/transaction suites and expanded native/readback cases.
- [ ] Verify picker/filter ownership, remapped/physical input and Windows 11/DPI interaction.

**W01-C11 — bounded UI callback dispatch (1.2.6, 1.4.1, Tree 4): implementation added, execution pending.** [UI dispatch ownership](UI_DISPATCH_OWNERSHIP.md) bounds pending callbacks and initial per-frame FIFO batches, defers nested posts, isolates callback/reporting failures and rejects posting after closure. Production factories and both Inspector paths use strict posting so full/closed queues reach existing request failure observers. Delivery retains current controls/owner publication guards.

- [x] Add bounded dispatch, whole-turn reentry/shutdown guards, bounded failure diagnostics and strict producer wiring.
- [x] Add four queue/seven editor integration definitions, including validation, unpublished preference cleanup and Inspector retry/acceptance boundaries.
- [ ] Compile and execute dispatch/worker/request/cleanup suites and inherited native scenarios.
- [ ] Measure queue/frame behavior and verify all producers, startup/shutdown and Windows 11 interaction.

**W05-C02 — validate retained compiled snapshots (13.1.2–13.1.4, 1.4.2–1.4.3): implementation added, execution pending.** [Compiled snapshot admission](COMPILATION_SNAPSHOT_OWNERSHIP.md) preserves validation-first rejection, validates the actual retained serialized candidate and adopts it without recapturing caller data. compileWorld returns owned-candidate diagnostics; constructor/selected-noise routes retain exception rejection. This addresses a concrete validation/capture gap, not the full compilation audit.

- [x] Share opaque owned preparation across public entry routes; preserve malformed-input traversal and immutable result diagnostics.
- [x] Add five module regression definitions and an independent engine-JAR replay defining 24 rejections, 162 field observations and 8 chunks / 288 EMPTY-cell expectations.
- [ ] Compile and execute snapshot, validation/result ownership and inherited compilation suites plus the expanded ProjectParseConsumer on Linux/Windows.
- [ ] Measure additional direct-constructor validation cost and finish remaining W05 API/plan/thread/cache/archive inventory.

**W05-C03 — reuse canonical compilation within world plans (13.1.3–13.1.4, 13.1.7, 14.2.4): implementation added, execution pending.** [World field compilation reuse](WORLD_FIELD_COMPILATION_REUSE.md) replaces one full model-copying NoiseCompiler per placement with one per distinct effective seed during each plan construction. The existing UUID cache reuses fields/dependencies; resolved settings, transforms, targets, attribution and UUID-keyed probability remain independent. No sampled-value or cross-context cache is added.

- [x] Add construction-local effective-seed reuse and correct the earlier per-operation-copy comment.
- [x] Add three construction-cost definitions and an engine-JAR replay defining 16 healthy worlds, 144 traces / 576 field-and-draw pairs, 16 cancelled requests and four failure/recovery cases.
- [x] Define independent retained final/stage expectations: 36 chunks / 1,184 final cells and 32 stage snapshots / 1,152 stage cells.
- [ ] Compile and execute new/inherited world-use, work-attribution, seed, dependency, stage and concurrency suites plus the expanded ProjectParseConsumer on Linux/Windows.
- [ ] Measure compilation/heap cost for repeated and unique effective seeds; finish remaining W05 leaf/caller inventory.

**W05-C04 — bound column prefetch by effective Y (10.2.4, 10.7.9, 13.4.1, 13.5.4, 19.5.4): implementation added, execution pending.** [Column prefetch bounds](COLUMN_PREFETCH_BOUNDS.md) prevents field/coordinate errors from placements excluded from every voxel in a chunk. Inclusive long-arithmetic intersection of chunk, project and resolved placement bounds preserves eligible endpoint failures and earlier writes; the same path serves stage pairs. Excluded fields have no prefetch timing, while normal rejected-operation visits retain attribution.

- [x] Guard prefetch using the combined three-way interval without changing field math, public signatures, formats or staging estimates.
- [x] Add five module definitions and independent 60-world/120-trace/60-region/60-capture definitions plus 32 eligible failure checks.
- [x] Define retained async/collection and stage expectations: 120 final-chunk observations / 4,320 cells and 120 stage observations / 4,320 cells.
- [ ] Compile and execute new/inherited world-use, bounds, trace, stage, region, cancellation and profiler suites plus the expanded independent consumer on Linux/Windows.
- [ ] Finish W04/W05/W07 leaf/caller inventories and representative performance/native/Windows 11 verification; this child fix does not close those audits.

**W05-C05 — observe explicit cancellation during parent polls (13.3.1–13.3.4, 13.4.2, 13.5.7): implementation added, execution pending.** [Parent-poll cancellation](CANCELLATION_PARENT_POLLS.md) closes a false-result window where a supplier or another thread cancelled the token before its parent returned false. A post-poll flag read preserves one parent call and existing passive-parent/callback/failure behavior. Cached requests and empty grids now observe that cancellation at their initial check.

- [x] Recheck explicit state after a normally false parent poll without changing public signatures, callback ownership, formats or math.
- [x] Add six module definitions plus independent four-direct/36-cached/12-NO_CACHE/14-grid rejection definitions, seven grid controls and passive/fault cases.
- [x] Define independent 54 scalar/two batch outputs, 42 cancelled destination/19 control padding cells and eight retained chunks / 288 exact voxel expectations.
- [ ] Compile and execute new/inherited cancellation, subscriber, cache, batch, stage and region suites plus the expanded independent consumer on Linux/Windows.
- [ ] Measure cancellation-poll cost and finish the wider W05 sampler/admission/cancellation/scheduler inventory; this child does not close those audits.

**W05-C06 — preserve archive decoder cancellation (14.3.5, 14.4.1–14.4.4, 19.6.3): implementation added, execution pending.** [Archive decoder cancellation](ARCHIVE_DECODER_CANCELLATION.md) stops the RuntimeException corruption wrapper from masking existing tree checkpoint CancellationExceptions. The interrupt flag and no-partial-result contract remain, decode resources still retire, and an open reader can retry explicitly after interruption clears. No codec, limit, format, registry or public signature changes.

- [x] Preserve decoder cancellation through Reader.read/readSingleChunk while keeping ordinary corruption and cleanup paths.
- [x] Add six module definitions and independent 36-decode/six-append/six-abort/six-single-read cancellation definitions, six cancellation/corruption pairs and six closed-reader controls.
- [x] Define independent 4,672 source cells and 66 retained chunks / 12,848 remapped cell expectations, including deterministic retry bytes and unchanged registries/destinations.
- [ ] Compile and execute archive/tree/cleanup/publication/cancellation suites plus the expanded independent consumer on Linux/Windows.
- [ ] Complete remaining archive allocation/error/producer inventory, measured IO/stress, scheduler/native/Windows 11 and release verification; this child does not close W05.

**W05-C07 — cancel before staged snapshot finalization (12.3.1, 12.3.5, 12.4, 13.2.4): implementation added, execution pending.** [Staged snapshot cancellation](STAGED_SNAPSHOT_CANCELLATION.md) closes the last-callback gap in VoxelChunk generation/decoding: shared builder finalization now observes interruption before capturing arrays. Callback faults/invalid-value priority and earlier completed snapshots remain; immediate uniform/no-op/full-box/read behavior is unchanged.

- [x] Add one final checkpoint shared by generated, decoded and staged replacement storage, with no callback replay or format/API changes.
- [x] Add six module definitions and independent 30-sampler/eight-mapping cancellation definitions, four callback faults, two invalid-value and four immediate-path controls.
- [x] Define four independent input trees and 72 retained chunks / 13,456 exact cells with occupied-iteration/statistic, palette-copy and edit/source ownership expectations.
- [ ] Compile and execute storage/tree/decoder/cancellation/engine publication suites plus the expanded independent consumer on Linux/Windows.
- [ ] Complete remaining storage/callback/producer inventory and measured allocation/performance, native/Windows 11 and release verification; this child does not close W05.

**W05-C08 — validate coordinate root sizes (11.1.5, 11.2.1–11.2.3, 13.2.1, 13.8.1): implementation added, execution pending.** [Coordinate root validation](COORDINATE_ROOT_VALIDATION.md) rejects unsupported ChunkPos.fromBlock dimensions through the shared validator before reading coordinates. Size zero now has the same typed rejection as other invalid roots; supported-root floor division and valid-root null exception type remain. The sole production caller already owns validated WorldOptions.

- [x] Add shared root admission and Javadoc without changing signed coordinate arithmetic, signatures, schemas or registries.
- [x] Add four module definitions with independent arbitrary-precision quotient/remainder and inclusive-count expectations.
- [x] Add engine-JAR definitions for 128 vectors / 384 axis reconstructions, 96 invalid-root calls, 16 root-before-null/eight valid-null cases, 56 region counts / 24 clip controls, 256 fields, 128 lookups / 128 async chunks and eight retained EMPTY roots / 64 corners.
- [ ] Compile and execute coordinate/storage/region/API suites and the expanded independent consumer on Linux/Windows.
- [ ] Complete nonempty all-root generation, coordinate/renderer and region/scheduler caller inventories, measured performance, Windows 11 and release verification; this child does not close W05.

**W05-C09 — consistent sampler lookup admission (13.1.4, 13.3.3, 13.8.1): implementation added, execution pending.** [Sampler lookup admission](SAMPLER_LOOKUP_ADMISSION.md) replaces NoSuchElementException on visible-ID misses with the UUID route's IllegalArgumentException policy. Selection stays exact, case-sensitive and dimension-specific. UUID existence/dimension checks now precede compiler construction; accepted executable math and ownership remain.

- [x] Add typed visible misses and overload Javadoc; preserve stable UUID resolution, null rejection, namespace separation and valid-resource compilation failures.
- [x] Add three module definitions for selector rejection, independent exact-name fields and snapshot/sampler ownership after caller edits/removal.
- [x] Add engine-JAR definitions for 36 typed rejections, 486 independent fields, four renamed-value controls and 54 retained-sampler observations, with exact caller JSON comparisons.
- [ ] Compile and execute direct compiler/sampler/dependency/batch suites and the expanded independent consumer on Linux/Windows.
- [ ] Complete remaining overload/error/thread-safety, region/cache/lifecycle and performance inventories plus native/Windows 11/release verification; this child does not close W05.

**W05-C10 — cancel between world operations (13.5.7, 13.4.1–13.4.2, 1.4.2–1.4.3): implementation added, execution pending.** [World operation cancellation](WORLD_OPERATION_CANCELLATION.md) adds the missing cooperative scope check within each generated voxel's compiled operation sequence, including layer transitions and stage snapshots. Prefetch also checks before each operation's allocation/profiling; its existing row checks remain. Direct point/trace replay and accepted numeric evaluation retain their behavior.

- [x] Carry the existing generation scope through private sampling and check before each operation, after stage-limit admission, with no new public API or format.
- [x] Add five module definitions for profiled prefetch/per-voxel phase cancellation, root/layer/async/stage combinations, held explicit cancellation, fault priority and prior-output/accounting ownership.
- [x] Add engine-JAR definitions for 45 cancellations (36 async/nine stage), three parent faults/three domain faults/three retries and 51 retained chunks / 9,144 exact and occupied-iteration cells with statistics/palettes and exact caller JSON.
- [ ] Compile and execute world/cancellation/stage/profiler/subscriber suites and the expanded independent consumer on Linux/Windows.
- [ ] Complete remaining field/subscriber/region/cache/scheduler cancellation and polling-cost inventories, native/Windows 11 and release verification; this child does not close W05.

**W05-C11 — recheck shared ownership after parent polls (13.4.2, 13.5.7–13.5.8, 13.7.4): implementation added, execution pending.** [Shared-generation ownership recheck](ABANDONMENT_RECHECK.md) prevents a stale active/changed snapshot from starting another operation after its owner becomes empty or closed during a host callback. Current membership/closure is reconciled under the existing lock; parent queries remain outside it, healthy peers retain work and conditional removal preserves same-position replacements.

- [x] Reconcile closed/empty ownership after every parent poll without changing future terminal outcomes, public signatures, cache policies or math.
- [x] Add four module definitions spanning last-subscriber future/token/fault retirement, context closure, healthy admitted peers and replacement ownership at held operation/prefetch boundaries.
- [x] Add engine-JAR definitions for 72 interleavings across roots 2/4, two cache policies and three field paths, with exact raw/terminal/accounting outcomes, unchanged caller JSON and 168 retained chunks / 6,048 exact/occupied cells.
- [ ] Compile and execute cancellation/subscriber/lifecycle/cache/profiler suites and the expanded independent consumer on Linux/Windows.
- [ ] Complete remaining membership/close/region/scheduler races, all-root/policy and lock-cost verification, native/Windows 11 and release work; this child does not close W05.

**W06-C01 — reject late CPU receipts after scene close (1.4.1–1.4.3, 14.1.3/14.1.5, handoff lifetime only): implementation added, execution pending.** [Scene mailbox closure](SCENE_MAILBOX_CLOSURE.md) prevents workers from refilling ChunkScene's receipt queue after close clears it. A synchronized FIFO mailbox discards pending references atomically with closure and rejects late data/error/mesh offers. Existing admission, prepared/Future acceptance, immutable output and caller-owned executor retain their contracts.

- [x] Route all three producer sites through the close-aware mailbox and close it before entry/source cancellation without changing public shapes, geometry or headless dependencies.
- [x] Add three mailbox definitions for FIFO/discard/idempotence/delivered-snapshot ownership, three held late producers and 32 four-publisher close races.
- [x] Add a real-source return-after-close definition with once-only source close, zero closed-scene ownership/uploads/display, shared-executor usability and 64 retained immutable cells.
- [ ] Compile and execute mailbox/scene/completion/mesh/LOD suites and native closure/replacement scenarios on Linux/Windows.
- [ ] Finish remaining open-scene capacity, priority/upload/caller and measured overhead, renderer/hardware/Windows 11 and release inventories; this child does not close W05/W06.

**W06-C02 — settle scene jobs before cancellation callbacks (1.4.1–1.4.3, 14.1.3/14.1.5, ownership only): implementation added, execution pending.** [Scene cancellation ownership](SCENE_CANCELLATION_OWNERSHIP.md) releases/revokes old job ownership before token/Future notifications, attempts both even on faults and preserves an observed primary failure. Snapshot traversal and current identity/window/required checks prevent reentrant closure or successor admission from corrupting reservations or eviction accounting.

- [x] Detach before notifications in cancellation, admission and terminal-fault paths; guard queue feedback, contain closed notification faults without host text and preserve original Throwables.
- [x] Guard planning/eviction/closure traversal and stop closed ticks before further accounting/admission.
- [x] Add five completion definitions for three camera-closure cases, two-job notification failures, six terminal/six admission primary-fault controls and same-entry successor completion; public APIs, geometry and headless boundaries remain unchanged.
- [ ] Compile and execute scene/cancellation/mailbox/completion/mesh/LOD suites and native camera/closure/replacement scenarios on Linux/Windows.
- [ ] Finish remaining callback/caller, upload/scheduling, allocation/latency, renderer/hardware/Windows 11 and release inventories; this child does not close W05/W06.

**W06-C03 — bound scene upload acknowledgements (14.1.6, 15.5.3, 14.1.5/17.1.4, metadata only): implementation added, execution pending.** [Scene upload acknowledgement versions](SCENE_UPLOAD_ACKNOWLEDGEMENTS.md) rejects stale entry/invalidation, future and non-advancing versions before displayed picking inputs or CPU accounting change. Invalidation gives reusable CPU geometry a fresh opaque version; valid older in-flight candidates can still commit while newer LOD data waits.

- [x] Establish a minimum at new mesh lifetime/invalidation, retain the current-candidate ceiling and require strictly advancing display acknowledgement without changing public shapes or GPU staging/compaction.
- [x] Add five picking definitions for invalidated/future/equal-version rejection, fresh CPU-payload reuse, valid pending LOD commits and retired-entry same-coordinate reload, with independent exact/proxy positions and accounting checks.
- [ ] Compile and execute scene/picking/LOD/mesh suites and real native upload/resize/recovery scenarios on Linux/Windows.
- [ ] Complete remaining acknowledgement/caller/GPU, scheduling/resource/performance, renderer/hardware/Windows 11 and release inventories; this child does not close W05/W06.

**W06-C04 — preserve volume cleanup ownership and primary faults (1.4.1, 14.1.3/14.1.5, 15.5.3, cleanup only): implementation added, execution pending.** [Volume texture cleanup](VOLUME_TEXTURE_CLEANUP.md) retains pre-publication transfer fault identity and successful-transfer counters even when candidate deletion throws. clear()/close() detach both captured handles before backend callbacks and attempt both deletions; close() drops its staging reference in finally. A distinct later fault is suppressed, with self-suppression avoided and no blind replay of ambiguous texture names.

- [x] Detach cleanup ownership, preserve primary faults/counters and attempt both captured clear/close handles despite failure.
- [x] Add six independent CPU-backend definitions for host/copy faults, before/after-release cleanup, shared Throwable identity, teardown, callback replacement and explicit retry with exact sample/counter expectations.
- [ ] Compile and execute upload/copy/cleanup suites and native resource/pressure/recovery scenarios on Linux/Windows.
- [ ] Audit failure while deleting the previous texture after a new resident/version commits, including GlViewport/Viewports accepted mode/origin/palette/hover metadata. This separate boundary is unchanged by C04.
- [ ] Complete wider caller/scheduling/resource/performance and hardware/Windows 11/release inventories; W05/W06 remain open.

## W05 cache-policy audit disposition

The [13.6.1–13.6.6 headless source inventory](CACHE_POLICY_SOURCE_AUDIT.md) now records each leaf's implementation and verification owner. No new confirmed implementation child is opened for this slice. Existing CacheAdmissionTest retains local/global pressure and concurrency definitions; the new engine-JAR CachePolicyConsumer joins Example with 24 policy worlds / 12 seed pairs, 162 independent fields, 138 generations, 12 automatic LRU evictions, six retention rejections and 198 retained observations / 38,544 exact cells. Cache-hit observations may reference the same immutable snapshot. All new definitions remain uncompiled/untested; no report or runtime evidence exists.

- [x] Reconcile all six headless cache-policy leaves with source and existing definitions.
- [x] Add independent field/voxel, hit/LRU/control/pin, context/seed and accounting expectations without changing application behavior or duplicating module pressure suites.
- [ ] Compile and execute the existing cache/subscriber/scheduler suites and expanded engine-JAR/standalone Example on Linux/Windows.
- [ ] Complete remaining W05 leaves/callers and all-root/worker/pressure/performance, renderer/native/Windows 11 and release verification; the W05 source-audit box remains open.

## W05 region audit disposition

The [headless region inventory](REGION_SOURCE_AUDIT.md) records 19 source dispositions across vertical bounds, partition/delivery and region APIs. No new confirmed implementation child is opened for this slice. Existing suites retain acknowledgement/fault/cancellation/concurrency ownership; RegionPartitionConsumer adds independent stream/sync/async expectations for 144 healthy regions, 18 entirely outside-Y controls, 96 nonzero collection leases and 4,032 retained chunk observations / 677,376 exact cells. This includes 483,840 Y-clipped EMPTY cells and occupied complete-chunk X/Z edges outside requested bounds. New definitions remain uncompiled/untested; no report or runtime evidence exists.

- [x] Record each headless region leaf's inspected owner and existing verification scope, including signed-long count and cooperative host boundaries.
- [x] Add independent coordinate-table, voxel/occupied, requested/effective, order/progress, lease/cleanup and caller-ownership expectations without changing application behavior or duplicating module fault suites.
- [ ] Compile and execute region/world/subscriber/acknowledgement/failure/progress/collection suites and expanded engine-JAR replay on Linux/Windows.
- [ ] Complete remaining W05 leaves/callers and limits/stress/host-stage/race/performance, renderer/native/Windows 11 and release verification; the W05 source-audit box remains open.

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

The owner's current testing pause remains in force. No compiler, Gradle, JUnit, native probe, consumer, Python/schema/packaging checker, packaging operation, update request or workflow ran for these additions. Source review and git whitespace/content/metadata checks are the available verification. The implementation is not being declared fully developed or release-ready; next development should continue the remaining W01 and W05 leaf/caller inventories above and record concrete child gaps here, while retaining W02–W08's wider unresolved scopes.
