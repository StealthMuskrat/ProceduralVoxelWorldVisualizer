package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VolumeWindowsTest {
  private VolumeWindows.Window sample(
      VolumeWindows cache, long request, VolumeWindows.Bounds bounds, Field field) {
    return cache.sample(
        cache.begin("source", request, 64L << 20), bounds, () -> field, new CancellationToken());
  }

  @Test
  void wholeChunkBoundsUseSignedFloorAndClampOnlyAtDomainEdges() {
    assertEquals(new VolumeWindows.Bounds(32, 0, 0, 0), VolumeWindows.Bounds.around(31, 0, 16, 2));
    assertEquals(
        new VolumeWindows.Bounds(64, -64, -32, 0), VolumeWindows.Bounds.around(-.1, 0, 32, 33));
    assertEquals(Integer.MIN_VALUE, VolumeWindows.Bounds.around(Integer.MIN_VALUE, 0, 0, 256).x());
    assertEquals(
        (long) Integer.MAX_VALUE + 1 - 256,
        VolumeWindows.Bounds.around(Integer.MAX_VALUE, 0, 0, 256).x());
    assertThrows(
        IllegalArgumentException.class, () -> VolumeWindows.Bounds.around(Double.NaN, 0, 0, 32));
    assertThrows(
        IllegalArgumentException.class, () -> VolumeWindows.Bounds.around(2147483648., 0, 0, 32));
    assertThrows(IllegalArgumentException.class, () -> new VolumeWindows.Bounds(33, 0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new VolumeWindows.Bounds(32, -1, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> VolumeWindows.workingBytes(257));
  }

  @Test
  void cameraTravelReusesCanonicalTilesAndOneCompiledSourceAcrossSignedAxes() {
    var calls = new AtomicInteger();
    var compiles = new AtomicInteger();
    try (var cache = new VolumeWindows()) {
      Field field =
          (x, y, z) -> {
            calls.incrementAndGet();
            return x + 10 * y + 100 * z;
          };
      var first =
          cache.sample(
              cache.begin("a", 1, 64L << 20),
              new VolumeWindows.Bounds(64, -32, -32, -32),
              () -> {
                compiles.incrementAndGet();
                return field;
              },
              new CancellationToken());
      assertEquals(8, first.statistics().sampledChunks());
      assertEquals(64 * 64 * 64, calls.get());
      var moved =
          cache.sample(
              cache.begin("a", 2, 64L << 20),
              new VolumeWindows.Bounds(64, 0, -32, -32),
              () -> {
                throw new AssertionError("source recompiled on travel");
              },
              new CancellationToken());
      assertEquals(4, moved.statistics().sampledChunks());
      assertEquals(4, moved.statistics().reusedChunks());
      assertEquals(12 * 32 * 32 * 32, calls.get());
      var returned =
          cache.sample(
              cache.begin("a", 3, 64L << 20), first.bounds(), () -> field, new CancellationToken());
      assertEquals(0, returned.statistics().sampledChunks());
      assertEquals(8, returned.statistics().reusedChunks());
      assertEquals(first.statistics().generation(), returned.statistics().generation());
      assertArrayEquals(first.volume().copySamples(), returned.volume().copySamples());
      for (int z = 0; z < 64; z++)
        for (int y = 0; y < 64; y++)
          for (int x = 0; x < 64; x++)
            assertEquals(
                (x + .5) + 10 * (y - 31.5) + 100 * (z - 31.5), moved.volume().value(x, y, z));
      assertEquals(1, compiles.get());
      assertEquals(1, cache.memory().compilations());
      assertEquals(0, cache.memory().workingBytes());
    }
  }

  @Test
  void assembledTextureMatchesDirectEncodingAcrossTinyAndExtremeSourceChanges() {
    try (var cache = new VolumeWindows()) {
      long request = 0;
      for (double magnitude : new double[] {Double.MIN_VALUE, 1e-300, Double.MAX_VALUE}) {
        Field field = (x, y, z) -> x < 0 ? -magnitude : magnitude;
        var bounds = new VolumeWindows.Bounds(64, -32, -32, -32);
        var window =
            cache.sample(
                cache.begin("range" + request, ++request, 0),
                bounds,
                () -> field,
                new CancellationToken());
        var direct = VolumeData.sample(64, -32, -32, -32, field);
        assertEquals(direct.encoding(), window.volume().encoding());
        assertArrayEquals(direct.copySamples(), window.volume().copySamples());
        assertEquals(8, window.statistics().sampledChunks());
        assertEquals(0, cache.memory().bytes());
      }
    }
  }

  @Test
  void cacheEvictionAndShrinkingStayWithinInclusiveByteLimit() {
    try (var cache = new VolumeWindows()) {
      var bounds = new VolumeWindows.Bounds(64, 0, 0, 0);
      var lease = cache.begin("a", 1, 2 * VolumeWindows.TILE_BYTES);
      var first = cache.sample(lease, bounds, () -> (x, y, z) -> x, new CancellationToken());
      assertEquals(8, first.statistics().sampledChunks());
      assertEquals(2 * VolumeWindows.TILE_BYTES, cache.memory().bytes());
      assertEquals(2, cache.memory().chunks());
      cache.budget(VolumeWindows.TILE_BYTES - 1);
      assertEquals(0, cache.memory().bytes());
      var again =
          cache.sample(
              cache.begin("a", 2, 0),
              bounds,
              () -> {
                throw new AssertionError();
              },
              new CancellationToken());
      assertArrayEquals(first.volume().copySamples(), again.volume().copySamples());
      assertEquals(8, again.statistics().sampledChunks());
      assertEquals(
          12L * 64 * 64 * 64 + VolumeWindows.TILE_BYTES + 65536, VolumeWindows.workingBytes(64));
    }
  }

  @Test
  void staleOrClosedLeasesCannotCompileOrPublishCachedTiles() {
    var cache = new VolumeWindows();
    var old = cache.begin("a", 1, 64L << 20);
    var current = cache.begin("b", 2, 64L << 20);
    var bounds = new VolumeWindows.Bounds(32, 0, 0, 0);
    assertThrows(
        CancellationException.class,
        () ->
            cache.sample(
                old,
                bounds,
                () -> {
                  throw new AssertionError();
                },
                new CancellationToken()));
    assertThrows(CancellationException.class, () -> cache.begin("a", 1, 0));
    var accepted = cache.sample(current, bounds, () -> (x, y, z) -> 7, new CancellationToken());
    var prior = accepted.volume().copySamples();
    cache.clear();
    assertThrows(
        CancellationException.class,
        () ->
            cache.sample(
                current,
                bounds,
                () -> {
                  throw new AssertionError();
                },
                new CancellationToken()));
    cache.close();
    cache.close();
    assertEquals(0, cache.memory().bytes());
    assertArrayEquals(prior, accepted.volume().copySamples());
    assertThrows(CancellationException.class, () -> cache.begin("b", 3, 0));
  }

  @Test
  void cancellationAndFailuresNeverCacheIncompleteChunks() {
    try (var cache = new VolumeWindows()) {
      var calls = new AtomicInteger();
      var token = new CancellationToken();
      var lease = cache.begin("a", 1, 64L << 20);
      assertThrows(
          CancellationException.class,
          () ->
              cache.sample(
                  lease,
                  new VolumeWindows.Bounds(32, 0, 0, 0),
                  () ->
                      (x, y, z) -> {
                        calls.incrementAndGet();
                        token.cancel();
                        return 1;
                      },
                  token));
      assertTrue(calls.get() <= 32);
      assertEquals(0, cache.memory().chunks());
      var bad = cache.begin("nonfinite", 2, 64L << 20);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              cache.sample(
                  bad,
                  new VolumeWindows.Bounds(32, 0, 0, 0),
                  () -> (x, y, z) -> Double.NaN,
                  new CancellationToken()));
      assertEquals(0, cache.memory().bytes());
      var cause = new IllegalStateException("compiler failed");
      var broken = cache.begin("compiler", 3, 64L << 20);
      assertSame(
          cause,
          assertThrows(
              IllegalStateException.class,
              () ->
                  cache.sample(
                      broken,
                      new VolumeWindows.Bounds(32, 0, 0, 0),
                      () -> {
                        throw cause;
                      },
                      new CancellationToken())));
      var good = sample(cache, 4, new VolumeWindows.Bounds(32, 0, 0, 0), (x, y, z) -> 9);
      assertEquals(9, good.volume().value(0, 0, 0));
      assertEquals(0, cache.memory().workingBytes());
    }
  }

  @Test
  void obsoleteRunningWindowSerializesSuccessorsBeforeAllocationAndCloseCancelsPublication()
      throws Exception {
    var cache = new VolumeWindows();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var count = new AtomicInteger();
      var first = cache.begin("a", 1, 64L << 20);
      var task =
          executor.submit(
              () ->
                  cache.sample(
                      first,
                      new VolumeWindows.Bounds(32, 0, 0, 0),
                      () ->
                          (x, y, z) -> {
                            if (count.incrementAndGet() == 1) {
                              entered.countDown();
                              try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                  throw new AssertionError("worker release timeout");
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            }
                            return 1;
                          },
                      new CancellationToken()));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var next = cache.begin("a", 2, 64L << 20);
      var successor =
          executor.submit(
              () ->
                  cache.sample(
                      next,
                      new VolumeWindows.Bounds(64, 0, 0, 0),
                      () -> {
                        throw new AssertionError();
                      },
                      new CancellationToken()));
      assertThrows(TimeoutException.class, () -> successor.get(50, TimeUnit.MILLISECONDS));
      assertEquals(VolumeWindows.workingBytes(32), cache.memory().workingBytes());
      release.countDown();
      assertInstanceOf(
          CancellationException.class,
          assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS)).getCause());
      assertEquals(8, successor.get(5, TimeUnit.SECONDS).statistics().sampledChunks());
      assertEquals(1, cache.memory().compilations());
      cache.close();
      assertEquals(0, cache.memory().bytes());
    } finally {
      cache.close();
    }
  }

  @Test
  void cancellationParentsCanInspectCacheFromAnotherThreadWithoutOwningItsLock() throws Exception {
    try (var cache = new VolumeWindows();
        var executor = Executors.newSingleThreadExecutor()) {
      var armed = new java.util.concurrent.atomic.AtomicBoolean();
      var token =
          new CancellationToken(
              () -> {
                if (armed.getAndSet(false)) {
                  try {
                    executor.submit(cache::memory).get(1, TimeUnit.SECONDS);
                  } catch (Exception failure) {
                    throw new AssertionError("Cancellation callback held cache lock", failure);
                  }
                }
                return false;
              });
      var result =
          cache.sample(
              cache.begin("callback", 1, 0),
              new VolumeWindows.Bounds(32, 0, 0, 0),
              () -> {
                armed.set(true);
                return (x, y, z) -> 1;
              },
              token);
      assertEquals(1, result.volume().value(0, 0, 0));
    }
  }

  @Test
  void signedBoundarySamplingDoesNotWrapOrLoseHalfCellCoordinates() {
    try (var cache = new VolumeWindows()) {
      var high = new VolumeWindows.Bounds(32, Integer.MAX_VALUE - 31, 0, 0);
      var result = sample(cache, 1, high, (x, y, z) -> x - 2147483616.);
      assertEquals(.5, result.volume().value(0, 0, 0));
      assertEquals(31.5, result.volume().value(31, 0, 0));
      var low =
          sample(
              cache,
              2,
              new VolumeWindows.Bounds(32, Integer.MIN_VALUE, 0, 0),
              (x, y, z) -> x - 2147483616.);
      assertEquals(1, cache.memory().compilations());
      assertEquals(
          (float) ((double) Integer.MIN_VALUE + .5 - 2147483616.), low.volume().copySamples()[0]);
    }
  }
}
