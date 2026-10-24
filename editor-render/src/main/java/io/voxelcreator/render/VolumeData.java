package io.voxelcreator.render;

import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.util.concurrent.CancellationException;

/** Immutable cell-centered scalar texture. X is contiguous, then Y, then Z (OpenGL order). */
public final class VolumeData {
  private final int size;
  private final float[] samples;

  public VolumeData(int size, float[] values) {
    if (size < 2 || size > 256 || values.length != (long) size * size * size)
      throw new IllegalArgumentException("Volume size/count");
    for (float value : values)
      if (!Float.isFinite(value))
        throw new IllegalArgumentException("Finite display samples required");
    this.size = size;
    this.samples = values.clone();
  }

  public static VolumeData sample(
      int size, double originX, double originY, double originZ, Field field) {
    if (size < 2 || size > 256) throw new IllegalArgumentException("Volume size");
    float[] values = new float[size * size * size];
    for (int z = 0; z < size; z++) {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++) {
          double value = field.sample(originX + x + .5, originY + y + .5, originZ + z + .5);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException("Nonfinite canonical field sample");
          // GPU data is display-only. Saturation prevents finite double values overflowing float.
          values[(z * size + y) * size + x] =
              (float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, value));
        }
    }
    return new VolumeData(size, values);
  }

  public int size() {
    return size;
  }

  public float[] copySamples() {
    return samples.clone();
  }

  public float get(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size)
      throw new IndexOutOfBoundsException();
    return samples[(z * size + y) * size + x];
  }

  /** Matches GL_LINEAR / CLAMP_TO_EDGE at a local location in [0,size]^3. */
  public double interpolate(double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
      throw new IllegalArgumentException("Finite location required");
    x = Math.max(0, Math.min(size - 1, x - .5));
    y = Math.max(0, Math.min(size - 1, y - .5));
    z = Math.max(0, Math.min(size - 1, z - .5));
    int ix = (int) x,
        iy = (int) y,
        iz = (int) z,
        jx = Math.min(size - 1, ix + 1),
        jy = Math.min(size - 1, iy + 1),
        jz = Math.min(size - 1, iz + 1);
    double tx = x - ix, ty = y - iy, tz = z - iz;
    double
        lo =
            lerp(
                lerp(get(ix, iy, iz), get(jx, iy, iz), tx),
                lerp(get(ix, jy, iz), get(jx, jy, iz), tx),
                ty),
        hi =
            lerp(
                lerp(get(ix, iy, jz), get(jx, iy, jz), tx),
                lerp(get(ix, jy, jz), get(jx, jy, jz), tx),
                ty);
    return lerp(lo, hi, tz);
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
