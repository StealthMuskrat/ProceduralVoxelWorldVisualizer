package io.voxelcreator.editor;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** UI-thread manual request owner. Background completion has no authority until UI publication. */
final class UpdateCheckSession implements AutoCloseable {
  enum Phase {
    IDLE,
    RUNNING,
    READY,
    FAILED,
    CANCELLED,
    STALE,
    CLOSED
  }

  enum Comparison {
    NEWER,
    CURRENT,
    OLDER
  }

  record Result(UpdateChecker.Release release, Comparison comparison) {}

  record View(Phase phase, String message, Result result) {}

  @FunctionalInterface
  interface Fetcher {
    UpdateChecker.Release check(String endpoint, List<String> origins) throws Exception;
  }

  @FunctionalInterface
  interface Opener {
    void open(URI uri) throws Exception;
  }

  private record Applied(String endpoint, List<String> origins) {
    Applied {
      origins = List.copyOf(origins);
    }

    static Applied from(UserConfig user) {
      return new Applied(user.performance.updateEndpoint, user.performance.trustedOrigins);
    }
  }

  UserConfig preferences() {
    return user;
  }

  private final UserConfig user;
  private final ReleaseVersion installed;
  private final Executor executor;
  private final Consumer<Runnable> ui;
  private final Fetcher fetcher;
  private final Consumer<String> errors;
  private Phase phase = Phase.IDLE;
  private String message = "Check only when requested. No application download or installation.";
  private Applied applied;
  private Object token = new Object();
  private FutureTask<Void> task;
  private Result result;
  private boolean opening;

  UpdateCheckSession(
      UserConfig user,
      String installed,
      Executor executor,
      Consumer<Runnable> ui,
      Fetcher fetcher,
      Consumer<String> errors) {
    this.user = Objects.requireNonNull(user);
    this.installed = ReleaseVersion.parse(installed);
    this.executor = Objects.requireNonNull(executor);
    this.ui = Objects.requireNonNull(ui);
    this.fetcher = Objects.requireNonNull(fetcher);
    this.errors = Objects.requireNonNull(errors);
  }

  /** Opaque presentation identity only; result/configuration checks retain all authority. */
  Object requestToken() {
    return token;
  }

  View view() {
    refresh();
    return new View(phase, message, result);
  }

  boolean start() {
    refresh();
    if (phase == Phase.CLOSED || phase == Phase.RUNNING) return false;
    invalidate();
    final UpdateTrust.Values configuration;
    try {
      applied = Applied.from(user);
      configuration = UpdateTrust.parse(applied.endpoint(), String.join("\n", applied.origins()));
      if (configuration.endpoint().isEmpty())
        throw new IllegalStateException(
            "Configure and apply a trusted HTTPS metadata endpoint first");
    } catch (RuntimeException invalid) {
      failure(invalid);
      return false;
    }
    phase = Phase.RUNNING;
    message = "Checking the applied metadata endpoint…";
    Object request = token;
    task =
        new FutureTask<>(
            () -> {
              UpdateChecker.Release release = null;
              Exception failure = null;
              try {
                release = fetcher.check(configuration.endpoint(), configuration.origins());
              } catch (Exception rejected) {
                failure = rejected;
              }
              var value = release;
              var rejected = failure;
              ui.accept(() -> publish(request, configuration, value, rejected));
              return null;
            });
    try {
      executor.execute(task);
    } catch (RuntimeException rejected) {
      invalidate();
      failure(rejected);
      return false;
    }
    return true;
  }

  private void publish(
      Object request,
      UpdateTrust.Values configuration,
      UpdateChecker.Release release,
      Exception rejected) {
    if (request != token || phase != Phase.RUNNING) return;
    refresh();
    if (request != token || phase != Phase.RUNNING) return;
    task = null;
    if (rejected != null) {
      failure(rejected);
      return;
    }
    try {
      UpdateChecker.validateRelease(release, configuration.origins());
      ReleaseVersion version = ReleaseVersion.parse(release.version());
      int order = version.compareTo(installed);
      result =
          new Result(
              release,
              order > 0 ? Comparison.NEWER : order == 0 ? Comparison.CURRENT : Comparison.OLDER);
      phase = Phase.READY;
      message =
          switch (result.comparison()) {
            case NEWER -> "A newer release is available: " + release.version();
            case CURRENT ->
                "The configured feed matches this application version: " + release.version();
            case OLDER -> "The configured feed offers an older version: " + release.version();
          };
    } catch (RuntimeException invalid) {
      failure(invalid);
    }
  }

  void refresh() {
    if (phase != Phase.RUNNING && phase != Phase.READY) return;
    boolean current;
    try {
      current = applied.equals(Applied.from(user));
    } catch (RuntimeException invalid) {
      current = false;
    }
    if (!current) {
      invalidate();
      phase = Phase.STALE;
      message = "Applied update settings changed. Check again to use the new settings.";
    }
  }

  void open(Result displayed, Opener opener) throws Exception {
    requireResult(displayed);
    if (opening) throw new IllegalStateException("Release link is already being opened");
    opening = true;
    try {
      opener.open(URI.create(displayed.release().releaseUrl()));
    } finally {
      opening = false;
    }
  }

  void copy(Result displayed, Consumer<String> clipboard) {
    requireResult(displayed);
    clipboard.accept(displayed.release().releaseUrl());
  }

  private void requireResult(Result displayed) {
    refresh();
    if (phase != Phase.READY || displayed == null || result != displayed)
      throw new IllegalStateException("Release information changed. Check and review it again.");
  }

  void cancel() {
    if (phase == Phase.CLOSED || phase == Phase.IDLE || phase == Phase.CANCELLED) return;
    invalidate();
    phase = Phase.CANCELLED;
    message = "Update check cancelled. Check again when ready.";
  }

  private void invalidate() {
    token = new Object();
    result = null;
    if (task != null) task.cancel(true);
    task = null;
  }

  private void failure(Exception error) {
    result = null;
    phase = Phase.FAILED;
    message = "Update check failed: " + describe(error);
    errors.accept(message);
  }

  static String describe(Throwable error) {
    String text = error.getMessage();
    if (text == null || text.isBlank()) text = error.getClass().getSimpleName();
    return text.length() > 1024 ? text.substring(0, 1023) + "…" : text;
  }

  @Override
  public void close() {
    if (phase != Phase.CLOSED) {
      invalidate();
      phase = Phase.CLOSED;
      message = "Update checker closed";
    }
  }
}
