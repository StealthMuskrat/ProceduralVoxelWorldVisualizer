package io.voxelcreator.editor;

import java.util.*;

/** Local bounded raw-text deltas; one continuous control gesture is one history action. */
final class ProjectSettingsHistory {
  record Change(String field, String before, String after) {}

  private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private Change pending;
  private String gesture;

  void record(String field, String before, String after, String group) {
    if (group == null) {
      finish();
      push(new Change(field, before, after));
      return;
    }
    if (pending != null && (!pending.field().equals(field) || !group.equals(gesture))) finish();
    pending = new Change(field, pending == null ? before : pending.before(), after);
    gesture = group;
  }

  void focus(String group) {
    if (pending != null && !Objects.equals(gesture, group)) finish();
  }

  private boolean changedPending() {
    return pending != null && !pending.before().equals(pending.after());
  }

  void finish() {
    if (changedPending()) push(pending);
    pending = null;
    gesture = null;
  }

  private void push(Change change) {
    if (change.before().equals(change.after())) return;
    undo.push(change);
    redo.clear();
    while (undo.size() > 100) undo.removeLast();
  }

  boolean canUndo() {
    return changedPending() || !undo.isEmpty();
  }

  boolean canRedo() {
    return !changedPending() && !redo.isEmpty();
  }

  Change move(boolean forward) {
    finish();
    var from = forward ? redo : undo;
    var to = forward ? undo : redo;
    if (from.isEmpty()) return null;
    var change = from.pop();
    to.push(change);
    return change;
  }

  void clear() {
    undo.clear();
    redo.clear();
    pending = null;
    gesture = null;
  }
}
