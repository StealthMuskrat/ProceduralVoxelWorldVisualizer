package io.voxelcreator.editor;

import io.voxelcreator.core.NoiseAlgorithms;
import io.voxelcreator.render.SlicePlane;
import java.util.Objects;

/** Geometry and sampler captured together with one accepted orthogonal image. */
record SliceImageView(
    SlicePlane plane,
    int slice,
    double originU,
    double originV,
    double step,
    int side,
    NoiseAlgorithms.Field sampler) {
  record Point(double x, double y, double z) {}

  record Sample(Point point, double raw) {}

  SliceImageView {
    Objects.requireNonNull(plane);
    Objects.requireNonNull(sampler);
    if (!Double.isFinite(originU)
        || !Double.isFinite(originV)
        || !Double.isFinite(step)
        || step <= 0
        || side < 1
        || side > 256) throw new IllegalArgumentException("Invalid displayed slice geometry");
  }

  Point at(double x, double y) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1 || y < 0 || y > 1)
      throw new IllegalArgumentException("Pointer is outside the displayed slice");
    double u = originU + x * side * step, v = originV + y * side * step;
    if (!Double.isFinite(u) || !Double.isFinite(v))
      throw new IllegalArgumentException("Nonfinite displayed slice coordinate");
    return new Point(plane.x(u, v, slice), plane.y(u, v, slice), plane.z(u, v, slice));
  }

  Sample sample(double x, double y) {
    var point = at(x, y);
    double raw = sampler.sample(point.x(), point.y(), point.z());
    if (!Double.isFinite(raw)) throw new ArithmeticException("Nonfinite slice hover sample");
    return new Sample(point, raw);
  }
}
