package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VolumePressureTest {
  private static final VolumeStreamingLimits.Limits LARGE =
      new VolumeStreamingLimits.Limits(512L << 20, 512L << 20, 128L << 20, 256L * 256 * 256, 256);

  @Test
  void pressureReducesOnlyVisibilityThenCacheWhilePreservingConfiguredExtent() {
    assertEquals(64, VolumeStreamingLimits.plan(33, 0, LARGE, 0).side());
    for (int level = 0; level <= 5; level++) {
      var plan = VolumeStreamingLimits.plan(96, level, LARGE, 0);
      assertTrue(plan.allowed());
      assertEquals(level < 2 ? 96 : 64, plan.side());
      assertEquals(level < 3 ? 64L << 20 : 0, plan.cacheBytes());
      assertEquals(32, VolumeStreamingLimits.plan(2, level, LARGE, 0).side());
      assertEquals(32, VolumeStreamingLimits.plan(32, level, LARGE, 0).side());
    }
    assertEquals(224, VolumeStreamingLimits.plan(256, 2, LARGE, 0).side());
    assertEquals(96, VolumeStreamingLimits.plan(96, 0, LARGE, 0).side());
  }

  @Test
  void cpuAdmissionIncludesSamplingEncodingTileAndStagingBeforeCache() {
    long peak = 12L * 64 * 64 * 64 + (8L * 32 * 32 * 32 + 128) + 65536;
    var exact =
        new VolumeStreamingLimits.Limits(peak, 4L << 20, Long.MAX_VALUE, Long.MAX_VALUE, 256);
    var plan = VolumeStreamingLimits.plan(64, 0, exact, 0);
    assertTrue(plan.allowed());
    assertEquals(peak, plan.workingBytes());
    assertEquals(0, plan.cacheBytes());
    assertFalse(
        VolumeStreamingLimits.plan(
                64,
                0,
                new VolumeStreamingLimits.Limits(peak - 1, 4L << 20, 0, Long.MAX_VALUE, 256),
                0)
            .allowed());
    var remaining =
        VolumeStreamingLimits.plan(
            64,
            0,
            new VolumeStreamingLimits.Limits(peak + 12345, 4L << 20, 20000, Long.MAX_VALUE, 256),
            0);
    assertEquals(12345, remaining.cacheBytes());
  }

  @Test
  void gpuAdmissionKeepsAcceptedTextureAndRejectsOneByteShort() {
    long texture = 4L * 64 * 64 * 64;
    assertTrue(
        VolumeStreamingLimits.plan(
                64,
                0,
                new VolumeStreamingLimits.Limits(64L << 20, 2 * texture, 0, Long.MAX_VALUE, 256),
                texture)
            .allowed());
    assertFalse(
        VolumeStreamingLimits.plan(
                64,
                0,
                new VolumeStreamingLimits.Limits(
                    64L << 20, 2 * texture - 1, 0, Long.MAX_VALUE, 256),
                texture)
            .allowed());
    assertFalse(
        VolumeStreamingLimits.plan(
                32,
                3,
                new VolumeStreamingLimits.Limits(64L << 20, texture - 1, 0, Long.MAX_VALUE, 256),
                texture)
            .allowed());
    assertFalse(VolumeStreamingLimits.plan(32, 0, LARGE, Long.MAX_VALUE).allowed());
  }

  @Test
  void alignedExtentsRespectCellDimensionAndZeroBudgets() {
    var cells =
        new VolumeStreamingLimits.Limits(Long.MAX_VALUE, Long.MAX_VALUE, 0, 64L * 64 * 64, 64);
    assertTrue(VolumeStreamingLimits.plan(33, 0, cells, 0).allowed());
    assertFalse(VolumeStreamingLimits.plan(65, 0, cells, 0).allowed());
    assertTrue(VolumeStreamingLimits.plan(65, 2, cells, 0).allowed());
    assertFalse(
        VolumeStreamingLimits.plan(
                64,
                0,
                new VolumeStreamingLimits.Limits(
                    Long.MAX_VALUE, Long.MAX_VALUE, 0, 64L * 64 * 64 - 1, 64),
                0)
            .allowed());
    assertFalse(
        VolumeStreamingLimits.plan(
                64,
                0,
                new VolumeStreamingLimits.Limits(
                    Long.MAX_VALUE, Long.MAX_VALUE, 0, Long.MAX_VALUE, 63),
                0)
            .allowed());
    assertFalse(
        VolumeStreamingLimits.plan(2, 0, new VolumeStreamingLimits.Limits(0, 0, 0, 0, 0), 0)
            .allowed());
  }

  @Test
  void serializedWorkerAndQueuedReservationDoNotDoubleCountUploadOrCache() {
    assertEquals(107, VolumeStreamingLimits.cpuBytes(7, 100, 50, 25));
    assertEquals(207, VolumeStreamingLimits.cpuBytes(7, 100, 200, 25));
    assertEquals(307, VolumeStreamingLimits.cpuBytes(7, 100, 200, 300));
    assertEquals(7, VolumeStreamingLimits.cpuBytes(7, 0, 0, 0));
    assertThrows(
        ArithmeticException.class, () -> VolumeStreamingLimits.cpuBytes(Long.MAX_VALUE, 1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> VolumeStreamingLimits.cpuBytes(1, -1, 0, 0));
  }

  @Test
  void pauseDeferralAndGradualRestorationDoNotChangeConfiguredQuality() {
    var pressure = new RenderPressure();
    for (int i = 1; i <= 5; i++) {
      pressure.observe(i * 1_000_000_000L, .99, .99, 10, true);
      assertEquals(i, pressure.level());
      assertEquals(i < 2 ? 96 : 64, VolumeStreamingLimits.plan(96, i, LARGE, 0).side());
      assertEquals(i < 4, pressure.acceptExpensive());
    }
    pressure.observe(20_000_000_000L, 0, 0, 10, false);
    assertTrue(pressure.paused());
    pressure.resume(21_000_000_000L);
    assertEquals(3, pressure.level());
    for (int level = 2; level >= 0; level--) {
      long time = (60L - level * 10L) * 1_000_000_000L;
      pressure.observe(time, 0, 0, 10, false);
      pressure.observe(time + 6_000_000_000L, 0, 0, 10, false);
      assertEquals(level, pressure.level());
    }
    assertEquals(96, VolumeStreamingLimits.plan(96, pressure.level(), LARGE, 0).side());
  }

  @Test
  void pressureWindowsKeepCanonicalSamplesAndSourceWhileCacheIsReleased() {
    var compilations = new AtomicInteger();
    try (var cache = new VolumeWindows()) {
      var large =
          cache.sample(
              cache.begin("affine", 1, 64L << 20),
              new VolumeWindows.Bounds(64, -32, -32, -32),
              () -> {
                compilations.incrementAndGet();
                return (x, y, z) -> x + 10 * y + 100 * z;
              },
              new CancellationToken());
      assertEquals(8, large.statistics().sampledChunks());
      var plan = VolumeStreamingLimits.plan(64, 3, LARGE, 4L * 64 * 64 * 64);
      cache.budget(plan.cacheBytes());
      assertEquals(0, cache.memory().bytes());
      var small =
          cache.sample(
              cache.begin("affine", 2, plan.cacheBytes()),
              new VolumeWindows.Bounds(plan.side(), -32, -32, -32),
              () -> {
                throw new AssertionError("Pressure recompiled source");
              },
              new CancellationToken());
      assertEquals(0, cache.memory().bytes());
      assertEquals(1, small.statistics().sampledChunks());
      assertEquals(large.statistics().generation(), small.statistics().generation());
      assertEquals(1, compilations.get());
      for (int z = 0; z < 32; z++)
        for (int y = 0; y < 32; y++)
          for (int x = 0; x < 32; x++) {
            double expected = x - 31.5 + 10 * (y - 31.5) + 100 * (z - 31.5);
            assertEquals(expected, small.volume().value(x, y, z));
            assertEquals(large.volume().value(x, y, z), small.volume().value(x, y, z));
          }
    }
  }

  @Test
  void invalidRequestsFailBeforeAnyWorkCanBeAdmitted() {
    for (int side : new int[] {-1, 0, 1, 257, Integer.MAX_VALUE})
      assertThrows(
          IllegalArgumentException.class, () -> VolumeStreamingLimits.plan(side, 0, LARGE, 0));
    for (int level : new int[] {-1, 6})
      assertThrows(
          IllegalArgumentException.class, () -> VolumeStreamingLimits.plan(32, level, LARGE, 0));
    assertThrows(
        IllegalArgumentException.class, () -> VolumeStreamingLimits.plan(32, 0, LARGE, -1));
    assertThrows(NullPointerException.class, () -> VolumeStreamingLimits.plan(32, 0, null, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new VolumeStreamingLimits.Limits(-1, 0, 0, 0, 0));
  }
}
