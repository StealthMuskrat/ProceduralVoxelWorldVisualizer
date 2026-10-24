package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

public class SharedRequestTest {
  private static CompiledProject project() {
    return new CompiledProject(new ProjectData());
  }

  private static WorldOptions options() {
    return new WorldOptions(null, 32, WorldOptions.CachePolicy.NO_CACHE, 0);
  }

  @Test
  void concurrentSubscribersShareOneJobAndCancelIndependently() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world = engine.openWorld(project(), options())) {
      var hold = new CountDownLatch(1);
      var started = new CountDownLatch(1);
      engine.workers.execute(
          () -> {
            started.countDown();
            try {
              hold.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      var first = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      var second = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      var third = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      first.cancel(false);
      hold.countDown();
      assertSame(second.get(3, TimeUnit.SECONDS), third.get(3, TimeUnit.SECONDS));
      assertEquals(1, engine.metrics().completed());
      assertEquals(2, world.sharedRequests());
      assertEquals(0, world.inFlightChunks());
    }
  }

  @Test
  void synchronousWorkerCanStealItsQueuedSharedJob() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var world = engine.openWorld(project(), options())) {
      var done = new CompletableFuture<Boolean>();
      engine.workers.execute(
          () -> {
            try {
              var async = world.generateChunkAsync(new ChunkPos(0, 0, 0));
              var sync = world.generateChunk(new ChunkPos(0, 0, 0));
              done.complete(sync == async.join());
            } catch (Throwable failure) {
              done.completeExceptionally(failure);
            }
          });
      assertTrue(done.get(3, TimeUnit.SECONDS));
      assertEquals(1, engine.metrics().completed());
    }
  }

  @Test
  void sharingNeverCrossesContextsAndInvalidCoordinatesCannotWrap() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 16L << 20);
        var a = engine.openWorld(project(), options());
        var b = engine.openWorld(project(), options())) {
      var first = a.generateChunk(new ChunkPos(0, 0, 0));
      var second = b.generateChunk(new ChunkPos(0, 0, 0));
      assertNotSame(first, second);
      assertThrows(
          IllegalArgumentException.class,
          () -> a.generateChunk(new ChunkPos(Integer.MAX_VALUE, 0, 0)));
      var token = new CancellationToken();
      token.cancel();
      assertThrows(
          CancellationException.class,
          () -> a.generateChunkAsync(new ChunkPos(0, 0, 0), token).join());
    }
  }

  @Test
  void collectedResultsShareAdmissionAndReleaseWithoutInvalidatingSnapshots() {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var world = engine.openWorld(project(), options())) {
      var request =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(255, 31, 31)));
      try (var a = world.collectRegion(request);
          var b = world.collectRegion(request);
          var c = world.collectRegion(request)) {
        long held = engine.metrics().collectionBytes();
        assertTrue(held > 0);
        assertThrows(RejectedExecutionException.class, () -> world.collectRegion(request));
        var snapshot = a.chunks().getFirst();
        a.close();
        a.close();
        assertTrue(engine.metrics().collectionBytes() < held);
        assertEquals(0, snapshot.getVoxelTypeId(0, 0, 0));
        try (var next = world.collectRegion(request)) {
          assertEquals(8, next.chunks().size());
        }
      }
      assertEquals(0, engine.metrics().collectionBytes());
    }
  }

  @Test
  void oversizedCountsFailExplicitlyWithoutLeakingStreamSlots() {
    var huge =
        new RegionBounds(
            new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
            new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertTrue(
        assertThrows(IllegalArgumentException.class, () -> huge.chunkCount(2))
            .getMessage()
            .contains("REGION_TOO_LARGE"));
  }
}
