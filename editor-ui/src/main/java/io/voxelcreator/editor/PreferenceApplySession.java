package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Shared private-form staging; atomic preference activation follows the panel input decision. */
final class PreferenceApplySession implements AutoCloseable {
  private final UserConfig user;
  private final Path target;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Consumer<String> errors;
  private final boolean saving;
  private final OwnedPreviewMailbox<Payload> handoff = new OwnedPreviewMailbox<>();
  private UserConfig.PreferenceRequest request;
  private BooleanSupplier input;
  private FutureTask<Void> task;
  private Object token = new Object();
  private boolean running, ready, closed, stale;
  private long version;
  private String message = "";

  private final class Payload implements AutoCloseable {
    final UserConfig.PreparedPreference value;

    Payload(UserConfig.PreparedPreference value) {
      this.value = value;
    }

    @Override
    public void close() {
      try {
        value.close();
      } catch (Throwable failure) {
        report("Unpublished preference cleanup failed: " + BackgroundFailure.describe(failure));
      }
    }
  }

  PreferenceApplySession(
      UserConfig user, Path target, Executor executor,
      Consumer<Runnable> ui, Consumer<String> errors) {
    this(user, target, executor, ui, errors, false);
  }

  PreferenceApplySession(
      UserConfig user, Path target, Executor executor,
      Consumer<Runnable> ui, Consumer<String> errors, boolean saving) {
    this.user = Objects.requireNonNull(user);
    this.target = Objects.requireNonNull(target);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.errors = Objects.requireNonNull(errors);
    this.saving = saving;
  }

  void start(UserConfig.PreferenceRequest request, BooleanSupplier input) {
    if (closed || running) return;
    retire();
    Object work = token;
    try {
      this.request = Objects.requireNonNull(request);
      this.input = Objects.requireNonNull(input);
      if (!current()) throw new IllegalStateException("Preference input changed before Apply");
      var captured = request;
      long delivery = handoff.begin();
      running = true;
      message = saving ? "Saving active preferences in the background."
          : "Preparing preferences in the background. Cancel Apply or Escape keeps private input.";
      task =
          new FutureTask<>(
              () -> {
                Throwable failure = null;
                try {
                  var value = captured.prepare(target);
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

  private boolean current() {
    return request != null && input.getAsBoolean() && request.current(user);
  }

  private boolean owns(Object work) {
    return running && !closed && work == token;
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
      fail(work, new IllegalStateException("Preference preparation was not delivered"));
      return;
    }
    ready = true;
    task = null;
    token = new Object();
  }

  void refresh() {
    if (!running || closed) return;
    try {
      // Active saves coalesce on completion rather than cancelling a still-writing worker on
      // every workspace/slider frame. They still recheck full source values before publication.
      if ((!saving || ready || handoff.pending()) && !current()) {
        cancel();
        stale = true;
        message = saving ? "Preferences changed; saving the latest active values."
            : "Preferences or private input changed. Review and Apply again; Revert if required.";
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
    if (!running || !ready || closed) return false;
    var payload = handoff.take();
    Object work = token;
    try {
      if (payload == null) throw new IllegalStateException("Preference preparation is unavailable");
      if (!current()) throw new IllegalStateException("Preferences or private input changed");
      request.publish(payload.value, user);
      // Consumption precedes panel reload, native font/layout consequences and other callbacks.
      retire();
      message = saving ? "Preferences saved" : "Preferences saved and activated";
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

  boolean stale() {
    return stale;
  }

  void cancel() {
    if (!running || closed) return;
    retire();
    message = saving ? "Preference save cancelled."
        : "Preference Apply cancelled. Private input is preserved.";
  }

  private void retire() {
    var previous = task;
    token = new Object();
    version++;
    task = null;
    request = null;
    input = null;
    running = ready = stale = false;
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
            (saving ? "Preference Save failed: " : "Preference Apply failed: ")
                + BackgroundFailure.describe(failure)
                + (saving ? ". Retry Save Preferences explicitly."
                    : ". Retry Apply explicitly or Revert the private form."),
            3072);
    report(message);
  }

  private void report(String detail) {
    try {
      errors.accept(detail);
    } catch (Throwable ignored) {
      /* Reporting cannot strand recovery, cleanup or successor work. */
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
