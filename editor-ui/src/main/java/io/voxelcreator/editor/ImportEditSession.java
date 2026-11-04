package io.voxelcreator.editor;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Owned editor import: changed destinations require explicit reload and a displayed preview. */
final class ImportEditSession {
  enum Result {
    APPLIED,
    REJECTED,
    STALE,
    NOT_REVIEWED,
    BUSY,
    CLOSED
  }

  /** Privately owned initial plan; worker construction never touches the live editor. */
  static final class Prepared {
    private final ProjectData incoming;
    private final ResourceImport.Session model;
    private boolean consumed;

    private Prepared(ProjectData incoming, ResourceImport.Session model) {
      this.incoming = incoming;
      this.model = model;
    }
  }

  static Prepared prepare(ProjectData destination, ProjectData incoming) throws IOException {
    var owned = ProjectIO.copy(Objects.requireNonNull(incoming));
    var issues = NoiseCompiler.validate(owned);
    if (!issues.isEmpty())
      throw new IOException("Invalid import resources: " + issues.stream().limit(8).toList());
    var model = new ResourceImport.Session(destination, owned);
    model.preview();
    return new Prepared(owned, model);
  }

  private final EditorState state;
  private final ProjectData incoming;
  private final UUID projectId;
  private final Object document;
  private ProjectData destination;
  private long revision;
  private ResourceImport.Session model;
  private ResourceImport.Plan visible, shown;
  private Map<UUID, ResourceImport.Choice> choices = Map.of();
  private boolean closed, forcedStale, applying;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final BooleanSupplier blocked;
  private Object request = new Object();
  private FutureTask<Void> planningTask;
  private ProjectFileOwner planningOwner;
  private boolean planReady, reloadRequested;
  private String planningMessage = "";
  private List<Diagnostic> applyIssues = List.of();
  private int issueCount;

  ImportEditSession(EditorState state, ProjectData incoming) {
    this(state, incoming, null, null, () -> false);
  }

  ImportEditSession(
      EditorState state,
      ProjectData incoming,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    if (executor != null) Objects.requireNonNull(ui);
    this.incoming = ProjectIO.copy(Objects.requireNonNull(incoming));
    projectId = state.project().project.uuid;
    document = state.document.sessionToken();
    install(new ResourceImport.Session(state.project(), this.incoming));
  }

  ImportEditSession(EditorState state, Prepared prepared) {
    this(state, prepared, null, null, () -> false);
  }

  ImportEditSession(
      EditorState state,
      Prepared prepared,
      Executor executor,
      Consumer<Runnable> ui,
      BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = executor;
    this.ui = ui;
    this.blocked = Objects.requireNonNull(blocked);
    if (executor != null) Objects.requireNonNull(ui);
    Objects.requireNonNull(prepared);
    if (prepared.consumed || !prepared.model.current(state.project()))
      throw new IllegalStateException("Import preparation was consumed or the destination changed");
    incoming = prepared.incoming;
    projectId = state.project().project.uuid;
    document = state.document.sessionToken();
    install(prepared.model);
    prepared.consumed = true;
  }

  private void install(ResourceImport.Session next) {
    var preview = next.preview(); // Async callers supply an already computed, cached plan.
    model = next;
    visible = preview;
    choices = next.choices();
    destination = state.project();
    revision = state.revision;
    forcedStale = false;
    planReady = true;
    shown = null;
    planningMessage = "";
    clearIssues();
  }

  private void clearIssues() {
    applyIssues = List.of();
    issueCount = 0;
  }

  boolean current() {
    return !closed
        && !applying
        && !forcedStale
        && document == state.document.sessionToken()
        && Objects.equals(projectId, state.project().project.uuid)
        && state.project() == destination
        && state.revision == revision;
  }

  boolean canReload() {
    return !closed
        && !applying
        && document == state.document.sessionToken()
        && Objects.equals(projectId, state.project().project.uuid);
  }

  boolean busy() {
    return applying;
  }

  boolean closed() {
    return closed;
  }

  ResourceImport.Plan preview() {
    refresh();
    return visible;
  }

  Map<UUID, ResourceImport.Choice> choices() {
    return choices;
  }

  ResourceImport.Choice choice(UUID id) {
    return choices().getOrDefault(id, ResourceImport.Choice.COPY);
  }

  void choose(UUID id, ResourceImport.Choice choice) {
    if (!current()) throw new IllegalStateException("Reload the changed import destination first");
    if (choice == null) throw new IllegalArgumentException("Choose a resolution");
    var previous = choice(id);
    var next = new LinkedHashMap<>(choices);
    if (choice == ResourceImport.Choice.COPY) next.remove(id);
    else next.put(id, choice);
    var fork = model.withChoices(next); // Validate even an unchanged/default choice.
    fork.choose(id, choice);
    if (previous != choice) {
      choices = fork.choices();
      shown = null;
      clearIssues();
      if (executor == null) install(fork);
      else schedule(false, fork);
    }
  }

