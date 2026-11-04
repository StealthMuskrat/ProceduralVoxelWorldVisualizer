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
    if (requested) {
      work.refresh();
      if (work.stale()) {
        explicit |= manual;
        startLatest();
        return;
      }
      if (work.running()) {
        explicit |= manual;
        return;
      }
      // A UI-delivered failure may precede a Save click in the same frame. That click is
      // an explicit retry, not a promotion of the already failed request.
      rejected();
    }
    // A periodic tick is not an unchanged failure retry. Manual Save always permits retry.
    try {
      if (!manual && (captureFailed || failed != null && failed.current(user))) return;
    } catch (Throwable failure) {
      captureFailure(failure);
      return;
    }
    requested = true;
    explicit = manual;
    startLatest();
  }

  private void startLatest() {
    try {
      source = user.requestSave();
      captureFailed = false;
      captureMessage = "";
      work.start(source, () -> true);
    } catch (Throwable failure) {
      captureFailure(failure);
    }
  }

  private void captureFailure(Throwable failure) {
    source = null;
    requested = explicit = false;
    captureFailed = true;
    captureMessage = "Preference Save failed: " + BackgroundFailure.describe(failure)
        + ". Retry Save Preferences explicitly.";
    report(captureMessage);
  }

  /** After all same-frame controls/workspace capture: UI delivery alone never replaces the file. */
  void frame() {
    if (closed || !requested) return;
    work.refresh();
    if (work.stale()) {
      startLatest();
      return;
    }
    if (!work.running()) {
      rejected();
      return;
    }
    if (work.accept()) {
      boolean notify = explicit;
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
    else if (!work.running()) rejected();
  }

  private void rejected() {
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
    requested = explicit = false;
    source = failed = null;
    work.close();
  }
}
