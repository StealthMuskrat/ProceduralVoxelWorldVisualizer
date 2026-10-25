# Bookmarks and coordinate navigation

The three existing project collections remain independent: `bookmarks.noise2D`, `bookmarks.noise3D` and `bookmarks.world`. Bookmarks contain UUID, name and integer coordinates. Duplicate names are allowed; selection and editing use UUID within the owning tab. No resource reference, camera orientation, display mode or navigation mode is stored.

The toolbar provides a searchable dropdown with clipped rows, Add Bookmark, Rename, Update Coordinates, Delete Bookmark and Teleport. Rows include coordinates to distinguish duplicate names. Names and signed coordinates are searchable. Immutable metadata is cached per committed project/revision/tab; filtering reuses that metadata and does not change selection or project state. Only visible rows submit native widgets.

Add captures the last picked coordinate when one is available, otherwise the current view center. Right-clicking the 2D image or a 3D slice captures that pixel's procedural coordinate. A World hit records the exact picked voxel; the Inspector also provides Add Bookmark at Coordinate for explicit EMPTY or occupied coordinates. The Add dialog shows and permits editing the captured integers before committing. Update Coordinates captures the current view center or free-fly position. Rename changes only the name.

For noninteger camera, image and slice positions, capture uses the containing cell (`floor` independently on each used axis). For example X = -0.1 captures X = -1. Nonfinite and out-of-range positions reject before narrowing; values never silently saturate. 2D capture ignores Y and stores no Y value. Three-dimensional collections require Y.

Add and Rename own their provisional values. Apply is one project transaction, and Cancel/Escape discards the draft. Rename rejects a deleted, moved or changed target, and permits unrelated project edits without replacing them. Add/Rename and direct Teleport dialogs retain their original tab even if another viewport becomes active. Dialogs reject a different project/world identity before committing or navigating. Stale or invalid Apply preserves the dialog and current document.

Bookmark create/rename/update/delete dirties the project and supports Undo/Redo. Selecting a bookmark or applying direct Teleport changes navigation only. Existing Camera behavior preserves the orbit offset/orientation or free-fly orientation. Teleport synchronizes the analysis center and selected slice plane; it leaves the selected noise, stage, representation and sampling settings intact. Bookmark edits do not change computation identity or exact generated values.

`BookmarkTransactions` exposes the same tab scopes, immutable coordinate values, checked capture, private Add/Rename drafts and update/delete operations through the headless model. Call a draft's `applyTo` inside the owning `DocumentSession.edit` transaction with the complete application validator. The helper does not move cameras.

Project format remains 2. The schema now specializes Bookmark2D/Bookmark3D coordinate requirements already enforced by semantic validation. Historical 2D bookmarks may omit Y or store null. Three-dimensional bookmarks require an integer Y. The strict reader also rejects missing UUID/name/X/Z instead of supplying primitive defaults; semantic diagnostics identify the exact tab/array index/field. `registries/bookmark-navigation.json` records this contract.

## Validation scope

The batch adds model and editor tests for negative/overflow capture, owned draft cancellation, tab identity, stale targets, unrelated-edit merge, precise parser/validator locations, cache/filter reuse, duplicate names, generation identity and orbit/free-fly navigation invariants.

`BookmarkReferenceProbe` runs the production native toolbar and dialogs in an isolated ImGui context with explicit fixture data. It uses actual mouse/text/Escape events for selection, Rename, Update, Delete, picked Add, tab changes, Cancel and direct Teleport. The probe checks one-transaction Undo/Redo, pending property-draft preservation, clipped/filter counts and save/reopen.

`BookmarkConsumer` compiles against the engine JAR alone. It loads the native result, checks independently specified negative coordinates and tab membership, samples the constant .625 field and checks EMPTY chunks before/after bookmark edits at negative chunks with roots 2, 4 and 8. Python separately validates the generated schema, coordinate-only shape, UUID uniqueness, row bounds and exact persisted coordinates.

Both platform pipelines run these checks after implementation alongside the existing clean build, native desktop/render/input references, packaging and byte reproducibility checks. This scoped evidence does not certify the full interactive GUI or Windows 11 hardware acceptance matrix.
