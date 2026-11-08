package io.voxelcreator.editor;

import java.util.*;
import java.util.function.Supplier;

/** UI-thread detachment first, then independent once-only captured cleanup. */
final class UiShutdown {
  private UiShutdown() {}

  static Runnable once(Runnable... callbacks) {
    var captured = callbacks.clone();
    for (var callback : captured) Objects.requireNonNull(callback);
    return new Cleanup(captured);
  }

  private static final class Cleanup implements Runnable {
    private Runnable[] pending;
    Cleanup(Runnable[] callbacks) { pending = callbacks; }
    public void run() {
      var attempts = pending;
      pending = null;
      if (attempts != null) rethrow(PreviewNotifications.attempt(attempts));
    }
  }

  /** Suppliers must only detach ownership; returned callbacks perform external cleanup. */
  static Runnable prepare(List<? extends Supplier<Runnable>> owners) {
    var captured = List.copyOf(owners);
    var cleanup = new ArrayList<Runnable>();
    Throwable first = null;
    for (var owner : captured) {
      try {
        var callback = owner.get();
        if (callback != null) cleanup.add(callback);
      } catch (RuntimeException | Error failure) {
        if (first == null) first = failure;
      }
    }
    var primary = first;
    cleanup.addFirst(() -> rethrow(primary));
    return once(cleanup.toArray(Runnable[]::new));
  }

  static void rethrow(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    if (failure instanceof Error error) throw error;
  }
}
