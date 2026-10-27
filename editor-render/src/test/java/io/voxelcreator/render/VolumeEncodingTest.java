package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VolumeEncodingTest {
  @Test
  void ordinaryTexelsRetainTheirOriginalValuesAndAxisOrder() {
    var d = VolumeData.sample(3, -2, 5, -7, (x, y, z) -> x + 10 * y + 100 * z);
    assertEquals(0, d.encoding().offset());
    assertEquals(1, d.encoding().scale());
    for (int z = 0; z < 3; z++)
      for (int y = 0; y < 3; y++)
        for (int x = 0; x < 3; x++) {
          double expected = (-2 + x + .5) + 10 * (5 + y + .5) + 100 * (-7 + z + .5);
          assertEquals(expected, d.value(x, y, z));
          assertEquals((float) expected, d.copySamples()[(z * 3 + y) * 3 + x]);
        }
  }

  @Test
  void largeOffsetsAndAdjacentFiniteEndpointsKeepTheirContrast() {
    double lo = Math.nextDown(Double.MAX_VALUE), hi = Double.MAX_VALUE;
    var d = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> x < 1 ? lo : hi);
    assertEquals(lo, d.value(0, 0, 0));
    assertEquals(hi, d.value(1, 0, 0));
    assertEquals(0, d.copySamples()[0]);
    assertEquals(1, d.copySamples()[1]);
    assertEquals(Float.MAX_VALUE, d.get(0, 0, 0));
    assertTrue(Double.isFinite(d.interpolate(1, 1, 1)));
    assertTrue(d.interpolate(1, 1, 1) >= lo && d.interpolate(1, 1, 1) <= hi);
  }

  @Test
  void tinyAndOppositeExtremeValuesRemainFiniteAndInterpolateSymmetrically() {
    for (double magnitude : new double[] {Double.MIN_VALUE, 1e-300, Double.MAX_VALUE}) {
      var d = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> x < 1 ? -magnitude : magnitude);
      assertEquals(-magnitude, d.value(0, 0, 0));
      assertEquals(magnitude, d.value(1, 0, 0));
      assertEquals(0, d.interpolate(1, 1, 1));
      for (float value : d.copySamples()) assertTrue(Float.isFinite(value));
      assertTrue(Double.isFinite(d.encoding().scale()));
    }
  }

  @Test
  void uniformExtremeAndSubnormalSamplesHaveStableCanonicalReconstruction() {
    for (double value :
        new double[] {
          Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE, 0
        }) {
      var d = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> value);
      assertEquals(value, d.value(0, 1, 1));
      assertEquals(value, d.interpolate(.25, 1.25, 1.75));
      assertTrue(d.encoding().scale() > 0);
      assertEquals(value, d.encoding().min());
      assertEquals(value, d.encoding().max());
    }
  }

  @Test
  void publicAndSampledSnapshotsOwnEncodedArraysAndPreserveHistoricalFloatAccess() {
    float[] source = new float[8];
    source[1] = Float.MAX_VALUE;
    var d = new VolumeData(2, source);
    source[1] = 0;
    assertEquals(Float.MAX_VALUE, d.get(1, 0, 0));
    var copy = d.copySamples();
    copy[1] = 0;
    assertEquals(Float.MAX_VALUE, d.value(1, 0, 0));
    var tiny =
        new VolumeData(
            2,
            new float[] {
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE,
              Float.MIN_VALUE
            });
    assertEquals(Float.MIN_VALUE, tiny.get(0, 0, 0));
  }

  @Test
  void invalidOriginsAndNonfiniteFieldsRejectWithoutPublishingPartialSamples() {
    var count = new AtomicInteger();
    for (double origin :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              VolumeData.sample(
                  2,
                  origin,
                  0,
                  0,
                  (x, y, z) -> {
                    count.incrementAndGet();
                    return 1;
                  }));
    assertEquals(0, count.get());
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> VolumeData.sample(2, 0, 0, 0, (x, y, z) -> Double.NaN));
    assertTrue(error.getMessage().contains("0,0,0"));
  }

  @Test
  void explicitCancellationStopsBothSamplingAndEncodingWithPreviousSnapshotIntact() {
    var previous = VolumeData.sample(2, 0, 0, 0, (x, y, z) -> 7);
    var before = previous.copySamples();
    var pre = new CancellationToken();
    pre.cancel();
    var count = new AtomicInteger();
    assertThrows(
        CancellationException.class,
        () ->
            VolumeData.sample(
                4,
                0,
                0,
                0,
                (x, y, z) -> {
                  count.incrementAndGet();
                  return 1;
                },
                pre));
    assertEquals(0, count.get());
    var during = new CancellationToken();
    assertThrows(
        CancellationException.class,
        () ->
            VolumeData.sample(
                4,
                0,
                0,
                0,
                (x, y, z) -> {
                  count.incrementAndGet();
                  during.cancel();
                  return 1;
                },
                during));
    assertTrue(count.get() <= 4);
    var sampled = new AtomicInteger();
    var checks = new AtomicInteger();
    var encoding =
        new CancellationToken(() -> sampled.get() == 64 && checks.incrementAndGet() >= 2);
    assertThrows(
        CancellationException.class,
        () ->
            VolumeData.sample(
                4,
                0,
                0,
                0,
                (x, y, z) -> {
                  sampled.incrementAndGet();
                  return 1e300;
                },
                encoding));
    assertEquals(64, sampled.get());
    assertArrayEquals(before, previous.copySamples());
  }

  @Test
  void samplerFailuresRetainTheirCauseAndSuccessfulGridVisitsEachCellOnce() {
    var cause = new IllegalStateException("source failure");
    assertSame(
        cause,
        assertThrows(
            IllegalStateException.class,
            () ->
                VolumeData.sample(
                    2,
                    0,
                    0,
                    0,
                    (x, y, z) -> {
                      throw cause;
                    })));
    var count = new AtomicInteger();
    VolumeData.sample(
        4,
        0,
        0,
        0,
        (x, y, z) -> {
          count.incrementAndGet();
          return z;
        });
    assertEquals(64, count.get());
  }
}
