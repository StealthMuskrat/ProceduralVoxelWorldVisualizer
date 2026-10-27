package io.voxelcreator.editor;

import java.util.Objects;

/**
 * One owned CPU-source handoff. Cleanup runs outside the monitor, including late worker results.
 */
final class OwnedPreviewMailbox<T extends AutoCloseable> implements AutoCloseable {
  private volatile long version;
  private volatile boolean closed;
  private T ready;

  long begin() {
    T old;
    long id;
    synchronized (this) {
      if (closed) throw new IllegalStateException("Source handoff closed");
      id = ++version;
      old = ready;
      ready = null;
    }
    dispose(old);
    return id;
  }

  void invalidate() {
    T old;
    synchronized (this) {
      if (closed) return;
      version++;
      old = ready;
      ready = null;
    }
    dispose(old);
  }

  boolean current(long id) {
    return !closed && version == id;
  }

  synchronized boolean pending() {
    return ready != null;
  }

  boolean publish(long id, T value) {
    Objects.requireNonNull(value);
    T discarded;
    boolean accepted;
    synchronized (this) {
      accepted = !closed && version == id;
      if (accepted) {
        discarded = ready;
        ready = value;
      } else discarded = value;
    }
    if (discarded != value || !accepted) dispose(discarded);
    return accepted;
  }

  synchronized T take() {
    var result = ready;
    ready = null;
    return result;
  }

  public void close() {
    T old;
    synchronized (this) {
      if (closed) return;
      closed = true;
      version++;
      old = ready;
      ready = null;
    }
    dispose(old);
  }

  private void dispose(T value) {
    if (value == null) return;
    try {
      value.close();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      /* Cleanup failure cannot strand a newer owned source. */
    }
  }
}
