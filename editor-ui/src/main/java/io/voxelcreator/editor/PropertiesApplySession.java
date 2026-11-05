package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Background candidate/compiler/delta work; exact UI and document owners guard one publication. */
final class PropertiesApplySession implements AutoCloseable {
  enum Phase {
    IDLE,
    RUNNING,
    APPLIED,
    REJECTED,
    FAILED,
    CANCELLED,
    STALE,
    CLOSED
  }

  record View(Phase phase, String message) {}

  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private Phase phase = Phase.IDLE;
  private String message = "";
  private Object token = new Object();
  private ProjectFileOwner owner;
  private List<Diagnostic> issues, issueValues;
  private String scope;
  private FutureTask<Void> task;
  private record Prepared(DocumentSession.PreparedEdit value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery, version;
  private boolean ready, admitted;

  PropertiesApplySession(
      EditorState state, Executor executor, Consumer<Runnable> ui, BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.blocked = Objects.requireNonNull(blocked);
  }

  View view() {
    refresh();
    return new View(phase, message);
  }

  boolean running() {
    refresh();
    return phase == Phase.RUNNING;
  }

  boolean start() {
    refresh();
    if (phase == Phase.RUNNING || phase == Phase.CLOSED) return false;
    retire();
    Object request = token;
    try {
      var preparation = state.requestDraftApply();
      if (preparation == null) {
        phase = Phase.REJECTED;
        message = "Correct Properties input and select one resource before Apply.";
        return false;
      }
      owner = new ProjectFileOwner(state);
      issues = state.issues;
      issueValues = List.copyOf(issues);
      scope = state.validationScope;
      phase = Phase.RUNNING;
      boolean unavailable = blocked.getAsBoolean();
      if (!owns(request)) return false;
      if (unavailable || !owner.valuesCurrent(state)) {
        stale();
        return false;
      }
      message =
          "Preparing Properties Apply in the background. Committed values remain active. Cancel"
              + " Apply or Escape to keep the edit private.";
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedEdit prepared = null;
                Throwable failure = null;
                try {
                  prepared = preparation.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(prepared, failure))) return null;
                ui.accept(() -> delivered(request, handed));
                return null;
              });
      executor.execute(task);
      if (owns(request)) admitted = true;
      return owns(request);
    } catch (Throwable failure) {
      fail(request, failure);
      return false;
    }
  }

  private boolean owns(Object request) {
    return request == token && phase == Phase.RUNNING;
  }

  long version() {
    return version;
  }

  private void delivered(Object request, long handed) {
    if (!owns(request) || delivery != handed || ready) return;
    // A receipt is not proof that posting or executor admission returned successfully.
    ready = true;
  }

  /** The visible footer and child editors decide current-frame cancellation before acceptance. */
  void acceptPrepared() {
    Object request = token;
    refresh();
    if (!owns(request) || !ready || !admitted) return;
    var completion = BackgroundFailure.completed(task);
    if (!owns(request) || completion == null) return;
    if (completion.failure() != null) {
      fail(request, completion.failure());
      return;
    }
    var value = handoff.take();
    if (value == null) {
      fail(request, new IllegalStateException("Properties Apply preparation unavailable"));
      return;
    }
    publish(request, value.value(), value.failure());
  }

  private boolean diagnosticsCurrent() {
    return issues == state.issues && Objects.equals(scope, state.validationScope);
  }

  private void publish(Object request, DocumentSession.PreparedEdit prepared, Throwable failure) {
    if (!owns(request)) return;
    try {
      if (!owner.valuesCurrent(state)
          || !diagnosticsCurrent()
          || !issueValues.equals(state.issues)) {
        stale();
        return;
      }
      if (failure != null) {
        fail(request, failure);
        return;
      }
      boolean unavailable = blocked.getAsBoolean();
      if (!owns(request)) return;
      if (unavailable
          || !owner.valuesCurrent(state)
          || !diagnosticsCurrent()
          || !issueValues.equals(state.issues)) {
        stale();
        return;
      }
      Objects.requireNonNull(prepared);
      if (!state.document.currentEdit(prepared)) {
        stale();
        return;
      }
      if (!prepared.success()) {
        var rejected = prepared.diagnostics();
        retire();
        phase = Phase.REJECTED;
        message =
            "Properties Apply was rejected. The edit remains private; correct it and Apply again.";
        state.rejectDraftApply(rejected);
        return;
      }
      try {
        state.document.applyEdit(prepared);
      } catch (IllegalStateException changed) {
        stale();
        return;
      }
      retire();
      phase = Phase.APPLIED;
      message = "Properties edit applied. Later edits remain private until Apply.";
      state.finishDraftApply();
    } catch (Throwable rejected) {
      if (phase == Phase.APPLIED) {
        message =
            "Properties edit committed; selection refresh failed: "
                + BackgroundFailure.describe(rejected);
        report("PROPERTIES_APPLY_REFRESH_FAILED", message);
      } else fail(request, rejected);
    }
  }

  private void refresh() {
    if (phase != Phase.RUNNING) return;
    Object request = token;
    try {
      if (!owner.current(state) || !diagnosticsCurrent() || !issueValues.equals(state.issues)) {
        stale();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (owns(request) && completion != null && completion.failure() != null)
        fail(request, completion.failure());
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  void cancel() {
    if (phase != Phase.RUNNING) return;
    retire();
    phase = Phase.CANCELLED;
    message = "Properties Apply cancelled. The project is unchanged and the edit remains private.";
  }

  private void stale() {
    retire();
    phase = Phase.STALE;
    message =
        "Properties Apply was cancelled because its document, inputs or pending action changed."
            + " Review the current draft and Apply again.";
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    Object failed = token;
    String feedback =
        "Properties Apply failed: "
            + BackgroundFailure.describe(failure)
            + ". The edit remains private; Apply again to retry.";
    if (failed != token || phase != Phase.FAILED) return;
    message = feedback;
    report("PROPERTIES_APPLY_FAILED", message);
  }

  private void report(String code, String text) {
    try {
      state.error(code, EditorEvents.Subsystem.PROJECT, text);
    } catch (Throwable ignored) {
      /* Keep correction/cancellation available. */
    }
  }

  private void retire() {
    var previous = task;
    token = new Object();
    task = null;
    ready = admitted = false;
    handoff.advance(++delivery);
    version++;
    owner = null;
    issues = issueValues = null;
    scope = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    phase = Phase.CLOSED;
    retire();
    handoff.close();
    message = "Properties Apply closed.";
  }
}
