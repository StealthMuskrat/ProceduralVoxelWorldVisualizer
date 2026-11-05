package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coalesced persistence of active values; private forms and live preference owners stay separate. */
final class PreferenceSaveSession implements AutoCloseable {
  private final UserConfig user;
  private final Path file;
  private final PreferenceApplySession work;
  private final Consumer<String> saved, errors;
  private UserConfig.PreferenceRequest source, failed;
  private boolean requested, explicit, closed, captureFailed;
  private Object token = new Object();
  private String captureMessage = "";

  PreferenceSaveSession(UserConfig user, Path file, Executor executor,
      Consumer<Runnable> ui, Consumer<String> saved, Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.file = Objects.requireNonNull(file);
    this.saved = Objects.requireNonNull(saved);
    this.errors = Objects.requireNonNull(errors);
    work = new PreferenceApplySession(user, file, executor, ui, errors, true);
  }

  void request(boolean manual) {
    if (closed) return;
    Object attempt = token;
    if (requested) {
      work.refresh();
      if (!owns(attempt)) return;
      if (work.stale()) {
        explicit |= manual;
        startLatest();
        return;
      }
      boolean running = work.running();
      if (!owns(attempt)) return;
      if (running) {
        explicit |= manual;
        return;
      }
      // A UI-delivered failure may precede a Save click in the same frame. That click is
      // an explicit retry, not a promotion of the already failed request.
      rejected();
      attempt = token;
    }
    // A periodic tick is not an unchanged failure retry. Manual Save always permits retry.
    try {
      if (!manual && (captureFailed || failed != null && failed.current(user))) return;
    } catch (Throwable failure) {
      captureFailure(attempt, failure);
      return;
    }
    if (closed || attempt != token) return;
    requested = true;
    explicit = manual;
    startLatest();
  }

  private void startLatest() {
    if (closed) return;
    token = new Object();
    Object attempt = token;
    try {
      var captured = user.requestSave();
      if (closed || attempt != token) return;
      source = captured;
      captureFailed = false;
      captureMessage = "";
      work.start(source, () -> true);
    } catch (Throwable failure) {
      captureFailure(attempt, failure);
    }
  }

  private void captureFailure(Object attempt, Throwable failure) {
    if (closed || attempt != token) return;
    token = new Object();
    Object failedAttempt = token;
    source = null;
    requested = explicit = false;
    captureFailed = true;
    String feedback = "Preference Save failed: " + BackgroundFailure.describe(failure)
        + ". Retry Save Preferences explicitly.";
    if (closed || failedAttempt != token) return;
    captureMessage = feedback;
    report(captureMessage);
  }

  /** After all same-frame controls/workspace capture: UI delivery alone never replaces the file. */
  void frame() {
    if (closed || !requested) return;
    Object attempt = token;
    work.refresh();
    if (!owns(attempt)) return;
    if (work.stale()) {
      startLatest();
      return;
    }
    boolean running = work.running();
    if (!owns(attempt)) return;
    if (!running) {
      rejected();
      return;
    }
    boolean accepted = work.accept();
    if (!owns(attempt)) return;
    if (accepted) {
      boolean notify = explicit;
      token = new Object();
      requested = explicit = false;
      source = failed = null;
      // Consumption precedes notification, including a throwing/reentrant status callback.
      if (notify) {
        try {
          saved.accept("Preferences saved");
        } catch (Throwable failure) {
          report("Preferences were saved, but status refresh failed: "
              + BackgroundFailure.describe(failure));
        }
      }
    } else if (work.stale()) startLatest();
    else {
      running = work.running();
      if (owns(attempt) && !running) rejected();
    }
  }

  private boolean owns(Object attempt) {
    return !closed && requested && attempt == token;
  }

  private void rejected() {
    token = new Object();
    failed = source;
    source = null;
    requested = explicit = false;
  }

  boolean pending() {
    return requested;
  }

  String message() {
    return captureMessage.isEmpty() ? work.message() : captureMessage;
  }

  PreferenceApplySession probeWork() {
    return work;
  }

  /** Exit barrier: retire every earlier handoff before synchronously persisting latest values. */
  void finish() {
    if (closed) return;
    close();
    user.save(file);
  }

  private void report(String detail) {
    try {
      errors.accept(detail);
    } catch (Throwable ignored) {
      /* Notification cannot strand a completed save or prevent its successor. */
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    token = new Object();
    requested = explicit = false;
    source = failed = null;
    work.close();
  }
}
