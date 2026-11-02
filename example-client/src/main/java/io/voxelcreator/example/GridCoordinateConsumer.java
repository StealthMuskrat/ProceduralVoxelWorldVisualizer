package io.voxelcreator.example;

import io.voxelcreator.api.*;
import java.nio.file.*;
import java.util.*;

/** Deferred public engine-JAR-only signed-coordinate traversal replay. */
public final class GridCoordinateConsumer {
  private static final double[] STEPS = {0.0, -0.0, .25, -.25};

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    long two = replay(2), three = replay(3);
    if (two != 768 || three != 12288) throw new AssertionError("Incomplete coordinate matrix");

    // This cancellation point has a different result under fused multiply-add.
    NoiseSampler2D rounding = (x, z) -> x;
    double[] rounded = new double[11];
    rounding.sampleGrid(-1, 0, 11, 1, .1, rounded);
    bits(0.0, rounded[10]);

    var cause = new ArithmeticException("second sample");
    int[] calls = {0};
    NoiseSampler3D failing =
        (x, y, z) -> {
          if (++calls[0] == 2) throw cause;
          return x;
        };
    double[] prefix = {99, 99, 99, 99};
    try {
      failing.sampleGrid(-0.0, -0.0, -0.0, 2, 1, 1, 0.0, prefix);
      throw new AssertionError("Sampler failure disappeared");
    } catch (BatchSamplingException failure) {
      if (failure.sampleIndex != 1 || failure.getCause() != cause)
        throw new AssertionError("Failure ownership changed", failure);
      bits(-0.0, failure.x);
      bits(-0.0, failure.y);
      bits(-0.0, failure.z);
      bits(-0.0, prefix[0]);
      for (int i = 1; i < prefix.length; i++) bits(99, prefix[i]);
    }
    Files.writeString(
        out.resolve("grid-coordinate-report.txt"),
        "twoDimensionalValues="
            + two
            + "\nthreeDimensionalValues="
            + three
            + "\ngridValues="
            + (two + three)
            + "\npointValues="
            + (two + three)
            + "\nscalarValues="
            + (two + three)
            + "\n");
  }

  private static long replay(int dimensions) {
    int width = dimensions == 2 ? 3 : 2, count = dimensions == 2 ? 6 : 8;
    int stepCombinations = dimensions == 2 ? 16 : 64;
    long observations = 0;
    for (int signs = 0; signs < (1 << dimensions); signs++)
      for (int steps = 0; steps < stepCombinations; steps++) {
        double[] origins = new double[dimensions], increments = new double[dimensions];
        double[][] coordinates = new double[dimensions][count];
        for (int axis = 0; axis < dimensions; axis++) {
          origins[axis] = (signs & (1 << axis)) == 0 ? 0.0 : -0.0;
          increments[axis] = STEPS[(steps >> (2 * axis)) & 3];
        }
        int[] addresses = new int[count];
        for (int i = 0; i < count; i++) {
          int x = i % width, z = (i / width) % 2, y = i / (width * 2);
          addresses[i] = 1 + x + z * 5 + y * 13;
          int[] indices = dimensions == 2 ? new int[] {x, z} : new int[] {x, y, z};
          for (int axis = 0; axis < dimensions; axis++) {
            // Nonzero coordinates are exact quarter-unit multiples, independently of grid code.
            coordinates[axis][i] =
                indices[axis] == 0 || increments[axis] == 0
                    ? origins[axis]
                    : indices[axis] * increments[axis];
          }
        }
        for (int axis = 0; axis < dimensions; axis++) {
          final int selected = axis;
          double[] grid = new double[32], points = new double[count + 2];
          Arrays.fill(grid, 99);
          Arrays.fill(points, 99);
          if (dimensions == 2) {
            NoiseSampler2D sampler = (x, z) -> selected == 0 ? x : z;
            sampler.sampleGrid(
                origins[0],
                origins[1],
                width,
                2,
                increments[0],
                increments[1],
                grid,
                1,
                5,
                new CancellationToken());
            sampler.samplePoints(
                coordinates[0], coordinates[1], 0, count, points, 1, new CancellationToken());
            for (int i = 0; i < count; i++)
              bits(coordinates[axis][i], sampler.sample(coordinates[0][i], coordinates[1][i]));
          } else {
            NoiseSampler3D sampler = (x, y, z) -> selected == 0 ? x : selected == 1 ? y : z;
            sampler.sampleGrid(
                origins[0],
                origins[1],
                origins[2],
                width,
                2,
                2,
                increments[0],
                increments[1],
                increments[2],
                grid,
                1,
                5,
                13,
                new CancellationToken());
            sampler.samplePoints(
                coordinates[0],
                coordinates[1],
                coordinates[2],
                0,
                count,
                points,
                1,
                new CancellationToken());
            for (int i = 0; i < count; i++)
              bits(
                  coordinates[axis][i],
                  sampler.sample(coordinates[0][i], coordinates[1][i], coordinates[2][i]));
          }
          boolean[] written = new boolean[grid.length];
          for (int i = 0; i < count; i++) {
            written[addresses[i]] = true;
            bits(coordinates[axis][i], grid[addresses[i]]);
            bits(coordinates[axis][i], points[i + 1]);
            observations++;
          }
          for (int i = 0; i < grid.length; i++) if (!written[i]) bits(99, grid[i]);
          bits(99, points[0]);
          bits(99, points[count + 1]);
        }
      }
    return observations;
  }

  private static void bits(double expected, double actual) {
    if (Double.doubleToRawLongBits(expected) != Double.doubleToRawLongBits(actual))
      throw new AssertionError(
          "Coordinate bits differ: "
              + Double.toHexString(expected)
              + " versus "
              + Double.toHexString(actual));
  }
}
