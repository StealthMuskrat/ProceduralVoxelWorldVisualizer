package io.voxelcreator.editor;

import java.util.Arrays;

/** Display-only, origin-relative grid geometry. No world-coordinate stepping loops. */
public final class NoiseImageGrid {
  public static final int MAX_LINES = 128;

  private NoiseImageGrid() {}

  /** Owned normalized positions in [0,1], for the same centered span as the image. */
  public static double[] lines(double center, double step, int side) {
    if (!Double.isFinite(center) || !Double.isFinite(step) || step <= 0 || side < 1 || side > 256)
      throw new IllegalArgumentException("Invalid image grid");
    double extent = side * step;
    if (!Double.isFinite(extent) || extent == 0)
      throw new IllegalArgumentException("Invalid image grid extent");
    // Match the historical decade spacing. The lower bound also avoids subnormal powers.
    double spacing = Math.pow(10, Math.floor(Math.log10(Math.max(.00001, extent / 12))));
    double phase = center % spacing;
    double half = extent / 2;
    double first = Math.ceil((phase - half) / spacing) * spacing - phase;
    double[] result = new double[MAX_LINES];
    int count = 0;
    for (int i = 0; i < MAX_LINES; i++) {
      double offset = first + i * spacing;
      if (offset > half) break;
      double position = .5 + offset / extent;
      if (position >= 0 && position <= 1) result[count++] = position;
    }
    return Arrays.copyOf(result, count);
  }
}
