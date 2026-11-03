package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One owned project transition; a failed or canceled save cannot retain a destructive action. */
final class ProjectFileWorkflow implements AutoCloseable {
  enum Intent {
    NEW,
    OPEN,
    EXIT
  }

  enum Phase {
    IDLE,
    DRAFT,
    GUARD,
    OPEN,
    LOADING,
    SAVE
  }

  enum Decision {
    SAVE,
    DISCARD,
    CANCEL
  }

  enum DraftDecision {
    APPLY,
    DISCARD,
    CANCEL
  }

  private final EditorState state;
  private final Runnable replaced, exit;
  private final BooleanSupplier blocked;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private FutureTask<Void> openTask;
  private boolean closed;
  private ProjectFileOwner owner;
  private boolean transition;
  private Phase phase = Phase.IDLE;
  private Intent pending;
  private String error = "";
  private long requestVersion;
  private long generation;
  private boolean working;

  ProjectFileWorkflow(EditorState state, Runnable replaced, Runnable exit) {
    this(state, replaced, exit, () -> false);
  }

  ProjectFileWorkflow(
      EditorState state, Runnable replaced, Runnable exit, BooleanSupplier blocked) {
    this(state, replaced, exit, blocked, null, null);
  }

  ProjectFileWorkflow(
      EditorState state,
      Runnable replaced,
      Runnable exit,
      BooleanSupplier blocked,
      Executor executor,
      Consumer<Runnable> ui) {
    this.state = Objects.requireNonNull(state);
    this.replaced = Objects.requireNonNull(replaced);
    this.exit = Objects.requireNonNull(exit);
    this.blocked = Objects.requireNonNull(blocked);
    this.executor = executor;
    this.ui = ui;
    if (executor != null) Objects.requireNonNull(ui);
  }

  long requestVersion() {
    return requestVersion;
  }

  Phase phase() {
    refreshOpen();
    return phase;
  }

  boolean busy() {
    return working || phase != Phase.IDLE;
  }

  String error() {
    return error;
  }

  String action() {
    return pending == null
        ? "Save Project"
        : switch (pending) {
          case NEW -> "New Project";
          case OPEN -> "Open Project";
          case EXIT -> "Exit";
        };
  }

  void request(Intent intent) {
    if (closed || busy()) return;
    Objects.requireNonNull(intent);
    long attempt = generation;
    try {
      var opening = new ProjectFileOwner(state);
      boolean denied = blocked.getAsBoolean();
      if (generation != attempt || busy()) return;
      if (denied) {
        error = "Finish or cancel the open editor or resource dialog before continuing.";
        state.error("PROJECT_TRANSITION_BLOCKED", EditorEvents.Subsystem.PROJECT, error);
        return;
      }
      if (!opening.current(state)) {
        stale(attempt, "");
        return;
      }
      boolean draft = modifiedDraft();
      error = "";
      pending = intent;
      requestVersion++;
      generation++;
      transition = true;
      owner = opening;
      if (draft) phase = Phase.DRAFT;
      else afterDraft();
    } catch (RuntimeException failure) {
      report("PROJECT_FILE_GUARD_FAILED", "Project file request failed: ", failure, attempt);
    }
  }

