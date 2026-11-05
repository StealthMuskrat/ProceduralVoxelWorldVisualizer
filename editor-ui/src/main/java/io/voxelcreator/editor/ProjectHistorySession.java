package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Project history never implicitly applies or disposes of an owned Properties draft. */
final class ProjectHistorySession implements AutoCloseable {
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
    CANCELLED,
    PREPARING
  }

  private final EditorState state;
  private final BooleanSupplier blocked;
  private final Runnable refresh;
  private Direction pending;
  private ProjectFileOwner owner;
  private long requestVersion;
  private long generation;
  private boolean moving;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private FutureTask<Void> task;
  private record Prepared(DocumentSession.PreparedHistory value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery, workVersion;
  private boolean ready, admitted;
  private Object workToken = new Object();
  private boolean closed;

  ProjectHistorySession(EditorState state, BooleanSupplier blocked) {
    this(state, blocked, () -> state.reconcileSelection(state.multiSelection, state.selected));
  }

  ProjectHistorySession(EditorState state, BooleanSupplier blocked, Runnable refresh) {
    this(state, blocked, refresh, null, null);
  }

  ProjectHistorySession(
      EditorState state,
      BooleanSupplier blocked,
      Runnable refresh,
      Executor executor,
      Consumer<Runnable> ui) {
    this.state = state;
    this.blocked = Objects.requireNonNull(blocked);
    this.refresh = Objects.requireNonNull(refresh);
    this.executor = executor;
    this.ui = ui;
    if (executor != null) Objects.requireNonNull(ui);
  }

  long requestVersion() {
    return requestVersion;
  }

  boolean pending() {
    refreshBackground();
    return pending != null;
  }

  boolean busy() {
    return moving || pending();
  }

  Direction direction() {
    return pending;
  }

  boolean preparing() {
    refreshBackground();
    return task != null || ready;
  }

  long workVersion() {
    return workVersion;
  }

  private boolean available(Direction direction) {
    return direction == Direction.UNDO ? state.document.canUndo() : state.document.canRedo();
  }

  Result request(Direction direction) {
    Objects.requireNonNull(direction);
    if (closed || moving || pending() || !available(direction)) return Result.UNAVAILABLE;
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
      if (!(executor == null ? opening.current(state) : opening.valuesCurrent(state))
          || !available(direction)) {
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
    if (preparing()) return Result.UNAVAILABLE;
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
      if (denied
          || !(executor == null ? original.current(state) : original.valuesCurrent(state))
          || !available(direction)) {
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
    if (executor != null) return prepare(direction);
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

  private Result prepare(Direction direction) {
    pending = direction;
    workVersion++;
    Object token = workToken;
    try {
      owner = new ProjectFileOwner(state);
      var preparation =
          direction == Direction.UNDO ? state.document.requestUndo() : state.document.requestRedo();
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedHistory result = null;
                Throwable failure = null;
                try {
                  result = preparation.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(result, failure))) return null;
                ui.accept(() -> delivered(token, handed));
                return null;
              });
      executor.execute(task);
      if (ownsWork(token)) admitted = true;
      return Result.PREPARING;
    } catch (Throwable failure) {
      failBackground(token, failure);
      return Result.REJECTED;
    }
  }

  private boolean ownsWork(Object token) {
    return token == workToken && (task != null || ready) && pending != null && !closed;
  }

  private void delivered(Object token, long handed) {
    if (!ownsWork(token) || delivery != handed || ready) return;
    // A readiness receipt does not prove complete posting or successful admission.
    ready = true;
  }

  /** The shown history modal processes current cancellation before installing a history step. */
  void acceptPrepared() {
    Object token = workToken;
    refreshBackground();
    if (!ownsWork(token) || !ready || !admitted || moving) return;
    var completed = BackgroundFailure.completed(task);
    if (!ownsWork(token) || completed == null) return;
    if (completed.failure() != null) {
      failBackground(token, completed.failure());
      return;
    }
    var value = handoff.take();
    if (value == null) {
      failBackground(token, new IllegalStateException("History preparation unavailable"));
      return;
    }
    publish(token, pending, value.value(), value.failure());
  }

  private void publish(
      Object token,
      Direction direction,
      DocumentSession.PreparedHistory prepared,
      Throwable failure) {
    if (!ownsWork(token)) return;
    try {
      if (!owner.valuesCurrent(state)) {
        staleBackground();
        return;
      }
      if (failure != null) {
        failBackground(token, failure);
        return;
      }
      boolean denied = blocked.getAsBoolean();
      if (!ownsWork(token)) return;
      if (denied || !owner.valuesCurrent(state) || !state.document.currentHistory(prepared)) {
        staleBackground();
        return;
      }
      if (!prepared.success()) {
        failBackground(
            token,
            new IllegalStateException(
                "History candidate rejected ("
                    + prepared.diagnostics().size()
                    + " issues): "
                    + prepared.diagnostics().stream().limit(8).toList()));
        return;
      }
      // Retire confirmation authority before any publication/refresh callback, retaining the
      // existing non-reentrant final movement fence until selection reconciliation completes.
      moving = true;
      try {
        Object consumed = retire();
        if (closed || consumed != workToken) return;
        try {
          state.document.applyHistory(prepared);
        } catch (Throwable rejected) {
          reportBackground("PROJECT_HISTORY_FAILED", "History publication failed: ", rejected);
          return;
        }
        state.revision++;
        try {
          refresh.run();
          state.status =
              direction == Direction.UNDO ? "Project edit undone" : "Project edit redone";
        } catch (Throwable rejected) {
          reportBackground(
              "PROJECT_HISTORY_REFRESH_FAILED", "History moved; UI refresh failed: ", rejected);
        }
      } finally {
        moving = false;
      }
    } catch (Throwable rejected) {
      failBackground(token, rejected);
    }
  }

  private void refreshBackground() {
    if ((task == null && !ready) || closed) return;
    Object token = workToken;
    try {
      if (!owner.current(state)) {
        staleBackground();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (ownsWork(token) && completion != null && completion.failure() != null)
        failBackground(token, completion.failure());
    } catch (Throwable failure) {
      failBackground(token, failure);
    }
  }

  private void staleBackground() {
    Object invalidated = retire();
    if (closed || invalidated != workToken) return;
    state.status =
        "History preparation became stale. Request Undo or Redo again; current private input is"
            + " retained.";
  }

  private void failBackground(Object token, Throwable failure) {
    if (token != workToken || closed) return;
    Object failed = retire();
    if (closed || failed != workToken) return;
    String feedback =
        "History preparation failed; request Undo or Redo again: "
            + BackgroundFailure.describe(failure);
    if (closed || failed != workToken) return;
    try {
      state.error(
          "PROJECT_HISTORY_FAILED", EditorEvents.Subsystem.PROJECT,
          StatusBarText.clip(feedback, 2048));
    } catch (Throwable ignored) {
      /* Leave cancellation and a fresh request available. */
    }
  }

  private void reportBackground(String code, String prefix, Throwable failure) {
    try {
      state.error(
          code,
          EditorEvents.Subsystem.PROJECT,
          StatusBarText.clip(prefix + BackgroundFailure.describe(failure), 2048));
    } catch (Throwable ignored) {
      /* Leave cancellation and a fresh request available. */
    }
  }

  private void report(String code, String prefix, RuntimeException failure) {
    String detail = failure.getMessage();
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    state.error(code, EditorEvents.Subsystem.PROJECT, StatusBarText.clip(prefix + detail, 1024));
  }

  void clear() {
    retire();
  }

  private Object retire() {
    var previous = task;
    Object retired = new Object();
    workToken = retired;
    task = null;
    ready = admitted = false;
    handoff.advance(++delivery);
    workVersion++;
    generation++;
    pending = null;
    owner = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    clear();
    handoff.close();
  }
}
