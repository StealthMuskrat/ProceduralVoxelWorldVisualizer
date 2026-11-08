# Local membership Undo/Redo

Status: **uncompiled and untested** under the owner's testing pause. This closes the membership-specific source gap W01-C01 in [remaining work](REMAINING_WORK.md), covering specification 3.5.3 and 9.4.3. It does not complete the audit of other temporary editors or certify a runtime/GUI acceptance gate. The observed baseline remains 922 tests / 120 suites.

Edit Members now provides Undo Members and Redo Members for its private selection. A checkbox change is one local action; Select Matching and Clear Matching each form one action across the complete filtered list. Undo restores removed members at their original positions; Redo restores the exact edited order. A no-op preserves revision and Redo. A changed choice after Undo discards the abandoned Redo branch. Bulk input is completely checked before any choice changes.

History retains add/remove UUID positions, rather than full membership snapshots per step. It retains at most 100 steps and prunes old steps above a 65,536-reference budget. The latest bulk action remains undoable even if that action alone exceeds the budget; this is a soft reference budget, not a constant-byte memory guarantee. Temporary before/after views still cost proportional to the current member list. Private duplicate references are preserved when removed/restored; history does not normalize malformed input or bypass final validation.

The existing configured Undo/Redo chords operate locally while this modal owns focus and no text input or active widget owns the keys. Exact modifiers and remapping apply; unbound/invalid chords do not dispatch. Cancel/Escape wins before history or Apply in the same decision frame. The persistent footer has three rows: Apply Members/Cancel/page toggle; Select Matching/Clear Matching/Selected Only; Undo Members/Redo Members. Both Members and Issues retain access to history. Successful history movement clears local rejection feedback and reveals Members.

Changing local history retires queued or ready Apply work through the existing owned request lifetime. A late predecessor result cannot publish, and a new Apply is required explicitly. Empty Undo/Redo does not cancel current work. Local changes leave the project, dirty state, document revision/history, preferences and separate Properties ID/raw seed input intact. Apply still validates and commits only member UUIDs in one project transaction; project Undo/Redo reverses/reapplies that transaction. Apply, Cancel and close dispose of local history. Replacement Properties drafts cannot use the old dialog's history.

`MembershipEditSession` adds editor-only `canUndo`, `canRedo`, `undo` and `redo`. No persisted field, shortcut registry ID, schema, engine signature, membership interpretation or engine arithmetic changes.

## Deferred verification

Ten new JUnit definitions await compilation/execution:

| Definitions | Intended checks |
| --- | --- |
| `MembershipHistoryTest` (2) | Step/reference retention, latest oversized bulk action, exact duplicate-reference positions. |
| `MembershipShortcutsTest` (2) | Defaults/remapping, exact modifiers, focus/text ownership, invalid/unbound bindings. |
| `MembershipLocalHistoryTest` (6) | Set/group order and parent-state isolation; atomic bulk/no-op behavior; Redo branching; rejected Apply feedback and one project transaction; closed/replaced owners; queued/ready Apply retirement and explicit retry. |

The existing 30-case `ResourceReviewLayoutProbe` now defines actual button and Ctrl+Z/Ctrl+Y input, selected-only bulk Undo/Redo and unchanged parent project/private ID checks. Its report adds `localMembershipHistory`; the independent reader requires it and the third action row. The 18-case private-editor reader also requires the new footer row. Case counts and saved member-only JSON/independent engine arithmetic remain unchanged. These definitions and readers have not run. Physical input, DPI, Windows 11 and complete A01–A36 acceptance remain pending.

Only source review and git whitespace/content/metadata checks were used. No compiler, Gradle, JUnit, native probe, consumer, Python/schema/packaging checker, packaging operation or workflow ran.
