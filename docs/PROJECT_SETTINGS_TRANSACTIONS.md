# Project Settings transactions and diagnostic focus

Status: **implemented, awaiting validation** on `codex/development-phase`. The owner has paused GitHub testing until the program is fully developed. Source and deferred regression/native/engine replay definitions in this batch have not been compiled or executed.

Project Settings now uses one private session for the menu and diagnostic entry points. It retains the actual text for Project seed, Minimum Y and Maximum Y. Incomplete or invalid text remains visible and blocks Apply, so an invalid seed cannot silently apply an earlier parsed value. Fields accept signed ASCII decimal integers, with exact signed 64-bit seed and signed 32-bit inclusive Y bounds. Whitespace, exponent/hex syntax, overflow and minimum greater than maximum are rejected. The private session limits each field to 128 UTF-16 characters; the UI uses bounded UTF-8 buffers and rejects excess text explicitly.

Inline lexical/bounds feedback is cached while text is unchanged. Rejected Apply publishes field-scoped diagnostics under **Rejected Project Settings** without changing the committed document, revision or history. Valid local text still passes through the document's complete configured project validator before publication. A successful Apply changes only seed/minY/maxY in one ordinary project transaction. No-op Apply adds no history. Saving remains explicit.

## Ownership and recovery

The session belongs to its exact document instance/revision and original three metadata values. A project edit, Undo/Redo, same-UUID reopen, New, or a changed source metadata value makes the old session stale. Stale text is preserved for review; Apply refuses publication. **Discard and Reload** explicitly replaces it from the current project. The guard is deliberately conservative: an unrelated document edit also requires reload. Saving and user-preference changes alone do not invalidate it.

Following a project diagnostic while the session is open preserves its pending text. Switching settings categories/pages also keeps that private Project Settings session while the shared settings window remains open. Cancel, successful Apply or closing the shared Settings window discards it. Opening settings does not reset a selected resource's private Properties draft, apply that draft, select a different resource, mutate User Config or save a project. Applying project settings likewise preserves the exact private resource draft object.

## Source controls

Recognized seed/minY/maxY diagnostics open the shared Project Settings panel and submit one focus request to the corresponding text field. The generic `/project` bounds destination leads to Maximum Y. Explicit logical field keys take precedence over contradictory pointers. Focus belongs to the settings session rather than whichever resource is selected in Properties; it cancels when the session/project becomes stale or a new destination replaces it. Actual widget activation acknowledges the request, and later frames do not reset text selection.

Unsupported project metadata, including file format version and root-order internals, retains its error details without an unavailable Project Settings action. The existing schemas, engine seed conversion, inclusive-bound semantics and public API are unchanged. This batch provides no migration/repair wizard or automatic document replacement.

## Deferred verification definitions

`ProjectSettingsSessionTest` defines nine contracts for retained incomplete/invalid text with no fallback Apply; signed extrema and inclusive integer bounds; lexical/overflow/range routes; three-field-only publication and one Undo/Redo while preserving an unapplied resource draft; configured full-project-validator rejection; document/revision/Undo/unrevisioned metadata staleness; same-UUID reopen/New; save/preference/no-op behavior; and bounded text/cache/Cancel lifetimes.

`ProjectSettingsNavigationTest` defines three contracts for exact fields/pointers and qualified-key precedence, foreign/resource destinations without buffer replacement, and supported versus unsupported project metadata actions. Existing diagnostic-navigation and focus definitions remain relevant; their previous results do not validate this extension.

`ProjectSettingsProbe` defines a production-panel native workflow. It enters `-`, rejects Apply, follows the resulting diagnostic without replacing that text, preserves a stale private seed `123` after a committed seed change, refuses stale Apply, explicitly reloads and cancels another private edit. It then enters seed 17 and inclusive bounds -2..3 through field links, checks private document/history/resource-draft ownership, applies once and checks one Undo/Redo. The saved canonical resource remains `context`, while its separate private ID `private-unapplied` stays uncommitted. The ambient expression changes from 639 to 24 at X=0.

`ProjectSettingsConsumer` defines 141 exact engine-JAR observations: 81 signed X/Z samples against `x+17-2+3+6`, plus 60 voxel observations over roots 2/4 checking occupied cells exactly inside inclusive Y=-2..3. `verify_project_settings.py` defines schema, saved metadata, private-buffer report, ambient arithmetic and unchanged placement checks. Future Linux/Windows workflow entries and retained artifacts reference these definitions; development-branch trigger exclusions remain unchanged and none has been executed during this batch.

Full Desktop menu/page-switch/window-X gestures, physical keyboard/IME/DPI cases, arbitrary focus/scroll layouts and complete project-lifecycle/GUI/hardware acceptance remain pending. Earlier evidence remains scoped to its recorded source.

The subsequent [project transition guard](PROJECT_DRAFT_TRANSITIONS.md) blocks New/Open/Exit while Project Settings contains pending raw input. Unmodified sessions are canceled after successful project replacement. These changes remain unvalidated.
