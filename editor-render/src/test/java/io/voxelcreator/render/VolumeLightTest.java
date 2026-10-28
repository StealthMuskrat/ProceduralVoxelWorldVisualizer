package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VolumeLightTest {
  private static VolumeTransfer transfer(VolumeData data, double opacity, double threshold) {
    return VolumeTransfer.of(
        data.encoding(), new VolumeTransfer.Settings(0, 1, opacity, threshold, 192));
  }

  @Test
  void homogeneousAttenuationMatchesIndependentBeerLambertForAllStepChoices() {
    var volume = VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 1);
    double distance = 6 * Math.sqrt(1.25);
    for (int steps : new int[] {8, 16, 32})
      assertEquals(
          Math.exp(-.25 * distance),
          VolumeLight.transmittance(
              volume, transfer(volume, .25, 0), 2, 2, 2, new VolumeLight.Settings(true, steps)),
          1e-12);
  }

  @Test
  void eachPositiveFaceCanLimitTheLightSegment() {
    var volume = VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 1);
    for (double[] point : new double[][] {{7.9, 1, 1}, {1, 7.9, 1}, {1, 1, 7.9}}) {
      double distance =
          Math.min((8 - point[0]) / .4, Math.min(8 - point[1], (8 - point[2]) / .3))
              * Math.sqrt(1.25);
      assertEquals(
          Math.exp(-.5 * distance),
          VolumeLight.transmittance(
              volume,
              transfer(volume, .5, 0),
              point[0],
              point[1],
              point[2],
              new VolumeLight.Settings(true, 16)),
          1e-12);
    }
  }

  @Test
  void emptyZeroOpacityThresholdAndExitBoundaryTransmitAllLight() {
    var volume = VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 1);
    assertEquals(
        1,
        VolumeLight.transmittance(
            volume, transfer(volume, 0, 0), 2, 2, 2, new VolumeLight.Settings(true, 16)));
    assertEquals(
        1,
        VolumeLight.transmittance(
            volume, transfer(volume, 1, 2), 2, 2, 2, new VolumeLight.Settings(true, 16)));
    assertEquals(
        1,
        VolumeLight.transmittance(
            volume, transfer(volume, 1, 0), 2, 8, 2, new VolumeLight.Settings(true, 16)));
    assertEquals(
        1,
        VolumeLight.transmittance(
            volume, transfer(volume, 1, 0), 2, 2, 2, new VolumeLight.Settings(false, 16)));
    var empty = VolumeData.sample(8, 0, 0, 0, (x, y, z) -> 0);
    assertEquals(
        1,
        VolumeLight.transmittance(
            empty, transfer(empty, 1, 0), 2, 2, 2, new VolumeLight.Settings(true, 16)));
  }

  @Test
  void clampedAffineIntegralConvergesToIndependentPiecewiseAntiderivative() {
    var volume = VolumeData.sample(8, 0, 0, 0, (x, y, z) -> y / 8);
    double integral = ((7.5 * 7.5 - 2 * 2) / 16 + .5 * 7.5 / 8) * Math.sqrt(1.25),
        expected = Math.exp(-.2 * integral);
    double low =
        VolumeLight.transmittance(
            volume, transfer(volume, .2, 0), 2, 2, 2, new VolumeLight.Settings(true, 8));
    double high =
        VolumeLight.transmittance(
            volume, transfer(volume, .2, 0), 2, 2, 2, new VolumeLight.Settings(true, 32));
    assertEquals(expected, high, 1e-4);
    assertTrue(Math.abs(high - expected) < Math.abs(low - expected));
  }

  @Test
  void extremeCanonicalEncodingsRetainEquivalentPhysicalOpticalDepth() {
    for (double density : new double[] {1e300, 1e-300, -1e300}) {
      var data = VolumeData.sample(4, 0, 0, 0, (x, y, z) -> density);
      double min = density > 0 ? 0 : 2 * density, max = density > 0 ? density : density;
      var t =
          VolumeTransfer.of(
              data.encoding(),
              new VolumeTransfer.Settings(min, max, .25, density > 0 ? 0 : 1.5 * density, 192));
      assertEquals(
          Math.exp(-.25 * 2 * Math.sqrt(1.25)),
          VolumeLight.transmittance(data, t, 2, 2, 2, new VolumeLight.Settings(true, 32)),
          1e-12);
    }
  }

  @Test
  void invalidSettingsAndOutsidePointsReject() {
    for (int steps : new int[] {0, 1, 7, 17, 33, 512})
      assertThrows(IllegalArgumentException.class, () -> new VolumeLight.Settings(true, steps));
    var data = VolumeData.sample(4, 0, 0, 0, (x, y, z) -> 1);
    var t = transfer(data, 1, 0);
    for (double x : new double[] {-.1, 4.1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () -> VolumeLight.transmittance(data, t, x, 2, 2, new VolumeLight.Settings(true, 16)));
  }
}
