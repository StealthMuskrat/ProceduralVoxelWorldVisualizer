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
  private record Prepared(ImportEditSession.Prepared value, Throwable failure) {}
  private final PreviewMailbox<Prepared> handoff = new PreviewMailbox<>();
  private long delivery;
  private boolean preparedReady, admitted;
  private String sourceText;

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

  Object requestToken() {
    return token;
  }

  boolean start(String location) {
    refresh();
    if (phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    Object request = retire();
    if (request != token || phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    try {
      Path source = ResourcePackageFiles.location(location);
      sourceText = location;
      owner = new ProjectFileOwner(state);
      var destination = ProjectIO.copy(state.project());
      phase = Phase.RUNNING;
      message =
          "Reading and validating the package and preparing its conflict preview in the"
              + " background.";
      long handed = ++delivery;
      handoff.advance(handed);
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
                if (!handoff.publish(handed, new Prepared(result, failure))) return null;
                ui.accept(() -> delivered(request, handed));
                return null;
              });
      executor.execute(task);
      if (owns(request)) admitted = true;
      return owns(request);
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

  private void delivered(Object request, long handed) {
    if (!owns(request) || delivery != handed || preparedReady) return;
    // Readiness is only a receipt; posting and executor admission must both finish normally.
    preparedReady = true;
  }

  void acceptPrepared() {
    acceptPrepared(sourceText, () -> true);
  }

  /** Path controls and the exact current opening decide before review can be installed. */
  void acceptPrepared(String displayed, BooleanSupplier shown) {
    Object request = token;
    refresh();
    if (!owns(request) || !preparedReady || !admitted) return;
    if (!Objects.equals(sourceText, displayed)) {
      stale();
      return;
    }
    Objects.requireNonNull(shown);
    var completed = BackgroundFailure.completed(task);
    if (!owns(request) || completed == null) return;
    if (completed.failure() != null) {
      if (completed.failure() instanceof CancellationException) cancel();
      else fail(request, completed.failure());
      return;
    }
    var value = handoff.take();
    if (value == null) {
      fail(request, new IllegalStateException("Import package preparation unavailable"));
      return;
    }
    publish(request, value.value(), value.failure(), shown);
  }

  private boolean current(Object request, BooleanSupplier shown) {
    if (!current(request)) return false;
    boolean visible = shown.getAsBoolean();
    return visible && current(request);
  }

  private void publish(
      Object request,
      ImportEditSession.Prepared prepared,
      Throwable failure,
      BooleanSupplier shown) {
    if (!owns(request)) return;
    refresh();
    if (!owns(request)) return;
    try {
      if (!current(request, shown)) {
        if (!owns(request)) return;
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
      if (unavailable || !current(request, shown)) {
        if (!owns(request)) return;
        stale();
        return;
      }
      boolean opened = review.open(Objects.requireNonNull(prepared), () -> current(request, shown));
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
    Object request = token;
    try {
      if (!owner.current(state)) {
        stale();
        return;
      }
      var completion = BackgroundFailure.completed(task);
      if (!owns(request) || completion == null || completion.failure() == null) return;
      if (completion.failure() instanceof CancellationException) cancel();
      else fail(request, completion.failure());
    } catch (Throwable failure) {
      fail(request, failure);
    }
  }

  void cancel() {
    if (phase != Phase.RUNNING) return;
    phase = Phase.CANCELLED;
    Object cancelled = retire();
    if (cancelled != token || phase != Phase.CANCELLED) return;
    message = "Import preparation cancelled. The project is unchanged.";
  }

  private void stale() {
    phase = Phase.STALE;
    Object invalidated = retire();
    if (invalidated != token || phase != Phase.STALE) return;
    message =
        "The project, private inputs or pending action changed. Retry to prepare the current"
            + " import.";
  }

  private void release() {
    token = new Object();
    owner = null;
    task = null;
    sourceText = null;
    preparedReady = admitted = false;
    handoff.advance(++delivery);
  }

  /** Preserve the identity reserved before cancellation can admit a successor. */
  private Object retire() {
    var previous = task;
    release();
    Object retired = token;
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
  }

  private void fail(Object request, Throwable failure) {
    if (request != token || phase == Phase.CLOSED) return;
    phase = Phase.FAILED;
    Object failed = retire();
    if (failed != token || phase != Phase.FAILED) return;
    String feedback = "Import preparation failed: " + BackgroundFailure.describe(failure);
    if (failed != token || phase != Phase.FAILED) return;
    message = feedback;
    try {
      state.error("FILE_IMPORT_PREPARATION_FAILED", EditorEvents.Subsystem.IMPORT_EXPORT, message);
    } catch (Throwable ignored) {
      // Reporting must not trap the file workspace or publish a rejected import.
    }
  }

  @Override
  public void close() {
    if (phase == Phase.CLOSED) return;
    phase = Phase.CLOSED;
    retire();
    handoff.close();
    message = "Import preparation closed.";
  }
}
