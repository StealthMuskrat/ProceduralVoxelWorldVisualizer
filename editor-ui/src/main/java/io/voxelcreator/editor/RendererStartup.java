package io.voxelcreator.editor;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Render-thread resource admission. Failed startup waits for explicit retry, never every frame. */
final class RendererStartup<T> implements AutoCloseable {
  private final Supplier<T> create;
  private final Consumer<T> dispose;
  private T value;
  private int attempts;
  private boolean busy, closed;
  private String failure = "";

  RendererStartup(Supplier<T> create, Consumer<T> dispose) {
    this.create = Objects.requireNonNull(create);
    this.dispose = Objects.requireNonNull(dispose);
  }

  T value() {
    return value;
  }

  int attempts() {
    return attempts;
  }

  boolean busy() {
    return busy;
  }

  String failure() {
    return failure;
  }

  boolean closed() {
    return closed;
  }

  boolean initialize() {
    return attempts == 0 ? start() : value != null;
  }

  boolean retry() {
    return start();
  }

  private boolean start() {
    if (closed || busy) return false;
    if (value != null) return true;
    busy = true;
    attempts++;
    try {
      T candidate = Objects.requireNonNull(create.get(), "Renderer factory returned no resource");
      if (closed) {
        dispose.accept(candidate);
        return false;
      }
      value = candidate;
      failure = "";
      return true;
    } catch (RuntimeException rejected) {
      failure =
          StatusBarText.bounded(
              Objects.requireNonNullElse(rejected.getMessage(), rejected.toString()), 1024);
      return false;
    } finally {
      busy = false;
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    T accepted = value;
    value = null;
    if (accepted != null) dispose.accept(accepted);
  }
}
