package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Worker-owned configuration staging; final publication follows the settings input decision. */
final class MetadataApplySession implements AutoCloseable {
  private final EditorState state;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Supplier<Map<String, List<String>>> displayed;
  private final PreferenceFiles files;
  private final OwnedPreviewMailbox<Payload> handoff = new OwnedPreviewMailbox<>();
  private MetadataEditSession.Request request;
  private FutureTask<Void> task;
  private Object token = new Object();
  private boolean running, ready, admitted, closed;
  private long version;
  private String message = "";

  private final class Payload implements AutoCloseable {
    final MetadataEditSession.Prepared value;
    final Throwable failure;

    Payload(MetadataEditSession.Prepared value, Throwable failure) {
      this.value = value;
      this.failure = failure;
    }

    @Override
    public void close() {
      if (value == null) return;
      try {
        value.close();
      } catch (Throwable failure) {
        report(
            "APPLICATION_PROFILE_CLEANUP_FAILED",
            "Unpublished profile cleanup failed: " + BackgroundFailure.describe(failure));
      }
    }
  }

  MetadataApplySession(
      EditorState state,
      Executor executor,
      Consumer<Runnable> ui,
      Supplier<Map<String, List<String>>> displayed,
      PreferenceFiles files) {
    this.state = Objects.requireNonNull(state);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.displayed = Objects.requireNonNull(displayed);
    this.files = Objects.requireNonNull(files);
  }

  void start(MetadataEditSession session, Path target) {
    if (closed || running) return;
    Object work = retire();
    if (closed || work != token || running) return;
    try {
      var shown = displayed.get();
      if (closed || work != token || running) return;
      var captured = session.capture(state.metadata, shown);
      request = captured;
      long delivery = handoff.begin();
      running = true;
      message =
          "Validating and preparing the application profile in the background."
              + " Cancel Apply or Escape keeps your private profile.";
      task =
          new FutureTask<>(
              () -> {
                Payload payload;
                try {
                  var value = captured.prepare(target, files);
                  try {
                    payload = new Payload(value, null);
                  } catch (Throwable allocation) {
                    try {
                      value.close();
                    } catch (Throwable cleanup) {
                      if (allocation != cleanup) allocation.addSuppressed(cleanup);
                    }
                    throw allocation;
                  }
                } catch (Throwable rejected) {
                  payload = new Payload(null, rejected);
                }
                if (!handoff.publish(delivery, payload)) return null;
                // Queued UI callbacks retain identities, never a file or captured failure.
                ui.accept(() -> complete(work, delivery));
                return null;
              });
      executor.execute(task);
      if (owns(work)) admitted = true;
    } catch (Throwable failure) {
      fail(work, failure);
    }
  }

  private boolean owns(Object work) {
    return !closed && running && work == token;
  }

  private boolean current(Object work) {
    var captured = request;
    if (captured == null) return false;
    var shown = displayed.get();
    return owns(work) && request == captured && captured.current(state.metadata, shown);
  }

  private void complete(Object work, long delivery) {
    if (!owns(work) || !handoff.current(delivery)) return;
    // Success and preparation failure both wait for current panel controls. Keep the task
    // observable: an inline UI handoff may run this callback and then throw.
    ready = true;
  }

  void refresh() {
    if (!running || closed) return;
    Object work = token;
    try {
      boolean valid = current(work);
      if (!owns(work)) return;
      if (!valid) {
        Object invalidated = retire();
        if (closed || invalidated != token) return;
        message = "Profile or input changed. Apply the current profile explicitly.";
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(work, done.failure());
    } catch (Throwable failure) {
      fail(work, failure);
    }
  }

  boolean accept() {
    return accept(version);
  }

  boolean accept(long shownVersion) {
    Object work = token;
    if (shownVersion != version) return false;
    refresh();
    if (!owns(work) || !ready || !admitted || shownVersion != version) return false;
    Payload payload = null;
    try {
      var captured = request;
      if (!current(work) || !owns(work)) return false;
      var shown = displayed.get();
      if (!owns(work) || request != captured) return false;
      var completed = BackgroundFailure.completed(task);
      if (!owns(work) || request != captured || completed == null) return false;
      if (completed.failure() != null) {
        fail(work, completed.failure());
        return false;
      }
      payload = handoff.take();
      // The complete worker no longer needs observation during atomic publication.
      task = null;
      if (payload == null) throw new IllegalStateException("Profile preparation is unavailable");
      if (payload.failure != null) {
        fail(work, payload.failure);
        return false;
      }
      captured.publish(payload.value, state, shown);
      // Consume before panel reload, native consequences or external feedback.
      retire();
      message = "Application profile saved and activated";
      return true;
    } catch (Throwable failure) {
      fail(work, failure);
      return false;
    } finally {
      if (payload != null) payload.close();
    }
  }

  boolean running() {
    refresh();
    return running;
  }

  boolean ready() {
    refresh();
    return ready;
  }

  long version() {
    return version;
  }

  String message() {
    return message;
  }

  void cancel() {
    if (!running || closed) return;
    Object cancelled = retire();
    if (closed || cancelled != token) return;
    message = "Profile Apply cancelled. Your private profile is preserved.";
  }

  /** Return the identity reserved before cleanup, which may itself admit a successor. */
  private Object retire() {
    var previous = task;
    Object retired = new Object();
    token = retired;
    version++;
    task = null;
    request = null;
    running = ready = admitted = false;
    handoff.invalidate();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
    return retired;
  }

  private void fail(Object work, Throwable failure) {
    if (closed || work != token) return;
    Object failed = retire();
    if (closed || failed != token) return;
    String feedback =
        StatusBarText.bounded(
            "Profile Apply failed: "
                + BackgroundFailure.describe(failure)
                + ". Retry Apply explicitly or Revert Profile.",
            3072);
    if (closed || failed != token) return;
    message = feedback;
    report("APPLICATION_PROFILE_APPLY_FAILED", message);
  }

  private void report(String code, String detail) {
    try {
      state.error(code, EditorEvents.Subsystem.APPLICATION, detail);
    } catch (Throwable ignored) {
      /* Failure feedback must not strand cancellation, cleanup or the next Apply. */
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    retire();
    handoff.close();
  }
}
