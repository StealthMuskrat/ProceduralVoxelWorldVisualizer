package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Private browser selection intent. No gesture replaces a modified Properties draft implicitly. */
final class ResourceSelectionSession implements AutoCloseable {
  enum Result {
    SELECTED,
    NEEDS_DECISION,
    UNAVAILABLE,
    CANCELLED
  }

  private record Plan(List<UUID> ids, UUID active, String scope) {}

  private final EditorState state;
  private Plan pending;
  private ResourceReviewContext context;
  private long seedVersion;
  private boolean installing;
  private final Runnable afterInstall;
  private UUID source;
  private Object draft;
  private JsonNode draftValue;
  private List<UUID> sourceSelection;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private final Consumer<Result> completion;
  private ProjectFileOwner requestOwner, workOwner;
  private Object workToken = new Object();
  private FutureTask<Void> task;
  private boolean admitting, disposed;
  private long workVersion;
  private String progress = "";
  private List<Diagnostic> sharedIssues, sharedValues;
  private String sharedScope;

  ResourceSelectionSession(EditorState state) {
    this(state, () -> {});
  }

  ResourceSelectionSession(EditorState state, Runnable afterInstall) {
    this(state, afterInstall, null, null, () -> false, result -> {});
  }

  ResourceSelectionSession(
      EditorState state,
      Runnable afterInstall,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier blocked,
      Consumer<Result> completion) {
    this.state = Objects.requireNonNull(state);
    this.afterInstall = Objects.requireNonNull(afterInstall);
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    this.completion = Objects.requireNonNull(completion);
    if (executor != null) Objects.requireNonNull(ui);
  }

  boolean busy() {
    refresh();
    return installing;
  }

  boolean pending() {
    refresh();
    return pending != null;
  }

  boolean preparing() {
    refresh();
    return task != null || admitting;
  }

  long workVersion() {
    return workVersion;
  }

  String progress() {
    refresh();
    return progress;
  }

  List<UUID> destinations() {
    return pending == null ? List.of() : pending.ids;
  }

  Result click(UUID id, boolean toggle) {
    var target = ResourceGraph.find(state.project(), id);
    if (target == null) return Result.UNAVAILABLE;
    var ids = new LinkedHashSet<UUID>();
    if (toggle && sameScope(state.multiSelection, target.scope())) ids.addAll(state.multiSelection);
    if (!toggle || !ids.remove(id)) ids.add(id);
    UUID active = ids.contains(id) ? id : ids.stream().reduce((a, b) -> b).orElse(null);
    return request(List.copyOf(ids), active);
  }

  Result request(List<UUID> ids, UUID active) {
    if (disposed || installing || pending()) return Result.UNAVAILABLE;
    var plan = resolve(ids, active);
    if (plan == null) return Result.UNAVAILABLE;
    if (Objects.equals(active, state.selected) && ids.equals(List.copyOf(state.multiSelection)))
      return Result.SELECTED;
    if (modified()) {
      pending = plan;
      context = ResourceReviewContext.capture(state);
      source = state.selected;
      draft = state.draft;
      draftValue = ProjectIO.JSON.valueToTree(draft);
      sourceSelection = List.copyOf(state.multiSelection);
      seedVersion = state.seedInputs.version(state);
      if (executor != null) requestOwner = new ProjectFileOwner(state);
      progress = "";
      return Result.NEEDS_DECISION;
    }
    installing = true;
    try {
      return installResult(plan, false, false);
    } finally {
      installing = false;
    }
  }

  private boolean modified() {
    if (state.seedInputs.pending(state)) return true;
    if (state.draft == null) return false;
    var active = ResourceGraph.find(state.project(), state.selected);
    return active == null
        || !ProjectIO.JSON
            .valueToTree(active.resource())
            .equals(ProjectIO.JSON.valueToTree(state.draft));
  }

  private boolean sameScope(Collection<UUID> ids, String scope) {
    var remaining = new HashSet<>(ids);
    for (var entry : ResourceGraph.entries(state.project()))
      if (remaining.remove(entry.uuid()) && !entry.scope().equals(scope)) return false;
    return remaining.isEmpty();
  }

