package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GridCoordinateBitsTest {
  private static void bits(double expected, double actual) {
    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual));
  }

  @Test
  void twoDimensionalOriginsRetainTheirSignsOnEachAxis() {
    for (double ox : new double[] {0.0, -0.0})
      for (double oz : new double[] {0.0, -0.0}) {
        NoiseSampler2D x = (a, b) -> a, z = (a, b) -> b;
        double[] xs = new double[6], zs = new double[6];
        x.sampleGrid(ox, oz, 3, 2, .25, xs);
        z.sampleGrid(ox, oz, 3, 2, .25, zs);
        bits(ox, xs[0]);
        bits(ox, xs[3]);
        for (int i = 0; i < 3; i++) bits(oz, zs[i]);
        bits(.25, xs[1]);
        bits(.25, zs[3]);
      }
  }

  @Test
  void threeDimensionalOriginsRetainTheirSignsOnEveryPlaneAndRow() {
    NoiseSampler3D[] projections = {(x, y, z) -> x, (x, y, z) -> y, (x, y, z) -> z};
    for (int mask = 0; mask < 8; mask++) {
      double[] origins = {
        (mask & 1) == 0 ? 0.0 : -0.0, (mask & 2) == 0 ? 0.0 : -0.0, (mask & 4) == 0 ? 0.0 : -0.0
      };
      for (int axis = 0; axis < 3; axis++) {
        double[] out = new double[8];
        projections[axis].sampleGrid(origins[0], origins[1], origins[2], 2, 2, 2, -.5, out);
        for (int y = 0; y < 2; y++)
          for (int z = 0; z < 2; z++)
            for (int x = 0; x < 2; x++) {
              int index = axis == 0 ? x : axis == 1 ? y : z;
              bits(index == 0 ? origins[axis] : -.5, out[y * 4 + z * 2 + x]);
            }
      }
    }
  }

  @Test
  void twoDimensionalZeroStepsMatchDirectAndPointSamplesWithPadding() {
    for (double step : new double[] {0.0, -0.0}) {
      NoiseSampler2D sampler =
          (x, z) -> {
            bits(-0.0, x);
            bits(-0.0, z);
            return x;
          };
      double[] out = new double[12];
      Arrays.fill(out, 99);
      sampler.sampleGrid(-0.0, -0.0, 3, 2, step, -step, out, 1, 5, new CancellationToken());
      double[] xs = {-0.0, -0.0, -0.0, -0.0, -0.0, -0.0};
      double[] points = new double[6];
      sampler.samplePoints(xs, xs, 0, 6, points, 0, new CancellationToken());
      for (int z = 0; z < 2; z++)
        for (int x = 0; x < 3; x++) {
          bits(sampler.sample(-0.0, -0.0), out[1 + z * 5 + x]);
          bits(points[z * 3 + x], out[1 + z * 5 + x]);
        }
      for (int i : new int[] {0, 4, 5, 9, 10, 11}) bits(99, out[i]);
    }
  }

  @Test
  void threeDimensionalZeroStepsPreserveAllOriginSignsAndPadding() {
    NoiseSampler3D sampler =
        (x, y, z) -> {
          bits(-0.0, x);
          bits(0.0, y);
          bits(-0.0, z);
          return z;
        };
    double[] out = new double[20];
    Arrays.fill(out, 99);
    sampler.sampleGrid(
        -0.0, 0.0, -0.0, 2, 2, 2, 0.0, -0.0, 0.0, out, 1, 4, 10, new CancellationToken());
    for (int i = 0; i < out.length; i++) {
      boolean written =
          i == 1 || i == 2 || i == 5 || i == 6 || i == 11 || i == 12 || i == 15 || i == 16;
      bits(written ? -0.0 : 99, out[i]);
    }
  }

  @Test
  void advancingAxesKeepSeparateMultiplyAndAddRounding() {
    NoiseSampler2D x2 = (x, z) -> x, z2 = (x, z) -> z;
    NoiseSampler3D x3 = (x, y, z) -> x, y3 = (x, y, z) -> y, z3 = (x, y, z) -> z;
    double[] out = new double[11];
    x2.sampleGrid(-1, 0, 11, 1, .1, out);
    bits(0.0, out[10]);
    z2.sampleGrid(0, -1, 1, 11, .1, out);
    bits(0.0, out[10]);
    x3.sampleGrid(-1, 0, 0, 11, 1, 1, .1, out);
    bits(0.0, out[10]);
    y3.sampleGrid(0, -1, 0, 1, 11, 1, .1, out);
    bits(0.0, out[10]);
    z3.sampleGrid(0, 0, -1, 1, 1, 11, .1, out);
    bits(0.0, out[10]);
    assertNotEquals(0.0, Math.fma(10, .1, -1));
    x2.sampleGrid(-Double.MIN_VALUE, 0, 3, 1, Double.MIN_VALUE, out);
    bits(-Double.MIN_VALUE, out[0]);
    bits(0.0, out[1]);
    bits(Double.MIN_VALUE, out[2]);
  }

  @Test
  void failureCoordinatesRetainSignsAndOnlySuccessfulPrefixIsWritten() {
    var calls = new AtomicInteger();
    var cause = new ArithmeticException("fourth sample");
    NoiseSampler3D sampler =
        (x, y, z) -> {
          if (calls.getAndIncrement() == 3) throw cause;
          return y;
        };
    double[] out = new double[20];
    Arrays.fill(out, 99);
    var failure =
        assertThrows(
            BatchSamplingException.class,
            () ->
                sampler.sampleGrid(
                    -0.0,
                    -0.0,
                    -0.0,
                    2,
                    2,
                    2,
                    0.0,
                    0.0,
                    0.0,
                    out,
                    1,
                    4,
                    10,
                    new CancellationToken()));
    assertEquals(3, failure.sampleIndex);
    bits(-0.0, failure.x);
    bits(-0.0, failure.y);
    bits(-0.0, failure.z);
    assertSame(cause, failure.getCause());
    for (int i = 0; i < out.length; i++) bits(i == 1 || i == 2 || i == 5 ? -0.0 : 99, out[i]);
    assertEquals(4, calls.get());
  }

  @Test
  void zeroCoordinatesStillPreflightLayoutAndExtentBeforeSampling() {
    var calls = new AtomicInteger();
    NoiseSampler2D sampler = (x, z) -> calls.incrementAndGet();
    double[] out = {99, 99, 99, 99};
    assertThrows(
        IllegalArgumentException.class,
        () -> sampler.sampleGrid(-0.0, -0.0, 2, 2, 0.0, -0.0, out, 0, 1, new CancellationToken()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            sampler.sampleGrid(
                -0.0,
                Double.MAX_VALUE,
                1,
                2,
                0.0,
                Double.MAX_VALUE,
                out,
                0,
                1,
                new CancellationToken()));
    assertEquals(0, calls.get());
    assertArrayEquals(new double[] {99, 99, 99, 99}, out);
  }

  @Test
  void cancelledZeroStepGridsLeaveOutputUntouched() {
    var token = new CancellationToken();
    token.cancel();
    NoiseSampler3D sampler =
        (x, y, z) -> {
          fail("cancelled sampler ran");
          return 0;
        };
    double[] out = {99, 99, 99, 99, 99, 99, 99, 99};
    assertThrows(
        CancellationException.class,
        () -> sampler.sampleGrid(-0.0, -0.0, -0.0, 2, 2, 2, 0.0, 0.0, 0.0, out, 0, 2, 4, token));
    for (double value : out) bits(99, value);
  }
}
