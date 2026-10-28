package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred display-grid contracts, using direct arithmetic rather than a second sampling path. */
class VolumeGridTest {
  @Test
  void displayCentersKeepWorldExtentSeparateFromTexelCount() {
    for (int step : new int[] {1, 2, 4, 8, 16}) {
      var data =
          VolumeData.sampleGrid(
              4, step, -32, 16, -64, (x, y, z) -> x + 10 * y + 100 * z, new CancellationToken());
      assertEquals(4, data.size());
      assertEquals(step, data.sampleStep());
      assertEquals(4 * step, data.extent());
      for (int z = 0; z < 4; z++)
        for (int y = 0; y < 4; y++)
          for (int x = 0; x < 4; x++)
            assertEquals(
                -32 + (x + .5) * step + 10 * (16 + (y + .5) * step) + 100 * (-64 + (z + .5) * step),
                data.value(x, y, z));
    }
  }

  @Test
  void interpolationConsumesLocalWorldCoordinatesAndClampsAtCoarseCenters() {
    var data =
        VolumeData.sampleGrid(
            4, 2, 0, 0, 0, (x, y, z) -> x + 2 * y + 4 * z, new CancellationToken());
    assertEquals(7, data.interpolate(0, 0, 0));
    assertEquals(49, data.interpolate(8, 8, 8));
    for (double x : new double[] {1, 1.5, 3, 5.5, 7})
      assertEquals(x + 2 * 3 + 4 * 5, data.interpolate(x, 3, 5), 1e-12);
  }

  @Test
  void homogeneousOpticalDistanceIsInWorldUnitsForEveryGrid() {
    var settings = new VolumeTransfer.Settings(0, 1, .25, 0, 128);
    for (int step : new int[] {1, 2, 4}) {
      var data =
          VolumeData.sampleGrid(8 / step, step, 0, 0, 0, (x, y, z) -> 1, new CancellationToken());
      var transfer = VolumeTransfer.of(data.encoding(), settings);
      double light =
          VolumeLight.transmittance(data, transfer, 4, 4, 4, new VolumeLight.Settings(true, 16));
      assertEquals(Math.exp(-.25 * 4 / VolumeLight.LY), light, 1e-14);
      assertEquals(
          1,
          VolumeLight.transmittance(data, transfer, 8, 8, 8, new VolumeLight.Settings(true, 32)));
    }
  }

  @Test
  void unitAndCoarseCachesNeverAliasAndRefinementReusesItsOwnTiles() {
    try (var cache = new VolumeWindows()) {
      var bounds = new VolumeWindows.Bounds(64, -32, -32, -32);
      Field field = (x, y, z) -> x + 10 * y + 100 * z;
      var fine =
          cache.sample(
              cache.begin("same", 1, 64L << 20), bounds, () -> field, new CancellationToken());
      var coarse =
          cache.sample(
              cache.begin("same", 2, 64L << 20),
              bounds,
              2,
              () -> {
                throw new AssertionError();
              },
              new CancellationToken());
      assertEquals(8, coarse.statistics().sampledChunks());
      assertEquals(32768, coarse.statistics().sampledValues());
      assertEquals(
          8 * (VolumeWindows.TILE_BYTES + VolumeWindows.tileBytes(2)), cache.memory().bytes());
      var again =
          cache.sample(
              cache.begin("same", 3, 64L << 20),
              bounds,
              () -> {
                throw new AssertionError();
              },
              new CancellationToken());
      assertEquals(8, again.statistics().reusedChunks());
      assertArrayEquals(fine.volume().copySamples(), again.volume().copySamples());
      assertEquals(fine.statistics().generation(), coarse.statistics().generation());
      assertEquals(1, cache.memory().compilations());
      var moved =
          cache.sample(
              cache.begin("same", 4, 64L << 20),
              new VolumeWindows.Bounds(64, 0, -32, -32),
              2,
              () -> field,
              new CancellationToken());
      assertEquals(4, moved.statistics().sampledChunks());
      assertEquals(4, moved.statistics().reusedChunks());
      assertEquals(1 + 10 * (-31) + 100 * (-31), moved.volume().value(0, 0, 0));
    }
  }

