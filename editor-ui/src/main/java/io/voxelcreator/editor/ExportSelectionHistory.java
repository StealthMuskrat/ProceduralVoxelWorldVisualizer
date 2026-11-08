package io.voxelcreator.editor;

import java.util.*;

/** Bounded deltas for explicit export UUID choices; dependency coverage is always rebuilt. */
final class ExportSelectionHistory {
  private static final int MAX_STEPS = 100, MAX_REFERENCES = 65_536;

  private record Change(Set<UUID> added, Set<UUID> removed) {
    long size() {
      return (long) added.size() + removed.size();
    }

    Set<UUID> apply(Set<UUID> values, boolean forward) {
      var next = new LinkedHashSet<>(values);
      next.removeAll(forward ? removed : added);
      next.addAll(forward ? added : removed);
      return Set.copyOf(next);
    }
  }

  private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private long references;

  void record(Set<UUID> before, Set<UUID> after) {
    if (before.equals(after)) return;
    var added = new HashSet<>(after);
    added.removeAll(before);
    var removed = new HashSet<>(before);
    removed.removeAll(after);
    var change = new Change(Set.copyOf(added), Set.copyOf(removed));
    for (var discarded : redo) references -= discarded.size();
    redo.clear();
    undo.push(change);
    references += change.size();
    // Retain the newest bulk action even when it alone exceeds the soft reference budget.
    while (undo.size() > MAX_STEPS || references > MAX_REFERENCES && undo.size() > 1)
      references -= undo.removeLast().size();
  }

  boolean canUndo() {
    return !undo.isEmpty();
  }

  boolean canRedo() {
    return !redo.isEmpty();
  }

  Set<UUID> move(Set<UUID> values, boolean forward) {
    var from = forward ? redo : undo;
    var to = forward ? undo : redo;
    if (from.isEmpty()) return null;
    var change = from.peek();
    var next = change.apply(values, forward);
    from.pop();
    to.push(change);
    return next;
  }

  void clear() {
    undo.clear();
    redo.clear();
    references = 0;
  }
}
