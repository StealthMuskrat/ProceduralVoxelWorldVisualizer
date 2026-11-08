package io.voxelcreator.editor;

import java.util.*;

/** Dialog-local add/remove deltas retain member order without retaining full selection snapshots. */
final class MembershipHistory {
  private static final int MAX_STEPS = 100, MAX_REFERENCES = 65_536;

  private record Position(UUID id, int index) {}

  private record Change(List<Position> added, List<Position> removed) {
    int size() {
      return added.size() + removed.size();
    }

    void apply(List<UUID> values, boolean forward) {
      var erase = forward ? removed : added;
      var insert = forward ? added : removed;
      var ids = new HashSet<UUID>();
      erase.forEach(position -> ids.add(position.id()));
      values.removeIf(ids::contains);
      for (var position : insert) values.add(position.index(), position.id());
    }
  }

  private final Deque<Change> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
  private long references;

  void record(List<UUID> before, List<UUID> after) {
    if (before.equals(after)) return;
    var oldIds = new HashSet<>(before);
    var newIds = new HashSet<>(after);
    var added = new ArrayList<Position>();
    var removed = new ArrayList<Position>();
    for (int i = 0; i < before.size(); i++)
      if (!newIds.contains(before.get(i))) removed.add(new Position(before.get(i), i));
    for (int i = 0; i < after.size(); i++)
      if (!oldIds.contains(after.get(i))) added.add(new Position(after.get(i), i));
    var change = new Change(List.copyOf(added), List.copyOf(removed));
    for (var discarded : redo) references -= discarded.size();
    redo.clear();
    undo.push(change);
    references += change.size();
    // Keep the latest bulk action undoable even when it alone exceeds the reference budget.
    while (undo.size() > MAX_STEPS || references > MAX_REFERENCES && undo.size() > 1)
      references -= undo.removeLast().size();
  }

  boolean canUndo() {
    return !undo.isEmpty();
  }

  boolean canRedo() {
    return !redo.isEmpty();
  }

  boolean undo(List<UUID> values) {
    if (undo.isEmpty()) return false;
    var change = undo.pop();
    change.apply(values, false);
    redo.push(change);
    return true;
  }

  boolean redo(List<UUID> values) {
    if (redo.isEmpty()) return false;
    var change = redo.pop();
    change.apply(values, true);
    undo.push(change);
    return true;
  }

  void clear() {
    undo.clear();
    redo.clear();
    references = 0;
  }
}
