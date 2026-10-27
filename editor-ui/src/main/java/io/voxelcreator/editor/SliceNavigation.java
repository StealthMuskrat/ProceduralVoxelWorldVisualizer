package io.voxelcreator.editor;

import io.voxelcreator.render.SlicePlane;
import java.util.Objects;

/**
 * Finite plane-local navigation; requested extent and fixed coordinate are part of its identity.
 */
record SliceNavigation(SlicePlane plane, int slice, double u, double v, double step, int side) {
  SliceNavigation {
    Objects.requireNonNull(plane);
    if (!Double.isFinite(u)
        || !Double.isFinite(v)
        || !Double.isFinite(step)
        || step < NoiseImageNavigation.MIN_STEP
        || step > NoiseImageNavigation.MAX_STEP
        || side < 1
        || side > 256) throw new IllegalArgumentException("Invalid slice navigation");
  }

  SliceNavigation pan(double du, double dv) {
    if (!Double.isFinite(du) || !Double.isFinite(dv))
      throw new IllegalArgumentException("Invalid slice pan delta");
    if (du == 0 && dv == 0) return this;
    return new SliceNavigation(
        plane, slice, u - du * side * step, v - dv * side * step, step, side);
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
        u + (x * side - side / 2.) * (step - next),
        v + (y * side - side / 2.) * (step - next),
        next,
        side);
  }
}
