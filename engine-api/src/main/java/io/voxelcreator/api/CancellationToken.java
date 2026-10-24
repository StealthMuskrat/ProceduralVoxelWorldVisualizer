package io.voxelcreator.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancellationToken {
  @FunctionalInterface
  public interface Registration extends AutoCloseable {
    void close();
  }

  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final java.util.function.BooleanSupplier parent;
  private final Set<Runnable> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

  public CancellationToken() {
    this(() -> false);
  }

  public CancellationToken(java.util.function.BooleanSupplier parent) {
    this.parent = Objects.requireNonNull(parent);
  }

  public boolean cancel() {
    if (!cancelled.compareAndSet(false, true)) return false;
    List<Runnable> callbacks;
    synchronized (listeners) {
      callbacks = List.copyOf(listeners);
      listeners.clear();
    }
    for (var callback : callbacks)
      try {
        callback.run();
      } catch (RuntimeException ignored) {
      }
    return true;
  }

  /**
   * Explicit cancellation invokes callbacks on the cancelling thread. Parent suppliers remain
   * polled.
   */
  public Registration onCancel(Runnable callback) {
    Objects.requireNonNull(callback);
    if (isCancelled()) {
      callback.run();
      return () -> {};
    }
    Runnable registration = callback::run;
    synchronized (listeners) {
      if (!cancelled.get()) {
        listeners.add(registration);
        return () -> {
          synchronized (listeners) {
            listeners.remove(registration);
          }
        };
      }
    }
    callback.run();
    return () -> {};
  }

  public boolean isCancelled() {
    return cancelled.get() || parent.getAsBoolean();
  }

  public void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Generation cancelled");
  }
}
