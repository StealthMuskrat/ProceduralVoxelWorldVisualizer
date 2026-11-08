package io.voxelcreator.render;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Serial in-place uploads accept display metadata only after restoration and captured retirement. */
final class ViewportUpload implements AutoCloseable {
  private boolean busy, closed;
  private long generation;
  boolean busy() { return busy; }
  void invalidate() { generation++; }
  void requireIdle() {
    if (closed) throw new IllegalStateException("Viewport upload closed");
    if (busy) throw new IllegalStateException("Viewport upload already active");
  }
  void run(Runnable validate, Consumer<Runnable> write, Runnable retire,
      Runnable publish, Runnable failed) {
    Objects.requireNonNull(validate); Objects.requireNonNull(write); Objects.requireNonNull(retire);
    Objects.requireNonNull(publish); Objects.requireNonNull(failed);
    requireIdle();
    busy = true;
    long opening = generation;
    Runnable current = () -> {
      validate.run();
      if (closed || generation != opening)
        throw new CancellationException("Viewport upload superseded");
    };
    try {
      current.run(); write.accept(current); current.run();
      retire.run(); current.run(); publish.run(); current.run();
    } catch (RuntimeException | Error primary) {
      try { failed.run(); }
      catch (RuntimeException | Error ignored) { /* Keep the observed upload/retirement primary. */ }
      throw primary;
    } finally { busy = false; }
  }
  public void close() { closed = true; invalidate(); }
}
