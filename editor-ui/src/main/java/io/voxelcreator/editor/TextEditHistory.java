package io.voxelcreator.editor;

import java.util.*;

/** Local bounded raw-text deltas; one continuous control gesture is one history action. */
final class TextEditHistory {
  record Change(String field, String before, String after) {
    long characters() {
      return (long) before.length() + after.length();
    }
  }

  private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private Change pending;
  private String gesture;
  private long characters;

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
    for (var discarded : redo) characters -= discarded.characters();
    redo.clear();
    undo.push(change);
    characters += change.characters();
    // Retain the newest oversized edit, plus at most one unfinished gesture.
    while (undo.size() > 100 || characters > 65536 && undo.size() > 1)
      characters -= undo.removeLast().characters();
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
    characters = 0;
  }
}
