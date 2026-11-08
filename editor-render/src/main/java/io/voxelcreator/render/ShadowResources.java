package io.voxelcreator.render;

import java.util.Objects;
import java.util.function.Consumer;

/** Render-thread shadow ownership; resize release is reusable, terminal close is not. */
final class ShadowResources implements AutoCloseable {
  int texture, framebuffer, program, resolution;
  private boolean closed, retiring;
  private final Consumer<RenderRetirement.Handle> delete;

  ShadowResources(Consumer<RenderRetirement.Handle> delete) {
    this.delete = Objects.requireNonNull(delete);
  }

  void requireOpen() {
    if (closed || retiring) throw new IllegalStateException("Shadow map closed or retiring");
  }

  void release() {
    var handles = new RenderRetirement.Handle[] {
      new RenderRetirement.Handle(RenderRetirement.Kind.TEXTURE, texture),
      new RenderRetirement.Handle(RenderRetirement.Kind.FRAMEBUFFER, framebuffer),
      new RenderRetirement.Handle(RenderRetirement.Kind.PROGRAM, program)
    };
    texture = framebuffer = program = resolution = 0;
    boolean previous = retiring;
    retiring = true;
    try { new RenderRetirement(delete, handles).run(); }
    finally { retiring = previous; }
  }

  public void close() {
    if (closed) return;
    closed = true;
    release();
  }
}
