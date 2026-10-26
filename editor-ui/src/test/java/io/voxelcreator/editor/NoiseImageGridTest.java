package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NoiseImageGridTest {
  @Test
  void ordinaryCoordinatesMatchIndependentWorldMultiples() {
    for (double center : new double[] {-1234.5, -17.25, 0, 3.75, 1024.5})
      for (double step : new double[] {.0001, .25, 1, 37, 10000}) {
        double extent = 256 * step;
        double spacing = Math.pow(10, Math.floor(Math.log10(Math.max(.00001, extent / 12))));
        var actual = NoiseImageGrid.lines(center, step, 256);
        int index = 0;
        long first = (long) Math.ceil((center - extent / 2) / spacing);
        long last = (long) Math.floor((center + extent / 2) / spacing);
        for (long multiple = first; multiple <= last; multiple++) {
          double expected = (multiple * spacing - (center - extent / 2)) / extent;
          assertEquals(expected, actual[index++], 1e-10);
        }
        assertEquals(index, actual.length);
      }
  }

  @Test
  void extremeCentersRemainBoundedOrderedAndFinite() {
    // Historical x += spacing loops cannot advance at these magnitudes.
    for (double center :
        new double[] {1e20, -1e20, 1e300, -1e300, Double.MAX_VALUE, -Double.MAX_VALUE})
      for (double step : new double[] {.0001, .25, 1, 10000}) {
        var positions = NoiseImageGrid.lines(center, step, 256);
        assertTrue(positions.length > 0 && positions.length <= NoiseImageGrid.MAX_LINES);
        double previous = -1;
        for (double p : positions) {
          assertTrue(Double.isFinite(p) && p >= 0 && p <= 1 && p > previous);
          previous = p;
        }
      }
  }

  @Test
  void periodicTranslationReflectionAndReturnedOwnership() {
    var original = NoiseImageGrid.lines(3.75, 1, 256);
    assertArrayEquals(original, NoiseImageGrid.lines(103.75, 1, 256), 1e-14);
    var reflected = NoiseImageGrid.lines(-3.75, 1, 256);
    assertEquals(original.length, reflected.length);
    for (int i = 0; i < original.length; i++)
      assertEquals(1 - original[i], reflected[reflected.length - 1 - i], 1e-14);
    original[0] = 42;
    assertTrue(NoiseImageGrid.lines(3.75, 1, 256)[0] <= 1);
  }

  @Test
  void malformedOrOverflowingGridsRejectBeforeGeometry() {
    for (double center : new double[] {Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> NoiseImageGrid.lines(center, 1, 256));
    for (double step : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> NoiseImageGrid.lines(0, step, 256));
    assertThrows(IllegalArgumentException.class, () -> NoiseImageGrid.lines(0, 1, 0));
    assertThrows(IllegalArgumentException.class, () -> NoiseImageGrid.lines(0, 1, 257));
  }
}