  @Test
  void mixedSpacingLruUsesEachTilesActualBytes() {
    try (var cache = new VolumeWindows()) {
      var bounds = new VolumeWindows.Bounds(32, 0, 0, 0);
      long small = 8L * 16 * 16 * 16 + 128;
      cache.sample(
          cache.begin("same", 1, VolumeWindows.TILE_BYTES + small),
          bounds,
          () -> (x, y, z) -> 1,
          new CancellationToken());
      cache.sample(
          cache.begin("same", 2, VolumeWindows.TILE_BYTES + small),
          bounds,
          2,
          () -> {
            throw new AssertionError();
          },
          new CancellationToken());
      assertEquals(VolumeWindows.TILE_BYTES + small, cache.memory().bytes());
      cache.budget(small);
      assertEquals(small, cache.memory().bytes());
      assertEquals(1, cache.memory().chunks());
      var coarse =
          cache.sample(
              cache.begin("same", 3, small),
              bounds,
              2,
              () -> {
                throw new AssertionError();
              },
              new CancellationToken());
      assertEquals(1, coarse.statistics().reusedChunks());
      cache.budget(small - 1);
      assertEquals(0, cache.memory().bytes());
    }
  }

  @Test
  void coarseCancellationNeverCachesAnIncompleteTile() {
    try (var cache = new VolumeWindows()) {
      var token = new CancellationToken();
      var calls = new AtomicInteger();
      assertThrows(
          CancellationException.class,
          () ->
              cache.sample(
                  cache.begin("a", 1, 64L << 20),
                  new VolumeWindows.Bounds(32, 0, 0, 0),
                  2,
                  () ->
                      (x, y, z) -> {
                        calls.incrementAndGet();
                        token.cancel();
                        return 1;
                      },
                  token));
      assertTrue(calls.get() <= 16);
      assertEquals(0, cache.memory().bytes());
      assertEquals(0, cache.memory().workingBytes());
    }
  }

  @Test
  void signedDomainEdgesKeepCoarseCentersWithoutIntegerWrap() {
    try (var cache = new VolumeWindows()) {
      for (int origin : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE - 31}) {
        var result =
            cache.sample(
                cache.begin("edge" + origin, (long) origin - Integer.MIN_VALUE, 0),
                new VolumeWindows.Bounds(32, origin, 0, 0),
                2,
                () -> (x, y, z) -> x - origin,
                new CancellationToken());
        assertEquals(1, result.volume().value(0, 0, 0));
        assertEquals(31, result.volume().value(15, 0, 0));
      }
    }
  }

  @Test
  void firstFallbackAdmitsFewerSamplesBeforeChangingWorldExtent() {
    var limits = new VolumeStreamingLimits.Limits(1L << 20, 2L << 20, 64L << 20, 32L * 32 * 32, 32);
    assertFalse(VolumeStreamingLimits.plan(64, 0, limits, 1L << 20).allowed());
    var plan = VolumeStreamingLimits.plan(64, 1, limits, 1L << 20);
    assertTrue(plan.allowed());
    assertEquals(64, plan.side());
    assertEquals(32, plan.textureSide());
    assertEquals(2, plan.sampleStep());
    assertEquals(12L * 32 * 32 * 32 + 8L * 16 * 16 * 16 + 128 + 65536, plan.workingBytes());
    assertEquals(4L * 32 * 32 * 32, plan.textureBytes());
    assertEquals(32, VolumeStreamingLimits.plan(64, 2, limits, 1L << 20).side());
  }

  @Test
  void extremeFieldsRemainFiniteAndUseOneEncodingAtEachDetail() {
    for (double magnitude : new double[] {1e-300, Double.MAX_VALUE}) {
      var data =
          VolumeData.sampleGrid(
              4, 2, -4, 0, 0, (x, y, z) -> x < 0 ? -magnitude : magnitude, new CancellationToken());
      for (float value : data.copySamples()) assertTrue(Float.isFinite(value));
      assertEquals(-magnitude, data.value(0, 0, 0));
      assertEquals(magnitude, data.value(3, 0, 0));
    }
  }

  @Test
  void invalidSpacingRejectsBeforeSamplingAndLegacyMetadataStaysUnitGrid() {
    var calls = new AtomicInteger();
    for (int step : new int[] {0, -1, 3, 32, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class,
          () ->
              VolumeData.sampleGrid(
                  4, step, 0, 0, 0, (x, y, z) -> calls.incrementAndGet(), new CancellationToken()));
    assertEquals(0, calls.get());
    var unit = VolumeData.sample(4, 0, 0, 0, (x, y, z) -> 1);
    assertEquals(1, unit.sampleStep());
    assertEquals(4, unit.extent());
    double largeOrigin = 0x1.0p53;
    var historical = VolumeData.sample(2, largeOrigin, 0, 0, (x, y, z) -> x - largeOrigin);
    assertEquals(
        0, historical.value(1, 0, 0)); // (origin+1)+.5 retains the original rounding order.
    assertEquals(1, new VolumeWindows.Statistics(1, 0, 32768, 1).sampleStep());
    assertEquals(1, new VolumeStreamingLimits.Plan(32, 0, 0, null).sampleStep());
    assertEquals(4, new VolumeTextureUpload.Resident(1, 4, unit.encoding()).extent());
  }
}
