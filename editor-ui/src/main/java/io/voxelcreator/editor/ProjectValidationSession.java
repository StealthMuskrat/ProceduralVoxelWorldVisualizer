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
    retire();
    Object request = token;
    try {
      var snapshot = ProjectIO.copy(state.project());
      owner = new Owner(state, ProjectIO.JSON.valueToTree(snapshot));
      phase = Phase.RUNNING;
      message = "Validating a committed snapshot in the background. Previous results remain below.";
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
                var value = result;
                var rejected = failure;
                ui.accept(() -> publish(request, value, rejected));
                return null;
              });
      executor.execute(task);
      return true;
    } catch (Throwable rejected) {
      fail(request, rejected);
      return false;
    }
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
    var observed = task;
    if (observed == null || !observed.isDone()) return;
    Object request = token;
    try {
      // Successful workers await their queued UI publication. Exceptional handoff is surfaced
      // here, even when no callback could reach the queue.
      observed.get();
    } catch (CancellationException cancelled) {
      cancel();
    } catch (ExecutionException failed) {
      fail(request, failed.getCause() == null ? failed : failed.getCause());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(request, interrupted);
    }
  }

  void cancel() {
    if (phase != Phase.RUNNING) return;
    retire();
    phase = Phase.CANCELLED;
    message = "Validation cancelled. Previous results are retained; validate again when ready.";
  }

  private void stale() {
    retire();
    phase = Phase.STALE;
    message =
        "Project or diagnostic results changed. Validate again to check the current committed"
            + " project.";
  }

  private void release() {
    token = new Object();
    task = null;
    owner = null;
  }

  private void retire() {
    var old = task;
    release();
    if (old != null) {
      old.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(old);
    }
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    String detail;
    try {
      detail = failure.getMessage();
    } catch (Throwable unreadable) {
      detail = null;
    }
    if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
    if (detail.isBlank()) detail = failure.getClass().getName();
    message = "Project validation failed: " + StatusBarText.clip(detail, 1024);
    try {
      errors.accept(message);
    } catch (Throwable ignored) {
      /* Reporting cannot trap the validation workspace. */
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    retire();
    phase = Phase.CLOSED;
    message = "Validation workspace closed.";
  }
}
