package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** One stale-safe browser edit; failed candidates never dispose of private Properties input. */
final class ResourceEditSession implements AutoCloseable {
  enum Result {
    APPLIED,
    NEEDS_DECISION,
    REJECTED,
    UNAVAILABLE,
    CANCELLED
  }

  private final EditorState state;
  private ResourceBrowserEdits.Edit edit;
  private String label = "";
  private ResourceReviewContext context;
  private long seedVersion;
  private boolean executing;
  private final Runnable afterReconcile;
  private UUID selected;
  private Object draft;
  private JsonNode draftValue;
  private List<UUID> selection;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private final Consumer<Result> completion;
  private ProjectFileOwner requestOwner, workOwner;
  private Object workToken = new Object();
  private FutureTask<Void> task;
  private record Prepared(
      DocumentSession.PreparedEdit value, ResourceBrowserEdits.Selection destination,
      Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean ready;
  private boolean admitting, disposed, privateDecision, applyingDraft;
  private long workVersion;
  private String progress = "";
  private List<Diagnostic> sharedIssues, sharedValues;
  private String sharedScope;

  ResourceEditSession(EditorState state) {
    this(state, () -> {});
  }

  ResourceEditSession(EditorState state, Runnable afterReconcile) {
    this(state, afterReconcile, null, null, () -> false, result -> {});
  }

  ResourceEditSession(
      EditorState state,
      Runnable afterReconcile,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier blocked,
      Consumer<Result> completion) {
    this.state = Objects.requireNonNull(state);
    this.afterReconcile = Objects.requireNonNull(afterReconcile);
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    this.completion = Objects.requireNonNull(completion);
    if (executor != null) Objects.requireNonNull(ui);
  }

  boolean busy() {
    refresh();
    return executing;
  }

  boolean pending() {
    refresh();
    return edit != null;
  }

  String label() {
    return label;
  }

  boolean preparing() {
    refresh();
    return task != null || ready || admitting;
  }

  boolean privateDecision() {
    return privateDecision;
  }

  boolean preparingApply() {
    return applyingDraft;
  }

  long workVersion() {
    return workVersion;
  }

  String progress() {
    refresh();
    return progress;
  }

  Result request(String label, ResourceBrowserEdits.Edit edit) {
    if (disposed || executing || pending()) return Result.UNAVAILABLE;
    Objects.requireNonNull(label);
    this.label = label.length() > 240 ? label.substring(0, 239) + "…" : label;
    this.edit = Objects.requireNonNull(edit);
    context = ResourceReviewContext.capture(state);
    selected = state.selected;
    draft = state.draft;
    draftValue = ProjectIO.JSON.valueToTree(draft);
    selection = List.copyOf(state.multiSelection);
    seedVersion = state.seedInputs.version(state);
    privateDecision = modified();
    progress = "";
    if (executor != null) requestOwner = new ProjectFileOwner(state);
    if (privateDecision) return Result.NEEDS_DECISION;
    if (executor != null) return prepare(false);
    var result = execute(false);
    // No modal was opened for an unmodified draft; a failed command is requested afresh.
    if (result != Result.APPLIED) clear();
    return result;
  }

  private boolean modified() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var source = ResourceGraph.find(state.project(), state.selected);
    return source == null || !ProjectIO.JSON.valueToTree(source.resource()).equals(draftValue);
  }

