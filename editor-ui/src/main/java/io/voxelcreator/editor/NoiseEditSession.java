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
  private record Prepared(NoiseAuthoring.Plan value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean preparedReady, admitted;
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
    Object request = retire();
    if (closed || request != token || task != null) return;
    phase = Phase.RUNNING;
    message =
        "Checking the structural edit in the background. Cancel or Escape keeps Properties"
            + " unchanged."
            + (plan == null
                ? ""
                : " Previous preview issues remain visible until checking finishes.");
    var desired = new Request(action, target, resolution, replacement);
    try {
      long handed = ++delivery;
      handoff.advance(handed);
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
                if (!handoff.publish(handed, new Prepared(value, failure))) return null;
                ui.accept(() -> delivered(request, handed));
                return null;
              });
      executor.execute(task);
      if (owns(request)) admitted = true;
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private boolean owns(Object request) {
    return request == token && !closed && phase == Phase.RUNNING;
  }

  private void delivered(Object request, long handed) {
    if (!owns(request) || delivery != handed || preparedReady) return;
    // A receipt does not prove complete posting or successful executor admission.
    preparedReady = true;
  }

  void acceptPrepared() {
    acceptPrepared(() -> true);
  }

  /** Current resolution, Cancel/Escape and host controls decide before adopting this preview. */
  void acceptPrepared(BooleanSupplier shown) {
    Object request = token;
    refresh();
    if (!owns(request) || !preparedReady || !admitted) return;
    Objects.requireNonNull(shown);
    var completed = BackgroundFailure.completed(task);
    if (!owns(request) || completed == null) return;
    if (completed.failure() != null) {
      fail(request, completed.failure());
      return;
    }
    var value = handoff.take();
    if (value == null) {
      fail(request, new IllegalStateException("Noise edit preview preparation unavailable"));
      return;
    }
    publish(request, value.value(), value.failure(), shown);
  }

  private void publish(
      Object request, NoiseAuthoring.Plan completed, Throwable failure, BooleanSupplier shown) {
    if (!owns(request)) return;
    try {
      requireCurrent();
      boolean visible = shown.getAsBoolean();
      if (!owns(request)) return;
      if (!visible) {
        stale();
        return;
      }
      requireCurrent();
    } catch (RuntimeException changed) {
      if (owns(request)) stale();
      return;
    } catch (Throwable rejected) {
      fail(request, rejected);
      return;
    }
    // Retire the complete task with its request before installing the private preview.
    if (failure != null) {
      fail(request, failure);
      return;
    }
    Object adopted = retire();
    if (closed || adopted != token) return;
    plan = Objects.requireNonNull(completed);
    phase = Phase.READY;
    message = "";
  }

  private void refresh() {
    if (phase != Phase.RUNNING || closed) return;
    Object request = token;
    try {
      if (!current()) {
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

  private void stale() {
    if (closed || phase == Phase.STALE) return;
    phase = Phase.STALE;
    Object invalidated = retire();
    if (closed || invalidated != token) return;
    message = "Draft or project changed. Cancel and reopen the structural edit.";
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || closed) return;
    phase = Phase.FAILED;
    Object failed = retire();
    if (closed || failed != token) return;
    String feedback =
        "Noise edit preview failed: "
            + BackgroundFailure.describe(failure)
            + ". Retry preview or Cancel.";
    if (closed || failed != token) return;
    message = feedback;
    try {
      state.error("NOISE_EDIT_PREVIEW_FAILED", EditorEvents.Subsystem.APPLICATION, message);
    } catch (Throwable ignored) {
      /* Keep explicit retry and cancellation available. */
    }
  }

  private Object retire() {
    var previous = task;
    Object retired = new Object();
    token = retired;
    version++;
    task = null;
    preparedReady = admitted = false;
    handoff.advance(++delivery);
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
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
    handoff.close();
    return accepted.selected();
  }

  void cancel() {
    if (closed) return;
    closed = true;
    retire();
    phase = Phase.CLOSED;
    handoff.close();
  }

  boolean closed() {
    return closed;
  }

  @Override
  public void close() {
    cancel();
  }
}
