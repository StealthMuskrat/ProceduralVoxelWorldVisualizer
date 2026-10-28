# Expression error selection

Status: **implemented, awaiting validation** on `codex/development-phase`. The owner has paused GitHub testing until the program is fully developed. This source and its verification definitions have not been compiled or executed. The last validated baseline remains unchanged.

The inline **Focus Expression** action now selects the current parser/type error range when activating the expression field. Other expression problem links use the same current-feedback route. The expanded multiline editor offers **Select Expression Error**. Empty spans, including an incomplete expression at end of input, place a caret without inventing a token. Named-binding failures retain their separate **Go To Input** route.

Parser spans remain zero-based UTF-16 offsets with an exclusive end. The UI converts the selected range to ImGui callback UTF-8 byte positions. It clamps invalid bounds and keeps surrogate pairs intact; a caret inside a pair moves to its beginning, while a nonempty selection includes the complete pair. The parser, language, project format and public engine APIs are unchanged. A whole-expression type failure can select the whole expression.

Selection is a one-shot request belonging to the exact project, private Properties draft, item and source text. The callback checks the actual native buffer too. Replaced owners, changed draft text and mismatched buffers discard pending selection. Following another destination, clearing the location, explicitly choosing another row/input, or opening the expanded editor cancels inline selection. The expanded editor checks its existing session ownership before attempting focus. Selection changes cursor/selection positions only; subsequent typing follows the ordinary provisional edit path. It does not set buffer-dirty, apply, save, modify history or discard private edits. A later frame does not reset the user's selection.

The inline expression buffer now reserves the same UTF-8 capacity as the expanded editor, enough for the existing 16,384 UTF-16-character language bound. Longer provisional text still fails ordinary parser/Apply validation. The one pending selection retains at most one bounded expression and drops it on consumption/cancellation.

## Deferred verification definitions

`ExpressionSelectionTest` defines seven contracts for ASCII/multiline/Unicode byte positions, EOF and clamping, surrogate pairs, one-shot consumption, exact owner/source replacement, request replacement/cancellation/length limits and changed model text with a still-old native buffer. `ExpressionFeedbackTest` adds a parser-to-selection case for a Unicode binding prefix and an end-of-input span.

The existing `ExpressionRecoveryProbe` now repairs only the selected unknown token after a Unicode prefix, then appends more text across later frames to catch repeated selection. Its saved field is `x+é+bias+0`, with both constants 2. `ExpandedExpressionProbe` selects a multiline Unicode-prefixed error, replaces only that token, appends more text and cancels the private buffer, then continues its established stage/Apply/Undo/Redo workflow. `verify_expression_selection.py` defines report and schema/saved-field checks. The existing engine-only consumer still independently expects `x+4` and `x+6` over 81 points each. Future workflow entries reference these definitions; none has been run during this batch.

Native callback integration, physical keyboard/IME behavior, text scrolling across arbitrary error locations, DPI/platform cases and the complete expression GUI acceptance matrix remain unvalidated. No acceptance gate or test count is promoted by these definitions.

## Binding API reference

The adapter uses the pinned imgui-java 1.89.0 `inputText`/`inputTextMultiline` callback overloads and `CallbackAlways`, with cursor/selection setters from [ImGuiInputTextCallbackData](https://github.com/SpaiR/imgui-java/blob/v1.89.0/imgui-binding/src/main/java/imgui/ImGuiInputTextCallbackData.java). This source inspection establishes the intended integration, not execution evidence.

Development-phase buffer-bound follow-up: expanded expression feedback now rejects text above 16,384 UTF-16 characters before copying/compiling a candidate. Provisional edits retain up to 65,536 UTF-16 characters for correction; larger programmatic edits reject before replacement. The native dialog catches edit failures and verifies displayed/session text equality before staging, preventing fallback to an earlier accepted buffer. Existing incomplete-syntax staging and ordinary parent Apply validation remain unchanged. Three ExpressionBufferBoundsTest definitions cover rejection/correction, hard bounds, Unicode and displayed-buffer mismatch. This implementation is uncompiled/untested; oversized native paste/IME behavior remains pending under the owner testing pause.

[Expanded expression workspace ownership](EXPRESSION_WORKSPACE_OWNERSHIP.md) subsequently adds exact editor/source/selection ownership, consumed staging before refresh, retained second-open buffers, bounded non-null errors and live candidate reuse. Its ten additional regression definitions and expanded native/report definitions remain uncompiled/untested under the owner testing pause.