  private Plan resolve(List<UUID> ids, UUID active) {
    if (ids == null || new HashSet<>(ids).size() != ids.size()) return null;
    if (ids.isEmpty()) return active == null ? new Plan(List.of(), null, "") : null;
    if (active == null || !ids.contains(active)) return null;
    var entry = ResourceGraph.find(state.project(), active);
    if (entry == null || !sameScope(ids, entry.scope())) return null;
    return new Plan(List.copyOf(ids), active, entry.scope());
  }

  Result decide(SearchNavigation.Decision decision) {
    if (installing || !pending()) return Result.UNAVAILABLE;
    if (decision == SearchNavigation.Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    if (task != null || admitting) return Result.NEEDS_DECISION;
    var plan = pending;
    if (!context.current(state)
        || !Objects.equals(state.selected, source)
        || state.draft != draft
        || !sourceSelection.equals(List.copyOf(state.multiSelection))
        || !draftValue.equals(ProjectIO.JSON.valueToTree(state.draft))
        || seedVersion != state.seedInputs.version(state)
        || !plan.equals(resolve(plan.ids, plan.active))) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (executor != null) {
      Object token = workToken;
      admitting = true;
      try {
        boolean denied = blocked.getAsBoolean();
        if (token != workToken || pending != plan || disposed) return Result.UNAVAILABLE;
        if (denied
            || requestOwner == null
            || !requestOwner.valuesCurrent(state)
            || !plan.equals(resolve(plan.ids, plan.active))) {
          clear();
          return Result.UNAVAILABLE;
        }
        if (decision == SearchNavigation.Decision.APPLY) return prepare(plan);
      } catch (Throwable failure) {
        fail(token, failure);
        return pending == null ? Result.UNAVAILABLE : Result.NEEDS_DECISION;
      } finally {
        if (token == workToken) admitting = false;
      }
    }
    installing = true;
    boolean applied = false;
    try {
      if (decision == SearchNavigation.Decision.APPLY) {
        long before = state.revision;
        try {
          if (!state.applyDraft()) return Result.NEEDS_DECISION;
          applied = true;
        } catch (RuntimeException failure) {
          if (state.revision == before) {
            report("Resource selection rejected: ", failure);
            return Result.NEEDS_DECISION;
          }
          retire();
          report("Properties applied; resource selection refresh failed: ", failure);
          return Result.UNAVAILABLE;
        }
      }
      retire();
      // Apply can change resource membership. Never install an obsolete destination afterwards.
      if (!plan.equals(resolve(plan.ids, plan.active))) {
        return Result.UNAVAILABLE;
      }
      return installResult(plan, decision == SearchNavigation.Decision.DISCARD, applied);
    } finally {
      installing = false;
    }
  }

  private Result installResult(Plan plan, boolean discard, boolean applied) {
    try {
      state.selectResources(plan.ids, plan.active, plan.scope, discard);
      afterInstall.run();
      return Result.SELECTED;
    } catch (RuntimeException failure) {
      report(
          applied
              ? "Properties applied; resource selection refresh failed: "
              : "Resource selection refresh failed: ",
          failure);
      return Result.UNAVAILABLE;
    }
  }

  private void report(String prefix, RuntimeException failure) {
    state.error(
        "RESOURCE_SELECTION_FAILED",
        EditorEvents.Subsystem.PROJECT,
        StatusBarText.bounded(
            prefix + Objects.requireNonNullElse(failure.getMessage(), failure.toString()), 1024));
  }

  void clear() {
    if (installing) return;
    retire();
    progress = "";
  }

  private void retire() {
    retireWork();
    pending = null;
    context = null;
    source = null;
    draft = null;
    draftValue = null;
    sourceSelection = null;
    requestOwner = null;
  }

  private Result prepare(Plan plan) {
    retireWork();
    Object token = workToken;
    admitting = true;
    try {
      var request = state.requestSelectionApply();
      if (request == null) {
        progress = "Correct Properties input, or Cancel and request this selection again.";
        return Result.NEEDS_DECISION;
      }
      workOwner = new ProjectFileOwner(state);
      sharedIssues = state.issues;
      sharedValues = List.copyOf(sharedIssues);
      sharedScope = state.validationScope;
      progress =
          "Preparing Apply before resource selection in the background. Committed values and the"
              + " private draft remain active. Cancel or Escape keeps the current selection and"
              + " draft.";
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
                var result = prepared;
                var rejected = failure;
                ui.accept(() -> publish(token, plan, result, rejected));
                return null;
              });
      executor.execute(task);
      return Result.NEEDS_DECISION;
    } catch (Throwable failure) {
      fail(token, failure);
      return pending == null ? Result.UNAVAILABLE : Result.NEEDS_DECISION;
    } finally {
      if (token == workToken) admitting = false;
    }
  }

  private boolean owns(Object token, Plan plan) {
    return !disposed && token == workToken && pending == plan && task != null;
  }

  private boolean feedbackCurrent() {
    return sharedIssues == state.issues
        && sharedValues.equals(state.issues)
        && Objects.equals(sharedScope, state.validationScope);
  }

  private void publish(
      Object token, Plan plan, DocumentSession.PreparedEdit prepared, Throwable failure) {
    if (!owns(token, plan)) return;
    boolean applied = false;
    try {
      if (!workOwner.valuesCurrent(state)
          || !feedbackCurrent()
          || !plan.equals(resolve(plan.ids, plan.active))) {
        stale();
        return;
      }
      if (failure != null) {
        fail(token, failure);
        return;
      }
      boolean denied = blocked.getAsBoolean();
      if (!owns(token, plan)) return;
      if (denied
          || !workOwner.valuesCurrent(state)
          || !feedbackCurrent()
          || !plan.equals(resolve(plan.ids, plan.active))
          || !state.document.currentEdit(prepared)) {
        stale();
        return;
      }
      if (!prepared.success()) {
        var diagnostics = prepared.diagnostics();
        retireWork();
        progress =
            "Properties Apply was rejected. The selection and draft are retained. Retry Apply"
                + " explicitly, choose Discard, or Cancel before correcting the draft.";
        state.rejectDraftApply(diagnostics);
        return;
      }
      installing = true;
      state.document.applyEdit(prepared);
      applied = true;
      retire();
      state.finishDraftApply();
      // Applying a parent can remove or move destinations: resolve the entire sibling plan again.
      if (!plan.equals(resolve(plan.ids, plan.active))) {
        progress = "Properties applied. Requested resources changed; choose the selection again.";
        finish(Result.UNAVAILABLE);
        return;
      }
      var result = installResult(plan, false, true);
      progress =
          result == Result.SELECTED
              ? "Properties applied and resource selection installed."
              : "Properties applied; resource selection refresh failed. Request the selection"
                  + " again.";
      finish(result);
    } catch (Throwable rejected) {
      if (applied) {
        retire();
        progress =
            "Properties applied; resource selection refresh failed: "
                + BackgroundFailure.describe(rejected);
        reportFailure("RESOURCE_SELECTION_REFRESH_FAILED", progress);
        finish(Result.UNAVAILABLE);
      } else fail(token, rejected);
    } finally {
      installing = false;
    }
  }

  private void finish(Result result) {
    if (disposed) return;
    try {
      completion.accept(result);
    } catch (Throwable failure) {
      reportFailure(
          "RESOURCE_SELECTION_COMPLETION_FAILED",
          "Selection completion failed: " + BackgroundFailure.describe(failure));
    }
  }

  private void stale() {
    retire();
    progress =
        "Resource selection became stale. Current project, selection and private input are"
            + " retained; choose the resources again.";
    finish(Result.UNAVAILABLE);
  }

  private void refresh() {
    if (task == null || disposed) return;
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
        "Resource selection Apply failed: "
            + BackgroundFailure.describe(failure)
            + ". The draft and selection are retained; Apply again to retry, or Cancel.";
    reportFailure("RESOURCE_SELECTION_APPLY_FAILED", progress);
  }

  private void reportFailure(String code, String message) {
    try {
      state.error(code, EditorEvents.Subsystem.PROJECT, message);
    } catch (Throwable ignored) {
      /* Keep correction and cancellation available. */
    }
  }

  private void retireWork() {
    var previous = task;
    workToken = new Object();
    workVersion++;
    task = null;
    admitting = false;
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
    if (!installing) clear();
  }
}
