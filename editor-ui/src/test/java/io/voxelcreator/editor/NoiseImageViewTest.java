package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NoiseImageViewTest {
  @Test
  void everyPixelUsesOriginalGridOrderAndContinuousHoverIsExact() {
    var view = new NoiseImageView(-17.25, 3.5, .25, 256, (x, y, z) -> x * .5 + z * 3);
    for (int z = 0; z < 256; z++)
      for (int x = 0; x < 256; x++) {
        var p = view.sample(x / 256., z / 256.);
        double wx = -17.25 + (x - 128.) * .25, wz = 3.5 + (z - 128.) * .25;
        assertEquals(Double.doubleToLongBits(wx), Double.doubleToLongBits(p.x()));
        assertEquals(Double.doubleToLongBits(wz), Double.doubleToLongBits(p.z()));
        assertEquals(wx * .5 + wz * 3, p.raw());
      }
    var p = view.sample(.501, .499);
    assertEquals(-17.25 + (.501 * 256 - 128) * .25, p.x());
    assertEquals(3.5 + (.499 * 256 - 128) * .25, p.z());
  }

  @Test
  void requestedReplacementDoesNotChangeAcceptedGeometryOrSamplingProgram() {
    var accepted = new NoiseImageView(10, 20, 1, 256, (x, y, z) -> x + z);
    var requested = new NoiseImageView(-100, -200, .5, 256, (x, y, z) -> 999);
    assertEquals(30, accepted.sample(.5, .5).raw());
    assertEquals(999, requested.sample(.5, .5).raw());
    assertEquals(10, accepted.at(.5, .5).x());
    assertEquals(20, accepted.at(.5, .5).z());
  }

  @Test
  void displayMappingDoesNotClampRawAndFailureDoesNotInvalidateGeometry() {
    for (double raw : new double[] {-9, -1, 0, 1, 9}) {
      var view = new NoiseImageView(0, 0, 1, 256, (x, y, z) -> raw);
      assertEquals(raw, view.sample(.5, .5).raw());
      assertEquals(Math.max(0, Math.min(1, (raw + 1) / 2)), view.sample(.5, .5).display());
    }
    var view = new NoiseImageView(0, 0, 1, 256, (x, y, z) -> x == 0 ? Double.NaN : x);
    assertThrows(ArithmeticException.class, () -> view.sample(.5, .5));
    assertEquals(64, view.sample(.75, .5).raw());
    assertEquals(0, view.at(.5, .5).x());
  }

  @Test
  void invalidPointerAndNonfiniteCoordinatesRejectBeforeSampling() {
    var view =
        new NoiseImageView(
            0,
            0,
            1,
            256,
            (x, y, z) -> {
              throw new AssertionError("Unexpected sample");
            });
    for (double u : new double[] {-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> view.sample(u, .5));
    assertThrows(
        IllegalArgumentException.class, () -> new NoiseImageView(0, 0, 0, 256, (x, y, z) -> 0));
    var huge = new NoiseImageView(Double.MAX_VALUE, 0, Double.MAX_VALUE, 256, (x, y, z) -> 0);
    assertThrows(IllegalArgumentException.class, () -> huge.at(1, .5));
  }
}