  private boolean modifiedDraft() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var entry = ResourceGraph.find(state.project(), state.selected);
    return entry == null
        || !ProjectIO.JSON
            .valueToTree(entry.resource())
            .equals(ProjectIO.JSON.valueToTree(state.draft));
  }

  void decideDraft(DraftDecision decision) {
    if (working || phase != Phase.DRAFT) return;
    if (decision == DraftDecision.CANCEL) {
      cancel();
      return;
    }
    if (!current()) return;
    if (decision == DraftDecision.APPLY) {
      long attempt = generation;
      working = true;
      try {
        if (!state.applyDraft()) {
          error = "Properties Apply failed. Cancel to correct the draft, or choose Discard Draft.";
          return;
        }
        owner = new ProjectFileOwner(state);
        generation++;
      } catch (RuntimeException failure) {
        if (generation == attempt) {
          retire();
          attempt = generation;
        }
        report(
            "PROJECT_DRAFT_APPLY_FAILED", "Properties Apply completion failed: ", failure, attempt);
        return;
      } finally {
        working = false;
      }
      if (!current("Properties applied. ")) return;
    }
    error = "";
    // Discard grants permission for the transition; preserve raw text until it actually succeeds.
    afterDraft();
  }

  private void afterDraft() {
    if (state.document.dirty()) {
      phase = Phase.GUARD;
      generation++;
    } else complete();
  }

  void save(boolean as) {
    if (closed || busy()) return;
    error = "";
    pending = null;
    transition = false;
    requestVersion++;
    generation++;
    owner = new ProjectFileOwner(state);
    beginSave(as);
  }

  void decide(Decision decision) {
    if (working || phase != Phase.GUARD) return;
    if (decision == Decision.CANCEL) {
      cancel();
      return;
    }
    if (!current()) return;
    switch (decision) {
      case CANCEL -> cancel();
      case DISCARD -> complete();
      case SAVE -> beginSave(false);
    }
  }

  private void beginSave(boolean as) {
    if (as || state.document.path() == null) {
      phase = Phase.SAVE;
      generation++;
    } else {
      phase = Phase.IDLE;
      write(state.document.path().toString());
    }
  }

  void submit(String location) {
    if (working) return;
    if (phase == Phase.SAVE) write(location);
    else if (phase == Phase.OPEN) {
      if (!current()) return;
      if (executor != null) {
        startOpen(location);
        return;
      }
      working = true;
      try {
        var issues = state.document.open(path(location));
        if (!issues.isEmpty()) throw new IOException(issues.toString());
        state.revision++;
        state.selected = null;
        state.selectedKind = "";
        state.draft = null;
        state.seedInputs.synchronize(state);
        state.multiSelection.clear();
        state.issues = java.util.List.of();
        state.validationScope = "Committed project";
      } catch (Exception failure) {
        fail("OPEN", failure);
        return;
      } finally {
        working = false;
      }
      retire();
      error = "";
      long completed = generation;
      state.notify("Information", "Project opened");
      deliver(
          replaced,
          "PROJECT_REPLACEMENT_REFRESH_FAILED",
          "Project opened; UI refresh failed: ",
          completed);
    }
  }

  private void startOpen(String location) {
    long attempt = ++generation;
    try {
      var request = state.document.requestOpen(path(location));
      phase = Phase.LOADING;
      error = "";
      openTask =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedOpen prepared = null;
                Throwable failure = null;
                try {
                  prepared = request.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var result = prepared;
                var rejected = failure;
                ui.accept(() -> publishOpen(attempt, result, rejected));
                return null;
              });
      executor.execute(openTask);
    } catch (Throwable failure) {
      failedOpen(attempt, failure);
    }
  }

  private void refreshOpen() {
    if (phase != Phase.LOADING || closed) return;
    long attempt = generation;
    try {
      if (owner == null || !owner.current(state)) {
        stale(attempt, "");
        return;
      }
      var completed = BackgroundFailure.completed(openTask);
      if (completed != null && completed.failure() != null)
        failedOpen(attempt, completed.failure());
    } catch (Throwable failure) {
      failedOpen(attempt, failure);
    }
  }

  private void publishOpen(long attempt, DocumentSession.PreparedOpen prepared, Throwable failure) {
    if (closed || generation != attempt || phase != Phase.LOADING) return;
    // The preparation is complete; queued publication must not interrupt a successful worker.
    openTask = null;
    try {
      if (!current()) return;
      if (!owner.valuesCurrent(state)) {
        stale(attempt, "");
        return;
      }
      if (failure != null) {
        failedOpen(attempt, failure);
        return;
      }
      if (!prepared.success()) {
        failedOpen(attempt, new IOException(prepared.diagnostics().toString()));
        return;
      }
      state.document.applyOpen(prepared);
    } catch (Throwable rejected) {
      failedOpen(attempt, rejected);
      return;
    }
    retire();
    error = "";
    long completed = generation;
    try {
      state.revision++;
      state.selected = null;
      state.selectedKind = "";
      state.draft = null;
      state.multiSelection.clear();
      state.issues = java.util.List.of();
      state.validationScope = "Committed project";
      state.seedInputs.synchronize(state);
      state.notify("Information", "Project opened");
    } catch (Throwable completionFailure) {
      report(
          "PROJECT_REPLACEMENT_REFRESH_FAILED",
          "Project opened; UI refresh failed: ",
          completionFailure,
          completed);
    }
    deliver(
        replaced,
        "PROJECT_REPLACEMENT_REFRESH_FAILED",
        "Project opened; UI refresh failed: ",
        completed);
  }

  private void failedOpen(long attempt, Throwable failure) {
    if (closed || generation != attempt) return;
    releaseOpen();
    generation++;
    // Retry remains an explicit path decision; no predecessor transition can survive failure.
    pending = null;
    phase = Phase.OPEN;
    report("PROJECT_OPEN_FAILED", "OPEN failed: ", failure, generation, 8192);
  }

  private void releaseOpen() {
    var old = openTask;
    openTask = null;
    if (old != null) {
      old.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(old);
    }
  }

  private static Path path(String location) throws IOException {
    if (location == null || location.isBlank()) throw new IOException("Enter a project file path");
    return Path.of(location);
  }

  private void write(String location) {
    if (!current()) return;
    working = true;
    try {
      state.document.save(path(location));
    } catch (Exception failure) {
      // A retry is a standalone save. Never perform an action whose required save failed.
      pending = null;
      transition = false;
      generation++;
      owner = phase == Phase.IDLE ? null : new ProjectFileOwner(state);
      fail("SAVE", failure);
      return;
    } finally {
      working = false;
    }
    state.notify("Information", "Project saved");
    error = "";
    owner = new ProjectFileOwner(state);
    complete();
  }

  private boolean current() {
    return current("");
  }

  private boolean current(String completed) {
    long attempt = generation;
    var opening = owner;
    var openingPhase = phase;
    try {
      if (opening == null || !opening.current(state)) {
        stale(attempt, completed);
        return false;
      }
      boolean denied = transition && blocked.getAsBoolean();
      if (generation != attempt || owner != opening || phase != openingPhase) return false;
      if (!denied && opening.current(state)) return true;
      stale(attempt, completed);
    } catch (RuntimeException failure) {
      if (generation == attempt) {
        retire();
        attempt = generation;
      }
      report(
          "PROJECT_FILE_GUARD_FAILED",
          completed + "Project file decision failed: ",
          failure,
          attempt);
    }
    return false;
  }

  private void stale(long attempt, String completed) {
    if (generation != attempt) return;
    retire();
    error = completed + "The project, draft or open editor changed. Request the file action again.";
    state.error("STALE_PROJECT_FILE_ACTION", EditorEvents.Subsystem.PROJECT, error);
  }

  private void fail(String operation, Exception failure) {
    report("PROJECT_" + operation + "_FAILED", operation + " failed: ", failure, generation, 8192);
  }

  private void complete() {
    var intent = pending;
    var opening = owner;
    // Terminal callbacks may admit another action; leave no predecessor cleanup after delivery.
    retire();
    if (intent == null) return;
    switch (intent) {
      case NEW -> {
        working = true;
        try {
          state.newSession();
        } catch (RuntimeException failure) {
          fail("NEW", failure);
          return;
        } finally {
          working = false;
        }
        deliver(
            replaced,
            "PROJECT_REPLACEMENT_REFRESH_FAILED",
            "New project created; UI refresh failed: ",
            generation);
      }
      case OPEN -> {
        phase = Phase.OPEN;
        owner = opening;
        transition = true;
      }
      case EXIT -> {
        deliver(exit, "PROJECT_EXIT_CALLBACK_FAILED", "Exit request callback failed: ", generation);
      }
    }
  }

  void cancel() {
    if (working) return;
    retire();
    error = "";
  }

  private void retire() {
    releaseOpen();
    generation++;
    pending = null;
    phase = Phase.IDLE;
    owner = null;
    transition = false;
  }

  private void deliver(Runnable callback, String code, String prefix, long completed) {
    try {
      callback.run();
    } catch (RuntimeException failure) {
      report(code, prefix, failure, completed);
    }
  }

  private void report(String code, String prefix, Throwable failure, long attempt) {
    report(code, prefix, failure, attempt, 1024);
  }

  private void report(String code, String prefix, Throwable failure, long attempt, int limit) {
    String detail = BackgroundFailure.describe(failure);
    String message = StatusBarText.clip(prefix + detail, limit);
    if (generation == attempt) error = message;
    state.error(code, EditorEvents.Subsystem.PROJECT, message);
  }

  @Override
  public void close() {
    closed = true;
    retire();
    error = "";
  }
}
