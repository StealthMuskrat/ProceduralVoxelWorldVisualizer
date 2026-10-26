# Authoring transactions and stage analysis

## Curve editing

A curve stores a UUID, stable point UUIDs, input domain, independent output display range, per-segment interpolation, handle modes, slopes and outside-domain policy. The display range never clamps output. Smooth and Auto slopes use the monotone limiter. Bezier X handles stay one third of their adjacent segment width from a point; dragging a handle changes its slope, preserving a single-valued X→Y function. Aligned requires equal incoming/outgoing slopes; Free permits independent slopes. Auto derives slopes from surrounding points and requires an explicit mode change before manual handle edits.

The editor supports Shift selection, group movement with endpoint X locks, cumulative gesture snapping, tangent dragging, insertion near the actual curve, deletion of interior points, context actions, fit, pan/zoom and bounded private Undo/Redo. Invalid numeric or crossing-point edits retain the previous valid curve and show the rejection. A whole drag contributes one local history entry. Apply changes the parent Properties draft; applying Properties commits one project transaction. Cancel discards the isolated session.

## Resource operations

Ctrl/Shift selection is restricted to immediate siblings. Copy snapshots all selected resources together, including folder descendants; internal references are remapped together while external references remain unchanged. Cut/paste reads the live source identities and moves them only inside a successful document transaction. Folder ancestry is checked before mutation. F2 starts inline ID editing; failed validation keeps the editor open. Folder child order is persisted, and compatible drag/drop and move actions preserve selected-block order.

Deletion examines references across the entire removed subtree. Individual resources may use an explicitly chosen replacement of the same kind/dimension. Reference rewriting and deletion form one transaction; a failed validation leaves both untouched. Folder deletion remains blocked until external references to its contents are resolved. Voxel membership lists retain set semantics if replacement merges two entries.

Export uses explicit checkbox selection and includes the required dependency closure and folder ancestors. Import previews COPY, SKIP or REUSE per resource. REUSE requires equivalent definitions after normalization of hidden identities and organizational metadata; it does not overwrite destination definitions. SKIP also excludes incoming definitions whose required references are skipped. Copies receive new UUIDs and collision-safe visible IDs. The entire previewed result passes the same document validator before commit.

Project Undo/Redo stores structural value and array-splice deltas rather than serialized project snapshots. History is bounded to 100 transactions and approximately 32 MiB of recorded values (one oversized transaction is retained so it can still be undone). Provisional candidate copies and a saved-state comparison remain separate from history storage. Navigation and local search state do not enter project history.

## World stages and trace

`WorldContext.stages()` exposes stable layer/operation UUIDs, enabled state and before/after boundaries in the committed enabled pipeline. Disabled and empty stages have equal boundaries. `generateStageChunk(position, stageUuid[, token])` publishes an immutable `StageChunk` containing exact before/after chunks; both snapshots must finish before publication. Their generation shares cached 2D field values and uses bounded staging admission. They remain valid after context closure.

The World viewport offers Final, Before, After and Only Changes. Only Changes compares semantic runtime states: additions/replacements use the resulting type palette, and removal to EMPTY is shown with a red ghost of the removed cell. Ghost palette indices exist only in renderer meshes; they never enter engine chunks or archives. Picking follows the displayed stage, including removed-cell ghosts, and opens the final authoritative trace for that coordinate.

Inspector traces include effective values and their inheritance sources, executed field-gate branches, previous-state predicate result, deterministic probability draw, selection/action and last deciding operation. A skipped evaluation is distinct from false. Traces retain the semantic names from their generation snapshot and mark themselves stale after a document edit. Request/revision guards prevent old async inspections from replacing newer results.

Find builds a revision-scoped index for resources, transforms, processing rows, parameters and curve points. Kind/enabled/reference filters, debounced query updates and clipped result drawing avoid serializing the document on each frame. Selecting a control result opens its owning noise definition and reveals the relevant Properties section.

These features do not complete the streaming/LOD renderer, every metadata-driven control, or the full Windows 11 hardware acceptance matrix. See DECISIONS.md for remaining release work.

## Curve gesture cancellation and draft publication

Escape cancels an active point or tangent drag by restoring its exact baseline, with no history entry and no loss of an existing Redo branch. Repeated gesture starts retain the original baseline. Discrete edits finish an active gesture before creating their own entry. Local Undo/Redo/Delete also stop the UI pointer drag, so a held mouse button cannot immediately resume and overwrite the restored curve.

Both curve-opening paths use the same Properties callback. Applying a curve stages an owned JSON value into the opening draft and refreshes its display/index state. Changed project/revision/draft/item/source identity rejects staging, retains the curve window and shows the error; Cancel discards the local curve. The ordinary Properties Apply remains the complete-resource document transaction.

CurveGestureProbe exercises the production plot with real mouse events: Shift group selection, cumulative Y snapping, Alt bypass, Escape rollback, Undo while a pointer is held, local one-gesture Undo/Redo, Cancel and private Apply. An external engine-only consumer checks 147 samples against an independent three-segment linear formula, including exact knots and clamped outside inputs. This scoped evidence does not certify tangent pointer gestures, every curve mode, DPI/platform interactions or the full manual GUI acceptance matrix.
