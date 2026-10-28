package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Project history never implicitly applies or disposes of an owned Properties draft. */
final class ProjectHistorySession {
  enum Direction {
    UNDO,
    REDO
  }

  enum Decision {
    DISCARD,
    CANCEL
  }

  enum Result {
    MOVED,
    NEEDS_DECISION,
    UNAVAILABLE,
    REJECTED,
    CANCELLED
  }

  private final EditorState state;
  private final BooleanSupplier blocked;
  private Direction pending;
  private ProjectFileOwner owner;

  ProjectHistorySession(EditorState state, BooleanSupplier blocked) {
    this.state = state;
    this.blocked = Objects.requireNonNull(blocked);
  }

  boolean pending() {
    return pending != null;
  }

  Direction direction() {
    return pending;
  }

  private boolean available(Direction direction) {
    return direction == Direction.UNDO ? state.document.canUndo() : state.document.canRedo();
  }

  Result request(Direction direction) {
    Objects.requireNonNull(direction);
    if (pending() || !available(direction)) return Result.UNAVAILABLE;
    if (blocked.getAsBoolean()) {
      state.status =
          "Finish the private editor or pending project action before using project history";
      return Result.UNAVAILABLE;
    }
    if (modified()) {
      pending = direction;
      owner = new ProjectFileOwner(state);
      return Result.NEEDS_DECISION;
    }
    return move(direction);
  }

  private boolean modified() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var source = ResourceGraph.find(state.project(), state.selected);
    return source == null
        || !ProjectIO.JSON
            .valueToTree(source.resource())
            .equals(ProjectIO.JSON.valueToTree(state.draft));
  }

  Result decide(Decision decision) {
    if (!pending()) return Result.UNAVAILABLE;
    if (decision == Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    var direction = pending;
    if (!owner.current(state) || blocked.getAsBoolean() || !available(direction)) {
      clear();
      state.status = "Project history or draft changed; request Undo or Redo again";
      return Result.UNAVAILABLE;
    }
    return move(direction);
  }

  private Result move(Direction direction) {
    try {
      if (direction == Direction.UNDO) state.document.undo();
      else state.document.redo();
    } catch (RuntimeException failure) {
      clear();
      state.error(
          "PROJECT_HISTORY_FAILED",
          EditorEvents.Subsystem.PROJECT,
          Objects.requireNonNullElse(failure.getMessage(), failure.toString()));
      return Result.REJECTED;
    }
    clear();
    state.revision++;
    state.reconcileSelection(state.multiSelection, state.selected);
    state.status = direction == Direction.UNDO ? "Project edit undone" : "Project edit redone";
    return Result.MOVED;
  }

  void clear() {
    pending = null;
    owner = null;
  }
}
