package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class NoiseImageRowsTest {
  private static final long BUDGET = 1L << 20;

  private static NoiseImageRows.Image sample(
      NoiseImageRows c,
      String key,
      long version,
      NoiseAlgorithms.Field f,
      int side,
      double x,
      double z,
      double step,
      long budget,
      boolean force) {
    return c.sample(key, version, f, side, x, z, step, budget, force, new CancellationToken());
  }

  @Test
  void largeFractionalCoordinatesMatchTheOriginalScalarProgramBitForBit() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x + z;
      long version = 1;
      for (double base : new double[] {1e12, 1e16, -1e12}) {
        for (double step : new double[] {.1, .25, 3}) {
          var image = sample(c, "fractional", version++, f, 8, base, -base, step, BUDGET, false);
          for (int z = 0; z < 8; z++)
            for (int x = 0; x < 8; x++) {
              double expected = (base + (x - 4.) * step) + (-base + (z - 4.) * step);
              assertEquals(
                  Double.doubleToLongBits(expected), Double.doubleToLongBits(image.value(x, z)));
            }
        }
      }
    }
  }

  @Test
  void exactGridRowsRecolorPanAndReturnWithoutChangingCoordinateOrder() {
    try (var c = new NoiseImageRows()) {
      var calls = new AtomicInteger();
      NoiseAlgorithms.Field f =
          (x, y, z) -> {
            calls.incrementAndGet();
            return x * .5 + z * 3;
          };
      var a = sample(c, "a", 1, f, 8, -3, -5, .5, BUDGET, false);
      assertEquals(64, calls.get());
      var b = sample(c, "a", 2, f, 8, -3, -5, .5, BUDGET, false);
      assertEquals(0, b.statistics().sampledRows());
      var pan = sample(c, "a", 3, f, 8, -3, -4.5, .5, BUDGET, false);
      assertEquals(1, pan.statistics().sampledRows());
      assertEquals(7, pan.statistics().reusedRows());
      assertEquals(72, calls.get());
      var back = sample(c, "a", 4, f, 8, -3, -5, .5, BUDGET, false);
      assertEquals(0, back.statistics().sampledRows());
      for (int z = 0; z < 8; z++)
        for (int x = 0; x < 8; x++) {
          double expected = (-3 + (x - 4.) * .5) * .5 + (-5 + (z - 4.) * .5) * 3;
          assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(a.value(x, z)));
          assertEquals(a.value(x, z), b.value(x, z));
          assertEquals(a.value(x, z), back.value(x, z));
        }
      var copy = a.copyValues();
      copy[0] = 99;
      assertNotEquals(99, a.value(0, 0));
    }
  }

  @Test
  void sourceAxisStepAndExplicitRebuildNeverReuseIncompatibleRows() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x - z;
      sample(c, "a", 1, f, 4, 0, 0, 1, BUDGET, false);
      assertEquals(4, sample(c, "a", 2, f, 4, 1, 0, 1, BUDGET, false).statistics().sampledRows());
      assertEquals(4, sample(c, "a", 3, f, 4, 1, 0, .5, BUDGET, false).statistics().sampledRows());
      assertEquals(4, sample(c, "b", 4, f, 4, 1, 0, .5, BUDGET, false).statistics().sampledRows());
      assertEquals(4, sample(c, "b", 5, f, 4, 1, 0, .5, BUDGET, true).statistics().sampledRows());
      assertEquals(0, sample(c, "b", 6, f, 4, 1, 0, .5, BUDGET, false).statistics().sampledRows());
    }
  }

  @Test
  void signedZeroCoordinatesRetainTheirInputBits() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f =
          (x, y, z) -> Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(-0.) ? -1 : 1;
      var minus = sample(c, "a", 1, f, 1, -0., 1, Double.MIN_VALUE, BUDGET, false);
      var plus = sample(c, "a", 2, f, 1, 0., 1, Double.MIN_VALUE, BUDGET, false);
      assertEquals(-1, minus.value(0, 0));
      assertEquals(1, plus.value(0, 0));
      assertEquals(1, plus.statistics().sampledRows());
    }
  }

  @Test
  void byteBoundAndLruRemainValidAcrossBudgetChangesAndDisabledCache() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> z;
      long budget = 2 * NoiseImageRows.rowBytes(1);
      sample(c, "a", 1, f, 1, 0, 1, 1, budget, false);
      sample(c, "a", 2, f, 1, 0, 2, 1, budget, false);
      assertEquals(0, sample(c, "a", 3, f, 1, 0, 1, 1, budget, false).statistics().sampledRows());
      sample(c, "a", 4, f, 1, 0, 3, 1, budget, false);
      assertEquals(1, sample(c, "a", 5, f, 1, 0, 2, 1, budget, false).statistics().sampledRows());
      assertTrue(c.memory().bytes() <= budget);
      assertEquals(2, c.memory().rows());
      sample(c, "a", 6, f, 1, 0, 2, 1, 0, false);
      assertEquals(0, c.memory().rows());
      assertEquals(0, c.memory().bytes());
      assertEquals(1, sample(c, "a", 7, f, 1, 0, 2, 1, 0, false).statistics().sampledRows());
      sample(c, "a", 8, f, 4, 0, 0, 1, Long.MAX_VALUE, false);
      assertEquals(NoiseImageRows.MAX_BYTES, c.memory().limitBytes());
      c.budget(0);
      assertEquals(0, c.memory().rows());
      sample(c, "a", 9, f, 4, 0, 0, 1, Long.MAX_VALUE, false);
      assertEquals(0, c.memory().bytes());
      assertEquals(0, c.memory().limitBytes());
      c.budget(NoiseImageRows.MAX_BYTES);
      sample(c, "a", 10, f, 4, 0, 0, 1, Long.MAX_VALUE, false);
      assertEquals(4, c.memory().rows());
    }
  }

  @Test
  void failedOrCancelledRowsNeverPublishAPartialImage() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field valid = (x, y, z) -> x + z;
      var original = sample(c, "a", 1, valid, 4, 0, 0, 1, BUDGET, false);
      assertThrows(
          ArithmeticException.class,
          () -> sample(c, "b", 2, (x, y, z) -> Double.NaN, 4, 0, 0, 1, BUDGET, false));
      assertEquals(0, c.memory().rows());
      assertEquals(-4, original.value(0, 0));
      var token = new CancellationToken();
      var calls = new AtomicInteger();
      assertThrows(
          CancellationException.class,
          () ->
              c.sample(
                  "b",
                  3,
                  (x, y, z) -> {
                    if (calls.incrementAndGet() == 2) token.cancel();
                    return 1;
                  },
                  4,
                  0,
                  0,
                  1,
                  BUDGET,
                  false,
                  token));
      assertEquals(0, c.memory().rows());
      var memory = c.memory();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              sample(c, "bad", 4, valid, 4, Double.MAX_VALUE, 0, Double.MAX_VALUE, BUDGET, false));
      assertEquals(memory, c.memory());
    }
  }

  @Test
  void oldInFlightSourceCannotOverwriteANewerSourceAndCallbacksDoNotHoldTheCacheLock()
      throws Exception {
    try (var c = new NoiseImageRows();
        var workers = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old =
          workers.submit(
              () ->
                  sample(
                      c,
                      "old",
                      1,
                      (x, y, z) -> {
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Release timeout");
                        } catch (InterruptedException e) {
                          throw new CancellationException();
                        }
                        return 1;
                      },
                      4,
                      0,
                      0,
                      1,
                      BUDGET,
                      false));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try {
        sample(c, "new", 2, (x, y, z) -> 2, 4, 0, 0, 1, BUDGET, false);
      } finally {
        release.countDown();
      }
      assertEquals(1, old.get(5, TimeUnit.SECONDS).value(0, 0));
      assertEquals("new", c.memory().source());
      var reused =
          sample(
              c,
              "new",
              3,
              (x, y, z) -> {
                throw new AssertionError("Unexpected sample");
              },
              4,
              0,
              0,
              1,
              BUDGET,
              false);
      assertEquals(4, reused.statistics().reusedRows());
      assertEquals(2, reused.value(0, 0));
      sample(c, "old", 1, (x, y, z) -> 1, 4, 0, 0, 1, BUDGET, false);
      assertEquals("new", c.memory().source());
    }
  }

  @Test
  void horizontalDiagonalAndSubsampledGridsReuseOnlyExactOverlaps() {
    try (var c = new NoiseImageRows()) {
      var calls = new AtomicInteger();
      NoiseAlgorithms.Field f =
          (x, y, z) -> {
            calls.incrementAndGet();
            return x * .5 + z * 3;
          };
      sample(c, "a", 1, f, 8, 0, 0, 1, BUDGET, false);
      var horizontal = sample(c, "a", 2, f, 8, 1, 0, 1, BUDGET, false);
      assertEquals(8, horizontal.statistics().sampledValues());
      assertEquals(56, horizontal.statistics().reusedValues());
      assertEquals(72, calls.get());
      var diagonal = sample(c, "a", 3, f, 8, 2, 1, 1, BUDGET, false);
      assertEquals(15, diagonal.statistics().sampledValues());
      assertEquals(49, diagonal.statistics().reusedValues());
      assertEquals(87, calls.get());
      var back = sample(c, "a", 4, f, 8, 0, 0, 1, BUDGET, false);
      assertEquals(0, back.statistics().sampledValues());
      for (int z = 0; z < 8; z++)
        for (int x = 0; x < 8; x++)
          assertEquals((2 + (x - 4.)) * .5 + (1 + (z - 4.)) * 3, diagonal.value(x, z));
    }
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x - z;
      sample(c, "a", 1, f, 8, 0, 0, 1, BUDGET, false);
      var zoom = sample(c, "a", 2, f, 8, 0, 0, 2, BUDGET, false);
      assertEquals(16, zoom.statistics().reusedValues());
      assertEquals(48, zoom.statistics().sampledValues());
      assertEquals(0, zoom.statistics().reusedRows());
      // Returning to a finer axis can assemble a complete row from retained fragments.
      assertEquals(0, sample(c, "a", 3, f, 8, 0, 0, 1, BUDGET, false).statistics().sampledValues());
    }
  }

  @Test
  void overlappingCoordinatesCanAssembleRowsAcrossDifferentAxesAndSizes() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x + z;
      sample(c, "a", 1, f, 2, -1, 0, 1, BUDGET, false); // X -2,-1, Z -1,0
      sample(c, "a", 2, f, 2, 1, 0, 1, BUDGET, false); // X 0,1
      var whole = sample(c, "a", 3, f, 4, 0, 0, 1, BUDGET, false);
      assertEquals(8, whole.statistics().sampledValues());
      assertEquals(8, whole.statistics().reusedValues());
      assertEquals(2, whole.statistics().reusedRows());
      for (int z = 0; z < 4; z++)
        for (int x = 0; x < 4; x++) assertEquals(x + z - 4., whole.value(x, z));
    }
  }

  @Test
  void nearlyEqualCoordinatesAreNeverSnappedAndSignedZeroSurvivesFallbackSearch() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x - z;
      sample(c, "a", 1, f, 4, 1, 1, 1, BUDGET, false);
      var shifted = sample(c, "a", 2, f, 4, Math.nextUp(1.), 1, 1, BUDGET, false);
      int expected = 0;
      for (int x = 0; x < 4; x++) {
        double query = Math.nextUp(1.) + (x - 2.);
        boolean found = false;
        for (int old = 0; old < 4; old++)
          found |= Double.doubleToLongBits(query) == Double.doubleToLongBits(1. + (old - 2.));
        if (!found) expected += 4;
      }
      assertEquals(expected, shifted.statistics().sampledValues());
      assertTrue(expected > 0);
    }
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f =
          (x, y, z) -> Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(-0.) ? -1 : 1;
      sample(c, "a", 1, f, 2, -0., 1, Double.MIN_VALUE, BUDGET, false);
      var plus = sample(c, "a", 2, f, 1, 0., 1, Double.MIN_VALUE, BUDGET, false);
      assertEquals(1, plus.value(0, 0));
      assertEquals(1, plus.statistics().sampledValues());
    }
  }

  @Test
  void cancellationAndFailureAfterOverlapNeverRetainAnAssembledPartialRow() {
    try (var c = new NoiseImageRows()) {
      NoiseAlgorithms.Field f = (x, y, z) -> x + z;
      sample(c, "a", 1, f, 4, 0, 0, 1, BUDGET, false);
      int before = c.memory().rows();
      var token = new CancellationToken();
      assertThrows(
          CancellationException.class,
          () ->
              c.sample(
                  "a",
                  2,
                  (x, y, z) -> {
                    token.cancel();
                    return 7;
                  },
                  4,
                  1,
                  0,
                  1,
                  BUDGET,
                  false,
                  token));
      assertEquals(before, c.memory().rows());
      assertThrows(
          ArithmeticException.class,
          () -> sample(c, "a", 3, (x, y, z) -> Double.NaN, 4, 1, 0, 1, BUDGET, false));
      assertEquals(before, c.memory().rows());
      var valid = sample(c, "a", 4, f, 4, 1, 0, 1, BUDGET, false);
      assertEquals(4, valid.statistics().sampledValues());
      assertEquals(12, valid.statistics().reusedValues());
      c.budget(0);
      var disabled = sample(c, "a", 5, f, 4, 2, 0, 1, BUDGET, false);
      assertEquals(16, disabled.statistics().sampledValues());
      assertEquals(0, disabled.statistics().retainedBytes());
    }
  }
}
