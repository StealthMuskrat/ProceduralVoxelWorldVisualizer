package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.ScalarCurve;
import io.voxelcreator.model.ProjectData.Curve;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Accepted staging is consumed before UI consequences; worker results require exact authority. */
final class CurveApplySession implements AutoCloseable {
  private final Consumer<Curve> stage;
  private final Runnable accepted;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier current;
  private final Supplier<Curve> local;
  private final Consumer<CurveDraftEdit.Prepared> preparedStage;
  private final Function<JsonNode, CurveDraftEdit.Prepared> prepare;
  private boolean closed, applying;
  private String error = "", progress = "";
  private RuntimeException followUpFailure;
  private FutureTask<Void> task;
  private JsonNode input;
  private CurveDraftEdit.Prepared prepared;
  private Object token = new Object();
  private long version;

  CurveApplySession(Consumer<Curve> stage, Runnable accepted) {
    this.stage = Objects.requireNonNull(stage);
    this.accepted = Objects.requireNonNull(accepted);
    executor = null;
    ui = null;
    current = null;
    local = null;
    preparedStage = null;
    prepare = null;
  }

  CurveApplySession(
      CurveDraftEdit owner,
      EditorState state,
      Runnable accepted,
      Executor executor,
      Consumer<Runnable> ui,
      Supplier<Curve> local) {
    this(
        accepted,
        executor,
        ui,
        () -> owner.current(state),
        local,
        value -> owner.stagePrepared(state, value),
        CurveDraftEdit::prepare);
  }

  CurveApplySession(
      Runnable accepted,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier current,
      Supplier<Curve> local,
      Consumer<CurveDraftEdit.Prepared> preparedStage,
      Function<JsonNode, CurveDraftEdit.Prepared> prepare) {
    stage = null;
    this.accepted = Objects.requireNonNull(accepted);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.current = Objects.requireNonNull(current);
    this.local = Objects.requireNonNull(local);
    this.preparedStage = Objects.requireNonNull(preparedStage);
    this.prepare = Objects.requireNonNull(prepare);
  }

  boolean apply(Curve curve) {
    if (closed || applying) throw new IllegalStateException("Curve publication is closed or busy");
    if (executor != null) {
      start(curve);
      return closed;
    }
    applying = true;
    try {
      return publish(
          () -> {
            var candidate =
                ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(curve), Curve.class);
            new ScalarCurve(candidate);
            stage.accept(candidate);
          });
    } finally {
      applying = false;
    }
  }

  private void start(Curve curve) {
    retire();
    error = "";
    applying = true;
    progress =
        "Preparing Curve Apply in the background. Editing cancels this Apply; Cancel Apply"
            + " or Escape keeps the private curve open.";
    Object request = token;
    try {
      if (!current.getAsBoolean())
        throw new IllegalStateException("Curve source changed; Cancel and reopen");
      input = ProjectIO.JSON.valueToTree(curve);
      var captured = input;
      task =
          new FutureTask<>(
              () -> {
                CurveDraftEdit.Prepared value = null;
                Throwable failure = null;
                try {
                  value = Objects.requireNonNull(prepare.apply(captured.deepCopy()));
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var completed = value;
                var rejected = failure;
                ui.accept(() -> complete(request, completed, rejected));
                return null;
              });
      executor.execute(task);
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private boolean owns(Object request) {
    return request == token && applying && !closed;
  }

  private boolean exact() {
    return current.getAsBoolean() && input.equals(ProjectIO.JSON.valueToTree(local.get()));
  }

  private void complete(Object request, CurveDraftEdit.Prepared value, Throwable failure) {
    if (!owns(request)) return;
    try {
      if (!exact()) {
        cancelApply();
        progress = "Curve or source changed. Apply the current curve explicitly.";
        return;
      }
      if (failure != null) {
        fail(request, failure);
        return;
      }
      // Delivery only makes the result available. The dialog decides after Cancel/Escape input.
      token = new Object();
      task = null;
      prepared = value;
    } catch (Throwable rejected) {
      fail(token, rejected);
    }
  }

  boolean ready() {
    refresh();
    return !closed && applying && prepared != null;
  }

  boolean accept() {
    if (!ready()) return false;
    try {
      var value = prepared;
      retire();
      progress = "";
      applying = true;
      try {
        return publish(() -> preparedStage.accept(value));
      } finally {
        applying = false;
      }
    } catch (Throwable rejected) {
      fail(token, rejected);
      return false;
    }
  }

  private boolean publish(Runnable publication) {
    try {
      publication.run();
    } catch (RuntimeException rejected) {
      error =
          StatusBarText.bounded(
              Objects.requireNonNullElse(rejected.getMessage(), rejected.toString()), 2048);
      return false;
    }
    closed = true;
    error = "";
    progress = "";
    try {
      accepted.run();
    } catch (RuntimeException failure) {
      followUpFailure = failure;
    }
    return true;
  }

  void refresh() {
    if (executor == null || !applying || closed) return;
    try {
      if (!exact()) {
        cancelApply();
        progress = "Curve or source changed. Apply the current curve explicitly.";
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(token, done.failure());
    } catch (Throwable failure) {
      fail(token, failure);
    }
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || closed) return;
    retire();
    applying = false;
    progress = "";
    error =
        "Curve Apply failed: "
            + BackgroundFailure.describe(failure)
            + ". Retry Apply explicitly or Cancel.";
    error = StatusBarText.bounded(error, 2048);
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    input = null;
    prepared = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  void cancelApply() {
    if (closed || executor == null || !applying) return;
    retire();
    applying = false;
    error = "";
    progress = "Curve Apply cancelled. Private curve edits are preserved.";
  }

  boolean running() {
    refresh();
    return applying;
  }

  long version() {
    return version;
  }

  boolean closed() {
    return closed;
  }

  String progress() {
    return progress;
  }

  String error() {
    return error;
  }

  RuntimeException followUpFailure() {
    return followUpFailure;
  }

  void cancel() {
    closed = true;
    retire();
    applying = false;
  }

  @Override
  public void close() {
    cancel();
  }
}
