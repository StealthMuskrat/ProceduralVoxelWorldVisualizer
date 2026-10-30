package io.voxelcreator.editor;

import imgui.ImGuiInputTextCallbackData;
import imgui.callback.ImGuiInputTextCallback;

/** Applies a selection without changing text or repeatedly resetting the user's cursor. */
final class ExpressionSelectionCallback extends ImGuiInputTextCallback {
  private final ExpressionErrorSelection selection = new ExpressionErrorSelection();

  void context(Object project, Object draft, Object item, String currentSource) {
    selection.context(project, draft, item, currentSource);
  }

  void request(String source, ExpressionFeedback.Result feedback) {
    cancel();
    if (!feedback.valid() && feedback.input() == null)
      selection.request(source, feedback.start(), feedback.end());
  }

  void cancel() {
    selection.cancel();
  }

  ExpressionSelection.Range applied() {
    return selection.applied();
  }

  boolean pending() {
    return selection.pending();
  }

  @Override
  public void accept(ImGuiInputTextCallbackData data) {
    if (!selection.pending()) return;
    var range = selection.take(data.getBuf());
    if (range == null) return;
    data.setSelectionStart(range.start());
    data.setSelectionEnd(range.end());
    data.setCursorPos(range.end());
  }
}