  Result decide(SearchNavigation.Decision decision) {
    if (executing || !pending()) return Result.UNAVAILABLE;
    if (decision == SearchNavigation.Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    if (task != null || ready || admitting) return Result.NEEDS_DECISION;
    if (!context.current(state)
        || !Objects.equals(selected, state.selected)
        || state.draft != draft
        || !selection.equals(List.copyOf(state.multiSelection))
        || !draftValue.equals(ProjectIO.JSON.valueToTree(state.draft))
        || seedVersion != state.seedInputs.version(state)) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (executor != null)
      return prepare(privateDecision && decision == SearchNavigation.Decision.APPLY);
    return execute(decision == SearchNavigation.Decision.APPLY);
  }

  private Result execute(boolean applyDraft) {
    executing = true;
    try {
      var acceptedEdit = edit;
      ResourceBrowserEdits.Selection[] destination = {null};
      java.util.function.Consumer<ProjectData> change = p -> destination[0] = acceptedEdit.apply(p);
      boolean accepted;
      try {
        accepted = applyDraft ? state.changeWithDraft(change) : state.change(change);
      } catch (RuntimeException failure) {
        report("RESOURCE_EDIT_FAILED", "Resource edit rejected: ", failure);
        return Result.REJECTED;
      }
      if (!accepted) return Result.REJECTED;
      var requested = destination[0];
      retire();
      try {
        reconcile(requested);
        afterReconcile.run();
      } catch (RuntimeException failure) {
        report(
            "RESOURCE_EDIT_RECONCILE_FAILED",
            "Resource edit committed; selection refresh failed: ",
            failure);
      }
      return Result.APPLIED;
    } finally {
      executing = false;
    }
  }

  private void report(String code, String prefix, RuntimeException failure) {
    state.error(
        code,
        EditorEvents.Subsystem.PROJECT,
        StatusBarText.bounded(
            prefix + Objects.requireNonNullElse(failure.getMessage(), failure.toString()), 1024));
  }

  private void reconcile(ResourceBrowserEdits.Selection requested) {
    state.reconcileSelection(
        requested == null ? state.multiSelection : requested.ids(),
        requested == null ? state.selected : requested.active());
  }

  void clear() {
    if (executing) return;
    retire();
    progress = "";
  }

  private void retire() {
    retireWork();
    edit = null;
    label = "";
    context = null;
    draft = null;
    draftValue = null;
    selection = null;
    selected = null;
    requestOwner = null;
    privateDecision = false;
  }

  private Result prepare(boolean applyDraft) {
    retireWork();
    applyingDraft = applyDraft;
    Object token = workToken;
    admitting = true;
    try {
      var acceptedEdit = edit;
      boolean denied = blocked.getAsBoolean();
      if (token != workToken || edit != acceptedEdit || disposed) return Result.UNAVAILABLE;
      if (denied || requestOwner == null || !requestOwner.valuesCurrent(state)) {
        clear();
        return Result.UNAVAILABLE;
      }
      var destination = new AtomicReference<ResourceBrowserEdits.Selection>();
      var request =
          state.requestBrowserEdit(applyDraft, p -> destination.set(acceptedEdit.apply(p)));
      if (request == null) {
        progress =
            "Properties input was rejected. Cancel to correct it, or choose Discard to run only the"
                + " resource action.";
        return Result.REJECTED;
      }
      workOwner = new ProjectFileOwner(state);
      sharedIssues = state.issues;
      sharedValues = List.copyOf(sharedIssues);
      sharedScope = state.validationScope;
      progress =
          "Preparing the resource action in the background. Committed values and private Properties"
              + " input remain active. Cancel or Escape before publication keeps them unchanged.";
      long handed = ++delivery;
      handoff.advance(handed);
      task =
          new FutureTask<>(
              () -> {
                DocumentSession.PreparedEdit prepared = null;
                Throwable failure = null;
                try {
                  prepared = request.prepare();
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                if (!handoff.publish(handed, new Prepared(prepared, destination.get(), failure)))
                  return null;
                ui.accept(() -> delivered(token, handed));
                return null;
              });
      executor.execute(task);
      return Result.NEEDS_DECISION;
    } catch (Throwable failure) {
      fail(token, failure);
      return edit == null ? Result.UNAVAILABLE : Result.NEEDS_DECISION;
    } finally {
      if (token == workToken) admitting = false;
    }
  }

  private boolean owns(Object token, ResourceBrowserEdits.Edit acceptedEdit) {
    return !disposed && token == workToken && edit == acceptedEdit && (task != null || ready);
  }

  private void delivered(Object token, long handed) {
    if (!owns(token, edit) || edit == null || delivery != handed || ready) return;
    refresh();
    if (!owns(token, edit) || edit == null) return;
    task = null;
    ready = true;
  }

  /** The shown action dialog processes Cancel/Escape before committing the prepared command. */
  void acceptPrepared() {
    Object token = workToken;
    var acceptedEdit = edit;
    refresh();
    if (!owns(token, acceptedEdit) || !ready || edit == null || executing) return;
    var value = handoff.take();
    if (value == null) {
      fail(token, new IllegalStateException("Resource action preparation unavailable"));
      return;
    }
    publish(token, acceptedEdit, value.value(), value.destination(), value.failure());
  }

  private boolean feedbackCurrent() {
    return sharedIssues == state.issues
        && sharedValues.equals(state.issues)
        && Objects.equals(sharedScope, state.validationScope);
  }

  private void publish(
      Object token,
      ResourceBrowserEdits.Edit acceptedEdit,
      DocumentSession.PreparedEdit prepared,
      ResourceBrowserEdits.Selection destination,
      Throwable failure) {
    if (!owns(token, acceptedEdit)) return;
    boolean applied = false;
    try {
      if (!workOwner.valuesCurrent(state) || !feedbackCurrent()) {
        stale();
        return;
      }
      if (failure != null) {
        fail(token, failure);
        return;
      }
      boolean denied = blocked.getAsBoolean();
      if (!owns(token, acceptedEdit)) return;
      if (denied
          || !workOwner.valuesCurrent(state)
          || !feedbackCurrent()
          || !state.document.currentEdit(prepared)) {
        stale();
        return;
      }
      if (!prepared.success()) {
        var diagnostics = prepared.diagnostics();
        retireWork();
        progress =
            "The draft or resource action was rejected. Private input remains active; retry"
                + " explicitly, choose Discard if available, or Cancel before correcting the"
                + " request.";
        state.rejectDraftApply(diagnostics);
        return;
      }
      executing = true;
      state.document.applyEdit(prepared);
      applied = true;
      retire();
      state.revision++;
      state.issues = List.of();
      state.validationScope = "Committed project";
      state.status = "Resource edit applied";
      progress = "Resource action applied. Saving remains explicit.";
      try {
        reconcile(destination);
        afterReconcile.run();
      } catch (Throwable rejected) {
        progress =
            "Resource edit committed; selection refresh failed: "
                + BackgroundFailure.describe(rejected);
        reportFailure("RESOURCE_EDIT_RECONCILE_FAILED", progress);
      }
      finish(Result.APPLIED);
    } catch (Throwable rejected) {
      if (applied) {
        retire();
        progress =
            "Resource edit committed; completion failed: " + BackgroundFailure.describe(rejected);
        reportFailure("RESOURCE_EDIT_RECONCILE_FAILED", progress);
        finish(Result.APPLIED);
      } else fail(token, rejected);
    } finally {
      executing = false;
    }
  }

  private void finish(Result result) {
    if (disposed) return;
    try {
      completion.accept(result);
    } catch (Throwable failure) {
      reportFailure(
          "RESOURCE_EDIT_COMPLETION_FAILED",
          "Resource edit completion failed: " + BackgroundFailure.describe(failure));
    }
  }

  private void stale() {
    retire();
    progress =
        "Resource action became stale. Current project, selection and private input are retained;"
            + " request the action again.";
    finish(Result.UNAVAILABLE);
  }

  private void refresh() {
    if ((task == null && !ready) || disposed) return;
    try {
      if (!workOwner.current(state) || !feedbackCurrent()) {
        stale();
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(workToken, done.failure());
    } catch (Throwable failure) {
      fail(workToken, failure);
    }
  }

  private void fail(Object token, Throwable failure) {
    if (token != workToken || disposed) return;
    retireWork();
    progress =
        "Resource action preparation failed: "
            + BackgroundFailure.describe(failure)
            + ". Private input is retained; retry explicitly or Cancel.";
    reportFailure("RESOURCE_EDIT_PREPARE_FAILED", progress);
  }

  private void reportFailure(String code, String message) {
    try {
      state.error(code, EditorEvents.Subsystem.PROJECT, message);
    } catch (Throwable ignored) {
      /* Keep explicit recovery available. */
    }
  }

  private void retireWork() {
    var previous = task;
    workToken = new Object();
    workVersion++;
    task = null;
    ready = false;
    handoff.advance(++delivery);
    admitting = false;
    applyingDraft = false;
    workOwner = null;
    sharedIssues = sharedValues = null;
    sharedScope = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  @Override
  public void close() {
    disposed = true;
    if (!executing) clear();
    handoff.close();
  }
}
