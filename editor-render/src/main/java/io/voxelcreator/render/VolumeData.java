package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.util.Objects;

/** Immutable cell-centered scalar texture. X is contiguous, then Y, then Z (OpenGL order). */
public final class VolumeData {
  /** Texture values decode as offset + encoded*scale; min/max bound float reconstruction. */
  public record Encoding(double offset, double scale, double min, double max) {
    public Encoding {
      if (!Double.isFinite(offset)
          || !Double.isFinite(scale)
          || scale <= 0
          || !Double.isFinite(min)
          || !Double.isFinite(max)
          || min > max) throw new IllegalArgumentException("Volume encoding");
    }

    double decode(double value) {
      return Math.max(min, Math.min(max, offset + value * scale));
    }
  }

  private final int size;
  private final float[] samples;
  private final Encoding encoding;

  public VolumeData(int size, float[] values) {
    size(size);
    Objects.requireNonNull(values);
    if (values.length != (long) size * size * size)
      throw new IllegalArgumentException("Volume size/count");
    float[] owned = values.clone();
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (float value : owned) {
      if (!Float.isFinite(value))
        throw new IllegalArgumentException("Finite display samples required");
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    this.size = size;
    encoding = encoding(min, max);
    samples = owned;
    for (int i = 0; i < samples.length; i++) samples[i] = encode(samples[i], encoding);
  }

  private VolumeData(int size, float[] owned, Encoding encoding) {
    this.size = size;
    samples = owned;
    this.encoding = encoding;
  }

  private static void size(int size) {
    if (size < 2 || size > 256) throw new IllegalArgumentException("Volume size 2..256");
  }

  private static Encoding encoding(double min, double max) {
    double magnitude = Math.max(Math.abs(min), Math.abs(max));
    if (min == max) return new Encoding(min, magnitude == 0 ? 1 : magnitude, min, max);
    if (magnitude >= 1e-30 && magnitude <= 1e30) return new Encoding(0, 1, min, max);
    double span = max - min;
    if (!Double.isFinite(span)) return new Encoding(0, magnitude, min, max);
    return new Encoding(min, span == 0 ? magnitude : span, min, max);
  }

  private static float encode(double value, Encoding encoding) {
    return (float) ((value - encoding.offset) / encoding.scale);
  }

  public static VolumeData sample(
      int size, double originX, double originY, double originZ, Field field) {
    return sample(size, originX, originY, originZ, field, new CancellationToken());
  }

  public static VolumeData sample(
      int size,
      double originX,
      double originY,
      double originZ,
      Field field,
      CancellationToken token) {
    size(size);
    Objects.requireNonNull(field);
    Objects.requireNonNull(token);
    if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ))
      throw new IllegalArgumentException("Finite volume origin required");
    token.throwIfCancelled();
    double[] raw = new double[size * size * size];
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (int z = 0; z < size; z++)
      for (int y = 0; y < size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x < size; x++) {
          double value = field.sample(originX + x + .5, originY + y + .5, originZ + z + .5);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException(
                "Nonfinite canonical volume sample at " + x + "," + y + "," + z);
          raw[(z * size + y) * size + x] = value;
          min = Math.min(min, value);
          max = Math.max(max, value);
        }
      }
    return encodeOwned(size, raw, min, max, token);
  }

  /** Package-owned canonical staging is never retained or exposed after float publication. */
  static VolumeData encodeOwned(
      int size, double[] raw, double min, double max, CancellationToken token) {
    token.throwIfCancelled();
    var encoding = encoding(min, max);
    float[] encoded = new float[raw.length];
    for (int z = 0; z < size; z++)
      for (int y = 0; y < size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x < size; x++) {
          int i = (z * size + y) * size + x;
          encoded[i] = encode(raw[i], encoding);
        }
      }
    token.throwIfCancelled();
    return new VolumeData(size, encoded, encoding);
  }

  public int size() {
    return size;
  }

  public Encoding encoding() {
    return encoding;
  }

  /**
   * Encoded float texels; use encoding() to interpret canonical values. The returned copy is owned.
   */
  public float[] copySamples() {
    return samples.clone();
  }

  /** Copy only the requested encoded texels into bounded render-thread staging. */
  void copySamples(int offset, java.nio.FloatBuffer target) {
    Objects.requireNonNull(target);
    Objects.checkFromIndexSize(offset, target.remaining(), samples.length);
    target.put(samples, offset, target.remaining());
  }

  private float encoded(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size)
      throw new IndexOutOfBoundsException();
    return samples[(z * size + y) * size + x];
  }

  /** Canonical reconstruction in double precision from the display texture. */
  public double value(int x, int y, int z) {
    return encoding.decode(encoded(x, y, z));
  }

  /**
   * Historical saturated float accessor; canonical double reconstruction is available as value().
   */
  public float get(int x, int y, int z) {
    return (float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, value(x, y, z)));
  }

  /** Matches encoded GL_LINEAR / CLAMP_TO_EDGE, decoded at a local location in [0,size]^3. */
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
    double lo =
        lerp(
            lerp(encoded(ix, iy, iz), encoded(jx, iy, iz), tx),
            lerp(encoded(ix, jy, iz), encoded(jx, jy, iz), tx),
            ty);
    double hi =
        lerp(
            lerp(encoded(ix, iy, jz), encoded(jx, iy, jz), tx),
            lerp(encoded(ix, jy, jz), encoded(jx, jy, jz), tx),
            ty);
    return encoding.decode(lerp(lo, hi, tz));
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
