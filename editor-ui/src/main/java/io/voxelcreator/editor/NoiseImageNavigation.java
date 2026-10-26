package io.voxelcreator.editor;

/** Finite, atomic 2D navigation. Deltas and anchors are normalized image coordinates. */
public record NoiseImageNavigation(double x, double z, double step) {
  public static final double MIN_STEP = .0001, MAX_STEP = 10000;

  public NoiseImageNavigation {
    if (!Double.isFinite(x)
        || !Double.isFinite(z)
        || !Double.isFinite(step)
        || step < MIN_STEP
        || step > MAX_STEP) throw new IllegalArgumentException("Invalid 2D navigation");
  }

  public NoiseImageNavigation pan(double du, double dv) {
    if (!Double.isFinite(du) || !Double.isFinite(dv))
      throw new IllegalArgumentException("Invalid pan delta");
    if (du == 0 && dv == 0) return this;
    return new NoiseImageNavigation(x - du * 256 * step, z - dv * 256 * step, step);
  }

  public NoiseImageNavigation zoom(double u, double v, double wheel) {
    if (!Double.isFinite(u)
        || !Double.isFinite(v)
        || u < 0
        || u > 1
        || v < 0
        || v > 1
        || !Double.isFinite(wheel)) throw new IllegalArgumentException("Invalid zoom gesture");
    if (wheel == 0) return this;
    double next = Math.max(MIN_STEP, Math.min(MAX_STEP, step * Math.exp(-wheel * .15)));
    if (next == step) return this;
    // Use a finite scale difference, avoiding a huge intermediate world anchor.
    return new NoiseImageNavigation(
        x + (u * 256 - 128) * (step - next), z + (v * 256 - 128) * (step - next), next);
  }
}
