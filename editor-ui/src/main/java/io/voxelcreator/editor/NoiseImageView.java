package io.voxelcreator.editor;

import io.voxelcreator.core.NoiseAlgorithms;
import java.util.Objects;

/** Immutable geometry and sampling program belonging to one accepted 2D image. */
public record NoiseImageView(
    double panX, double panZ, double step, int side, NoiseAlgorithms.Field sampler) {
  public record Point(double x, double z) {}

  public record Sample(double x, double z, double raw, double display) {}

  public NoiseImageView {
    Objects.requireNonNull(sampler);
    if (!Double.isFinite(panX)
        || !Double.isFinite(panZ)
        || !Double.isFinite(step)
        || step <= 0
        || side < 1
        || side > 256) throw new IllegalArgumentException("Invalid displayed image geometry");
  }

  public Point at(double u, double v) {
    if (!Double.isFinite(u) || !Double.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1)
      throw new IllegalArgumentException("Pointer is outside the displayed image");
    double x = panX + (u * side - side / 2.) * step;
    double z = panZ + (v * side - side / 2.) * step;
    if (!Double.isFinite(x) || !Double.isFinite(z))
      throw new IllegalArgumentException("Nonfinite displayed image coordinate");
    return new Point(x, z);
  }

  public Sample sample(double u, double v) {
    var p = at(u, v);
    double raw = sampler.sample(p.x, 0, p.z);
    if (!Double.isFinite(raw)) throw new ArithmeticException("Nonfinite hover sample");
    return new Sample(p.x, p.z, raw, Math.max(0, Math.min(1, (raw + 1) / 2)));
  }
}
