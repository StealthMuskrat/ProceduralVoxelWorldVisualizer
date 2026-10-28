package io.voxelcreator.editor;

import imgui.ImGuiInputTextCallbackData;
import imgui.callback.ImGuiInputTextCallback;

/** Applies a selection without changing text or repeatedly resetting the user's cursor. */
final class ExpressionSelectionCallback extends ImGuiInputTextCallback {
  private final ExpressionSelection selection = new ExpressionSelection();
  private Object project, draft, item;
  private ExpressionSelection.Range applied;

  void context(Object project, Object draft, Object item, String currentSource) {
    if (this.project != project || this.draft != draft || this.item != item) cancel();
    this.project = project;
    this.draft = draft;
    this.item = item;
    selection.synchronize(project, draft, item, currentSource);
  }

  void request(String source, ExpressionFeedback.Result feedback) {
    cancel();
    if (!feedback.valid() && feedback.input() == null)
      selection.request(project, draft, item, source, feedback.start(), feedback.end());
  }

  void cancel() {
    selection.cancel();
    applied = null;
  }

  ExpressionSelection.Range applied() {
    return applied;
  }

  @Override
  public void accept(ImGuiInputTextCallbackData data) {
    if (!selection.pending()) return;
    var range = selection.take(project, draft, item, data.getBuf());
    if (range == null) return;
    data.setSelectionStart(range.start());
    data.setSelectionEnd(range.end());
    data.setCursorPos(range.end());
    applied = range;
  }
}
