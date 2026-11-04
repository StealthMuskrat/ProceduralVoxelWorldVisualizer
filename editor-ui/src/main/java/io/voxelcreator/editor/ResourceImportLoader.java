package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.*;

/** UI-owned package preparation; workers own only destination and incoming snapshots. */
final class ResourceImportLoader implements AutoCloseable {
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
    ImportEditSession.Prepared prepare(Path source, ProjectData destination) throws Exception;
  }

  @FunctionalInterface
  interface Reviewer {
    boolean open(ImportEditSession.Prepared prepared, BooleanSupplier current);
  }

  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Preparation preparation;
  private final Reviewer review;
  private final BooleanSupplier blocked;
  private Phase phase = Phase.IDLE;
  private String message = "Choose a package to prepare for import.";
  private Object token = new Object();
  private ProjectFileOwner owner;
  private FutureTask<Void> task;

  ResourceImportLoader(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Preparation preparation,
      Reviewer review,
      BooleanSupplier blocked) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.preparation = Objects.requireNonNull(preparation);
    this.review = Objects.requireNonNull(review);
    this.blocked = Objects.requireNonNull(blocked);
  }

  View view() {
    refresh();
    return new View(phase, message);
  }

  boolean start(String location) {
    refresh();
    if (phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    retire();
    Object request = token;
    try {
      Path source = ResourcePackageFiles.location(location);
      owner = new ProjectFileOwner(state);
      var destination = ProjectIO.copy(state.project());
      phase = Phase.RUNNING;
      message =
          "Reading and validating the package and preparing its conflict preview in the"
              + " background.";
      task =
          new FutureTask<>(
              () -> {
                ImportEditSession.Prepared result = null;
                Throwable failure = null;
                try {
                  result = preparation.prepare(source, destination);
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
    } catch (Throwable failure) {
      fail(request, failure);
      return false;
    }
  }

  private boolean owns(Object request) {
    return request == token && phase == Phase.RUNNING;
  }

  private boolean current(Object request) {
    return owns(request) && owner.valuesCurrent(state);
  }

  private void publish(Object request, ImportEditSession.Prepared prepared, Throwable failure) {
    if (!owns(request)) return;
    refresh();
    if (!owns(request)) return;
    try {
      if (!current(request)) {
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
      if (unavailable || !current(request)) {
        stale();
        return;
      }
      boolean opened = review.open(Objects.requireNonNull(prepared), () -> current(request));
      if (!owns(request)) return;
      if (!opened) {
        fail(request, new IllegalStateException("Import review is blocked; retry when ready"));
        return;
      }
      release();
      phase = Phase.READY;
      message = "Package prepared. Review COPY, REUSE and SKIP before importing.";
    } catch (Throwable rejected) {
      fail(request, rejected);
    }
  }

  void refresh() {
    if (phase != Phase.RUNNING) return;
    try {
      if (!owner.current(state)) {
        stale();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (completion == null || completion.failure() == null) return;
      if (completion.failure() instanceof CancellationException) cancel();
      else fail(token, completion.failure());
    } catch (Throwable failure) {
      fail(token, failure);
    }
  }

  void cancel() {
    if (phase != Phase.RUNNING) return;
    retire();
    phase = Phase.CANCELLED;
    message = "Import preparation cancelled. The project is unchanged.";
  }

  private void stale() {
    retire();
    phase = Phase.STALE;
    message =
        "The project, private inputs or pending action changed. Retry to prepare the current"
            + " import.";
  }

  private void release() {
    token = new Object();
    owner = null;
    task = null;
  }

  private void retire() {
    var previous = task;
    release();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    retire();
    phase = Phase.FAILED;
    message = "Import preparation failed: " + BackgroundFailure.describe(failure);
    try {
      state.error("FILE_IMPORT_PREPARATION_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, message);
    } catch (Throwable ignored) {
      // Reporting must not trap the file workspace or publish a rejected import.
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    retire();
    phase = Phase.CLOSED;
    message = "Import preparation closed.";
  }
}
