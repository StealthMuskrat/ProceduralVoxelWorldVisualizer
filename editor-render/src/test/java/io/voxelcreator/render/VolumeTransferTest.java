package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VolumeTransferTest {
  private VolumeTransfer transfer(double value, double min, double max, double threshold) {
    var data = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> value);
    return VolumeTransfer.of(
        data.encoding(), new VolumeTransfer.Settings(min, max, .5, threshold, 192));
  }

  private float encoded(double value) {
    return VolumeData.sample(2, 0, 0, 0, (x, y, z) -> value).copySamples()[0];
  }

  @Test
  void canonicalScalingPreservesMappedValuesAndThresholdOrdering() {
    for (double scale : new double[] {1, 1e-300, 1e300}) {
      var t = transfer(.5 * scale, 0, scale, .75 * scale);
      assertEquals(.5, t.mapped(encoded(.5 * scale)), 1e-6);
      assertTrue(encoded(.5 * scale) < t.threshold());
    }
  }

  @Test
  void fullFiniteSignedRangeAndTinyUniformFieldsHaveCorrectTransfer() {
    assertEquals(
        .25,
        transfer(-Double.MAX_VALUE / 2, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE)
            .mapped(encoded(-Double.MAX_VALUE / 2)),
        1e-6);
    assertEquals(.5, transfer(0, -Double.MAX_VALUE, Double.MAX_VALUE, 0).mapped(encoded(0)), 1e-6);
    assertEquals(
        1,
        transfer(Double.MIN_VALUE, 0, Double.MIN_VALUE, 0).mapped(encoded(Double.MIN_VALUE)),
        1e-6);
  }

  @Test
  void adjacentHugeBoundsResolveBothUniformEndpoints() {
    for (double hi : new double[] {Double.MAX_VALUE, 1, 1e-100}) {
      double lo = Math.nextDown(hi);
      assertEquals(0, transfer(lo, lo, hi, 0).mapped(encoded(lo)), 0);
      assertEquals(1, transfer(hi, lo, hi, 0).mapped(encoded(hi)), 0);
      assertTrue(encoded(lo) < transfer(lo, 0, hi, hi).threshold());
    }
  }

  @Test
  void filteringKeepsTinyNegativeSignsEvenWithHugeDisplayBounds() {
    var t = transfer(-Double.MIN_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, 0);
    assertTrue(encoded(-Double.MIN_VALUE) < t.threshold());
    var allowed =
        transfer(-Double.MIN_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE, -Double.MIN_VALUE);
    assertEquals(encoded(-Double.MIN_VALUE), allowed.threshold());
    assertEquals(.5, allowed.mapped(encoded(-Double.MIN_VALUE)), 1e-6);
  }

  @Test
  void allGpuCoefficientsStayFiniteForValidExtremeControls() {
    for (double value :
        new double[] {
          -Double.MAX_VALUE, -1, -Double.MIN_VALUE, 0, Double.MIN_VALUE, 1, Double.MAX_VALUE
        })
      for (double[] range :
          new double[][] {
            {-Double.MAX_VALUE, Double.MAX_VALUE},
            {0, Double.MIN_VALUE},
            {Math.nextDown(Double.MAX_VALUE), Double.MAX_VALUE}
          }) {
        var t = transfer(value, range[0], range[1], -Double.MAX_VALUE);
        assertTrue(Float.isFinite(t.gain()));
        assertTrue(Float.isFinite(t.bias()));
        assertTrue(Float.isFinite(t.threshold()));
        assertTrue(Float.isFinite(t.mapped(encoded(value))));
      }
  }

  @Test
  void invalidControlsRejectAndOpacityRetainsItsExistingCap() {
    assertThrows(
        IllegalArgumentException.class, () -> new VolumeTransfer.Settings(1, 1, .5, 0, 192));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VolumeTransfer.Settings(0, Double.POSITIVE_INFINITY, .5, 0, 192));
    assertThrows(
        IllegalArgumentException.class, () -> new VolumeTransfer.Settings(0, 1, -1, 0, 192));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VolumeTransfer.Settings(0, 1, .5, Double.NaN, 192));
    assertThrows(
        IllegalArgumentException.class, () -> new VolumeTransfer.Settings(0, 1, .5, 0, 15));
    var data = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> 1);
    assertEquals(
        10,
        VolumeTransfer.of(data.encoding(), new VolumeTransfer.Settings(0, 1, 100, 0, 512))
            .opacity());
  }
}
