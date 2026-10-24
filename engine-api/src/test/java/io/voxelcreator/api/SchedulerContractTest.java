package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class SchedulerContractTest {
  private static final WorldOptions OPTIONS =
      new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0);

  private static WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(new CompiledProject(new ProjectData()), OPTIONS);
  }

  private static CountDownLatch hold(NoiseGeneratorEngine engine) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    engine.workers.execute(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    return release;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline)
      TimeUnit.MILLISECONDS.sleep(2);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  void admittedWorldsInterleaveRatherThanDrainOneWorldFirst() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var a = world(engine);
        var b = world(engine)) {
      var release = hold(engine);
      var order = new CopyOnWriteArrayList<String>();
      var pending = new ArrayList<CompletableFuture<VoxelChunk>>();
      for (int i = 0; i < 3; i++) {
        int n = i;
        var f = a.generateChunkAsync(new ChunkPos(i, 0, 0));
        f.thenRun(() -> order.add("a" + n));
        pending.add(f);
      }
      for (int i = 0; i < 2; i++) {
        int n = i;
        var f = b.generateChunkAsync(new ChunkPos(i, 0, 0));
        f.thenRun(() -> order.add("b" + n));
        pending.add(f);
      }
      assertEquals(2, engine.schedulerStats().queuedScopes());
      release.countDown();
      CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
      await(() -> order.size() == 5);
      assertEquals(List.of("a0", "b0", "a1", "b1", "a2"), order);
      assertEquals(5, engine.metrics().completed());
    }
  }

  @Test
  void interactiveSubscriberPromotesSharedBackgroundWorkWithoutDuplicatingIt() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var w = world(engine)) {
      var release = hold(engine);
      var order = new CopyOnWriteArrayList<Integer>();
      var normal = w.generateChunkAsync(new ChunkPos(0, 0, 0));
      normal.thenRun(() -> order.add(0));
      var background = w.generateChunkAsync(new ChunkPos(1, 0, 0), GenerationPriority.BACKGROUND);
      background.thenRun(() -> order.add(1));
      var interactive =
          w.generateChunkAsync(
              new ChunkPos(1, 0, 0), new CancellationToken(), GenerationPriority.INTERACTIVE);
      assertEquals(2, engine.schedulerStats().queued());
      assertEquals(1, engine.schedulerStats().promotions());
      release.countDown();
      assertSame(background.get(5, TimeUnit.SECONDS), interactive.get(5, TimeUnit.SECONDS));
      normal.get(5, TimeUnit.SECONDS);
      await(() -> order.size() == 2);
      assertEquals(List.of(1, 0), order);
      assertEquals(2, engine.metrics().completed());
    }
  }

  @Test
  void explicitCancellationImmediatelyReleasesQueueCapacityAndKeepsOtherSubscribers()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 1, 8L << 20);
        var w = world(engine)) {
      var release = hold(engine);
      var token = new CancellationToken();
      var a = w.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      var b = w.generateChunkAsync(new ChunkPos(0, 0, 0));
      assertTrue(token.cancel());
      assertTrue(a.isCancelled());
      assertEquals(1, engine.schedulerStats().queued());
      assertFalse(b.isDone());
      b.cancel(false);
      assertEquals(0, engine.schedulerStats().queued());
      assertEquals(0, w.inFlightChunks());
      var replacement = w.generateChunkAsync(new ChunkPos(1, 0, 0));
      release.countDown();
      assertTrue(replacement.get(5, TimeUnit.SECONDS).isEmpty());
      assertEquals(1, engine.metrics().completed());
    }
  }

  @Test
  void fullQueueRejectsWithoutLeakingAndClosureCancelsOnlyItsWorld() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 1, 8L << 20);
        var a = world(engine);
        var b = world(engine)) {
      var release = hold(engine);
      var queued = a.generateChunkAsync(new ChunkPos(0, 0, 0));
      var rejected = b.generateChunkAsync(new ChunkPos(0, 0, 0), GenerationPriority.INTERACTIVE);
      var error = assertThrows(CompletionException.class, rejected::join);
      assertInstanceOf(RejectedExecutionException.class, error.getCause());
      assertEquals(0, b.inFlightChunks());
      a.close();
      assertTrue(queued.isCancelled());
      assertEquals(0, engine.schedulerStats().queued());
      var accepted = b.generateChunkAsync(new ChunkPos(0, 0, 0));
      release.countDown();
      assertTrue(accepted.get(5, TimeUnit.SECONDS).isEmpty());
    }
  }

  @Test
  void cancelledRunningFlightCannotCaptureALaterRequestAndParentRunsOutsideOwnershipLock()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var w = world(engine);
        var canceller = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var token =
          new CancellationToken(
              () -> {
                if (!Thread.currentThread().getName().startsWith("voxel-generation-")) return false;
                entered.countDown();
                try {
                  release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
                return true;
              });
      try {
        var old = w.generateChunkAsync(new ChunkPos(0, 0, 0), token);
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertTrue(canceller.submit(token::cancel).get(2, TimeUnit.SECONDS));
        var replacement = w.generateChunkAsync(new ChunkPos(0, 0, 0));
        assertTrue(old.isCancelled());
        assertFalse(replacement.isDone());
        release.countDown();
        assertTrue(replacement.get(5, TimeUnit.SECONDS).isEmpty());
        assertEquals(1, engine.metrics().completed());
        assertEquals(0, w.inFlightChunks());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void asyncCollectionCancellationReleasesItsLeaseWhileWorkerIsStillBusy() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var w = world(engine)) {
      var release = hold(engine);
      var request =
          new RegionRequest(
              new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(7, 1, 1)),
              RegionRequest.FailurePolicy.CONTINUE,
              RegionRequest.DeliveryOrder.COMPLETION,
              4,
              GenerationPriority.BACKGROUND);
      var result = w.collectRegionAsync(request);
      await(() -> engine.schedulerStats().queued() > 0);
      result.cancel(false);
      await(
          () ->
              engine.schedulerStats().queued() == 0
                  && engine.metrics().collectionBytes() == 0
                  && w.inFlightChunks() == 0);
      assertTrue(result.isCancelled());
      release.countDown();
    }
  }

  @Test
  void throwingParentSupplierFailsItsSubscriberWithoutBreakingSharedGeneration() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var w = world(engine)) {
      var release = hold(engine);
      var fail = new AtomicBoolean();
      var token =
          new CancellationToken(
              () -> {
                if (fail.get()) throw new IllegalArgumentException("bad parent");
                return false;
              });
      var bad = w.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      var good = w.generateChunkAsync(new ChunkPos(0, 0, 0));
      fail.set(true);
      release.countDown();
      assertTrue(good.get(5, TimeUnit.SECONDS).isEmpty());
      assertInstanceOf(
          IllegalArgumentException.class,
          assertThrows(CompletionException.class, bad::join).getCause());
      assertEquals(0, w.inFlightChunks());
    }
  }

  @Test
  void registrationFailureAfterAdmissionLeavesNoQueuedOrOwnedJob() {
    try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
        var w = world(engine)) {
      var calls = new AtomicInteger();
      var token =
          new CancellationToken(
              () -> {
                if (calls.incrementAndGet() > 1) throw new IllegalArgumentException("parent");
                return false;
              });
      var result = w.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      assertThrows(CompletionException.class, result::join);
      assertEquals(0, w.inFlightChunks());
      assertEquals(0, engine.schedulerStats().queued());
    }
  }

  @Test
  void legacyRegionAndCacheStatsConstructorsRemainAvailable() {
    var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
    assertEquals(GenerationPriority.NORMAL, RegionRequest.of(bounds).priority());
    assertEquals(
        GenerationPriority.NORMAL,
        new RegionRequest(
                bounds,
                RegionRequest.FailurePolicy.CONTINUE,
                RegionRequest.DeliveryOrder.COORDINATE,
                1)
            .priority());
    assertEquals(0, new CacheStats(0, 0, 0, 0, 0).automaticEvictions());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RegionRequest(
                bounds,
                RegionRequest.FailurePolicy.CONTINUE,
                RegionRequest.DeliveryOrder.COMPLETION,
                1,
                null));
  }
}
