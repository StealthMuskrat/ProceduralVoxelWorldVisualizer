package io.voxelcreator.render;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Render-thread scene allocation/epoch owner; backend callbacks cannot publish an old opening. */
final class SceneRendererOwner<R> {
  interface Backend<R> {
    R create(int bytes);
    void epoch(R renderer, long epoch);
    void close(R renderer);
  }

  private final Backend<R> backend;
  private R renderer;
  private int budget;
  private long generation;
  private boolean active, busy, allocating, closed, valid;

  SceneRendererOwner(Backend<R> backend) { this.backend = Objects.requireNonNull(backend); }

  R renderer() { return renderer; }
  int budget() { return budget; }
  boolean active() { return active; }
  boolean transitioning() { return busy || allocating; }
  long version() { return generation; }
  boolean current(R value, long version) {
    return !closed && renderer == value && generation == version && active && valid && !transitioning();
  }

  R activate(long epoch, int bytes, Runnable prepare, Consumer<R> publish) {
    if (closed) throw new IllegalStateException("Scene renderer owner closed");
    if (bytes < 16 || bytes % 16 != 0) throw new IllegalArgumentException("16-byte arena alignment");
    boolean reuse = renderer != null && budget == bytes && !busy && valid;
    long opening = ++generation;
    active = false;
    busy = true;
    // A factory may own native handles before it can return them. Do not overlap allocations.
    if (allocating) {
      busy = false;
      throw new CancellationException("Scene allocation already in progress");
    }
    R attempted = null;
    try {
      prepare.run();
      requireCurrent(opening);
      if (!reuse) {
        var previous = renderer;
        renderer = null;
        budget = 0;
        valid = false;
        if (previous != null) backend.close(previous);
        requireCurrent(opening);
        R candidate;
        allocating = true;
        try {
          candidate = Objects.requireNonNull(backend.create(bytes), "Scene factory returned null");
        } finally {
          allocating = false;
        }
        if (!current(opening)) {
          closeQuietly(candidate);
          throw new CancellationException("Scene allocation superseded");
        }
        renderer = candidate;
        budget = bytes;
      }
      var selected = renderer;
      attempted = selected;
      valid = false;
      backend.epoch(selected, epoch);
      requireCurrent(opening);
      valid = true;
      active = true;
      busy = false;
      publish.accept(selected);
      requireCurrent(opening);
      return selected;
    } catch (RuntimeException | Error failure) {
      if (current(opening) || attempted != null && renderer == attempted && !valid)
        closeQuietly(detach());
      throw failure;
    }
  }

  void disable() {
    generation++;
    active = busy = false;
  }

  /** Caller owns the captured close, allowing independent shadow/scene retirement. */
  R detach() {
    generation++;
    var previous = renderer;
    renderer = null;
    budget = 0;
    valid = false;
    active = busy = false;
    return previous;
  }

  R closeOwner() {
    closed = true;
    return detach();
  }

  private boolean current(long opening) { return !closed && generation == opening; }

  private void requireCurrent(long opening) {
    if (!current(opening)) throw new CancellationException("Scene activation superseded");
  }

  private void closeQuietly(R value) {
    if (value == null) return;
    try { backend.close(value); }
    catch (RuntimeException | Error ignored) {
      // Cleanup cannot replace the primary fault or revoke a callback-admitted successor.
    }
  }
}
