# Revision-cached resource browsing

The Resources panel now renders fixed-height flat rows through ImGui's list clipper. Protected roots stay fixed. Folder/layer expansion determines the projected hierarchy; clipped rows retain depth, UUID identity, selection, context menus, rename and drag/drop routing. Inline rename occupies the same row height, with rejection feedback outside the list. Very deep hierarchies cap visual indentation at 32 levels while retaining their complete logical depth and parent identity.

Filtering matches IDs without changing stored text. Matching descendants reveal their ancestor path, including normally collapsed folders/layers; matching containers reveal their contents. Clearing the filter restores the user's expansion choices. A Shift range follows the displayed hierarchy's sibling order, including `Folder.childOrder`, and excludes filtered-out resources and different parents. Offscreen siblings within that projected range remain eligible. Ctrl selection and mutations continue through the existing editor/resource transaction services.

Voxel Manager has a shared ID filter and clipped type/set/group lists. Type swatches remain exact stored RGB values. Find References to Selected opens the existing unified search with the selected UUID. Adding a noise generator to a layer is available from the layer context menu and the selected-layer action. Its modal searches and clips the combined 2D/3D resource list; Add Placement creates one operation with the existing dimensional interpretation, unique ID and absent-override inheritance behavior.

## Ownership and work bounds

`ResourceTreeIndex` captures immutable UUIDs, labels, kinds, scopes, enabled flags and RGB swatches. One scan builds adjacency and kind tables; folder child-order ranks sort only their own children. Snapshot construction and projection never serialize the project. Traversal is iterative, including descendant search, so the browser does not depend on Java recursion depth.

`ResourceTreeIndex.Cache` invalidates on committed revision or project-object replacement. `ResourceTreeView` caches projected rows until the index, filter or expansion changes. Scrolling and navigation reuse the same projections. Filter/projection changes remain O(resources in the selected root); filtering a large list is not claimed to be sublinear. Storage is O(resources plus cached projected rows). Rendering submits viewport rows and the clipper's small measurement overhead rather than one widget per resource. Manager/placement query results also cache until their actual inputs change.

Normal project startup stays empty. Filtering, expansion, scrolling and drawing never change project content, property drafts, history, dirty state, computation identity or engine samples. Resource selection retains the existing draft-navigation behavior. Resource mutations still use existing explicit transactions. Saved formats, schemas and the engine artifact do not change in this batch. Mixed root ordering and organizational World folders are covered by the later [resource hierarchy extension](RESOURCE_HIERARCHY.md). Layer execution remains the canonical world array, separately from tree organization. Other property pickers and export/dependency lists are not covered by this virtualization change.

## Validation scope

Unit contracts check explicit mixed folder child-order vectors, descendant/container filtering, expansion restoration, layer/operation ownership, displayed sibling ranges, immutable metadata, revision/project replacement and rename/undo/redo invalidation. A 12,000-level synthetic hierarchy checks iterative traversal; a 25,000-type list checks filter membership, including a Turkish default locale. These are browser service checks rather than claims that every synthetic hierarchy passes project validation or meets a hardware latency target.

`ResourceBrowserProbe` creates an isolated real ImGui context and 7,102 resources: 2,000 noise generators, 5,000 types, two folders and 50 sets/groups each. It asserts bounded submitted viewport rows, changed membership after real native scrolling, unchanged build/projection counts while scrolling, ancestor revelation and exact manager filtering. It also checks document/draft/history/revision/dirty preservation and an independent constant sample. The JSON report is independently checked by `verify_resource_browser.py`. Desktop smoke visits the real Voxel Manager alongside all existing scenarios. Both CI platforms run the probe. Full drag/drop/rename/keyboard gestures, all scroll/scale/DPI cases, representative hardware timings and complete Windows 11 GUI acceptance remain separate.

The follow-up property selector, membership and export/import work is documented in [RESOURCE_SELECTION_WORKFLOWS.md](RESOURCE_SELECTION_WORKFLOWS.md).

## Development-phase selection guard

Row clicks and context-menu Properties in Resources and Voxel Manager now protect unapplied Properties drafts, including raw seed text and selection changes that would hide an editor in the sibling summary. Copy/Cut and drag-start no longer select an unselected context row. The source is uncompiled/untested; see [ownership, deferred definitions and remaining scope](RESOURCE_SELECTION_GUARDS.md).

Structural browser actions now share [guarded candidate transactions](RESOURCE_BROWSER_TRANSACTIONS.md), including combined Properties Apply/one Undo, rejection retention, candidate-scoped UUID commands, cut consumption and post-commit sibling reconciliation. Implementation and deferred definitions remain unvalidated.
