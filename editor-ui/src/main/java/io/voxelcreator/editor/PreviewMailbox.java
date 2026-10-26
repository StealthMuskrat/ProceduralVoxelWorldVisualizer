package io.voxelcreator.editor;

import io.voxelcreator.api.CancellationToken;
import java.util.Objects;

/** One bounded preview handoff; superseded/closed jobs cannot repopulate an empty slot. */
final class PreviewMailbox<T> implements AutoCloseable {
  private volatile long version;
  private volatile boolean closed;
  private T ready;

  synchronized void advance(long next) {
    if (closed) return;
    if (next <= version) throw new IllegalArgumentException("Preview version must advance");
    version = next;
    ready = null;
  }

  CancellationToken token(long requested) {
    return new CancellationToken(() -> closed || version != requested);
  }

  synchronized boolean publish(long requested, T value) {
    Objects.requireNonNull(value);
    if (closed || version != requested) return false;
    ready = value;
    return true;
  }

  synchronized T take() {
    var value = ready;
    ready = null;
    return value;
  }

  @Override
  public synchronized void close() {
    closed = true;
    ready = null;
  }
}
