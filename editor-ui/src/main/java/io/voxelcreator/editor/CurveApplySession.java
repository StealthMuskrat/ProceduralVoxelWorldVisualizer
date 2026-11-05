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
  private record Prepared(CurveDraftEdit.Prepared value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean ready;
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
      boolean valid = current.getAsBoolean();
      if (!owns(request)) return;
      if (!valid) throw new IllegalStateException("Curve source changed; Cancel and reopen");
      input = ProjectIO.JSON.valueToTree(curve);
      var captured = input;
      long handed = ++delivery;
      handoff.advance(handed);
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
                if (!handoff.publish(handed, new Prepared(value, failure))) return null;
                ui.accept(() -> delivered(request, handed));
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
    var expected = input;
    return current.getAsBoolean()
        && expected != null
        && expected.equals(ProjectIO.JSON.valueToTree(local.get()));
  }

  private void delivered(Object request, long handed) {
    if (!owns(request) || delivery != handed || ready) return;
    refresh();
    if (!owns(request)) return;
    task = null;
    ready = true;
  }

  boolean ready() {
    refresh();
    return !closed && applying && ready;
  }

  boolean accept() {
    Object request = token;
    refresh();
    if (!owns(request) || !ready) return false;
    Prepared value;
    try {
      boolean valid = exact();
      if (!owns(request)) return false;
      if (!valid) {
        cancelApply();
        progress = "Curve or source changed. Apply the current curve explicitly.";
        return false;
      }
      value = handoff.take();
      if (value == null) throw new IllegalStateException("Curve Apply preparation unavailable");
      if (value.failure() != null) {
        fail(request, value.failure());
        return false;
      }
    } catch (Throwable rejected) {
      fail(request, rejected);
      return false;
    }
    retire();
    progress = "";
    applying = true;
    try {
      return publish(() -> preparedStage.accept(Objects.requireNonNull(value.value())));
    } catch (Throwable rejected) {
      fail(token, rejected);
      return false;
    } finally {
      applying = false;
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
    handoff.close();
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
    Object request = token;
    try {
      boolean valid = exact();
      if (!owns(request)) return;
      if (!valid) {
        cancelApply();
        progress = "Curve or source changed. Apply the current curve explicitly.";
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(request, done.failure());
    } catch (Throwable failure) {
      fail(request, failure);
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
    ready = false;
    handoff.advance(++delivery);
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
    handoff.close();
    applying = false;
  }

  @Override
  public void close() {
    cancel();
  }
}
