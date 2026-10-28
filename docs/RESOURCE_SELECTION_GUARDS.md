# Resource selection guards

Status: **implemented, awaiting validation** on `codex/development-phase`. No compilation, regression tests, native probes or independent replay were executed for this batch. The owner's pause on GitHub testing remains in force; main and workflow trigger exclusions are unchanged.

Resource-tree and Voxel Manager row clicks, and their context-menu **Properties** action, now prepare a private sibling-selection plan before changing the current selection. A modified Properties draft, including invalid seed text that has not changed a canonical long, requires **APPLY**, **DISCARD** or **CANCEL**. Escape cancels. The existing Properties draft and selection remain installed while the decision is open.

Apply uses the ordinary validated parent transaction. A failed Apply retains the exact draft, pending seed text, selection and decision; Cancel then permits correction. Discard installs the requested selection without publishing the draft. Selection itself creates no document history, dirty state or User Config changes. Undo/Redo of an accepted parent transaction does not replay browser navigation.

Clicking the same sole selected owner preserves the exact draft and text buffers, including an invalid seed. A change that would hide a modified draft in the multi-selection summary still requires a decision even when the active UUID stays the same. Empty selection through Ctrl-click receives the same protection.

## Siblings, gestures and ownership

Ctrl-click toggles within the current root/immediate-parent scope. A different scope starts a singleton selection. Removing the active row chooses the last remaining selected sibling, or no resource when none remain. Shift-click uses the browser's current visible range and filters out descendants from other scopes. The planned UUID order and anchor are retained until a successful decision; Cancel leaves the previous anchor in place. Row filtering/scrolling cannot retarget the pending selection.

Plans contain no historical row index or mutable list alias. Duplicate UUIDs, missing resources, mixed scopes and an active UUID absent from its list are rejected. A pending plan cannot be overwritten by another gesture. Scope validation makes a linear scan of resource metadata for each validation rather than looking up every member through repeated full scans.

A decision is owned by the exact project object and revision, selected resource, ordered selection, exact Properties draft object, serialized draft value and raw seed-input revision. Any change rejects the old decision without replacing newer work. This deliberately includes unrelated document edits; the user must request selection again after them. Same-UUID/equal-value draft replacement is also stale. After a successful Apply, the plan is revalidated because applying a parent draft can remove a proposed destination. That explicitly requested Apply remains committed, but an obsolete selection is not installed.

An open private curve, expression, typed-item or membership editor blocks row/context-Properties selection through the existing Properties editor guard. The selection modal rechecks that guard before Apply/Discard. Context Copy/Cut and drag-start obtain their UUID list directly: an unselected context row is a singleton payload, while a selected row uses the current sibling selection. These operations no longer change the Properties owner simply to build a clipboard or drag payload.

## Deferred definitions and remaining scope

`ResourceSelectionSessionTest` defines thirteen contracts, including a fourteen-case stale-Apply/Discard matrix. Cases cover same-owner invalid text, Cancel, ordinary Apply/one Undo/Redo, Discard isolation, rejected model/raw input, Ctrl scope/removal, owned ranges, empty selection, invalid plans, retarget prevention and a parent Apply that removes the destination.

`ResourceSelectionProbe` defines actual production tree/manager row and modal mouse interactions, Ctrl/Shift events, Escape, rejected raw-seed Apply, repeated-owner clicks, range discard, final Ctrl deselection, stale same-UUID draft rejection and one Undo/Redo. Model/raw edits are staged as explicit probe fixtures; this does not claim physical typing coverage. It saves a document in which only the requested first-source constant change to 0.875 persists; discarded seed 43 and a pending ID are absent. `BrowserSelectionConsumer` defines 243 exact constant samples from that saved document with the engine JAR alone. `verify_browser_selection.py` defines schema, exact saved-model and native-report checks. Linux/Windows workflow entries and artifact retention are prepared for validation to resume.

This is a browser-selection batch. Create, Duplicate, Paste, rename, drop completion, placement insertion and Delete still use their existing structural workflows; this change does not certify their handling of separate unapplied drafts. File Save continues to save committed document data only. Full physical keyboard/mouse, private-editor interaction, context Copy/Cut/drag gestures, filter/scroll timing, DPI, arbitrary external mutations and Windows 11 acceptance remain pending. No passing test count or production acceptance status is advanced.
