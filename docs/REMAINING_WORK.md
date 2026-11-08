# Remaining implementation, validation and delivery work

Current source checkpoint: the preceding local main commit is a05a41e; its publication remains pending, with GitHub main at 6f76999. The standalone consumer, local membership history and independent World Editor selection are **uncompiled and untested** under the owner's testing pause. The last observed baseline remains **922 tests / 120 suites**. The [A01–A36 acceptance matrix](REQUIREMENTS_AND_TESTS.md) retains its existing evidence; this register does not promote any gate.

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
| 3.2.4 | Source reviewed: ResourcePanel context actions, inline F2 rename, hierarchy/drop/reorder entry points and ResourceBrowserEdits transaction handoff are present. | Execute real gestures and recovery cases when validation resumes. |
| 3.2.5 | Source reviewed: sibling-scope selection guards and consolidated Properties summary are present. | Execute selection transitions; independent World Editor source is recorded below. |
| 3.2.6, 10.9.2 | Confirmed missing independent World Editor selection; implemented as W01-C02 below. | Compile/execute deferred selection/navigation/native checks and complete remaining Tree 10 presentation audit. |
| 3.2.8 | Source reviewed: subtree dependents, Cancel/replacement review and validated atomic delete are present. | Execute dependency/replacement cases; retain all-dependents inventory in the broader audit. |
| 3.3.1 | Source reviewed: ResourcePanel resets clipboard on the document lifetime token, including same-UUID replacement; ordinary edits/history retain the session clipboard. | Execute reopen/close and pending-command transitions. |
| 3.3.2–3.3.5 | Source reviewed: captured copy subtree, internal UUID remapping, collision-safe names and live UUID-preserving cut/move pass through one owned project transaction. | Execute complete copy/folder/cut/rejection scenarios. |
| 3.3.6 | Source reviewed: application command suppression preserves native text clipboard ownership; project resource clipboard is separate. | Physical system clipboard/input verification remains pending. |
| 3.5.3, membership editor only | Confirmed missing local history; implemented as W01-C01 below. | Compile/execute deferred checks and audit every other temporary editor. |

Identity/namespace helpers and existing protected-root/move guards were inspected as supporting paths; they do not yet close the full 3.1/3.2 inventories. ProjectFileWorkflow's private-draft and dirty-document transition entry paths were traced as supporting source; the complete 2.6 lifecycle inventory remains open. W01 remains open for Trees 0–2, remaining hierarchy/docking leaves, 3.4 import/export, and the rest of 3.5 history/temporary editors. Continue with project lifecycle boundaries, import/export and other temporary editors before marking any parent audit complete.

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

**W05-C01 — standalone build-tool consumer (13.8.2, 13.8.3, 22.8.3): implementation added, execution pending.** The release assembler copied Java consumers and generated run.cmd, but supplied no independent Gradle project. [The standalone consumer](../example-client/standalone/README.md) now has its own settings/build, one engine-JAR file dependency, Java 25 toolchain and the existing Example entry point. Explicit source/JAR properties support a checkout; default paths support the extracted distribution. A precompile check requires the engine API and rejects known editor/render/native packages. Release assembly includes these exact support files beside the existing Java sources, using the same safe file-copy and deterministic manifest/archive path.

- [x] Add the independent build and checkout/distribution instructions without duplicating engine/example implementation.
- [x] Integrate its support files into release assembly and define missing-file/link-rejection preservation checks.
- [ ] Compile/run with the pinned wrapper against the built engine JAR from a checkout.
- [ ] Compile/run from an extracted distribution, including a path containing spaces.
- [ ] Exercise missing/wrong/desktop JAR rejection and retain the independent example's existing full PASS output and arithmetic/archive expectations.

Three new PackageReleaseTest definitions cover exact standalone support bytes in the staged directory/ZIP; each missing support file preserving prior outputs; and symbolic-link rejection preserving prior outputs. Existing Example, engine math, native receipts and independent arithmetic dimensions are unchanged. None of these definitions or Gradle tasks has run. W05-C01's source work does not close the remaining W05 audit or A22/A35/A36 acceptance.

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
