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
  private final Runnable refresh;
  private Direction pending;
  private ProjectFileOwner owner;
  private long requestVersion;
  private long generation;
  private boolean moving;

  ProjectHistorySession(EditorState state, BooleanSupplier blocked) {
    this(state, blocked, () -> state.reconcileSelection(state.multiSelection, state.selected));
  }

  ProjectHistorySession(EditorState state, BooleanSupplier blocked, Runnable refresh) {
    this.state = state;
    this.blocked = Objects.requireNonNull(blocked);
    this.refresh = Objects.requireNonNull(refresh);
  }

  long requestVersion() {
    return requestVersion;
  }

  boolean pending() {
    return pending != null;
  }

  boolean busy() {
    return moving || pending();
  }

  Direction direction() {
    return pending;
  }

  private boolean available(Direction direction) {
    return direction == Direction.UNDO ? state.document.canUndo() : state.document.canRedo();
  }

  Result request(Direction direction) {
    Objects.requireNonNull(direction);
    if (moving || pending() || !available(direction)) return Result.UNAVAILABLE;
    long attempt = generation;
    try {
      var opening = new ProjectFileOwner(state);
      boolean denied = blocked.getAsBoolean();
      if (generation != attempt || pending()) return Result.UNAVAILABLE;
      if (denied) {
        state.status =
            "Finish the private editor or pending project action before using project history";
        return Result.UNAVAILABLE;
      }
      if (!opening.current(state) || !available(direction)) {
        state.status = "Project history or draft changed; request Undo or Redo again";
        return Result.UNAVAILABLE;
      }
      boolean dirty = modified();
      requestVersion++;
      generation++;
      if (dirty) {
        pending = direction;
        owner = opening;
        return Result.NEEDS_DECISION;
      }
      return move(direction);
    } catch (RuntimeException failure) {
      report("PROJECT_HISTORY_GUARD_FAILED", "Project history request failed: ", failure);
      return Result.REJECTED;
    }
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
    if (moving || !pending()) return Result.UNAVAILABLE;
    if (decision == Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    var direction = pending;
    var original = owner;
    long attempt = generation;
    try {
      if (!original.current(state)) {
        clear();
        state.status = "Project history or draft changed; request Undo or Redo again";
        return Result.UNAVAILABLE;
      }
      boolean denied = blocked.getAsBoolean();
      if (generation != attempt || owner != original || pending != direction)
        return Result.UNAVAILABLE;
      if (denied || !original.current(state) || !available(direction)) {
        clear();
        state.status = "Project history or draft changed; request Undo or Redo again";
        return Result.UNAVAILABLE;
      }
      return move(direction);
    } catch (RuntimeException failure) {
      if (generation == attempt) clear();
      report("PROJECT_HISTORY_GUARD_FAILED", "Project history decision failed: ", failure);
      return Result.REJECTED;
    }
  }

  private Result move(Direction direction) {
    // Retire the decision before model validation; keep other history/file work fenced until
    // refresh.
    clear();
    moving = true;
    try {
      try {
        if (direction == Direction.UNDO) state.document.undo();
        else state.document.redo();
      } catch (RuntimeException failure) {
        report("PROJECT_HISTORY_FAILED", "Project history failed: ", failure);
        return Result.REJECTED;
      }
      state.revision++;
      try {
        refresh.run();
        state.status = direction == Direction.UNDO ? "Project edit undone" : "Project edit redone";
      } catch (RuntimeException failure) {
        report(
            "PROJECT_HISTORY_REFRESH_FAILED",
            (direction == Direction.UNDO ? "Undo" : "Redo") + " completed; UI refresh failed: ",
            failure);
      }
      return Result.MOVED;
    } finally {
      moving = false;
    }
  }

  private void report(String code, String prefix, RuntimeException failure) {
    String detail = failure.getMessage();
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    state.error(code, EditorEvents.Subsystem.PROJECT, StatusBarText.clip(prefix + detail, 1024));
  }

  void clear() {
    generation++;
    pending = null;
    owner = null;
  }
}
