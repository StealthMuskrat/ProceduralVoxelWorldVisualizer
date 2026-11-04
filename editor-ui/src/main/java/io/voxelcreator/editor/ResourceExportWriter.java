package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** Background private staging; only the current UI request may replace the reviewed destination. */
final class ResourceExportWriter implements AutoCloseable {
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

  @FunctionalInterface
  interface Preparation {
    ResourcePackageFiles.Prepared prepare(Path target, ProjectData resources, Path activeProject)
        throws Exception;
  }

  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Preparation preparation;
  private final BooleanSupplier blocked;
  private final OwnedPreviewMailbox<Payload> handoff = new OwnedPreviewMailbox<>();
  private Phase phase = Phase.IDLE;
  private String message = "Review the included resources before exporting.";
  private Object token = new Object();
  private ProjectFileOwner authority;
  private ExportEditSession source;
  private ResourceExportSession.Preview preview;
  private BooleanSupplier gate;
  private FutureTask<Void> task;
  private boolean publishing, closeRequested;

  private final class Payload implements AutoCloseable {
    final ResourcePackageFiles.Prepared prepared;

    Payload(ResourcePackageFiles.Prepared prepared) {
      this.prepared = prepared;
    }

    @Override
    public void close() {
      try {
        prepared.close();
      } catch (Throwable failure) {
        try {
          // Late cancelled workers may dispose here; publish only through the event service.
          state.error(
              "RESOURCE_EXPORT_CLEANUP_FAILED",
              EditorEvents.Subsystem.IMPORT_EXPORT,
              "Unpublished package cleanup failed: " + BackgroundFailure.describe(failure));
        } catch (Throwable ignored) {
          /* Cleanup reporting cannot strand a successor request. */
        }
      }
    }
  }

  ResourceExportWriter(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Preparation preparation,
      BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.preparation = Objects.requireNonNull(preparation);
    this.blocked = Objects.requireNonNull(blocked);
  }

  View view() {
    refresh();
    return new View(phase, message);
  }

  boolean publishing() {
    return publishing;
  }

  boolean start(
      String location,
      ExportEditSession source,
      ResourceExportSession.Preview preview,
      BooleanSupplier gate) {
    refresh();
    if (phase == Phase.RUNNING || phase == Phase.CLOSED) return false;
    retire();
    Object request = token;
    try {
      this.source = Objects.requireNonNull(source);
      this.preview = Objects.requireNonNull(preview);
      this.gate = Objects.requireNonNull(gate);
      Path target = ResourcePackageFiles.location(location);
      authority = new ProjectFileOwner(state);
      Path activeProject = state.document.path();
      var resources = source.exportSnapshot(preview);
      long delivery = handoff.begin();
      phase = Phase.RUNNING;
      message =
          "Validating and writing the reviewed resources to a private temporary package in the"
              + " background.";
      task =
          new FutureTask<>(
              () -> {
                Throwable failure = null;
                try {
                  var prepared = preparation.prepare(target, resources, activeProject);
                  if (!handoff.publish(delivery, new Payload(prepared))) return null;
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var rejected = failure;
                ui.accept(() -> publish(request, delivery, rejected));
                return null;
              });
      executor.execute(task);
      return true;
    } catch (Throwable failure) {
      fail(request, failure);
      return false;
    }
  }

  private boolean owns(Object request) {
    return request == token && phase == Phase.RUNNING;
  }

  private boolean current() {
    return authority.current(state)
        && source.current()
        && source.preview() == preview
        && gate.getAsBoolean();
  }

  void refresh() {
    if (phase != Phase.RUNNING || publishing) return;
    Object request = token;
    try {
      boolean current = current();
      if (!owns(request)) return;
      if (!current) {
        stale();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (completion != null && completion.failure() != null) {
        if (completion.failure() instanceof CancellationException) cancel();
        else fail(request, completion.failure());
      }
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  private void publish(Object request, long delivery, Throwable failure) {
    if (!owns(request) || !handoff.current(delivery)) return;
    refresh();
    if (!owns(request)) return;
    var payload = handoff.take();
    try {
      boolean accepted = current();
      if (!owns(request)) return;
      if (!accepted || !authority.valuesCurrent(state)) {
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
      boolean unavailable = blocked.getAsBoolean();
      if (!owns(request)) return;
      accepted = current();
      if (!owns(request)) return;
      if (unavailable || !accepted || !authority.valuesCurrent(state)) {
        stale();
        return;
      }
      if (payload == null) throw new IllegalStateException("Export preparation was not delivered");
      Object completed = new Object();
      publishing = true;
      source.publishPrepared(preview, payload.prepared);
      release(completed);
      phase = Phase.READY;
      message = "Resource package exported.";
    } catch (Throwable rejected) {
      fail(request, rejected);
    } finally {
      publishing = false;
      if (payload != null) payload.close();
      if (closeRequested) close();
    }
  }

  void cancel() {
    if (phase != Phase.RUNNING || publishing) return;
    retire();
    phase = Phase.CANCELLED;
    message = "Export cancelled before replacement. The previous destination is retained.";
  }

  private void stale() {
    retire();
    phase = Phase.STALE;
    message =
        "The export source, selection or private inputs changed. Review the current preview and"
            + " retry.";
  }

  private void release() {
    release(new Object());
  }

  private void release(Object next) {
    token = next;
    task = null;
    authority = null;
    source = null;
    preview = null;
    gate = null;
  }

  private void retire() {
    var previous = task;
    release();
    handoff.invalidate();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    message = "Export failed: " + BackgroundFailure.describe(failure);
    try {
      state.error("FILE_EXPORT_PREPARATION_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, message);
    } catch (Throwable ignored) {
      /* Feedback cannot trap the package workspace. */
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    if (publishing) {
      closeRequested = true;
      return;
    }
    retire();
    handoff.close();
    phase = Phase.CLOSED;
    message = "Export preparation closed.";
  }
}
