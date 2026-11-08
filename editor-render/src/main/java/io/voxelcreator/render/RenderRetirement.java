package io.voxelcreator.render;

import java.util.*;
import java.util.function.Consumer;

/** Captured native names retire once, coalescing duplicates only within the same GL namespace. */
final class RenderRetirement implements Runnable {
  enum Kind { PROGRAM, FRAMEBUFFER, BUFFER, VERTEX_ARRAY, TEXTURE, RENDERBUFFER }
  record Handle(Kind kind, int id) {
    Handle { Objects.requireNonNull(kind); }
  }

  private final Runnable retirement;

  RenderRetirement(Consumer<Handle> delete, Handle[] handles, Runnable... children) {
    Objects.requireNonNull(delete);
    var captured = handles.clone();
    var callbacks = new ArrayList<Runnable>();
    for (var child : children.clone()) callbacks.add(Objects.requireNonNull(child));
    var unique = new LinkedHashSet<Handle>();
    for (var handle : captured) {
      Objects.requireNonNull(handle);
      if (handle.id() != 0) unique.add(handle);
    }
    for (var handle : unique) callbacks.add(() -> delete.accept(handle));
    retirement = RenderCleanup.deferred(callbacks.toArray(Runnable[]::new));
  }

  public void run() { retirement.run(); }
}
