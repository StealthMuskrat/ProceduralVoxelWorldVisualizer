# Keyboard input and preferences

User Settings > Input edits a private binding draft for 24 commands. Type a chord or use Record, then Apply Bindings to validate, save and activate the complete map. Unbind stages an empty value, Default resets one row, Reset Binding Defaults stages the entire default map, and Revert Bindings reloads the active map. Invalid drafts stay editable and show the conflicting command names. Closing or changing the settings category preserves the draft but cancels recording. Save Preferences and periodic preference persistence save only active bindings; they never apply an invalid or incomplete draft.

Recording first waits for held keys and modifiers to release. It then records one fresh named-key press with its exact modifiers. Escape cancels without changing the draft; the captured key is suppressed for application dispatch in that frame. Chords fire on a new press rather than repeat. The registry and menus share command IDs and current binding labels. File transitions, project undo/redo, validation, resource actions and curve history use their existing services rather than a second implementation of their edits.

## Grammar and contexts

Modifiers are Ctrl, Shift, Alt and Super, accepted in any order and case with optional surrounding whitespace. Storage/display canonicalizes them to `Ctrl+Shift+Alt+Super+Key`. Duplicate modifiers, extra tokens, modifier-only bindings, unknown names and values longer than 80 UTF-16 units reject. A blank string means unbound. Key names include A–Z, 0–9, F1–F12, Enter/Tab/Space/Backspace, Insert/Delete, Home/End/PageUp/PageDown, Left/Right/Up/Down, named punctuation and Keypad0–9/arithmetic/Enter/Equal. The checked-in registry lists the exact vocabulary. Escape is reserved for cancellation and free-fly release.

These are named physical keys, not character or IME interception; for example `Equal` names the key independently of the resulting shifted text. Super follows the backend's Super modifier. Modifier equality is exact, so Ctrl+S does not also fire on Ctrl+Shift+S, Ctrl+Alt+S or Ctrl+Super+S. OS-reserved combinations remain subject to the desktop/window system.

| Scope | Commands and eligibility |
|---|---|
| GLOBAL | New/Open/Save/Save As, Import/Export, Find/Find References, Validate, Settings, Help, Profiler and Logs; eligible in general, resource or curve focus |
| DOCUMENT | Project Undo/Redo; general or resource focus |
| RESOURCES | Rename/Duplicate/Copy/Cut/Paste/Delete; resource-window focus and a selected resource |
| CURVE | Curve Delete/Undo/Redo; curve-window focus, affecting the private curve session |

Global bindings conflict with every scope. Document and Resources overlap. Curve is separate from Document/Resources, allowing the same Ctrl+Z/Ctrl+Y and Delete keys to affect the appropriate local history/selection. Conflicts compare canonical chords and overlapping scopes; unbound commands do not conflict. At most one application command dispatches in a frame, in stable registry order if separate keys are pressed together.

Text input, active widgets/gestures, open popups, recording and free-fly suppress application commands. Text clipboard/undo stays with ImGui, resource clipboard remains project-session scoped, and resource Delete opens the existing confirmation/replacement flow. Curve Delete targets its selected interior points. Mouse/free-fly navigation gestures remain defined by their existing controllers and are not remapped by this registry. This batch does not certify every OS layout/IME, focus transition, gesture or shortcut reserved by a window manager.

## Persistence and compatibility

User Config remains format 1 with the same shape: `input.shortcuts` maps stable command IDs to strings. The structural schema bounds the known command names and string lengths; runtime validation enforces grammar and context conflicts. An explicit empty value remains disabled. Existing authored chords win during reads. Missing historical Save/Undo/Redo/Find keys stay unbound; missing introduced commands receive defaults only if they do not conflict with authored or earlier introduced choices. Otherwise they stay unbound until the user remaps them. Reads do not rewrite the file. Unknown command IDs and invalid chords reject strict reads; normal startup keeps its established fallback to default preferences on invalid configuration.

Apply validates a complete candidate and verifies that another successful edit has not replaced the draft's base. It serializes a separate preference snapshot to a unique temporary sibling, forces its contents and atomically replaces the destination where supported, falling back to a same-directory replacement. Only a successful write publishes the active binding map. Validation/write failures preserve live bindings and the existing destination; temporary output is cleaned where possible. Ordinary preference saves use the same staged writer. This does not promise rollback after device/power failure on filesystems lacking atomic replacement or directory durability guarantees.

Bindings, recording, navigation and preference writes never change the project document, its isolated property draft, revision, undo history or dirty state. Engine compilation, sampling, voxels and archives do not depend on keyboard preferences. The standalone engine JAR remains outside this feature's dependency graph.

Validation includes every key/modifier combination, exact modifiers, canonical conflicts and disjoint scopes, historical upgrades, disabled bindings, capture release/cancel behavior, immutable compiled maps, stale draft rejection, successful restart and failed atomic replacement. The isolated native key-event probe uses real ImGui named-key events and text/popup state, then saves/reloads a remap while checking project invariance. Native desktop smoke renders a real conflicting private draft and verifies that active bindings remain unchanged. The full interactive A32 matrix and Windows 11 hardware acceptance remain separate gates.

The subsequent [project history guard](PROJECT_HISTORY_OWNERSHIP.md) routes menu and configured Document Undo/Redo commands through one owned discard-or-cancel decision for unapplied Properties input. Existing Curve/text focus rules remain authoritative. This implementation and its deferred definitions are unvalidated.
