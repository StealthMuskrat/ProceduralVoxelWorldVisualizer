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
    APPLYING,
    GUARD,
    OPEN,
    LOADING,
    SAVING,
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
  private FutureTask<Void> openTask, saveTask, draftTask;
  private record OpenResult(DocumentSession.PreparedOpen value, Throwable failure) {}
  private final PreviewMailbox<OpenResult> openHandoff = new PreviewMailbox<>();
  private long openDelivery;
  private boolean openReady;
  private String openText;
  private record DraftResult(DocumentSession.PreparedEdit value, Throwable failure) {}
  private final PreviewMailbox<DraftResult> draftHandoff = new PreviewMailbox<>();
  private long draftDelivery;
  private boolean draftReady;
  private java.util.List<Diagnostic> draftIssues, draftIssueValues;
  private String draftScope;
  private final OwnedPreviewMailbox<SavePayload> saveHandoff = new OwnedPreviewMailbox<>();
  private boolean saveReady;
  private String saveText;

  private final class SavePayload implements AutoCloseable {
    final DocumentSession.PreparedSave prepared;
    final Throwable failure;

    SavePayload(DocumentSession.PreparedSave prepared, Throwable failure) {
      this.prepared = prepared;
      this.failure = failure;
    }

    @Override
    public void close() {
      if (prepared == null) return;
      try {
        prepared.close();
      } catch (Throwable failure) {
        // May run on the worker after cancellation; post only to the thread-safe event service.
        state.error(
            "PROJECT_SAVE_CLEANUP_FAILED",
            EditorEvents.Subsystem.PROJECT,
            "Unpublished Save cleanup failed: " + BackgroundFailure.describe(failure));
      }
    }
  }

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

  long openVersion() {
    return generation;
  }

  long saveVersion() {
    return generation;
  }

  String savingLocation() {
    return saveText;
  }

  Phase phase() {
    refreshDraftApply();
    refreshOpen();
    refreshSave();
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
    requestOwned(intent);
  }

  /** Admission receipt for an explicitly supplied path; a reentrant successor is not admission. */
  boolean requestOpenLocation() {
    return requestOwned(Intent.OPEN);
  }

  private boolean requestOwned(Intent intent) {
    if (closed || busy()) return false;
    Objects.requireNonNull(intent);
    long attempt = generation;
    try {
      var opening = new ProjectFileOwner(state);
      boolean denied = blocked.getAsBoolean();
      if (generation != attempt || busy()) return false;
      if (denied) {
        error = "Finish or cancel the open editor or resource dialog before continuing.";
        state.error("PROJECT_TRANSITION_BLOCKED", EditorEvents.Subsystem.PROJECT, error);
        return false;
      }
      if (!opening.current(state)) {
        stale(attempt, "");
        return false;
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
      return true;
    } catch (RuntimeException failure) {
      report("PROJECT_FILE_GUARD_FAILED", "Project file request failed: ", failure, attempt);
      return false;
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
      if (executor != null) {
        startDraftApply();
        return;
      }
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

  private void startDraftApply() {
    long attempt = ++generation;
    try {
      // Transition Apply owns the active Properties resource even with sibling selection.
      var preparation = state.requestSelectionApply();
      if (preparation == null) {
        error = "Correct Properties input before Apply Draft, or cancel this transition.";
        return;
      }
      if (!current()) return;
      if (!owner.valuesCurrent(state)) {
        stale(attempt, "");
        return;
      }
      draftIssues = state.issues;
      draftIssueValues = java.util.List.copyOf(draftIssues);
      draftScope = state.validationScope;
      long delivery = ++draftDelivery;
      draftHandoff.advance(delivery);
      phase = Phase.APPLYING;
      error = "";
      draftTask = new FutureTask<>(() -> {
        DocumentSession.PreparedEdit value = null;
        Throwable failure = null;
        try {
          value = preparation.prepare();
        } catch (Throwable rejected) {
          failure = rejected;
        }
        if (!draftHandoff.publish(delivery, new DraftResult(value, failure))) return null;
        ui.accept(() -> deliveredDraft(attempt, delivery));
        return null;
      });
      executor.execute(draftTask);
    } catch (Throwable failure) {
      failedDraft(attempt, failure);
    }
  }

  private boolean draftDiagnosticsCurrent() {
    return draftIssues == state.issues
        && Objects.equals(draftScope, state.validationScope)
        && draftIssueValues.equals(state.issues);
  }

  private void refreshDraftApply() {
    if (phase != Phase.APPLYING || closed) return;
    long attempt = generation;
    try {
      if (owner == null || !owner.current(state) || !draftDiagnosticsCurrent()) {
        stale(attempt, "");
        return;
      }
      var completed = BackgroundFailure.completed(draftTask);
      if (completed != null && completed.failure() != null) failedDraft(attempt, completed.failure());
    } catch (Throwable failure) {
      failedDraft(attempt, failure);
    }
  }

  private void deliveredDraft(long attempt, long delivery) {
    if (closed || generation != attempt || phase != Phase.APPLYING
        || draftDelivery != delivery || draftReady) return;
    refreshDraftApply();
    if (generation != attempt || phase != Phase.APPLYING) return;
    draftTask = null;
    draftReady = true;
  }

  /** The modal processes Cancel/Escape before accepting a delivered draft transaction. */
  void acceptDraft() {
    refreshDraftApply();
    if (phase != Phase.APPLYING || !draftReady || closed) return;
    long attempt = generation;
    var result = draftHandoff.take();
    try {
      if (!current()) return;
      if (!owner.valuesCurrent(state) || !draftDiagnosticsCurrent()) {
        stale(attempt, "");
        return;
      }
      if (result == null) throw new IllegalStateException("Properties Apply preparation unavailable");
      if (result.failure() != null) {
        failedDraft(attempt, result.failure());
        return;
      }
      var prepared = Objects.requireNonNull(result.value());
      if (!state.document.currentEdit(prepared)) {
        stale(attempt, "");
        return;
      }
      if (!prepared.success()) {
        releaseDraft();
        generation++;
        phase = Phase.DRAFT;
        error = "Properties Apply was rejected. Cancel to correct the draft, or choose Discard Draft.";
        try {
          state.rejectDraftApply(prepared.diagnostics());
        } catch (Throwable failure) {
          report("PROJECT_DRAFT_REJECTION_FEEDBACK_FAILED", "Properties remains private; feedback failed: ",
              failure, generation);
        }
        return;
      }
      working = true;
      state.document.applyEdit(prepared);
    } catch (Throwable failure) {
      failedDraft(attempt, failure);
      return;
    } finally {
      working = false;
    }
    // Consume before refreshing the private editor or continuing into a file/exit callback.
    releaseDraft();
    generation++;
    phase = Phase.DRAFT;
    long completed = generation;
    try {
      working = true;
      state.finishDraftApply();
      if (closed || generation != completed) return;
      owner = new ProjectFileOwner(state);
      phase = Phase.DRAFT;
      error = "";
    } catch (Throwable failure) {
      if (generation == completed) {
        retire();
        completed = generation;
      }
      report("PROJECT_DRAFT_APPLY_REFRESH_FAILED", "Properties applied; refresh failed: ", failure, completed);
      return;
    } finally {
      working = false;
    }
    if (current("Properties applied. ")) afterDraft();
  }

  private void failedDraft(long attempt, Throwable failure) {
    if (closed || generation != attempt) return;
    releaseDraft();
    generation++;
    phase = Phase.DRAFT;
    report("PROJECT_DRAFT_APPLY_FAILED", "Apply Draft failed; retry explicitly or cancel: ", failure, generation);
  }

  private void releaseDraft() {
    var previous = draftTask;
    draftTask = null;
    draftReady = false;
    draftIssues = draftIssueValues = null;
    draftScope = null;
    draftHandoff.advance(++draftDelivery);
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
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
      openText = location;
      long handed = ++openDelivery;
      openHandoff.advance(handed);
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
                if (!openHandoff.publish(handed, new OpenResult(prepared, failure))) return null;
                ui.accept(() -> deliveredOpen(attempt, handed));
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

  private boolean ownsOpen(long attempt) {
    return !closed && generation == attempt && phase == Phase.LOADING;
  }

  private void deliveredOpen(long attempt, long handed) {
    if (!ownsOpen(attempt) || openDelivery != handed || openReady) return;
    refreshOpen();
    if (!ownsOpen(attempt)) return;
    // Keep observing the task until acceptance; an inline handoff can still fail afterward.
    openReady = true;
  }

  void acceptOpen() {
    acceptOpen(openText, () -> true);
  }

  /** Current loading controls decide before replacing the active document or reporting rejection. */
  void acceptOpen(String displayed, BooleanSupplier shown) {
    long attempt = generation;
    refreshOpen();
    if (!ownsOpen(attempt) || !openReady) return;
    if (!Objects.equals(openText, displayed)) {
      stale(attempt, "");
      return;
    }
    Objects.requireNonNull(shown);
    var value = openHandoff.take();
    if (value == null) {
      failedOpen(attempt, new IllegalStateException("Project Open preparation unavailable"));
      return;
    }
    publishOpen(attempt, value.value(), value.failure(), shown);
  }

  private boolean openCurrent(long attempt, BooleanSupplier shown) {
    if (!ownsOpen(attempt) || !current() || !ownsOpen(attempt)) return false;
    boolean visible = shown.getAsBoolean();
    if (!ownsOpen(attempt)) return false;
    if (visible && current() && ownsOpen(attempt) && owner.valuesCurrent(state)) return true;
    stale(attempt, "");
    return false;
  }

  private void publishOpen(
      long attempt, DocumentSession.PreparedOpen prepared, Throwable failure, BooleanSupplier shown) {
    if (!ownsOpen(attempt)) return;
    try {
      if (!openCurrent(attempt, shown)) return;
      // Accepted results must not interrupt a worker finishing its delivery call.
      openTask = null;
      if (failure != null) {
        failedOpen(attempt, failure);
        return;
      }
      if (!Objects.requireNonNull(prepared).success()) {
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
    openReady = false;
    openText = null;
    openHandoff.advance(++openDelivery);
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
    if (executor != null) {
      startSave(location);
      return;
    }
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

  private void startSave(String location) {
    long attempt = ++generation;
    saveText = location;
    try {
      var request = state.document.requestSave(path(location));
      long handoff = saveHandoff.begin();
      phase = Phase.SAVING;
      error = "";
      saveTask =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedSave prepared = null;
                Throwable failure = null;
                try {
                  prepared = Objects.requireNonNull(request.prepare());
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!saveHandoff.publish(handoff, new SavePayload(prepared, failure))) return null;
                ui.accept(() -> deliveredSave(attempt, handoff));
                return null;
              });
      executor.execute(saveTask);
    } catch (Throwable failure) {
      failedSave(attempt, failure);
    }
  }

  private void refreshSave() {
    if (phase != Phase.SAVING || closed) return;
    long attempt = generation;
    try {
      if (owner == null || !owner.current(state)) {
        stale(attempt, "");
        return;
      }
      var completion = BackgroundFailure.completed(saveTask);
      if (completion != null && completion.failure() != null)
        failedSave(attempt, completion.failure());
    } catch (Throwable failure) {
      failedSave(attempt, failure);
    }
  }

  private boolean ownsSave(long attempt) {
    return !closed && generation == attempt && phase == Phase.SAVING;
  }

  private void deliveredSave(long attempt, long handoff) {
    if (!ownsSave(attempt) || !saveHandoff.current(handoff) || saveReady) return;
    refreshSave();
    if (!ownsSave(attempt)) return;
    // An inline readiness handoff can still throw; retain task observation until acceptance.
    saveReady = true;
  }

  void acceptSave() {
    acceptSave(saveText, () -> true);
  }

  /** Current saving controls decide before file replacement, checkpoint installation or failure. */
  void acceptSave(String displayed, BooleanSupplier shown) {
    long attempt = generation;
    refreshSave();
    if (!ownsSave(attempt) || !saveReady) return;
    if (!Objects.equals(saveText, displayed)) {
      stale(attempt, "");
      return;
    }
    Objects.requireNonNull(shown);
    var payload = saveHandoff.take();
    publishSave(attempt, payload, shown);
  }

  private boolean saveCurrent(long attempt, BooleanSupplier shown) {
    if (!ownsSave(attempt) || !current() || !ownsSave(attempt)) return false;
    boolean visible = shown.getAsBoolean();
    if (!ownsSave(attempt)) return false;
    if (visible && current() && ownsSave(attempt) && owner.valuesCurrent(state)) return true;
    stale(attempt, "");
    return false;
  }

  private void publishSave(long attempt, SavePayload payload, BooleanSupplier shown) {
    var authority = owner;
    try {
      if (!saveCurrent(attempt, shown)) return;
      // Accepted results must not interrupt a worker finishing its delivery call.
      saveTask = null;
      if (payload == null) throw new IllegalStateException("Save preparation was not delivered");
      if (payload.failure != null) {
        failedSave(attempt, payload.failure);
        return;
      }
      working = true;
      state.document.applySave(Objects.requireNonNull(payload.prepared));
    } catch (Throwable rejected) {
      failedSave(attempt, rejected);
      return;
    } finally {
      working = false;
      if (payload != null) payload.close();
    }
    // Replacement is complete. Only errors in remaining UI/continuation work can follow it.
    try {
      if (state.document.dirty() || !authority.savedContentCurrent(state)) {
        stale(attempt, "Project saved. ");
        return;
      }
      owner = new ProjectFileOwner(state);
      error = "";
      state.notify("Information", "Project saved");
      if (generation != attempt || phase != Phase.SAVING) return;
      if (!current("Project saved. ")) return;
      if (!owner.valuesCurrent(state)) {
        stale(attempt, "Project saved. ");
        return;
      }
      complete();
    } catch (Throwable completionFailure) {
      long reported = attempt;
      if (generation == attempt) {
        retire();
        reported = generation;
      }
      report(
          "PROJECT_SAVE_COMPLETION_FAILED",
          "Project saved; completion failed: ",
          completionFailure,
          reported);
    }
  }

  private void failedSave(long attempt, Throwable failure) {
    if (closed || generation != attempt) return;
    String failedLocation = saveText;
    releaseSave();
    saveText = failedLocation;
    generation++;
    pending = null;
    transition = false;
    phase = Phase.SAVE;
    owner = new ProjectFileOwner(state);
    report("PROJECT_SAVE_FAILED", "SAVE failed: ", failure, generation, 8192);
  }

  private void releaseSave() {
    var old = saveTask;
    saveTask = null;
    saveReady = false;
    saveText = null;
    saveHandoff.invalidate();
    if (old != null) {
      old.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(old);
    }
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
    releaseDraft();
    releaseOpen();
    releaseSave();
    generation++;
    pending = null;
    phase = Phase.IDLE;
    owner = null;
    transition = false;
  }

  private void deliver(Runnable callback, String code, String prefix, long completed) {
    try {
      callback.run();
    } catch (Throwable failure) {
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
    saveHandoff.close();
    draftHandoff.close();
    openHandoff.close();
    error = "";
  }
}
