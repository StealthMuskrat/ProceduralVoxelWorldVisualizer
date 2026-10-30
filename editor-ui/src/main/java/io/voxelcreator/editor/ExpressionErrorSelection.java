package io.voxelcreator.editor;

import java.util.Objects;

/** Exact displayed source owns pending focus and the last acknowledged parser selection. */
final class ExpressionErrorSelection {
  private final ExpressionSelection selection = new ExpressionSelection();
  private Object project, draft, item;
  private String source;
  private ExpressionSelection.Range applied;

  void context(Object project, Object draft, Object item, String source) {
    Objects.requireNonNull(source);
    if (this.project != project
        || this.draft != draft
        || this.item != item
        || !Objects.equals(this.source, source)) cancel();
    this.project = project;
    this.draft = draft;
    this.item = item;
    this.source = source;
  }

  void request(String displayed, int start, int end) {
    cancel();
    if (project == null || draft == null || item == null || !Objects.equals(source, displayed))
      return;
    selection.request(project, draft, item, displayed, start, end);
  }

  ExpressionSelection.Range take(String actual) {
    if (!selection.pending()) return null;
    var range = selection.take(project, draft, item, actual);
    applied = range;
    return range;
  }

  void cancel() {
    selection.cancel();
    applied = null;
  }

  boolean pending() {
    return selection.pending();
  }

  ExpressionSelection.Range applied() {
    return applied;
  }
}
