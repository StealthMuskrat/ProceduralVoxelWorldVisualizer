package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred real-coordinator observations; no sampler timing or polling interleaving is assumed. */
class RegionProgressTest {
  private static CompiledProject project(int minimum, int maximum) {
    var project = new ProjectData();
    project.project.minY = minimum;
    project.project.maxY = maximum;
    return new CompiledProject(project);
  }

  private static WorldContext world(NoiseGeneratorEngine engine, int root) {
    return engine.openWorld(
        project(-4, 3), new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private static RegionRequest request(
      int root, RegionRequest.DeliveryOrder order, RegionRequest.FailurePolicy policy) {
    // Independently known chunk spans: X -128..128, Y -1..0, Z -1..0 = 1,028 chunks.
    return new RegionRequest(
        new RegionBounds(
            new BlockPos(-128 * root, -root, -root),
            new BlockPos(129 * root - 1, root - 1, root - 1)),
        policy,
        order,
        8);
  }

  private static void partition(TaskProgress progress, long total) {
    assertTrue(
        progress.pending() >= 0
            && progress.completed() >= 0
            && progress.failed() >= 0
            && progress.cancelled() >= 0,
        progress.toString());
    assertEquals(
        total,
        Math.addExact(
            Math.addExact(progress.pending(), progress.completed()),
            Math.addExact(progress.failed(), progress.cancelled())),
        progress.toString());
    if (!List.of("PENDING", "RUNNING").contains(progress.state()))
      assertEquals(0, progress.pending());
  }

  private static long observe(RegionTask task, long total, CountDownLatch ready) throws Exception {
    var completion = task.completion();
    var previous = task.progress();
    partition(previous, total);
    ready.countDown();
    long observations = 1, until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      var current = task.progress();
      partition(current, total);
      assertTrue(current.completed() >= previous.completed());
      assertTrue(current.failed() >= previous.failed());
      assertTrue(current.cancelled() >= previous.cancelled());
      assertTrue(current.pending() <= previous.pending());
      previous = current;
      observations++;
      if (System.nanoTime() > until) fail("Region did not reach terminal ownership");
      if (observations % 256 == 0) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
      else Thread.onSpinWait();
    } while (!completion.isDone());
    assertEquals(completion.get(3, TimeUnit.SECONDS).progress(), task.progress());
    return observations;
  }

  @Test
  void concurrentReadersSeeOneCountPartitionAcrossWorkerOrderAndDeliveryFailures()
      throws Exception {
    for (int root : new int[] {2, 4})
      for (var order : RegionRequest.DeliveryOrder.values())
        for (boolean failures : new boolean[] {false, true})
          try (var engine = new NoiseGeneratorEngine(2, 64, 16L << 20);
              var world = world(engine, root);
              var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CompletableFuture<Void>();
            var entered = new CountDownLatch(1);
            var task =
                world.streamRegion(
                    request(root, order, RegionRequest.FailurePolicy.CONTINUE),
                    chunk -> {
                      entered.countDown();
                      return gate.thenCompose(
                          ignored ->
                              failures && Math.floorMod(chunk.position().x(), 2) != 0
                                  ? CompletableFuture.<Void>failedFuture(
                                      new IllegalStateException("delivery fixture"))
                                  : CompletableFuture.<Void>completedFuture(null));
                    });
            try {
              assertTrue(entered.await(3, TimeUnit.SECONDS));
              var retained = task.progress();
              assertEquals(new TaskProgress("RUNNING", 1028, 0, 0, 0), retained);
              var ready = new CountDownLatch(4);
              var observations = new ArrayList<Future<Long>>();
              for (int i = 0; i < 4; i++)
                observations.add(readers.submit(() -> observe(task, 1028, ready)));
              assertTrue(ready.await(3, TimeUnit.SECONDS));
              gate.complete(null);
              for (var reader : observations) assertTrue(reader.get(12, TimeUnit.SECONDS) >= 2);
              var summary = task.completion().get(3, TimeUnit.SECONDS);
              assertEquals(failures ? 512 : 0, summary.progress().failed());
              assertEquals(failures ? 516 : 1028, summary.progress().completed());
              assertEquals(0, summary.progress().cancelled());
              assertEquals(
                  failures ? "COMPLETED_WITH_ERRORS" : "COMPLETED", summary.progress().state());
              assertEquals(new TaskProgress("RUNNING", 1028, 0, 0, 0), retained);
            } finally {
              gate.complete(null);
              task.cancel();
            }
          }
  }

  @Test
  void pendingCountsUseTheClippedEffectiveChunkSpanRatherThanRequestedHeight() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 32, 8L << 20);
        var world =
            engine.openWorld(
                project(0, 0), new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      var bounds = new RegionBounds(new BlockPos(-2, -3, -2), new BlockPos(3, 3, 3));
      var summary =
          world
              .streamRegion(
                  RegionRequest.of(bounds), chunk -> CompletableFuture.completedFuture(null))
              .completion()
              .get(3, TimeUnit.SECONDS);
      assertEquals(
          Optional.of(new RegionBounds(new BlockPos(-2, 0, -2), new BlockPos(3, 0, 3))),
          summary.effective());
      assertEquals(new TaskProgress("COMPLETED", 0, 9, 0, 0), summary.progress());
      assertEquals(bounds, summary.requested());
    }
  }

  @Test
  void emptyEffectiveRegionPublishesAZeroPartitionWithoutInvokingTheConsumer() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine, 2)) {
      var task =
          world.streamRegion(
              RegionRequest.of(new RegionBounds(new BlockPos(0, 10, 0), new BlockPos(7, 20, 7))),
              chunk -> {
                throw new AssertionError("Empty region delivered a chunk");
              });
      var summary = task.completion().get(3, TimeUnit.SECONDS);
      assertTrue(summary.effective().isEmpty());
      assertEquals(new TaskProgress("COMPLETED", 0, 0, 0, 0), summary.progress());
      assertEquals(summary.progress(), task.progress());
    }
  }

  @Test
  void failFastDeliveryPartitionsAllUnacknowledgedWorkExactlyOnce() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine, 2)) {
      var task =
          world.streamRegion(
              request(
                  2, RegionRequest.DeliveryOrder.COORDINATE, RegionRequest.FailurePolicy.FAIL_FAST),
              chunk -> CompletableFuture.failedFuture(new IllegalArgumentException("refused")));
      var summary = task.completion().get(3, TimeUnit.SECONDS);
      assertEquals(new TaskProgress("CANCELLED", 0, 0, 1, 1027), summary.progress());
      assertEquals(summary.progress(), task.progress());
      assertEquals("DELIVERY", summary.failures().getFirst().phase());
    }
  }

  @Test
  void cancellationRetainsTheHeldObservationAndDoesNotCompleteTheHostsAcknowledgement()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine, 2)) {
      var ack = new CompletableFuture<Void>();
      var entered = new CountDownLatch(1);
      var task =
          world.streamRegion(
              request(
                  2, RegionRequest.DeliveryOrder.COORDINATE, RegionRequest.FailurePolicy.CONTINUE),
              chunk -> {
                entered.countDown();
                return ack;
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var retained = task.progress();
        assertTrue(task.cancel());
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(new TaskProgress("CANCELLED", 0, 0, 0, 1028), summary.progress());
        assertEquals(new TaskProgress("RUNNING", 1028, 0, 0, 0), retained);
        assertFalse(ack.isDone());
      } finally {
        ack.complete(null);
      }
    }
  }

  @Test
  void contextClosePublishesTerminalCountsWhileIndependentContextAndRetainedChunksSurvive()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var closedWorld = world(engine, 2);
        var peer = world(engine, 2)) {
      var retained = peer.generateChunk(new ChunkPos(0, 0, 0));
      var ack = new CompletableFuture<Void>();
      var entered = new CountDownLatch(1);
      var task =
          closedWorld.streamRegion(
              request(
                  2, RegionRequest.DeliveryOrder.COMPLETION, RegionRequest.FailurePolicy.CONTINUE),
              chunk -> {
                entered.countDown();
                return ack;
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        closedWorld.close();
        assertEquals(
            new TaskProgress("CANCELLED", 0, 0, 0, 1028),
            task.completion().get(3, TimeUnit.SECONDS).progress());
        assertEquals(0, peer.generateChunk(new ChunkPos(1, 0, 0)).getVoxelTypeId(0, 0, 0));
        assertEquals(0, retained.getVoxelTypeId(0, 0, 0));
        assertFalse(ack.isDone());
      } finally {
        ack.complete(null);
      }
    }
  }

  @Test
  void fatalConsumerOutcomePublishesACoherentFailedSnapshotBeforeObserverFailure()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 32, 8L << 20);
        var world = world(engine, 2)) {
      var fatal = new AssertionError("fatal consumer fixture");
      var task =
          world.streamRegion(
              request(
                  2, RegionRequest.DeliveryOrder.COORDINATE, RegionRequest.FailurePolicy.CONTINUE),
              chunk -> {
                throw fatal;
              });
      assertSame(
          fatal,
          assertThrows(ExecutionException.class, () -> task.completion().get(3, TimeUnit.SECONDS))
              .getCause());
      assertEquals(new TaskProgress("FAILED", 0, 0, 0, 1028), task.progress());
    }
  }

  @Test
  void boundedFailureDetailsDoNotLimitTheExactFailedChunkCount() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 16L << 20);
        var world = world(engine, 2)) {
      var request =
          RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(4097, 1, 1)));
      var summary =
          world
              .streamRegion(
                  request,
                  chunk -> CompletableFuture.failedFuture(new IllegalArgumentException("refused")))
              .completion()
              .get(10, TimeUnit.SECONDS);
      assertEquals(new TaskProgress("COMPLETED_WITH_ERRORS", 0, 0, 2049, 0), summary.progress());
      assertEquals(1024, summary.failures().size());
      assertThrows(UnsupportedOperationException.class, summary.failures()::clear);
    }
  }

  @Test
  void publicSummaryAndCollectedResultDetachTheCallersMutableFailureList() {
    var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
    var failure = new RegionSummary.ChunkFailure(new ChunkPos(0, 0, 0), "DELIVERY", "retained");
    var source = new ArrayList<>(List.of(failure));
    var summary =
        new RegionSummary(
            bounds,
            Optional.of(bounds),
            new TaskProgress("COMPLETED_WITH_ERRORS", 0, 0, 1, 0),
            source);
    int hash = summary.hashCode();
    try (var result = new RegionResult(List.of(), summary)) {
      source.clear();
      source.add(new RegionSummary.ChunkFailure(new ChunkPos(1, 0, 0), "GENERATION", "foreign"));
      assertEquals(List.of(failure), summary.failures());
      assertEquals(hash, summary.hashCode());
      assertEquals(summary, result.summary());
      assertThrows(
          UnsupportedOperationException.class,
          () -> result.summary().failures().set(0, source.getFirst()));
      result.close();
      assertEquals(List.of(failure), result.summary().failures());
    }
  }

  @Test
  void lazyRegionNearSignedLongCapacityKeepsEveryCountBitWithoutAllocatingTheRegion()
      throws Exception {
    long expected =
        java.math.BigInteger.valueOf(Integer.MAX_VALUE).pow(2).shiftLeft(1).longValueExact();
    try (var engine = new NoiseGeneratorEngine(2, 32, 8L << 20);
        var world = world(engine, 2)) {
      var bounds =
          new RegionBounds(
              new BlockPos(Integer.MIN_VALUE + 2, -2, Integer.MIN_VALUE + 2),
              new BlockPos(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
      var ack = new CompletableFuture<Void>();
      var entered = new CountDownLatch(1);
      var task =
          world.streamRegion(
              RegionRequest.of(bounds),
              chunk -> {
                entered.countDown();
                return ack;
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var retained = task.progress();
        assertEquals(new TaskProgress("RUNNING", expected, 0, 0, 0), retained);
        task.cancel();
        assertEquals(
            new TaskProgress("CANCELLED", 0, 0, 0, expected),
            task.completion().get(3, TimeUnit.SECONDS).progress());
        assertEquals(expected, retained.pending());
        assertFalse(ack.isDone());
      } finally {
        ack.complete(null);
        task.cancel();
      }
    }
  }

  @Test
  void malformedSummaryOwnershipIsRejectedAtConstruction() {
    var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
    var progress = new TaskProgress("COMPLETED", 0, 1, 0, 0);
    assertThrows(
        NullPointerException.class,
        () -> new RegionSummary(null, Optional.empty(), progress, List.of()));
    assertThrows(
        NullPointerException.class, () -> new RegionSummary(bounds, null, progress, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new RegionSummary(bounds, Optional.empty(), null, List.of()));
    assertThrows(
        NullPointerException.class,
        () -> new RegionSummary(bounds, Optional.empty(), progress, null));
    assertThrows(
        NullPointerException.class,
        () ->
            new RegionSummary(
                bounds,
                Optional.empty(),
                progress,
                Arrays.asList((RegionSummary.ChunkFailure) null)));
  }
}
