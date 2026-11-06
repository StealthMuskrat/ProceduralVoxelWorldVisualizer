package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** UI-thread owner of a read-only committed snapshot and its background compiler result. */
final class ProjectValidationSession implements AutoCloseable {
  enum Phase {
    IDLE,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,
    STALE,
    CLOSED
  }

  record View(Phase phase, String message) {}

  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Function<ProjectData, List<Diagnostic>> validator;
  private final Consumer<String> errors;
  private Phase phase = Phase.IDLE;
  private String message = "Validate the committed project when requested.";
  private Object token = new Object();
  private FutureTask<Void> task;
  private record Prepared(List<Diagnostic> issues, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean preparedReady, admitted;
  private Owner owner;

  private static final class Owner {
    final Object lifetime;
    final ProjectData project;
    final long revision;
    final List<Diagnostic> issues, issueValues;
    final String scope;
    final JsonNode values;

    Owner(EditorState state, JsonNode snapshot) {
      lifetime = state.document.sessionToken();
      project = state.project();
      revision = state.revision;
      issues = state.issues;
      issueValues = List.copyOf(issues);
      scope = state.validationScope;
      values = snapshot;
    }

    boolean current(EditorState state) {
      return lifetime == state.document.sessionToken()
          && project == state.project()
          && revision == state.revision
          && issues == state.issues
          && Objects.equals(scope, state.validationScope);
    }

    boolean valuesCurrent(EditorState state) {
      return issueValues.equals(state.issues)
          && values.equals(ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  ProjectValidationSession(EditorState state) {
    this(
        state,
        state.background,
        state.uiQueue::offer,
        NoiseCompiler::validate,
        text -> state.error("PROJECT_VALIDATION_FAILED", EditorEvents.Subsystem.PROJECT, text));
  }

  ProjectValidationSession(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Function<ProjectData, List<Diagnostic>> validator,
      Consumer<String> errors) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.validator = Objects.requireNonNull(validator);
    this.errors = Objects.requireNonNull(errors);
  }

  Object requestToken() {
    return token;
  }

  View view() {
    refresh();
    return new View(phase, message);
  }

  boolean start() {
    refresh();
    if (phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    Object request = retire();
    if (request != token || phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    try {
      var snapshot = ProjectIO.copy(state.project());
      var captured = new Owner(state, ProjectIO.JSON.valueToTree(snapshot));
      if (request != token || phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
      owner = captured;
      phase = Phase.RUNNING;
      message = "Validating a committed snapshot in the background. Previous results remain below.";
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                List<Diagnostic> result = null;
                Throwable failure = null;
                try {
                  result = List.copyOf(validator.apply(snapshot));
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(result, failure))) return null;
                ui.accept(() -> delivered(request, handed));
                return null;
              });
      executor.execute(task);
      if (request == token && phase == Phase.RUNNING) admitted = true;
      return request == token && phase == Phase.RUNNING;
    } catch (Throwable rejected) {
      fail(request, rejected);
      return false;
    }
  }

  private void delivered(Object request, long handed) {
    if (request != token || phase != Phase.RUNNING || delivery != handed || preparedReady) return;
    // Delivery is only a receipt. Admission and complete worker return are checked on acceptance.
    preparedReady = true;
  }

  /** Visible controls decide first; hidden results retain their explicit background completion. */
  void acceptPrepared() {
    Object request = token;
    refresh();
    if (request != token || phase != Phase.RUNNING || !preparedReady || !admitted) return;
    var completed = BackgroundFailure.completed(task);
    if (request != token
        || phase != Phase.RUNNING
        || completed == null
        || completed.failure() != null) return;
    var value = handoff.take();
    if (value == null) {
      fail(request, new IllegalStateException("Project validation result unavailable"));
      return;
    }
    publish(request, value.issues(), value.failure());
  }

  private void publish(Object request, List<Diagnostic> result, Throwable failure) {
    if (request != token || phase != Phase.RUNNING) return;
    refresh();
    if (request != token || phase != Phase.RUNNING) return;
    try {
      if (!owner.valuesCurrent(state)) {
        stale();
        return;
      }
      if (failure instanceof CancellationException) {
        cancel();
        return;
      }
      if (failure != null) {
        fail(request, failure);
        return;
      }
      release();
      Object publication = token;
      phase = Phase.READY;
      message =
          result.isEmpty()
              ? "Committed project is valid."
              : "Committed project validation completed.";
      state.publishValidation(result);
      // Ready refresh only needs identity: release the completed snapshot's JSON ownership.
      if (token == publication && phase == Phase.READY) owner = new Owner(state, null);
    } catch (Throwable rejected) {
      // Publication belongs to this UI turn. Preserve any already installed diagnostic values.
      if (phase == Phase.READY) fail(token, rejected);
      else fail(request, rejected);
    }
  }

  void refresh() {
    if (phase != Phase.RUNNING && phase != Phase.READY) return;
    if (owner == null || !owner.current(state)) {
      stale();
      return;
    }
    if (phase == Phase.READY) return;
    Object request = token;
    var completed = BackgroundFailure.completed(task);
    if (request != token || phase != Phase.RUNNING || completed == null) return;
    if (completed.failure() instanceof CancellationException) cancel();
    else if (completed.failure() != null) fail(request, completed.failure());
  }

  void cancel() {
    if (phase != Phase.RUNNING) return;
    phase = Phase.CANCELLED;
    Object cancelled = retire();
    if (cancelled != token || phase != Phase.CANCELLED) return;
    message = "Validation cancelled. Previous results are retained; validate again when ready.";
  }

  private void stale() {
    phase = Phase.STALE;
    Object invalidated = retire();
    if (invalidated != token || phase != Phase.STALE) return;
    message =
        "Project or diagnostic results changed. Validate again to check the current committed"
            + " project.";
  }

  private Object release() {
    Object released = new Object();
    token = released;
    task = null;
    preparedReady = admitted = false;
    handoff.advance(++delivery);
    owner = null;
    return released;
  }

  private Object retire() {
    var old = task;
    // Return this reserved identity, never a successor installed by cancellation callbacks.
    Object retired = release();
    if (old != null) {
      old.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(old);
    }
    return retired;
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    phase = Phase.FAILED;
    Object failed = retire();
    if (failed != token || phase != Phase.FAILED) return;
    String detail;
    try {
      detail = failure.getMessage();
    } catch (Throwable unreadable) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    if (detail.isBlank()) detail = failure.getClass().getName();
    String feedback = "Project validation failed: " + StatusBarText.clip(detail, 1024);
    if (failed != token || phase != Phase.FAILED) return;
    message = feedback;
    try {
      errors.accept(message);
    } catch (Throwable ignored) {
      /* Reporting cannot trap the validation workspace. */
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    phase = Phase.CLOSED;
    retire();
    handoff.close();
    message = "Validation workspace closed.";
  }
}
