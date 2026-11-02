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

  private final GridExtent shape;
  private final int sampleStep;
  private final float[] samples;
  private final Encoding encoding;

  public VolumeData(int size, float[] values) {
    this(GridExtent.cube(size), values);
  }

  public VolumeData(GridExtent shape, float[] values) {
    validateShape(shape);
    Objects.requireNonNull(values);
    if (values.length != shape.cells()) throw new IllegalArgumentException("Volume size/count");
    float[] owned = values.clone();
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (float value : owned) {
      if (!Float.isFinite(value))
        throw new IllegalArgumentException("Finite display samples required");
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    this.shape = shape;
    sampleStep = 1;
    encoding = encoding(min, max);
    samples = owned;
    for (int i = 0; i < samples.length; i++) samples[i] = encode(samples[i], encoding);
  }

  private VolumeData(GridExtent shape, int sampleStep, float[] owned, Encoding encoding) {
    this.shape = shape;
    this.sampleStep = sampleStep;
    samples = owned;
    this.encoding = encoding;
  }

  private static void validateShape(GridExtent shape) {
    Objects.requireNonNull(shape);
    if (shape.width() < 2 || shape.height() < 2 || shape.depth() < 2)
      throw new IllegalArgumentException("Volume axes must be 2..256 texels");
  }

  public static void validateSampleStep(int sampleStep) {
    if (sampleStep < 1 || sampleStep > 16 || (sampleStep & (sampleStep - 1)) != 0)
      throw new IllegalArgumentException("Volume sample spacing must be 1, 2, 4, 8 or 16");
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
    return sampleGrid(size, 1, originX, originY, originZ, field, token);
  }

  public static VolumeData sample(
      GridExtent shape,
      double originX,
      double originY,
      double originZ,
      Field field,
      CancellationToken token) {
    return sampleGrid(shape, 1, originX, originY, originZ, field, token);
  }

  public static VolumeData sample(
      GridExtent shape, double originX, double originY, double originZ, Field field) {
    return sample(shape, originX, originY, originZ, field, new CancellationToken());
  }

  /** Canonical field sampled at coarse-cell centers for display only; size counts texels. */
  public static VolumeData sampleGrid(
      int size,
      int sampleStep,
      double originX,
      double originY,
      double originZ,
      Field field,
      CancellationToken token) {
    return sampleGrid(GridExtent.cube(size), sampleStep, originX, originY, originZ, field, token);
  }

  public static VolumeData sampleGrid(
      GridExtent shape,
      int sampleStep,
      double originX,
      double originY,
      double originZ,
      Field field,
      CancellationToken token) {
    validateShape(shape);
    validateSampleStep(sampleStep);
    Objects.requireNonNull(field);
    Objects.requireNonNull(token);
    if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ))
      throw new IllegalArgumentException("Finite volume origin required");
    token.throwIfCancelled();
    double[] raw = new double[shape.cells()];
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (int z = 0; z < shape.depth(); z++)
      for (int y = 0; y < shape.height(); y++) {
        token.throwIfCancelled();
        for (int x = 0; x < shape.width(); x++) {
          double value =
              field.sample(
                  coordinate(originX, x, sampleStep),
                  coordinate(originY, y, sampleStep),
                  coordinate(originZ, z, sampleStep));
          if (!Double.isFinite(value))
            throw new IllegalArgumentException(
                "Nonfinite canonical volume sample at " + x + "," + y + "," + z);
          raw[(z * shape.height() + y) * shape.width() + x] = value;
          min = Math.min(min, value);
          max = Math.max(max, value);
        }
      }
    return encodeOwned(shape, sampleStep, raw, min, max, token);
  }

  private static double coordinate(double origin, int index, int sampleStep) {
    // Preserve historical unit-grid floating-point order, including large fixed-analysis origins.
    return sampleStep == 1 ? origin + index + .5 : origin + (index + .5) * sampleStep;
  }

  /** Package-owned canonical staging is never retained or exposed after float publication. */
  static VolumeData encodeOwned(
      int size, double[] raw, double min, double max, CancellationToken token) {
    return encodeOwned(size, 1, raw, min, max, token);
  }

  static VolumeData encodeOwned(
      int size, int sampleStep, double[] raw, double min, double max, CancellationToken token) {
    return encodeOwned(GridExtent.cube(size), sampleStep, raw, min, max, token);
  }

  static VolumeData encodeOwned(
      GridExtent shape,
      int sampleStep,
      double[] raw,
      double min,
      double max,
      CancellationToken token) {
    validateShape(shape);
    validateSampleStep(sampleStep);
    if (raw.length != shape.cells()) throw new IllegalArgumentException("Volume sample count");
    token.throwIfCancelled();
    var encoding = encoding(min, max);
    float[] encoded = new float[raw.length];
    for (int z = 0; z < shape.depth(); z++)
      for (int y = 0; y < shape.height(); y++) {
        token.throwIfCancelled();
        for (int x = 0; x < shape.width(); x++) {
          int i = (z * shape.height() + y) * shape.width() + x;
          encoded[i] = encode(raw[i], encoding);
        }
      }
    token.throwIfCancelled();
    return new VolumeData(shape, sampleStep, encoded, encoding);
  }

  /** Legacy cube accessor; rectangular consumers use shape()/width()/height()/depth(). */
  public int size() {
    return shape.width();
  }

  public GridExtent shape() {
    return shape;
  }

  public int width() {
    return shape.width();
  }

  public int height() {
    return shape.height();
  }

  public int depth() {
    return shape.depth();
  }

  public int extentX() {
    return width() * sampleStep;
  }

  public int extentY() {
    return height() * sampleStep;
  }

  public int extentZ() {
    return depth() * sampleStep;
  }

  /** World units between adjacent texture sample centers. */
  public int sampleStep() {
    return sampleStep;
  }

  /** Legacy horizontal world span; rectangular consumers use extentX/Y/Z. */
  public int extent() {
    return extentX();
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

  /** Gather a bounded rectangular encoded region into reusable packed staging. */
  void copyRegion(
      int x, int y, int z, int width, int height, int depth, java.nio.FloatBuffer target) {
    Objects.requireNonNull(target);
    if (width <= 0 || height <= 0 || depth <= 0)
      throw new IllegalArgumentException("Positive volume region required");
    Objects.checkFromIndexSize(x, width, shape.width());
    Objects.checkFromIndexSize(y, height, shape.height());
    Objects.checkFromIndexSize(z, depth, shape.depth());
    int count = width * height * depth;
    if (target.remaining() < count) throw new java.nio.BufferOverflowException();
    for (int dz = 0; dz < depth; dz++)
      for (int dy = 0; dy < height; dy++)
        target.put(samples, ((z + dz) * shape.height() + y + dy) * shape.width() + x, width);
  }

  private float encoded(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= shape.width() || y >= shape.height() || z >= shape.depth())
      throw new IndexOutOfBoundsException();
    return samples[(z * shape.height() + y) * shape.width() + x];
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

  /** Matches encoded GL_LINEAR / CLAMP_TO_EDGE, decoded in the local per-axis world extents. */
  public double interpolate(double x, double y, double z) {
    return encoding.decode(interpolateEncoded(x, y, z));
  }

  /** Encoded GL_LINEAR/CLAMP_TO_EDGE reconstruction for display-only CPU references. */
  double interpolateEncoded(double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
      throw new IllegalArgumentException("Finite location required");
    x = Math.max(0, Math.min(shape.width() - 1, x / sampleStep - .5));
    y = Math.max(0, Math.min(shape.height() - 1, y / sampleStep - .5));
    z = Math.max(0, Math.min(shape.depth() - 1, z / sampleStep - .5));
    int ix = (int) x,
        iy = (int) y,
        iz = (int) z,
        jx = Math.min(shape.width() - 1, ix + 1),
        jy = Math.min(shape.height() - 1, iy + 1),
        jz = Math.min(shape.depth() - 1, iz + 1);
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
    return lerp(lo, hi, tz);
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }
}
