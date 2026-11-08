package io.voxelcreator.render;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Serial shadow work accepts a candidate or pass result only after restoration and current checks. */
final class ShadowMapWork implements AutoCloseable {
  interface Allocation {
    int texture();
    int framebuffer();
    void storage(int texture, int framebuffer, int resolution);
    int program();
    void complete();
  }
  final ShadowResources resources;
  private final Consumer<RenderRetirement.Handle> delete;
  private ShadowResources candidate;
  private boolean busy, closed;

  ShadowMapWork(Consumer<RenderRetirement.Handle> delete) {
    this.delete = Objects.requireNonNull(delete);
    resources = new ShadowResources(delete);
  }
  void requireIdle() {
    resources.requireOpen();
    if (closed || busy) throw new IllegalStateException("Shadow map closed or active");
  }
  private void requireCurrent() {
    if (closed) throw new CancellationException("Shadow work superseded");
    resources.requireOpen();
  }
  void allocate(int size, IntSupplier maximum, Supplier<ShadowAllocationState> capture, Allocation backend) {
    requireIdle();
    DirectionalShadow.bytes(size);
    if (size == 0) throw new IllegalArgumentException("Empty shadow map");
    if (size == resources.resolution) return;
    busy = true;
    try {
      int limit = maximum.getAsInt();
      requireCurrent();
      if (size > limit) throw new IllegalStateException("Shadow map exceeds device texture limit");
      var state = capture.get();
      requireCurrent();
      state = state.retiring(resources.texture, resources.framebuffer);
      // Retire logical ownership before creating a candidate; do not overlap complete allocations.
      resources.release();
      requireCurrent();
      candidate = new ShadowResources(delete);
      state.run(this::requireCurrent, () -> {
        candidate.texture = create(RenderRetirement.Kind.TEXTURE, backend::texture);
        candidate.framebuffer = create(RenderRetirement.Kind.FRAMEBUFFER, backend::framebuffer);
        backend.storage(candidate.texture, candidate.framebuffer, size);
        requireCurrent();
        candidate.program = create(RenderRetirement.Kind.PROGRAM, backend::program);
        return null;
      }, this::retireCandidate);
      backend.complete();
      requireCurrent();
      resources.texture = candidate.texture;
      resources.framebuffer = candidate.framebuffer;
      resources.program = candidate.program;
      resources.resolution = size;
      candidate.texture = candidate.framebuffer = candidate.program = candidate.resolution = 0;
      candidate = null;
    } catch (RuntimeException | Error primary) {
      try { retireCandidate(); }
      catch (RuntimeException | Error ignored) { /* Preserve work or first restoration failure. */ }
      throw primary;
    } finally { busy = false; }
  }
  private int create(RenderRetirement.Kind kind, IntSupplier allocation) {
    int name = allocation.getAsInt();
    try {
      requireCurrent();
      if (name == 0) throw new IllegalStateException("Shadow object allocation failed");
      return name;
    } catch (RuntimeException | Error primary) {
      try {
        new RenderRetirement(delete, new RenderRetirement.Handle[] {
          new RenderRetirement.Handle(kind, name)
        }).run();
      } catch (RuntimeException | Error ignored) { /* Keep the obsolete/allocation primary. */ }
      throw primary;
    }
  }
  int render(Supplier<ShadowRasterState> capture, IntUnaryOperator draw) {
    return render(capture, draw, () -> {});
  }
  int render(Supplier<ShadowRasterState> capture, IntUnaryOperator draw, Runnable complete) {
    requireIdle();
    if (resources.program == 0) throw new IllegalStateException("Shadow map not allocated");
    busy = true;
    try {
      int framebuffer = resources.framebuffer, resolution = resources.resolution, program = resources.program;
      var state = capture.get();
      requireCurrent();
      int count = state.run(this::requireCurrent, framebuffer, resolution, () -> draw.applyAsInt(program));
      complete.run();
      requireCurrent();
      return count;
    } finally { busy = false; }
  }
  private void retireCandidate() {
    var previous = candidate;
    candidate = null;
    if (previous != null) previous.close();
  }
  public void close() {
    if (closed) return;
    closed = true;
    var previous = candidate;
    candidate = null;
    RenderCleanup.run(resources::close, () -> { if (previous != null) previous.close(); });
  }
}
