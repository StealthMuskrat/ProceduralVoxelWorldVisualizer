package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Search-specific draft protection. A target always resolves UUIDs in the current project. */
public final class SearchNavigation implements AutoCloseable {
  public enum Result {
    NAVIGATED,
    NEEDS_DECISION,
    UNAVAILABLE,
    CANCELLED
  }

  public enum Decision {
    APPLY,
    DISCARD,
    CANCEL
  }

  private final EditorState state;
  private EditorEvents.Target pending;
  private UUID source;
  private JsonNode draftBase, activeBase;
  private long seedVersion;
  private ProjectData project;
  private Object draftOwner;
  private List<UUID> selection;
  private long version;

  record Completion(EditorEvents.Target target, Result result, boolean applied) {}

  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Consumer<Completion> completion;
  private Object workToken = new Object();
  private FutureTask<Void> task;
  private record Prepared(DocumentSession.PreparedEdit value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean ready;
  private BooleanSupplier workBefore, workAfter;
  private boolean admitting, disposed;
  private long workVersion;
  private String progress = "";
  private ProjectFileOwner requestOwner, workOwner;
  private List<Diagnostic> sharedIssues, sharedValues;
  private String sharedScope;

  public SearchNavigation(EditorState state) {
    this(state, null, null, null);
  }

  SearchNavigation(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Consumer<Completion> completion) {
    this.state = state;
    this.executor = executor;
    this.ui = ui;
    this.completion = completion;
    if (executor != null) {
      Objects.requireNonNull(ui);
      Objects.requireNonNull(completion);
    }
  }

  public boolean pending() {
    refresh();
    return pending != null;
  }

  boolean preparing() {
    refresh();
    return task != null || ready || admitting;
  }

  long workVersion() {
    return workVersion;
  }

  String progress() {
    refresh();
    return progress;
  }

  public EditorEvents.Target target() {
    return pending;
  }

  public Result request(EditorEvents.Target target) {
    if (disposed || pending != null) return Result.UNAVAILABLE;
    if (!state.canNavigate(target)) return Result.UNAVAILABLE;
    version++;
    if (!Objects.equals(state.selected, target.resource()) && modified()) {
      pending = target;
      source = state.selected;
      project = state.project();
      draftOwner = state.draft;
      selection = List.copyOf(state.multiSelection);
      draftBase = ProjectIO.JSON.valueToTree(state.draft);
      activeBase = active();
      seedVersion = state.seedInputs.version(state);
      if (executor != null) requestOwner = new ProjectFileOwner(state);
      return Result.NEEDS_DECISION;
    }
    return state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE;
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

  private JsonNode active() {
    var entry = ResourceGraph.find(state.project(), state.selected);
    return ProjectIO.JSON.valueToTree(entry == null ? null : entry.resource());
  }

  public Result decide(Decision decision) {
    return decide(decision, () -> true);
  }

  Result decide(Decision decision, BooleanSupplier afterApply) {
    return decide(decision, () -> true, afterApply);
  }

  Result decide(Decision decision, BooleanSupplier beforeDecision, BooleanSupplier afterApply) {
    if (pending == null) return Result.UNAVAILABLE;
    if (decision == Decision.CANCEL) {
      clear();
      return Result.CANCELLED;
    }
    if (task != null || ready || admitting) return Result.NEEDS_DECISION;
    long owner = version;
    Object gate = workToken;
    if (executor != null) admitting = true;
    boolean allowed;
    try {
      allowed = beforeDecision.getAsBoolean();
    } catch (RuntimeException | Error failure) {
      if (executor == null) throw failure;
      if (gate != workToken || version != owner) return Result.UNAVAILABLE;
      fail(gate, failure);
      return Result.NEEDS_DECISION;
    } finally {
      if (executor != null && gate == workToken) admitting = false;
    }
    if (!allowed || version != owner) {
      if (version == owner) clear();
      return Result.UNAVAILABLE;
    }
    var target = pending;
    if (state.project() != project
        || state.draft != draftOwner
        || !selection.equals(List.copyOf(state.multiSelection))
        || !Objects.equals(state.selected, source)
        || seedVersion != state.seedInputs.version(state)
        || !draftBase.equals(ProjectIO.JSON.valueToTree(state.draft))
        || !activeBase.equals(active())
        || !state.canNavigate(target)) {
      clear();
      return Result.UNAVAILABLE;
    }
    if (decision == Decision.APPLY && executor != null) return prepare(beforeDecision, afterApply);
    if (decision == Decision.APPLY && !state.applyDraft()) return Result.NEEDS_DECISION;
    // A post-Apply source check may open a successor; retire this intent before calling it.
    clear();
    if (decision == Decision.APPLY) {
      long completed = version, revision = state.revision;
      var document = state.document.sessionToken();
      var applied = state.project();
      var selected = state.selected;
      var draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(draft);
      var selectedResources = List.copyOf(state.multiSelection);
      long inputs = state.seedInputs.version(state);
      if (!afterApply.getAsBoolean()
          || version != completed
          || state.document.sessionToken() != document
          || state.project() != applied
          || state.revision != revision
          || state.draft != draft
          || !Objects.equals(state.selected, selected)
          || !selectedResources.equals(List.copyOf(state.multiSelection))
          || state.seedInputs.version(state) != inputs
          || !values.equals(ProjectIO.JSON.valueToTree(state.draft))) return Result.UNAVAILABLE;
    }
    return state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE;
  }

  long version() {
    return version;
  }

  public void clear() {
    retireWork();
    version++;
    pending = null;
    source = null;
    draftBase = null;
    activeBase = null;
    project = null;
    draftOwner = null;
    selection = null;
    requestOwner = null;
    progress = "";
  }

  private Result prepare(BooleanSupplier beforeDecision, BooleanSupplier afterApply) {
    retireWork();
    Object token = workToken;
    admitting = true;
    try {
      if (requestOwner == null || !requestOwner.valuesCurrent(state)) {
        clear();
        return Result.UNAVAILABLE;
      }
      var request = state.requestDraftApply();
      if (request == null) {
        progress = "Correct the private Properties input, or Cancel and request navigation again.";
        return Result.NEEDS_DECISION;
      }
      workOwner = new ProjectFileOwner(state);
      sharedIssues = state.issues;
      sharedValues = List.copyOf(sharedIssues);
      sharedScope = state.validationScope;
      progress =
          "Preparing Apply in the background. Committed values and the private draft remain"
              + " active. Cancel or Escape keeps the draft and stays at the source.";
      var target = pending;
      long intent = version;
      workBefore = beforeDecision;
      workAfter = afterApply;
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
                if (!handoff.publish(handed, new Prepared(prepared, failure))) return null;
                ui.accept(() -> delivered(token, intent, target, handed));
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

  private boolean owns(Object token, long intent, EditorEvents.Target target) {
    return !disposed
        && token == workToken
        && version == intent
        && pending == target
        && (task != null || ready);
  }

  private void delivered(Object token, long intent, EditorEvents.Target target, long handed) {
    if (!owns(token, intent, target) || delivery != handed || ready) return;
    refresh();
    if (!owns(token, intent, target)) return;
    task = null;
    ready = true;
  }

  /** Owner calls only after its modal processes this frame's Cancel/Escape controls. */
  void acceptPrepared() {
    refresh();
    if (!ready || disposed || pending == null) return;
    var value = handoff.take();
    if (value == null) {
      fail(workToken, new IllegalStateException("Navigation Apply preparation unavailable"));
      return;
    }
    publish(workToken, version, pending, value.value(), value.failure(), workBefore, workAfter);
  }

  private boolean feedbackCurrent() {
    return sharedIssues == state.issues
        && sharedValues.equals(state.issues)
        && Objects.equals(sharedScope, state.validationScope);
  }

  private void publish(
      Object token,
      long intent,
      EditorEvents.Target target,
      DocumentSession.PreparedEdit prepared,
      Throwable failure,
      BooleanSupplier beforeDecision,
      BooleanSupplier afterApply) {
    if (!owns(token, intent, target)) return;
    boolean applied = false;
    long completed = -1;
    try {
      if (!workOwner.valuesCurrent(state) || !feedbackCurrent() || !state.canNavigate(target)) {
        stale(target);
        return;
      }
      if (failure != null) {
        fail(token, failure);
        return;
      }
      boolean current = beforeDecision.getAsBoolean();
      if (!owns(token, intent, target)) return;
      if (!current
          || !workOwner.valuesCurrent(state)
          || !feedbackCurrent()
          || !state.canNavigate(target)
          || !state.document.currentEdit(prepared)) {
        stale(target);
        return;
      }
      if (!prepared.success()) {
        var diagnostics = prepared.diagnostics();
        retireWork();
        progress =
            "Apply was rejected. The draft remains private; review Validation Results, retry"
                + " Apply explicitly, or Cancel before correcting the draft.";
        state.rejectDraftApply(diagnostics);
        return;
      }
      state.document.applyEdit(prepared);
      applied = true;
      // Retire the intent before refresh/freshness callbacks can open a successor.
      clear();
      completed = version;
      state.finishDraftApply();
      var published = new ProjectFileOwner(state);
      if (!afterApply.getAsBoolean()
          || disposed
          || version != completed
          || !published.valuesCurrent(state)
          || !state.canNavigate(target)) {
        if (!disposed && version == completed) emit(target, Result.UNAVAILABLE, true);
        return;
      }
      emit(target, state.navigate(target) ? Result.NAVIGATED : Result.UNAVAILABLE, true);
    } catch (Throwable rejected) {
      if (applied) {
        report(
            "NAVIGATION_APPLY_COMPLETION_FAILED",
            "Draft applied; navigation failed: " + BackgroundFailure.describe(rejected));
        if (!disposed && version == completed) emit(target, Result.UNAVAILABLE, true);
      } else fail(token, rejected);
    }
  }

  private void emit(EditorEvents.Target target, Result result, boolean applied) {
    try {
      completion.accept(new Completion(target, result, applied));
    } catch (Throwable failure) {
      report(
          "NAVIGATION_APPLY_REVEAL_FAILED",
          (applied ? "Draft applied; " : "") + BackgroundFailure.describe(failure));
    }
  }

  private void stale(EditorEvents.Target target) {
    clear();
    emit(target, Result.UNAVAILABLE, false);
  }

  private void refresh() {
    if ((task == null && !ready) || disposed) return;
    try {
      if (!workOwner.current(state) || !feedbackCurrent()) {
        stale(pending);
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
        "Apply preparation failed: "
            + BackgroundFailure.describe(failure)
            + ". The draft remains private; Apply again to retry, or Cancel.";
    report("NAVIGATION_APPLY_FAILED", progress);
  }

  private void report(String code, String text) {
    try {
      state.error(code, EditorEvents.Subsystem.PROJECT, text);
    } catch (Throwable ignored) {
      /* Keep cancellation and correction available. */
    }
  }

  private void retireWork() {
    var previous = task;
    workToken = new Object();
    workVersion++;
    task = null;
    ready = false;
    handoff.advance(++delivery);
    workBefore = workAfter = null;
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
    clear();
    handoff.close();
  }
}
