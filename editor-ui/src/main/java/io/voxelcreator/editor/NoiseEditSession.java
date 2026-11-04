package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseAuthoring;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One private structural edit tied to its opening Properties and complete project values. */
final class NoiseEditSession implements AutoCloseable {
  enum Phase {
    RUNNING,
    READY,
    FAILED,
    STALE,
    CLOSED
  }

  private final EditorState state;
  private final ProjectData project;
  private final Object lifetime;
  private final UUID projectId, ownerId;
  private final Noise owner;
  private final long revision, seedVersion;
  private final String kind;
  private final List<UUID> selection;
  private final JsonNode draftBase, projectBase;
  private final Action action;
  private final UUID target;
  private Resolution resolution;
  private UUID replacement;
  private NoiseAuthoring.Plan plan;
  private boolean closed;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Planner planner;
  private FutureTask<Void> task;
  private Object token = new Object();
  private long version;
  private Phase phase;
  private String message = "";

  @FunctionalInterface
  interface Planner {
    NoiseAuthoring.Plan plan(ProjectData project, Noise noise, Request request);
  }

  NoiseEditSession(EditorState state, Noise owner, Request request) {
    this(state, owner, request, null, null, NoiseAuthoring::plan);
  }

  NoiseEditSession(
      EditorState state, Noise owner, Request request, Executor executor, Consumer<Runnable> ui) {
    this(state, owner, request, executor, ui, NoiseAuthoring::plan);
  }

  NoiseEditSession(
      EditorState state,
      Noise owner,
      Request request,
      Executor executor,
      Consumer<Runnable> ui,
      Planner planner) {
    Objects.requireNonNull(request);
    if (state.draft != owner
        || !Objects.equals(state.selected, owner.uuid)
        || !(EditorState.find(state.project(), owner.uuid) instanceof Noise))
      throw new IllegalArgumentException("Select the noise's current Properties draft");
    this.state = state;
    this.owner = owner;
    this.executor = executor;
    this.ui = ui;
    this.planner = Objects.requireNonNull(planner);
    if (executor != null) Objects.requireNonNull(ui);
    project = state.project();
    lifetime = state.document.sessionToken();
    projectId = project.project.uuid;
    ownerId = owner.uuid;
    revision = state.revision;
    seedVersion = state.seedInputs.version(state);
    kind = state.selectedKind;
    selection = List.copyOf(state.multiSelection);
    draftBase = ProjectIO.JSON.valueToTree(owner);
    projectBase = ProjectIO.JSON.valueToTree(project);
    action = request.action();
    target = request.target();
    resolution = request.resolution();
    replacement = request.replacement();
    if (executor == null) {
      plan = planner.plan(project, owner, request);
      phase = Phase.READY;
    } else schedule();
  }

  boolean current() {
    return !closed
        && lifetime == state.document.sessionToken()
        && state.project() == project
        && Objects.equals(projectId, project.project.uuid)
        && state.revision == revision
        && state.draft == owner
        && Objects.equals(ownerId, owner.uuid)
        && Objects.equals(state.selected, ownerId)
        && Objects.equals(state.selectedKind, kind)
        && selection.equals(List.copyOf(state.multiSelection))
        && seedVersion == state.seedInputs.version(state);
  }

  private void requireCurrent() {
    if (!current()
        || !draftBase.equals(ProjectIO.JSON.valueToTree(owner))
        || !projectBase.equals(ProjectIO.JSON.valueToTree(state.project()))) {
      if (executor != null && !closed) stale();
      throw new IllegalStateException("Draft or project changed; Cancel and reopen this edit");
    }
  }

  NoiseAuthoring.Plan preview() {
    refresh();
    return plan;
  }

  Resolution resolution() {
    return resolution;
  }

  UUID replacement() {
    return replacement;
  }

  Phase phase() {
    refresh();
    return phase;
  }

  String message() {
    refresh();
    return message;
  }