  void reload() {
    if (!canReload())
      throw new IllegalStateException("The project was replaced; reopen Import for this project");
    if (executor == null) {
      var next = new ResourceImport.Session(state.project(), incoming);
      choices.forEach(next::choose);
      install(next);
    } else schedule(true, null);
  }

  boolean planning() {
    refresh();
    return planningTask != null;
  }

  String planningMessage() {
    refresh();
    return planningMessage;
  }

  boolean canRetryPlan() {
    refresh();
    return !planReady && planningTask == null && canReload() && (reloadRequested || current());
  }

  void retryPlan() {
    if (!canRetryPlan()) return;
    if (reloadRequested) reload();
    else schedule(false, model.withChoices(choices));
  }

  private void schedule(boolean reload, ResourceImport.Session fork) {
    retirePlanning();
    planReady = false;
    shown = null;
    clearIssues();
    reloadRequested = reload;
    Object ticket = request;
    try {
      planningOwner = new ProjectFileOwner(state);
      var snapshot = reload ? ProjectIO.copy(state.project()) : null;
      var resolutions = choices;
      planningMessage =
          "Rebuilding the conflict preview in the background. Rows show the last completed plan."
              + " Review the new plan before Import.";
      planningTask =
          new FutureTask<>(
              () -> {
                ResourceImport.Session result = null;
                Throwable failure = null;
                try {
                  var next = reload ? new ResourceImport.Session(snapshot, incoming) : fork;
                  if (reload) resolutions.forEach(next::choose);
                  next.preview();
                  result = next;
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var completed = result;
                var rejected = failure;
                ui.accept(() -> publish(ticket, completed, rejected));
                return null;
              });
      executor.execute(planningTask);
    } catch (Throwable failure) {
      failPlanning(ticket, failure);
    }
  }

  private boolean ownsPlanning(Object ticket) {
    return ticket == request && planningTask != null && !closed;
  }

  private void publish(Object ticket, ResourceImport.Session result, Throwable failure) {
    if (!ownsPlanning(ticket)) return;
    try {
      if (!planningOwner.valuesCurrent(state)) {
        stalePlanning();
        return;
      }
      if (failure != null) {
        failPlanning(ticket, failure);
        return;
      }
      boolean unavailable = blocked.getAsBoolean();
      if (!ownsPlanning(ticket)) return;
      if (unavailable
          || !planningOwner.valuesCurrent(state)
          || !canReload()
          || !Objects.requireNonNull(result).current(state.project())) {
        stalePlanning();
        return;
      }
      retirePlanning();
      install(result);
    } catch (Throwable rejected) {
      failPlanning(ticket, rejected);
    }
  }

  private void refresh() {
    if (planningTask == null || closed) return;
    try {
      if (!planningOwner.current(state)) {
        stalePlanning();
        return;
      }
      var completion = BackgroundFailure.completed(planningTask);
      if (completion != null && completion.failure() != null)
        failPlanning(request, completion.failure());
    } catch (Throwable rejected) {
      failPlanning(request, rejected);
    }
  }

  private void stalePlanning() {
    retirePlanning();
    if (!model.current(state.project())) forcedStale = true;
    planningMessage =
        "The destination, private inputs or pending action changed. Reload or Retry preview before"
            + " Import.";
  }

  private void failPlanning(Object ticket, Throwable failure) {
    if (ticket != request || closed) return;
    retirePlanning();
    planningMessage =
        "Preview rebuild failed: "
            + BackgroundFailure.describe(failure)
            + ". Retry preview or Cancel.";
    try {
      state.error("IMPORT_PREVIEW_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, planningMessage);
    } catch (Throwable ignored) {
      // Feedback cannot trap the workspace or authorize an old plan.
    }
  }

  private void retirePlanning() {
    var previous = planningTask;
    request = new Object();
    planningTask = null;
    planningOwner = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  void markShown(ResourceImport.Plan preview) {
    refresh();
    if (current() && planReady && preview == visible) shown = preview;
  }

  boolean canApply() {
    refresh();
    return current() && planReady && shown == visible && shown != null && shown.valid();
  }

  List<Diagnostic> applyIssues() {
    return applyIssues;
  }

  int issueCount() {
    return issueCount;
  }

  Result apply() {
    if (closed) return Result.CLOSED;
    if (applying) return Result.BUSY;
    refresh();
    if (!planReady) return Result.NOT_REVIEWED;
    if (!current() || !model.current(state.project())) {
      forcedStale = true;
      return Result.STALE;
    }
    if (shown != visible) return Result.NOT_REVIEWED;
    if (!shown.valid()) return Result.REJECTED;
    // Import only appends/reuses resources. Preserve the exact separate Properties draft/selection.
    applying = true;
    try {
      var result = state.changeResult(model::applyTo);
      if (!result.applied()) {
        issueCount = result.issueCount();
        applyIssues = result.issues();
        return Result.REJECTED;
      }
      closed = true;
      shown = null;
      return Result.APPLIED;
    } finally {
      applying = false;
    }
  }

  void cancel() {
    if (applying) return;
    retirePlanning();
    closed = true;
    shown = null;
    applyIssues = List.of();
    issueCount = 0;
  }
}
