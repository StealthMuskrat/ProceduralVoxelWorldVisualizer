# Resource hierarchy and execution order

The three protected roots now support mixed folder/resource ordering. Noise generators can appear before, between or after folders. World folders can organize layers and nested folders. All folders remain organizational: moving a folder/layer or changing tree order does not change generation, inheritance, resource seeds or the canonical execution array.

Layer rows show their global execution ordinal (`[run N]`), including disabled layers. Properties repeats that order. The ordinal identifies the layer's position, not a flattened per-operation stage snapshot. **Move Up/Down in Tree** and tree drag/drop organize rows. **Run Layer Earlier/Later** explicitly changes execution. Placement reorder remains execution order within its layer. Folder drops move inside; holding Shift over a folder places the dragged sibling block before it. Explicit execution commands are disabled for mixed folder/layer selections.

## Model contract and migration

Project format stays 2. Optional `project.rootOrder` maps `noise2D`, `noise3D` and `world` to sparse UUID arrays of distinct direct children. Listed children appear first; unlisted children retain their historical relative order. Missing orders default to an empty map. Existing `Folder.childOrder` carries the same sparse ordering within a folder. Optional `Layer.parentFolderUuid` defaults to null and must identify a World folder when present. Layer IDs remain unique across the entire world, regardless of organization.

`worldGenerator.layers` remains the execution authority. `ResourceGraph.children` supplies presentation order. `ResourceGraph.shift` / `reorderBefore` now organize World layers rather than editing their execution array; API clients that intentionally alter execution must use `shiftExecution`. These are development API behavior changes. New readers retain historical format-2 defaults; older strict readers are not guaranteed to accept newly written optional fields.

The generated `resource-hierarchy.json` registry describes the fixed roots, order paths and execution separation. Structural schema checks fixed map keys, UUID shapes and duplicates; runtime validation checks direct-child ownership, root compatibility, parent references and folder cycles. Unknown/dangling/nested/cross-root order entries reject loading instead of being silently repaired. User operations reconcile affected metadata before validated publication.

## Transactions, clipboard and portable resources

Hierarchy edits use existing explicit document transactions and Undo/Redo. Mixed selections must remain compatible siblings. Moves and copies preserve the source's displayed sibling order even when the supplied selection UUID collection has another order. World-folder copies remap contained layer/operation identities and internal parents/orders; external procedural references retain their original UUIDs. Copies append their new layers to execution in canonical source order. Cuts preserve identities and execution order. Invalid root/parent/cycle destinations reject.

Root/folder orders survive UUID remapping and portable import/export. Existing destination rows retain their relative order; incoming root blocks append in their authored mixed order. Reused destination folder definitions retain their own metadata; new children without an authored destination order use the stable fallback. Project identity/seed/bounds remain those of the destination. Operation-only exports include carrier-layer defaults and World-folder ancestry, with order metadata pruned to included children. Export pruning runs on owned copies and cannot modify the source document.

Subtree discovery now uses iterative adjacency traversal rather than repeated full scans. Presentation index snapshots also consume root ordering and retain immutable layer ordinals. Find breadcrumbs/directories include World-folder ancestry. Editor computation identities omit root order and layer parent metadata, while explicit execution changes still alter them. Normal startup remains empty; no folder introduces defaults or placement overrides.

## Evidence and remaining scope

Contracts cover mixed root/block ordering, sparse metadata rejection, World-folder parents, cycle/root safety, copy/cut UUIDs, order remapping, export ownership/ancestry, import/append ownership, historical defaults, Undo/Redo, display indices and Find context. Independent engine expectations use a constant selection field: SET then CLEAR produces EMPTY across roots/negative chunks; organization leaves that unchanged, while explicitly running CLEAR before SET produces solid voxels.

The isolated native hierarchy probe renders the organized tree, verifies layer order and computation identity, round-trips the project and checks immutable export behavior. An engine-only consumer and independent Python validate its saved model and numerical/structural expectations. Desktop smoke explicitly creates organizational test folders, verifies unchanged computation identity and saves `hierarchy-smoke-project.json`; regular startup still creates no user resources. Full drag/drop/clipboard/rename/scroll/DPI acceptance, arbitrary failure timing, representative hardware performance and complete Windows 11 GUI certification remain separate.
