package io.voxelcreator.editor;

import io.voxelcreator.render.SlicePlane;
import java.util.Objects;

/**
 * Finite plane-local navigation; requested extent and fixed coordinate are part of its identity.
 */
record SliceNavigation(
    SlicePlane plane, int slice, double u, double v, double step, int width, int height) {
  SliceNavigation(SlicePlane plane, int slice, double u, double v, double step, int side) {
    this(plane, slice, u, v, step, side, side);
  }

  /** Legacy square callers use the horizontal sample count. */
  int side() {
    return width;
  }

  SliceNavigation {
    Objects.requireNonNull(plane);
    if (!Double.isFinite(u)
        || !Double.isFinite(v)
        || !Double.isFinite(step)
        || step < NoiseImageNavigation.MIN_STEP
        || step > NoiseImageNavigation.MAX_STEP
        || width < 1
        || width > 256
        || height < 1
        || height > 256) throw new IllegalArgumentException("Invalid slice navigation");
  }

  SliceNavigation pan(double du, double dv) {
    if (!Double.isFinite(du) || !Double.isFinite(dv))
      throw new IllegalArgumentException("Invalid slice pan delta");
    if (du == 0 && dv == 0) return this;
    return new SliceNavigation(
        plane, slice, u - du * width * step, v - dv * height * step, step, width, height);
  }

  SliceNavigation zoom(double x, double y, double wheel) {
    if (!Double.isFinite(x)
        || !Double.isFinite(y)
        || x < 0
        || x > 1
        || y < 0
        || y > 1
        || !Double.isFinite(wheel))
      throw new IllegalArgumentException("Invalid slice zoom gesture");
    if (wheel == 0) return this;
    double next =
        Math.max(
            NoiseImageNavigation.MIN_STEP,
            Math.min(NoiseImageNavigation.MAX_STEP, step * Math.exp(-wheel * .15)));
    if (next == step) return this;
    return new SliceNavigation(
        plane,
        slice,
        u + (x * width - width / 2.) * (step - next),
        v + (y * height - height / 2.) * (step - next),
        next,
        width,
        height);
  }
}
