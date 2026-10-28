# Diagnostics and recovery

The editor now retains structured diagnostics in one bounded grouped history. View > Logs provides text, severity and subsystem filters, a clipped result list and a selected detail view with timestamps, occurrence count, code, source/control UUID, parameter and path. Copy Details copies the selected event. The notification panel uses the same history and selection; View Selected in Logs opens that exact retained event with filters cleared.

Notifications have independent unread, action-required and dismissed states. Mark All Read changes unread state only. Mark Action Resolved explicitly acknowledges the action; it does not repair project data or retry generation. Dismiss is available after resolution and hides the notification while retaining its historical log group. A new equivalent occurrence restores unread/visible state, retains the first timestamp and stable event ID, updates the last timestamp and increments the count. Resolved actions become required again when a new publisher explicitly requires action. The status bar shows unread and action-required group counts.

EditorEvents is thread-safe. It retains at most 1,000 groups by default, configurable to 1..10,000 for isolated consumers. Equivalent events group by code, severity, subsystem, message fingerprint and project/source target; recency moves repeated groups to the front. Retention evicts the oldest group, including any associated notification, and reports an eviction count. Counts saturate at signed-long capacity. Retained messages are bounded to 8,192 UTF-16 units, codes to 96, parameters to 256 and paths to 2,048. A SHA-256 fingerprint distinguishes messages differing after the retained prefix. There is no automatic on-disk log stream.

Engine diagnostic listeners publish directly into the bounded store and one coalesced status slot. They do not enqueue an additional native UI callback for every diagnostic. Rendering, file IO and validation use stable subsystem/code routing where context is available. Legacy generic information notices continue to use APPLICATION_MESSAGE. Engine events without a known project identity retain their details but do not offer a potentially incorrect source-navigation action.

## Validation and source navigation

View > Validate Project opens a read-only result window with explicit valid confirmation, clipped issues, detail copying and relevant source/settings actions. Rejected Apply results are labelled Rejected edit; the authoritative committed document remains unchanged. Revert/selection refresh clears obsolete rejected-draft inline issues while grouped historical events remain available.

DiagnosticLocations enriches semantic validation results against the candidate that was validated. It resolves array indices, escaped JSON Pointer tokens and existing UUID-style paths before candidate order can change. It retains owner resource, nested processing/transform/shared-parameter or curve-point identity and parameter key. Existing explicit compiler context takes precedence over pointer fallback. The public Diagnostic record shape and project/user schemas remain unchanged.

CompilationFailure is an IllegalArgumentException with diagnostic() and the original cause. Processing and coordinate-transform compilation retain the innermost failing resource/control. A failing referenced definition keeps that definition's UUID instead of being attributed to its caller. Expression compilation names the expression parameter and preserves Expression.Problem as the cause, including its source span. Algorithm/shared-parameter boundaries also retain available context.

Go To Problem uses stored UUIDs, scoped to the originating project UUID. It never resolves an old array index against a newer document. It becomes unavailable when the resource/control is deleted, belongs to a different project, or is absent from the current selected draft. For a current draft, navigation preserves the draft object and provisional text; it collapses a multi-selection to the target resource without committing or creating undo history. Noise items expand and scroll using the existing control-row navigation, with a visible parameter/path location banner. Project-bound diagnostics without a resource can open Project Settings. Global/runtime problems without a supported destination retain details without an inert navigation button.

Source actions in Inspector, Logs, Notifications and Profiler now share Apply/Discard/Cancel draft protection. Trace targets retain snapshot fingerprints, resource/item UUIDs and logical setting keys; changed targets require a retrace. See [trace explanations](TRACE_EXPLANATIONS_AND_NAVIGATION.md). Full expression-input routing and the complete manual GUI acceptance matrix remain separate work.

## Validation evidence

Contract tests cover candidate path enrichment, escaped keys, nested operations and curve points, UUID paths and reordering, parser/transform/referenced compiler context, concurrent grouped publication and retention, long-message bounds, read/resolve/dismiss transitions, composed filters, deleted/stale targets, rejected-draft navigation, read-only validation and unchanged project/history/revision state. The native smoke explicitly rejects an invalid expression draft, navigates to its real control without discarding text, renders logs/notifications/validation, checks grouped occurrences and confirms the authoritative project is unchanged. These scoped checks do not certify all interactive recovery scenarios or Windows 11 hardware.

Binding diagnostics now retain an exact decoded `inputs/<name>` navigation key from structural and compiler paths. Properties selects and opens that binding row, including names containing JSON-pointer escapes, and requests a scroll for Go To Problem. Selection uses exact names so a prefix input cannot steal a nested-looking name. Parser span focusing and the full native recovery matrix remain unverified.

## Expression binding recovery

Processing input failures now retain `inputs/<name>` compiler context rather than being attributed to expression text. Missing required inputs receive the same route. An error inside a referenced definition still retains that inner definition. Compiler input pointers escape slash and tilde tokens; logical destination keys retain the authored name.

Inline expression feedback distinguishes malformed bindings from parser/type spans. Binding feedback offers Go To Input; parser feedback displays a zero-based, end-exclusive UTF-16 character range and Focus Expression. Go To Problem also focuses expression text and scrolls it into view. These actions preserve the private draft and do not publish history. The feedback Result record adds an optional input name, retaining its prior constructor; record-pattern/reflection users need to account for the added component.

The isolated ExpressionRecoveryProbe uses production controls and real mouse/keyboard events to check an inline input destination, rejected text preservation, keyboard repair via Focus Expression, one Apply and one Undo/Redo. An external engine-only consumer checks 81 independent samples of the saved repaired formula. This scoped workflow does not certify span selection, expanded text editing or the complete GUI recovery matrix.

## Expanded expression editing

Expand Expression opens a multiline text buffer separate from Properties. Cancel and Escape discard it. Use in Properties stages only expression text and creates no document history; the ordinary Apply validates the complete resource and commits one transaction. Invalid syntax can be staged for continued editing, and rejected Apply preserves that draft.

The editor retains project/revision, selected draft and item identity plus the original expression value/presence. Changed expressions, removed items, replaced drafts and changed projects reject staging rather than overwriting newer text. Other pending Properties fields are preserved. Inline and expanded controls clear their text cache after staging so they show the same source.

The buffer has fixed UTF-8 byte capacity sufficient for the language's 16,384 UTF-16 character limit. Staging rejects longer strings without truncation; oversized existing text refuses expanded opening. Live feedback includes binding errors or parser/type character ranges. This does not add token-span selection or a new serialization format.

ExpressionEditSessionTest covers private cancellation/staging, incomplete text, one Apply/Undo/Redo, stale ownership and Unicode/length boundaries. ExpandedExpressionProbe drives the production modal using real keyboard/mouse events, including newline entry, Cancel, Escape and private staging. The saved multiline formula is replayed at 81 points by the external engine-only consumer. Full manual GUI, DPI and hardware acceptance remain separate.


Named-input navigation now resolves exact binding leaves and focuses production editors, including coordinate components and custom UUID pickers. Each item's input selection is independent and navigation selects once, so later user choices remain stable. See [input binding navigation](INPUT_BINDING_NAVIGATION.md) for ownership, recovery and observed full-Properties scope.

The development branch now separates keyboard activation from scroll acknowledgement and routes registered source/manipulation controls, typed/shared constants and specialized warp bindings to their actual widgets. These changes and their deferred regression/native definitions are uncompiled and untested while GitHub testing remains paused. See [property control focus](PROPERTY_CONTROL_FOCUS.md).
