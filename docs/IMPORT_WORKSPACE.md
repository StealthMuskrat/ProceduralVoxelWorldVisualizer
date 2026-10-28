# Owned import workspace

Status: **implemented, awaiting validation** on `codex/development-phase`. No compilation, regression/native execution or independent replay was performed. The owner's pause on GitHub testing remains in force, and no passing count or acceptance status advances.

The production COPY/REUSE/SKIP dialog now has a dedicated editor session and panel. Incoming definitions and preview candidates remain owned by the existing `ResourceImport.Session` model. The wrapper adds exact destination object/revision ownership and explicit review/reload boundaries. No portable-package, project, registry or engine API format changes.

## Review and reload

Changing a resolution invalidates the displayed preview token. Apply becomes available only after the panel displays the current exact plan. An unchanged choice retains that token and its stable COPY identities. The panel acknowledges the plan backing its current filtered/clipped view; it does not require every row to fit on screen or force the user to visit every row.

A committed destination change keeps the old preview visible and disables resolution edits and Apply. **Reload Preview** explicitly rebuilds against the current destination, retaining choices and requiring a subsequent displayed plan. COPY UUIDs can change on this explicit rebuild, which the banner explains. REUSE compatibility and dependency exclusions are recomputed. A retained REUSE choice that is now incompatible produces a blocking preview diagnostic until corrected. A different project UUID requires Cancel and reopening Import.

Current-frame checks use the project object and editor revision rather than serializing the destination every frame. The model's full-value destination check still runs at publication. Unsupported in-place destination changes detected there mark the workspace stale and require reload. Even a revision-only committed edit requires explicit reload in this editor wrapper. Save alone changes no model revision and does not invalidate the import preview.

## Publication and private drafts

Apply publishes only the exact reviewed import candidate through the existing complete editor validator as one ordinary document transaction. The package's destination identity, seed, bounds and bookmarks remain authoritative, and existing definitions are never replaced. Reused resources use their existing UUIDs; copied resources use the reviewed targets; SKIP closes over dependent resources according to the model's existing rules. Placements remain represented by their layer rows.

Import preserves the exact separate Properties draft, selected UUID, sibling selection and invalid seed ledger. It neither applies nor refreshes that private input. Private Properties edits can therefore remain available after importing unrelated resources, and committed-only Save still omits them. Failed configured/compiler validation leaves the import open with the same plan/COPY identities, original document, revision, history and Properties ownership. A successful retry uses that same reviewed candidate if the destination has not changed.

Cancel/Escape releases the workspace without publication. Another package cannot replace an open workspace. Desktop blocks package entry behind private source dialogs, browser decisions/forms, navigation decisions, edited Project Settings, project file decisions and project history decisions. The open workspace in turn participates in the existing file/history blocker. Import/export path ownership still clears on successful submission or Cancel/Escape.

## Presentation and bounds

The workspace filters IDs, kinds, incoming UUIDs, resolutions and target IDs, with separate kind and resolution filters. Header counts distinguish COPY, REUSE and SKIP including dependent exclusion. Rows show source identity, conflict/compatibility, the editable resolution and resulting target ID/UUID. Incompatible REUSE is omitted from the combo; a stale retained incompatible choice still appears in its blocking preview diagnostics.

Filtered rows cache the exact plan/query, and a list clipper submits only visible rows. Scrolling does not rebuild the plan or filter. Labels and tooltips bound displayed text; exact UUIDs remain available for disambiguation. Reload/reopen resets row scrolling. The issue child has fixed height, renders at most eight preview plus eight rejected-candidate diagnostics and bounds each displayed message. The session retains at most 256 candidate diagnostics with the original issue count; the editor's existing diagnostic/log publication remains authoritative for the full rejection. Incoming/model/candidate snapshots still use the model's existing whole-transaction allocation; this is not a new package-size or planner-performance guarantee.

## Deferred definitions and remaining scope

`ImportEditSessionTest` defines twelve contracts for displayed-plan gating, choice/UUID stability, immutable choices and inputs, explicit reload, replaced-project rejection, bounded validation details/retry, compiler rejection/SKIP correction, changed REUSE compatibility, selective placement exclusion, exact separate Properties preservation and revision/in-place stale detection. Existing headless `ResourceImportSessionTest` semantics remain unchanged.

`ImportWorkspaceProbe` defines production mouse/filter/combo/Escape interactions over 1,004 incoming rows. One thousand bulk SKIP choices are staged fixture setup; special COPY/compatible REUSE/incompatible COPY/SKIP choices use the actual controls. It checks clipped top/scrolled views, query/plan reuse, Cancel retention, retarget rejection, disabled stale Apply, explicit reload with retained choices/new COPY UUIDs, validation-details entry, rejected Apply/retry, exact private input, User Config isolation and one model Undo/Redo round trip. The fixture calls document Undo/Redo directly to isolate the import's parent delta; it does not certify configured history key dispatch. A blocker is injected through the panel interface; physical interaction with every private editor remains separate.

The saved result contains three unchanged existing constant fields plus copied values 0.9 and 0.75, reuses the existing 0.25 source and excludes the discarded/bulk sources. `ImportWorkspaceConsumer` defines engine-JAR-only replay of 405 exact scalar values, 144 EMPTY voxels across roots 2/4 and signed chunks, unchanged existing definitions and reviewed target identities. `verify_import_workspace.py` defines independent package/result schemas, complete existing-definition equality, exact resolution effects, metadata/private-value exclusion and native-report bounds. Linux/Windows steps and artifacts are prepared with development trigger exclusions unchanged. None has executed during this batch.

Full physical chooser/package-file interaction, all type/folder/layer combinations, very large packages and planner latency, every failure/platform/DPI case, private-window focus interactions and Windows 11 acceptance remain pending. This implementation and its deferred definitions do not certify complete A07 acceptance or change earlier evidence scopes.
