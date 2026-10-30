# Resource browser transactions

Status: **implemented, awaiting validation** on `codex/development-phase`. Compilation, regression/native execution and independent replay remain deferred under the owner's GitHub testing pause. This source change advances no passing count or production acceptance gate.

## One guarded browser edit

Create, Duplicate, Paste, inline Rename, confirmed Delete, placement insertion, tree/stage reordering, Move to Root and completed drops now use one browser edit session. Opening a form or entering an inline name does not commit a project change. At submission, an unapplied Properties draft or retained invalid seed text requires **APPLY**, **DISCARD** or **CANCEL**. Unmodified drafts permit the requested edit immediately. This is deliberately conservative: even an action on an unrelated resource requires a decision when Properties is modified.

Apply independently validates the Properties definition first, including raw seed input, then validates and publishes the draft together with the browser action as **one document transaction**. One Undo restores both changes; selection is outside that history. An invalid draft cannot be made acceptable by simultaneously deleting the resource containing it. A missing or mismatched Properties identity is rejected rather than silently applying a different resource.

Discard executes the action against the committed project and disposes of the private draft only after successful publication. Invalid IDs, incompatible parents, broken references, failed candidate validation and command exceptions preserve the exact private draft, raw inputs, document, revision and history. The decision remains open after rejection, allowing Cancel or another decision. Cancel/Escape executes nothing. An unmodified action rejected without opening the guard returns to its existing form or reports the error; it is not retained as a hidden pending command.

The browser guard keeps one command at a time, with bounded action text and frozen inputs. It rejects an old decision when the project object/revision, selected UUID, ordered selection, exact draft object, serialized draft content or seed-input revision changes. A pending command cannot be retargeted by another gesture. Existing private curve, expression, membership and typed-item editors, and an open browser selection decision, block resource-action entry. Project reset clears the command and its post-commit callback.

## Command and selection ownership

Command factories copy UUID collections. Insert owns a serialized definition, its UUID and creation-profile values before the decision opens. Rename owns the entered string. Move/reorder/delete/placement commands resolve UUIDs against the transaction's current candidate. Their callbacks do not capture a historical resource object or mutable text buffer.

Duplicate now uses the same dependency/UUID-aware model clipboard machinery for one resource, multiple siblings and folders. It resolves the source after an explicitly chosen Apply, so a newly applied definition is the definition duplicated. It selects only the newly created sibling roots. Duplicate does not overwrite the session clipboard. Context duplication of an unselected row uses that row rather than an unrelated existing multi-selection.

Paste retains the exact immutable clipboard payload requested. Copied definitions remain snapshot copies; cuts continue to move their live UUIDs. A cut payload is cleared only after accepted publication and only if it is still the same clipboard object. Cancel, failure and stale decisions do not consume it. No second selection or draft refresh occurs merely to build a payload.

After successful publication, selection reconciliation removes deleted resources, retains an existing active UUID when possible and keeps only siblings in its current scope. Create selects its prepared resource; Duplicate selects its new roots. Moving part of a selection cannot leave mixed-parent selected members. An unrelated Delete preserves a surviving selection. Properties refreshes from the accepted document after success; a failed action never refreshes it.

Drag payload UUIDs, root, project object and revision are captured once when native dragging starts. A changed project rejects the completed drop and asks for a new gesture. Release clears the drag owner after target processing. Drops then use the same guarded candidate publication as other commands. This adds an ownership rule; physical drag timing remains unvalidated.

## Deferred verification definitions

`ResourceEditSessionTest` defines fourteen contracts, including a twelve-case stale Apply/Discard matrix. Definitions cover one-transaction Apply/Undo/Redo, explicit restoration timing, failed action rollback, invalid-draft deletion, cancellation/retarget rejection, owned inserted definitions and UUID lists, duplicate-after-Apply, sibling reconciliation, failed cut reuse, live placement inheritance, atomic reference replacement/deletion, thrown partial-candidate failure and no-op history.

`ResourceEditProbe` defines production inline rename with native text/Enter input, actual action-guard and Delete-confirmation mouse clicks, Escape, and the shared Copy/Cut/Paste/Duplicate/Delete shortcut entry points. Property/raw-seed edits are explicitly staged fixtures. It checks combined Undo/Redo, failed colliding rename without partial Properties publication, invalid-seed rejection, explicit duplicate/delete discard, delete Undo/Redo, canceled cut retention, successful cut consumption and User Config isolation. It saves only the accepted rename/seed 17 and final folder move; rejected seed 23, raw signs and the temporary duplicate do not persist.

`BrowserEditConsumer` defines engine-JAR-only replay of that document: 243 exact constant samples and 144 EMPTY voxels across roots 2/4 and signed chunk coordinates. `verify_browser_edits.py` independently checks schema, exact persisted IDs/seeds/parameters, folder membership/order, absence of discarded resources and the native report. Linux/Windows workflow entries and artifact retention are prepared without changing trigger exclusions. None of these definitions has been executed in this batch.

Actual native drag/drop, creation and placement forms, multi-resource/folder duplication, private-editor blocking, replacement picker interaction, all command/platform/failure combinations, physical input/DPI and Windows 11 acceptance remain pending. The unit definitions do not certify those gestures. Import/export, separate Find/source navigation and project-file transitions retain their separately documented transaction policies. Save still persists committed project data only; there is no autosave or recovery-file workflow.

The subsequent [project history implementation](PROJECT_HISTORY_OWNERSHIP.md) shares the successful-publication sibling reconciler with browser edits, and blocks project history while browser forms/decisions remain owned. These changes are also unvalidated.

The unvalidated [resource decision layout follow-up](RESOURCE_DECISION_LAYOUT.md) adds a persistent Apply/Discard/Cancel row, scrolling explanation, cancellation-first frame arbitration and scoped footer activation. A consumed post-commit callback reports RuntimeException refresh failures once without replaying the accepted edit. Exact combined transaction and draft ownership remain unchanged; all new definitions are deferred.
