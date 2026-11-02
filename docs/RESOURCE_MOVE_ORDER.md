# Resource move order — unvalidated development

This main-branch source follow-up addresses specification 3.2.3/3.2.5, 3.3.2–3.3.5 and 3.5.2, with A06/A08/A16/A22 coverage definitions. No compilation, JUnit, independent consumer, native probe, checker or workflow ran. The owner testing pause and last observed 922-test / 120-suite validated baseline remain.

## Ordered live blocks

ResourceGraph.move already obtained displayed sibling order for organizational metadata, but moved actual definitions by iterating the supplied selection collection. Placements and flat resources were appended in that input order. Selecting the same placements in reverse could therefore reverse SET/CLEAR execution after a cross-layer move or drop before an anchor. A ResourceClipboard cut stores identity membership at cut time, so it could also restore an obsolete order after the live source had been reordered.

Move now validates selected identities and sibling/root compatibility, then obtains the selected entries from the current source sibling order. The same ordered entries drive definition relocation, flat-list append and organizational metadata. Selection duplicates remain deduplicated; unselected resources retain their relative order. A same-layer placement move still appends the selected block, and reorderBefore inserts that block before its anchor. Destination and cycle checks still precede mutation.

Cuts resolve both live definitions and live order at paste time. Copies retain captured definitions and captured order and create new UUIDs. Existing ID collision resolution, external UUID references, sparse overrides and destination-layer inheritance are unchanged. Moving placements can still intentionally change their inherited settings by changing layers; this correction does not freeze resolved defaults into overrides. World folder/layer organization remains separate from the layer execution array. Public signatures, registries and project format 2 are unchanged; model code remains independent of editor/native dependencies.

## Deferred definitions

Eight ResourceMoveOrderTest definitions cover reversed/permuted/duplicate membership, cross-layer and same-layer ordered append, before-anchor drops, live reorder/value changes after Cut, captured Copy order/values/new identities/external references, collision-safe IDs, flat voxel ordering with retained membership links, and stale/mixed/incompatible destination rejection without mutation.

Three ResourceMoveTransactionTest definitions pass production browser commands through ResourceEditSession. They specify captured command membership resolved against current order, one document transaction with exact Undo/Redo, stable session lifetime/preferences, cross-layer before-anchor insertion and rejected stale Cut without publication or history movement. These definitions do not observe native pointer input or every private-draft decision.

ResourceMoveConsumer separately compiles against only the engine JAR. It moves a SET/CLEAR block using reversed input, checks whole-project expected state, moves Undo/Redo, cuts after a live reorder and then pastes a copy captured before that reorder. Four frozen compiled states must independently produce 84 exact coordinate-field observations and 1,176 exact signed-position voxel observations across roots 2/4. SET then CLEAR selects only X=1 in the fixture; CLEAR then SET selects X>=1, with inclusive project-Y clipping and EMPTY outside it. Saved state JSON must round-trip exactly; existing authoring data and external field references must remain intact.

Future Linux/Windows client commands and artifacts are added to the existing manual-only workflow without dispatch. Broader hierarchy/clipboard/drag/drop/private-draft/native/DPI/Windows 11 and final release acceptance remain pending. Main is authoritative; no development branch was created.
