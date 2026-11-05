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
    final Throwable failure;

    Payload(UserConfig.PreparedPreference value, Throwable failure) {
      this.value = value;
      this.failure = failure;
    }

    @Override
    public void close() {
      if (value == null) return;
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
      if (!input.getAsBoolean() || work != token || !request.current(user))
        throw new IllegalStateException("Preference input changed before Apply");
      var captured = request;
      long delivery = handoff.begin();
      running = true;
      message = saving ? "Saving active preferences in the background."
          : "Preparing preferences in the background. Cancel Apply or Escape keeps private input.";
      task =
          new FutureTask<>(
              () -> {
                Payload payload;
                try {
                  var value = captured.prepare(target);
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
    } catch (Throwable failure) {
      fail(work, failure);
    }
  }

  private boolean current(Object work) {
    var captured = request;
    var guard = input;
    return captured != null && guard != null && guard.getAsBoolean()
        && owns(work) && request == captured && captured.current(user);
  }

  private boolean owns(Object work) {
    return running && !closed && work == token;
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
      // Active saves coalesce on completion rather than cancelling a still-writing worker on
      // every workspace/slider frame. They still recheck full source values before publication.
      boolean check = !saving || ready || handoff.pending();
      boolean valid = !check || current(work);
      if (!owns(work)) return;
      if (!valid) {
        cancel();
        stale = true;
        message = saving ? "Preferences changed; saving the latest active values."
            : "Preferences or private input changed. Review and Apply again; Revert if required.";
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
    if (!owns(work) || !ready || shownVersion != version) return false;
    Payload payload = null;
    try {
      var captured = request;
      if (!current(work) || !owns(work)) return false;
      payload = handoff.take();
      // Once accepted, the finishing worker no longer owns cancellation authority.
      task = null;
      if (payload == null) throw new IllegalStateException("Preference preparation is unavailable");
      if (payload.failure != null) {
        fail(work, payload.failure);
        return false;
      }
      captured.publish(payload.value, user);
      // Consume before panel reload, native consequences or external feedback.
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
    closed = true;
    retire();
    handoff.close();
  }
}
