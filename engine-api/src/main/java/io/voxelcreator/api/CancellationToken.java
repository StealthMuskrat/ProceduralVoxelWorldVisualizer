package io.voxelcreator.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CancellationToken {
  @FunctionalInterface
  public interface Registration extends AutoCloseable {
    /**
     * Prevents an unclaimed callback, including one captured by cancel. Does not await a claimed
     * one.
     */
    void close();
  }

  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final java.util.function.BooleanSupplier parent;
  private final Set<Listener> listeners = Collections.newSetFromMap(new IdentityHashMap<>());

  private final class Listener implements Registration {
    private final AtomicReference<Runnable> pending;

    Listener(Runnable callback) {
      pending = new AtomicReference<>(callback);
    }

    void invoke() {
      var callback = pending.getAndSet(null);
      if (callback != null) callback.run();
    }

    public void close() {
      if (pending.getAndSet(null) == null) return;
      synchronized (listeners) {
        listeners.remove(this);
      }
    }
  }

  public CancellationToken() {
    this(() -> false);
  }

  public CancellationToken(java.util.function.BooleanSupplier parent) {
    this.parent = Objects.requireNonNull(parent);
  }

  /**
   * Requests cancellation once. Callbacks are claimed outside the registry lock; closed, unclaimed
   * callbacks are skipped. Errors are rethrown after eligible callbacks have been attempted, while
   * the token remains cancelled. Repeated calls return false without replaying callbacks.
   */
  public boolean cancel() {
    if (!cancelled.compareAndSet(false, true)) return false;
    List<Listener> callbacks;
    synchronized (listeners) {
      callbacks = List.copyOf(listeners);
      listeners.clear();
    }
    Error failure = null;
    for (var callback : callbacks)
      try {
        callback.invoke();
      } catch (RuntimeException ignored) {
      } catch (Error rejected) {
        if (failure == null) failure = rejected;
        else if (failure != rejected) failure.addSuppressed(rejected);
      }
    if (failure != null) throw failure;
    return true;
  }

  /**
   * Explicit cancellation invokes callbacks on the cancelling thread, outside the registry lock.
   * Closed, unclaimed registrations are skipped. Registered RuntimeExceptions are ignored; Errors
   * are rethrown after the remaining eligible callbacks, with later errors suppressed. Registration
   * after observed cancellation invokes inline and propagates its callback failure. Parent
   * suppliers remain polled; observing a parent does not itself fire previously registered
   * callbacks.
   */
  public Registration onCancel(Runnable callback) {
    Objects.requireNonNull(callback);
    if (isCancelled()) {
      callback.run();
      return () -> {};
    }
    var registration = new Listener(callback);
    synchronized (listeners) {
      if (!cancelled.get()) {
        listeners.add(registration);
        return registration;
      }
    }
    callback.run();
    return () -> {};
  }

  /**
   * Polls the parent once when not already explicitly cancelled. Explicit cancellation
   * during a normally returning parent poll wins over its false result. Parent observations are
   * not latched and do not drain listeners; parent failures retain their propagation.
   */
  public boolean isCancelled() {
    if (cancelled.get()) return true;
    boolean inherited = parent.getAsBoolean();
    return inherited || cancelled.get();
  }

  public void throwIfCancelled() {
    if (isCancelled() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Generation cancelled");
  }
}
