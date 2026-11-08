package io.voxelcreator.render;

import java.util.*;
import java.util.function.IntSupplier;

/** Owned raster snapshot; every restore is attempted before accepting a depth pass result. */
final class ShadowRasterState {
  enum Binding { READ_FRAMEBUFFER, DRAW_FRAMEBUFFER, PROGRAM, VERTEX_ARRAY, DEPTH_FUNCTION }
  enum Flag { DEPTH_TEST, CULL_FACE, SCISSOR_TEST, POLYGON_OFFSET_FILL, RASTERIZER_DISCARD, DEPTH_MASK }
  interface Backend {
    int binding(Binding item);
    void binding(Binding item, int value);
    boolean flag(Flag item);
    void flag(Flag item, boolean value);
    int[] viewport();
    void viewport(int[] value);
    float[] attribute();
    void attribute(float[] value);
    double clearDepth();
    void clearDepth(double value);
    double[] depthRange();
    void depthRange(double[] value);
    void clear();
  }
  private final Backend backend;
  private final int[] bindings, viewport;
  private final boolean[] flags;
  private final float[] attribute;
  private final double clear;
  private final double[] range;
  private ShadowRasterState(Backend backend, int[] bindings, boolean[] flags, int[] viewport,
      float[] attribute, double clear, double[] range) {
    this.backend = backend; this.bindings = bindings; this.flags = flags; this.viewport = viewport;
    this.attribute = attribute; this.clear = clear; this.range = range;
  }
  static ShadowRasterState capture(Backend backend) {
    Objects.requireNonNull(backend);
    var bindings = new int[Binding.values().length];
    for (var item : Binding.values()) bindings[item.ordinal()] = backend.binding(item);
    var flags = new boolean[Flag.values().length];
    for (var item : Flag.values()) flags[item.ordinal()] = backend.flag(item);
    int[] viewport = backend.viewport().clone();
    float[] attribute = backend.attribute().clone();
    double clear = backend.clearDepth();
    double[] range = backend.depthRange().clone();
    if (viewport.length != 4 || attribute.length != 4 || range.length != 2)
      throw new IllegalArgumentException("Invalid shadow state snapshot");
    return new ShadowRasterState(backend, bindings, flags, viewport, attribute, clear, range);
  }
  int run(Runnable current, int framebuffer, int resolution, IntSupplier draw) {
    var restores = new ArrayList<Runnable>();
    restores.add(() -> backend.binding(Binding.VERTEX_ARRAY, saved(Binding.VERTEX_ARRAY)));
    restores.add(() -> backend.attribute(attribute.clone()));
    restores.add(() -> backend.binding(Binding.PROGRAM, saved(Binding.PROGRAM)));
    restores.add(() -> backend.flag(Flag.DEPTH_MASK, saved(Flag.DEPTH_MASK)));
    restores.add(() -> backend.binding(Binding.DEPTH_FUNCTION, saved(Binding.DEPTH_FUNCTION)));
    restores.add(() -> backend.clearDepth(clear));
    restores.add(() -> backend.depthRange(range.clone()));
    for (var item : Flag.values()) if (item != Flag.DEPTH_MASK)
      restores.add(() -> backend.flag(item, saved(item)));
    restores.add(() -> backend.binding(Binding.READ_FRAMEBUFFER, saved(Binding.READ_FRAMEBUFFER)));
    restores.add(() -> backend.binding(Binding.DRAW_FRAMEBUFFER, saved(Binding.DRAW_FRAMEBUFFER)));
    restores.add(() -> backend.viewport(viewport.clone()));
    int result = RenderTransaction.run(() -> {
      current.run();
      step(current, () -> backend.binding(Binding.READ_FRAMEBUFFER, framebuffer));
      step(current, () -> backend.binding(Binding.DRAW_FRAMEBUFFER, framebuffer));
      step(current, () -> backend.viewport(new int[] {0, 0, resolution, resolution}));
      step(current, () -> backend.flag(Flag.DEPTH_TEST, true));
      step(current, () -> backend.flag(Flag.DEPTH_MASK, true));
      step(current, () -> backend.binding(Binding.DEPTH_FUNCTION, 0x0201)); // GL_LESS
      step(current, () -> backend.clearDepth(1));
      step(current, () -> backend.depthRange(new double[] {0, 1}));
      for (var item : Flag.values()) if (item != Flag.DEPTH_TEST && item != Flag.DEPTH_MASK)
        step(current, () -> backend.flag(item, false));
      step(current, backend::clear);
      int count = draw.getAsInt();
      current.run();
      return count;
    }, () -> {}, restores.toArray(Runnable[]::new));
    current.run();
    return result;
  }
  private int saved(Binding item) { return bindings[item.ordinal()]; }
  private boolean saved(Flag item) { return flags[item.ordinal()]; }
  private static void step(Runnable current, Runnable command) { command.run(); current.run(); }
}
