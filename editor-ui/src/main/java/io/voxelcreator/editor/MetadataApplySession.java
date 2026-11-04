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
  private boolean running, ready, closed;
  private long version;
  private String message = "";

  private final class Payload implements AutoCloseable {
    final MetadataEditSession.Prepared value;

    Payload(MetadataEditSession.Prepared value) {
      this.value = value;
    }

    @Override
    public void close() {
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
    retire();
    Object work = token;
    try {
      request = session.capture(state.metadata, displayed.get());
      var captured = request;
      long delivery = handoff.begin();
      running = true;
      message =
          "Validating and preparing the application profile in the background."
              + " Cancel Apply or Escape keeps your private profile.";
      task =
          new FutureTask<>(
              () -> {
                Throwable failure = null;
                try {
                  var value = captured.prepare(target, files);
                  Payload payload;
                  try {
                    payload = new Payload(value);
                  } catch (Throwable allocation) {
                    try {
                      value.close();
                    } catch (Throwable cleanup) {
                      if (allocation != cleanup) allocation.addSuppressed(cleanup);
                    }
                    throw allocation;
                  }
                  if (!handoff.publish(delivery, payload)) return null;
                } catch (Throwable rejected) {
                  failure = rejected;
                }
                var rejected = failure;
                ui.accept(() -> complete(work, delivery, rejected));
                return null;
              });
      executor.execute(task);
    } catch (Throwable failure) {
      fail(work, failure);
    }
  }

  private boolean owns(Object work) {
    return !closed && running && work == token;
  }

  private boolean current() {
    return request != null && request.current(state.metadata, displayed.get());
  }

  private void complete(Object work, long delivery, Throwable failure) {
    if (!owns(work) || !handoff.current(delivery)) return;
    refresh();
    if (!owns(work)) return;
    if (failure != null) {
      fail(work, failure);
      return;
    }
    if (!handoff.pending()) {
      fail(work, new IllegalStateException("Profile preparation was not delivered"));
      return;
    }
    // Keep the payload private until the panel processes Escape/Revert/Cancel for this frame.
    ready = true;
    task = null;
    token = new Object();
  }

  void refresh() {
    if (!running || closed) return;
    try {
      if (!current()) {
        cancel();
        message = "Profile or input changed. Apply the current profile explicitly.";
        return;
      }
      var done = BackgroundFailure.completed(task);
      if (done != null && done.failure() != null) fail(token, done.failure());
    } catch (Throwable failure) {
      fail(token, failure);
    }
  }

  boolean accept() {
    refresh();
    if (!ready || !running || closed) return false;
    var payload = handoff.take();
    Object work = token;
    try {
      if (payload == null) throw new IllegalStateException("Profile preparation is unavailable");
      request.publish(payload.value, state, displayed.get());
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
    retire();
    message = "Profile Apply cancelled. Your private profile is preserved.";
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    request = null;
    running = ready = false;
    handoff.invalidate();
    if (previous != null) {
      previous.cancel(true);
      if (executor instanceof ThreadPoolExecutor pool) pool.remove(previous);
    }
  }

  private void fail(Object work, Throwable failure) {
    if (closed || work != token) return;
    retire();
    message =
        StatusBarText.bounded(
            "Profile Apply failed: "
                + BackgroundFailure.describe(failure)
                + ". Retry Apply explicitly or Revert Profile.",
            3072);
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
    retire();
    handoff.close();
    closed = true;
  }
}
