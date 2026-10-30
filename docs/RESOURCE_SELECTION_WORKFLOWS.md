# Resource selection, membership and portable previews

Property noise/field, voxel-type and voxel-set selectors now share a simple searchable popup. Committed resource metadata is cached by project identity/revision; each open picker caches its filtered rows. UUIDs identify selections, including equal IDs in 2D and 3D roots. Display labels are escaped for ImGui identity, and unknown references are shown explicitly instead of silently choosing another resource. None/Placement field choices keep their original meanings and stay available independently of the filter. Only an explicit item activation invokes the parent draft callback. Typed processing-item/parameter selectors remain separate.

Popup lists use native clipping. At most 128 previously opened picker contexts retain search state; drawing closed selectors does not create or evict that state. Project/revision changes release old query catalogs while preserving retained search text. Query changes scan the relevant catalog once, while unchanged drawing/scrolling reuses results. This is bounded widget submission and caching, not a sublinear search or representative hardware latency claim.

## Temporary membership editor

Properties for voxel sets/groups now offers **Edit Members...**. The modal contains a search field, Selected only, clipped multi-checkbox rows, Select Matching/Clear Matching, Apply Members and Cancel. Bulk actions affect the matching list, including offscreen rows, and preserve unrelated selected members/order. New choices append without duplicates. Search, preview and Cancel never change the project or the parent property draft.

**Apply Members commits only the member list** as one document transaction when values change. A separately drafted resource ID stays in Properties for its normal Apply/Revert. The session checks the active membership and parent draft against its opening values, rejects stale/deleted owners and missing selected types, and validates the complete candidate through the existing document service. Failure preserves the current document and the private selection. Unrelated committed catalog changes can be retained. After success, the parent draft receives an owned copy of the member list without losing its other fields. One Undo restores the previous membership; Redo restores the accepted members.

## Export and import previews

The Export dialog separates explicit checkbox choices from a read-only included-resource preview. Both lists are clipped, and search changes visibility only. `ResourceExportSession` prepares the canonical `ResourceGraph.selection(..., true)` dependency payload only when project identity, revision or the explicit selection changes. It caches immutable included-resource metadata and owns its payload; the final explicit export obtains a separate copy. Filtering and scrolling do not rebuild dependency closures or serialize a project every frame. Dependency preparation still follows the existing canonical implementation and may be substantial for a very large selection.

Included rows distinguish explicitly Selected from Required resources, including folder context and operation carrier layers/default references. Invalid selected UUIDs reject preparation without replacing the last successful payload. Opening Export discards stale view selections, such as a resource removed by Undo, without changing the document. Later unavailable selections produce recoverable feedback and an explicit removal action. Existing portable version 1, UUID references, validation and import COPY/REUSE/SKIP semantics stay authoritative. Import resolution rows now filter and clip using a fixed row layout; filtering does not regenerate the import plan or modify choices. Typed-item selectors, deletion/dependency detail lists and complete GUI acceptance remain separate work.

## Validation scope

Unit contracts check query reuse, dimension/UUID disambiguation, incompatible/null choices and catalog ownership; private/cancelled membership, membership-only Apply, pending IDs, one Undo/Redo, filtered bulk edits, stale bases, missing types and owned copies; and export cache keys, exact dependency closure/carrier context, invalid preparation and payload ownership.

`ResourceWorkflowProbe` creates an isolated native context with 2,000 types and two noise resources. It selects a filtered picker item through actual mouse events, cancels a real membership modal with Escape, applies members while preserving a pending ID, verifies one Undo/Redo, and exports the cached selected closure. Independent Python checks the report/package and an engine-only Java consumer verifies the exact type/member identities, empty world and analytical 0.625 field. Desktop smoke renders the real Export dialog without writing a selection file. Linux and Windows CI run the references alongside existing smoke/render/input checks. These checks do not certify every picker/import/membership gesture, arbitrary filesystem destinations, all DPI/scroll conditions or complete Windows 11 hardware acceptance.

The subsequent [owned import workspace](IMPORT_WORKSPACE.md) applies displayed-plan publication, explicit stale reload, filtered/clipped COPY/REUSE/SKIP rows and bounded diagnostics to portable import. The implementation and deferred definitions are unvalidated.


## Development-phase membership ownership follow-up (unvalidated)

The membership editor now accepts only the selected, exact Properties draft at construction. Its session retains the originating EditorState, project UUID, draft object, active resource UUID/kind and ordered sibling selection. A newly refreshed draft with identical serialized values cannot inherit an older session's Apply. Changed selection ownership disables Apply in the modal; Apply independently checks ownership and the complete opening active/draft values before publication. Unrelated committed catalog edits remain permitted when the owner and both membership values are unchanged; every selected voxel UUID is still checked against the current catalog.

Successful Apply and Cancel consume the session. Subsequent edits, bulk edits and Apply reject rather than reusing a dismissed private selection. The existing membership-only transaction remains unchanged: a pending ID stays private, failed publication changes neither member list, and successful publication copies only accepted members back into the original draft. Normal desktop project replacement already resets Properties; the session also rejects another project UUID or EditorState.

Rejected candidate validation retains at most 256 diagnostics plus the total count. The modal shows at most eight messages, each at most 1,024 UTF-16 characters, in a fixed-height scroll region. Transaction exceptions receive generic retry/Logs guidance without displaying diagnostics from an earlier rejection. Exception messages without text also have a non-null fallback. The private member list remains available for correction/retry while its owner remains current.

Six deferred `MembershipOwnershipTest` definitions cover equal-value draft replacement, changed sibling selection, foreign editor/offscreen construction, consumed sessions, bounded rejected-candidate diagnostics and validator exceptions/retry. Existing `MembershipEditSessionTest` definitions retain the unrelated-catalog and pending-ID/Undo contracts. None of this follow-up has been compiled or tested. Native stale-owner, rejection/retry, keyboard and small-window layout checks remain pending under the owner testing pause; the earlier native evidence above does not validate these changes.


[Portable resource file safety](RESOURCE_PACKAGE_FILES.md) subsequently separates package defaults from the active project path, rejects active-project aliases, stages complete atomic exports and strictly reads version-1 envelopes. This development extension and its deferred file/native/report definitions remain uncompiled/untested under the owner testing pause.


[Owned export workspace](EXPORT_WORKSPACE.md) subsequently freezes the committed source, requires explicit stale reload and exact displayed-preview publication, and preserves retry/Cancel lifetimes. The lower-level cache API remains available; this editor extension and its deferred definitions remain uncompiled/untested under the owner testing pause.

[Attempt-owned transaction feedback](PROJECT_CHANGE_FEEDBACK.md) subsequently replaces shared diagnostic-list identity inference for membership retries, including repeated use of the same validator rejection list. Its implementation and deferred definitions remain uncompiled/untested.

The subsequent unvalidated [resource review layout follow-up](RESOURCE_REVIEW_LAYOUT.md) gives Membership persistent Apply/Cancel/bulk actions and Members/Issues pages, measured clipped checkbox rows and same-frame Cancel priority. Filtered private selection and membership-only transactions retain their existing semantics. It adds no passing evidence and does not change Export in this batch.
