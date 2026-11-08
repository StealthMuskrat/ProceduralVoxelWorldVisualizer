package io.voxelcreator.render;

import java.util.Objects;

/** Attempt captured native retirement callbacks independently after their caller detaches owners. */
final class RenderCleanup {
  private RenderCleanup() {}

  /** Captures callback identity now and consumes the entire plan before any callback can reenter. */
  static Runnable deferred(Runnable... callbacks) {
    var captured = callbacks.clone();
    for (var callback : captured) Objects.requireNonNull(callback);
    return new Runnable() {
      private Runnable[] pending = captured;
      public void run() {
        var attempts = pending;
        pending = null;
        if (attempts != null) RenderCleanup.run(attempts);
      }
    };
  }

  static void run(Runnable... callbacks) {
    Throwable first = null;
    for (var callback : callbacks) {
      try {
        callback.run();
      } catch (RuntimeException | Error failure) {
        if (first == null) first = failure;
      }
    }
    if (first instanceof RuntimeException runtime) throw runtime;
    if (first instanceof Error error) throw error;
  }
}
