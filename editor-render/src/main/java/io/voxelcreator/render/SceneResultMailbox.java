package io.voxelcreator.render;

import java.util.ArrayDeque;
import java.util.Objects;

/** Scene-owned CPU receipts. Close atomically discards pending results and rejects late offers. */
final class SceneResultMailbox<T> implements AutoCloseable {
  private final ArrayDeque<T> pending = new ArrayDeque<>();
  private boolean closed;

  synchronized boolean offer(T result) {
    Objects.requireNonNull(result);
    if (closed) return false;
    pending.addLast(result);
    return true;
  }

  synchronized T poll() {
    return pending.pollFirst();
  }

  @Override
  public synchronized void close() {
    closed = true;
    pending.clear();
  }
}
