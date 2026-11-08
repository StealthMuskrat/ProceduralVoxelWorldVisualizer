# Independent World Editor selection

Status: **uncompiled and untested** under the owner's testing pause. W01-C02 addresses specification 3.2.6 and 10.9.2. The existing Resources tree enforced sibling selection, but supplied no separate World Editor context. The observed baseline remains 922 tests / 120 suites; this addition does not advance an acceptance gate.

The new World Editor panel displays the committed World Generator in execution order: ordered layers followed by each layer's ordered placements, independent of organizational folders. Disabled layers/placements remain visible; a placement under a disabled layer is marked disabled. Rows show stage, ID and referenced noise ID. Search matches IDs, UUIDs and source names, preserves a matching placement's layer heading, and includes a matching layer's placements. Catalogs rebuild on a new committed project object/revision, and normalized search projections are cached. Native rows use stable UUID identities and a clipped, measured list.

Plain click selects one item here. Ctrl toggles sibling layers or placements within one layer. Moving to another context starts a new selection. Shift selects an inclusive visible range in execution order; Ctrl+Shift adds that range. A hidden anchor falls back to the clicked item. Filtering retains hidden selections; deleted items and placements moved out of the selected layer are pruned. Undo does not resurrect a pruned transient selection. New/Open clears the selection and filter, including reopening a project with the same UUID.

Multiple selected items produce a count and summary explanation. They do not populate mixed-value Properties fields or enable Edit Properties/Find References. Selecting here leaves Resources selection, private Properties ID/raw inputs, document history/revision/dirty state and preferences intact. Selection and filter are transient and never serialized.

For one item, Edit Properties explicitly enters the existing guarded source navigation: Apply/Discard/Cancel for private input, with normal validation and stale-owner checks. A document/selection-version ticket prevents a changed selection from authorizing an older pending navigation, even after selecting away and back. Footer native IDs also change with selection version so an old held release cannot target a successor. Find References receives the exact selected UUID. Both actions are unavailable during conflicting project/resource/private interactions.

The core panel is docked beside Resources in the default layout, with Undock/Redock controls and persisted native docking geometry. View > World Editor focuses it. Per-window scaling is available in Appearance, with no implicit override entry. The generated scale registry and deferred catalog/readback expectations now include twenty targets. Existing User Config format/map shape supports the additional window key; no project field, schema version, engine signature or arithmetic changes. Built-in context Help explains selection and navigation. Existing Create, Add Noise Generator, enable/move/duplicate/delete actions remain in Resources and settings remain in Properties; this selection addition does not claim completion of all Tree 10 presentation leaves.

## Deferred verification

Eight new `WorldEditorSelectionTest` definitions cover ordered/disabled/search context and immutable cached rows; complete parent-state isolation; sibling/context toggling; reverse/filtered Shift ranges; move/delete/history pruning; same-UUID reopen and empty New; nonreviving selection tickets; and actual guarded navigation Cancel/stale Discard/explicit retry. Existing scale catalog definitions and its independent reader require twenty targets. None has run.

`WorldEditorProbe` defines real production-panel mouse input for Ctrl and Shift selection, disabled multi-selection actions, exact single-item callbacks and held-action retirement. Callback fixtures capture targets/freshness; the unit definitions above exercise the actual navigation guard. Six production layout cases cover wide/narrow/short windows and global/window products through 6.25 over 305 rows (two layers and 303 placements). The independent reader checks body/footer containment/order, clipped submission counts, exact callback UUID and complete before/after project JSON equality. It does not claim physical input/DPI, actual monitors, docking gestures, every row combination or hardware responsiveness. Linux/Windows commands and artifact retention are wired into the existing manual-only workflow without dispatching it:

```text
java --enable-native-access=ALL-UNNAMED -cp build/release/jars/ProceduralVoxelCreator.jar io.voxelcreator.editor.WorldEditorProbe build/world-editor
python scripts/verify_world_editor.py build/world-editor
```

Only source review and git whitespace/content/metadata checks were used. No compiler, Gradle, JUnit, native probe, consumer, Python/schema/packaging checker, packaging operation or workflow ran. W01 remains open for the rest of its numbered inventory, with project lifecycle, import/export and other temporary editors next; Windows 11 and final release acceptance remain pending.