  long version() {
    return version;
  }

  boolean ready() {
    refresh();
    return phase == Phase.READY && plan != null;
  }

  boolean canStage() {
    return current() && ready() && plan.valid();
  }

  boolean canRetry() {
    refresh();
    return phase == Phase.FAILED && current();
  }

  void choose(Resolution resolution, UUID replacement) {
    requireCurrent();
    Objects.requireNonNull(resolution);
    if (this.resolution == resolution && Objects.equals(this.replacement, replacement)) return;
    if (executor == null) {
      var next = planner.plan(project, owner, new Request(action, target, resolution, replacement));
      this.resolution = resolution;
      this.replacement = replacement;
      plan = next;
    } else {
      this.resolution = resolution;
      this.replacement = replacement;
      schedule();
    }
  }

  void retry() {
    if (!canRetry()) return;
    requireCurrent();
    schedule();
  }

  private void schedule() {
    retire();
    phase = Phase.RUNNING;
    message =
        "Checking the structural edit in the background. Cancel or Escape keeps Properties"
            + " unchanged."
            + (plan == null
                ? ""
                : " Previous preview issues remain visible until checking finishes.");
    Object request = token;
    var desired = new Request(action, target, resolution, replacement);
    try {
      task =
          new FutureTask<>(
              () -> {
                NoiseAuthoring.Plan value = null;
                Throwable failure = null;
                try {
                  var projectCopy = ProjectIO.JSON.convertValue(projectBase, ProjectData.class);
                  var draftCopy = ProjectIO.JSON.convertValue(draftBase, Noise.class);
                  value = Objects.requireNonNull(planner.plan(projectCopy, draftCopy, desired));
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var completed = value;
                var rejected = failure;
                ui.accept(() -> publish(request, completed, rejected));
                return null;
              });
      executor.execute(task);
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private boolean owns(Object request) {
    return request == token && !closed && phase == Phase.RUNNING;
  }

  private void publish(Object request, NoiseAuthoring.Plan completed, Throwable failure) {
    if (!owns(request)) return;
    try {
      requireCurrent();
    } catch (RuntimeException changed) {
      stale();
      return;
    } catch (Throwable rejected) {
      fail(request, rejected);
      return;
    }
    if (failure != null) {
      fail(request, failure);
      return;
    }
    retire();
    plan = completed;
    phase = Phase.READY;
    message = "";
  }

  private void refresh() {
    if (phase != Phase.RUNNING || closed) return;
    try {
      if (!current()) {
        stale();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (completion != null && completion.failure() != null) fail(token, completion.failure());
    } catch (Throwable failure) {
      fail(token, failure);
    }
  }

  private void stale() {
    if (closed || phase == Phase.STALE) return;
    retire();
    phase = Phase.STALE;
    message = "Draft or project changed. Cancel and reopen the structural edit.";
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || closed) return;
    retire();
    phase = Phase.FAILED;
    message =
        "Noise edit preview failed: "
            + BackgroundFailure.describe(failure)
            + ". Retry preview or Cancel.";
    try {
      state.error("NOISE_EDIT_PREVIEW_FAILED", EditorEvents.Subsystem.APPLICATION, message);
    } catch (Throwable ignored) {
      /* Keep explicit retry and cancellation available. */
    }
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  /** Publication is private to Properties; the caller handles focus only after this is consumed. */
  UUID stage() {
    requireCurrent();
    if (!ready())
      throw new IllegalStateException("Wait for the current structural preview before Apply");
    var accepted = plan;
    accepted.applyTo(owner);
    closed = true;
    retire();
    phase = Phase.CLOSED;
    return accepted.selected();
  }

  void cancel() {
    if (closed) return;
    closed = true;
    retire();
    phase = Phase.CLOSED;
  }

  boolean closed() {
    return closed;
  }

  @Override
  public void close() {
    cancel();
  }
}
