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

## Canonical point insertion and relative tangents

Double-click insertion selects X from the pointer (with optional X snap), samples the canonical curve at that X, then applies only explicit Y snap. The new point inherits the left segment's interpolation mode and receives a stable UUID. This preserves a Linear or Step segment when Y snap is off; Smooth/Bezier insertion may change neighboring interpolation because their coefficients are recomputed. Invalid, duplicate or endpoint X and invalid enabled snap increments reject without publishing a point. Interior deletion preserves the mandatory endpoint identities and remains one local history edit.

Tangent dragging uses displacement from the exact gesture baseline rather than the rounded pointer's absolute Y. Clicking a handle without moving therefore does not change its slope or create history. Aligned keeps the paired slopes equal; Free changes only the selected side; Auto remains protected. Degenerate/nonfinite handle movement rejects without changing the current valid curve. Escape restores the baseline.

CurvePointEditingTest supplies independent canonical sampling/history/identity and relative-tangent expectations. The native CurvePointProbe covers Auto, Aligned and Free handles, zero-movement clicks, cumulative drag, Escape, local and document Undo/Redo, insertion/deletion and UUID restoration. The engine-only CurvePointConsumer compares saved explicit-tangent curves against independent cubic Bernstein evaluation and the inserted linear curve against the identity function at 441 points. Full manual GUI/DPI, all domain/context-menu gestures and hardware acceptance remain separate.

## Curve view navigation

Wheel zoom preserves the world coordinate beneath the cursor, within the existing 0.1..100 zoom bounds. A middle-button pan starts inside the plot and remains captured outside it until release. Escape restores the exact pan baseline. Fit restores the configured domain/display ranges. Wheel changes are ignored during point/tangent dragging, and all navigation remains separate from authored points, metadata, local curve history and document history.

CurveView holds immutable finite bounds. Nonfinite input, overflow and zoom/pan states whose spans cannot be represented as positive finite doubles are rejected without poisoning the last view. Changed domain/display metadata resets navigation to the new ranges; repeated synchronization and ordinary point edits preserve the current view. This does not add persisted view settings or change engine evaluation.

CurveViewTest supplies analytic anchor/pan/fit/inverse and finite-boundary checks. CurveNavigationProbe drives real native wheel/middle-pointer events, including movement outside the plot, Escape, Fit, ignored wheel input during point drag and exact draft/document/history preservation. Engine-only replay checks 195 independent samples of the unchanged saved field. Full manual GUI/DPI, domain/context-menu workflows and hardware acceptance remain separate.

## Numerical curve preview recovery

The plot captures 513 immutable canonical observations across its current view. Arithmetic/input-domain failures and nonfinite results become visible gaps, never substitute or clamped values; a bounded message reports the count and first failing input. Consecutive valid samples are connected, and no line crosses a failed sample. Fit and Cancel remain usable after failure. Numeric recovery catches only arithmetic/argument errors; unrelated programming failures are not silently suppressed.

Display sampling multiplies the span by a normalized fraction before adding the origin, avoiding overflow from a finite span multiplied by an integer sample index. This changes only preview observation. ScalarCurve and engine evaluation retain their existing raw-value/error boundaries; a failing preview does not mutate curve metadata, Properties, history or project state.

CurvePreviewTest checks finite raw vectors, partial extension overflow, tiny-domain Repeat/Mirror mapping errors, extreme finite view spans, view validation and owned snapshots. CurvePreviewProbe uses native wheel input to expose valid EXTEND overflow, then checks gaps, feedback, Fit recovery and Cancel with exact authoring preservation. An engine-only consumer verifies 99 finite raw vectors and six unchanged resource/item-scoped failures from the saved project. Full manual GUI/DPI/mode and hardware acceptance remain separate.
