# Expanded expression layout and error recovery

Status: implemented on `codex/development-phase`, **uncompiled and untested** under the owner pause. No build, JUnit/native probe, independent replay or GitHub workflow ran for this batch. The validated baseline remains 922 tests / 120 suites. Full GUI/DPI and Windows 11 acceptance remain pending.

## Scrolling content and persistent decisions

The Expanded Expression modal retains its existing Properties host, title/INI identity, popup fitting and private ExpressionEditSession. A scrolling body now contains the explanation, multiline field, character count and a separately scrolling feedback viewport. Five measured layout definitions describe an editor viewport capped at eight outer line heights and half the body, and feedback capped at four lines and 30% of the body. Both retain a one-pixel native minimum when space is exhausted. Fixed 140/175-pixel editor/tail budgets are removed. Long guidance or errors can scroll without moving decision controls out of reach.

Two persistent rows hold Use Text / Cancel, then full-width Show Error. Use Text is the shorter label for the existing Use in Properties action: it stages only the exact displayed buffer into the owning Properties draft. It does not apply or save the project, perform a new commit, or require syntactically valid provisional text. Ordinary Properties Apply remains responsible for complete enabled-definition validation. Existing 16,384-character staging/parser and 65,536-character provisional limits remain unchanged. Oversized or mismatched displayed text cannot stage an earlier accepted value.

Cancel or Escape is handled before a same-frame staging request. Successful staging still consumes its session before the refresh callback; a refresh failure reports a distinct event and closes without repeating the accepted stage. Failed staging leaves the complete private buffer available for correction. Source/selection changes retain text and Cancel while disabling Use Text; equal-value replacement parameter nodes still require reopening.

## Error reveal ownership

Show Error selects a current parser/type span when the complete displayed text exactly matches the feedback source. Named-input failures, source changes, limits and other non-span failures reveal feedback instead of inventing a text range. The body scrolls to the appropriate viewport. The existing inline Go To Input action retains its separate route; the expanded modal does not replace the Properties owner to navigate to another input.

The shared callback delegates to a pure ExpressionErrorSelection owner. A request belongs to exact project/draft/item identities and source text. It remains pending through unchanged contexts while the native multiline field is clipped, so focus can be retried after scrolling. The native callback consumes only a matching actual buffer, applies the UTF-8 range and stops requesting focus. Replaced owners, a different source, native-buffer mismatch or explicit cancellation discard the request. Editing text also clears the last acknowledged range, preventing an old highlight receipt from describing new text. Equal-value String objects retain the same source meaning; whitespace is not normalized.

The established UTF-16-to-UTF-8 conversion, surrogate-pair boundaries and EOF carets remain owned by ExpressionSelection. Selection changes no text or buffer-dirty flag. Initial editor focus is also retained until its field becomes active/focused. It does not repeatedly reactivate an already acknowledged selection. The inline Properties expression callback gains the same source/range invalidation while keeping its existing navigation/focus owner.

## Deferred definitions and limits

Eleven pure definitions comprise five ExpressionEditorLayoutTest and six ExpressionErrorSelectionTest cases. They describe independent row/share/scaling/extreme vectors, clipped-frame retention, one-shot acknowledgement, Unicode byte ranges, exact displayed/source matching, changed source and replacement owners, native mismatch, explicit retry/cancel and parser length bounds. None has run.

ExpressionLayoutProbe defines six native modal size/user-scale cases, including narrow, short and composed 6.25 scale. It attempts body scrolling, activates persistent Show Error and records an actual acknowledged Unicode parser range, a visible editor intersection, canonical widget identity and persistent geometry. Further native definitions cover simultaneous Stage/Escape cancellation, refresh failure consumption, oversized correction/retry, equal-value stale source replacement, named-input feedback and deliberately incomplete text-only staging. All retain a separate private resource ID/raw seed and unchanged committed project/history/engine/profiler.

scripts/verify_expression_layout.py independently describes the geometry, measured viewport caps, UTF-8 range and unchanged committed fixture. Future Linux and bundled Windows workflow steps include the scenario, replay, artifact directory and the existing isolated engine consumer at 81 analytical `x + 2` points. Definitions and replay remain unexecuted; workflow triggers are unchanged and this development branch does not trigger them.

The fixture uses a native ImGui context with synthetic user scale and no GLFW/GL renderer. It does not certify physical keyboard/IME, screen pixels, every clipped-focus path, native internal multiline font inheritance, GPU font upload, detached displays, mixed DPI, every expression control or complete Windows 11 GUI acceptance. These certification flags remain false. Body content can lie outside the current scroll viewport; outer/persistent geometry and an acknowledged range do not certify all content is simultaneously visible.
