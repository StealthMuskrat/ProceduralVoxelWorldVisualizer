package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Deferred owner/observer contracts; latches hold actual work instead of assuming its duration. */
class RegionCompletionTest {
  private static final RegionRequest ONE =
      RegionRequest.of(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)));

  private static WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private record Held(RegionTask task, CompletableFuture<Void> ack) {}

  private static Held hold(WorldContext world) throws Exception {
    var entered = new CountDownLatch(1);
    var ack = new CompletableFuture<Void>();
    var task =
        world.streamRegion(
            ONE,
            chunk -> {
              entered.countDown();
              return ack;
            });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    return new Held(task, ack);
  }

  private static CountDownLatch holdWorker(NoiseGeneratorEngine engine) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    engine.workers.execute(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    return release;
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < until) TimeUnit.MILLISECONDS.sleep(2);
    assertTrue(condition.getAsBoolean());
  }

  @Test
  void manualExceptionalCancelledAndObtrudedObserversNeverCompleteTheOwner() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine)) {
      var held = hold(world);
      var manual = held.task.completion();
      var exceptional = held.task.completion();
      var cancelled = held.task.completion();
      var obtruded = held.task.completion();
      assertNotSame(manual, exceptional);
      assertTrue(manual.complete(null));
      assertTrue(exceptional.completeExceptionally(new IllegalStateException("observer")));
      assertTrue(cancelled.cancel(true));
      obtruded.obtrudeValue(null);
      assertFalse(held.task.completion().isDone());
      assertEquals("RUNNING", held.task.progress().state());
      assertTrue(held.task.cancel());
      var summary = held.task.completion().get(3, TimeUnit.SECONDS);
      assertEquals("CANCELLED", summary.progress().state());
      assertEquals(1, summary.progress().cancelled());
      assertEquals(0, summary.progress().completed());
      assertFalse(held.ack.isDone(), "Cancellation cannot complete the host acknowledgement");
      manual.obtrudeException(new AssertionError("late observer"));
      assertEquals(summary, held.task.completion().get(3, TimeUnit.SECONDS));
      held.ack.complete(null);
    }
  }

  @Test
  void observerTimeoutDoesNotStopTheStreamOrDisableExplicitCancellation() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine)) {
      var held = hold(world);
      var timed = held.task.completion().orTimeout(1, TimeUnit.NANOSECONDS);
      assertInstanceOf(
          TimeoutException.class,
          assertThrows(ExecutionException.class, () -> timed.get(3, TimeUnit.SECONDS)).getCause());
      assertFalse(held.task.completion().isDone());
      assertTrue(held.task.cancel());
      assertEquals(1, held.task.completion().get(3, TimeUnit.SECONDS).progress().cancelled());
      assertFalse(held.ack.isDone());
      held.ack.complete(null);
    }
  }

  @Test
  void contextCloseStillCancelsAnObservedStreamAndLeavesAnIndependentContextUsable()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine);
        var peer = world(engine)) {
      var held = hold(world);
      held.task.completion().complete(null);
      world.close();
      assertEquals("CANCELLED", held.task.completion().get(3, TimeUnit.SECONDS).progress().state());
      assertTrue(peer.generateChunk(new ChunkPos(-1, 0, -1)).isEmpty());
      assertThrows(IllegalStateException.class, () -> world.streamRegion(ONE, c -> held.ack));
      held.ack.complete(null);
    }
  }

  @Test
  void allTerminalPathsReleaseTheirRegionSlotBeforeReentrantCompletionCallbacks() throws Exception {
    for (String mode : List.of("SUCCESS", "CANCEL", "DELIVERY_FAILURE", "FATAL"))
      try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
          var world = world(engine)) {
        var siblings = new ArrayList<Held>();
        var release = new CountDownLatch(1);
        try {
          for (int i = 0; i < 15; i++) siblings.add(hold(world));
          var entered = new CountDownLatch(1);
          var ack = new CompletableFuture<Void>();
          var task =
              world.streamRegion(
                  ONE,
                  chunk -> {
                    entered.countDown();
                    try {
                      release.await();
                    } catch (InterruptedException interrupted) {
                      Thread.currentThread().interrupt();
                      throw new CancellationException();
                    }
                    if (mode.equals("FATAL")) throw new AssertionError("consumer fatal");
                    if (mode.equals("DELIVERY_FAILURE"))
                      return CompletableFuture.failedFuture(
                          new IllegalArgumentException("delivery"));
                    return ack;
                  });
          assertTrue(entered.await(3, TimeUnit.SECONDS));
          assertThrows(
              RejectedExecutionException.class,
              () -> world.streamRegion(ONE, c -> CompletableFuture.completedFuture(null)));
          var successor = new CompletableFuture<RegionTask>();
          task.completion()
              .whenComplete(
                  (summary, failure) -> {
                    try {
                      successor.complete(
                          world.streamRegion(ONE, c -> CompletableFuture.completedFuture(null)));
                    } catch (Throwable rejected) {
                      successor.completeExceptionally(rejected);
                    }
                  });
          if (mode.equals("CANCEL")) assertTrue(task.cancel());
          release.countDown();
          if (!mode.equals("CANCEL")) ack.complete(null);
          assertEquals(
              1,
              successor
                  .get(3, TimeUnit.SECONDS)
                  .completion()
                  .get(3, TimeUnit.SECONDS)
                  .progress()
                  .completed());
          if (mode.equals("FATAL"))
            assertThrows(
                ExecutionException.class, () -> task.completion().get(3, TimeUnit.SECONDS));
          else {
            var summary = task.completion().get(3, TimeUnit.SECONDS);
            assertEquals(
                mode.equals("SUCCESS")
                    ? "COMPLETED"
                    : mode.equals("CANCEL") ? "CANCELLED" : "COMPLETED_WITH_ERRORS",
                summary.progress().state());
          }
        } finally {
          release.countDown();
          siblings.forEach(
              held -> {
                held.task.cancel();
                held.ack.complete(null);
              });
        }
      }
  }

  @Test
  void everyUndeliverableAsyncCollectionWithdrawsItsReservationWhileWorkersAreBusy()
      throws Exception {
    for (String mode : List.of("CANCEL", "EXCEPTION", "TIMEOUT", "VALUE"))
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var release = holdWorker(engine);
        try {
          var future = world.collectRegionAsync(ONE);
          await(() -> engine.metrics().collectionBytes() > 0 && world.inFlightChunks() == 1);
          switch (mode) {
            case "CANCEL" -> assertTrue(future.cancel(false));
            case "EXCEPTION" ->
                assertTrue(future.completeExceptionally(new IllegalStateException("withdrawn")));
            case "TIMEOUT" -> future.orTimeout(1, TimeUnit.NANOSECONDS);
            case "VALUE" -> assertTrue(future.complete(null));
          }
          await(
              () ->
                  future.isDone()
                      && engine.metrics().collectionBytes() == 0
                      && world.inFlightChunks() == 0);
          assertEquals(0, engine.metrics().queued());
          assertEquals(
              1, release.getCount(), "The worker must still be held when ownership is released");
        } finally {
          release.countDown();
        }
      }
  }

  @Test
  void aBlockedPublicCompletionCallbackCannotKeepAnUndeliverableCollectionLease() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var releaseWorker = holdWorker(engine);
      var entered = new CountDownLatch(1);
      var releaseCallback = new CountDownLatch(1);
      Thread terminal = null;
      try {
        var future = world.collectRegionAsync(ONE);
        await(() -> engine.metrics().collectionBytes() > 0 && world.inFlightChunks() == 1);
        future.whenComplete(
            (value, failure) -> {
              entered.countDown();
              try {
                releaseCallback.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
        terminal =
            Thread.ofVirtual()
                .start(() -> future.completeExceptionally(new IllegalStateException("withdrawn")));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        await(() -> engine.metrics().collectionBytes() == 0 && world.inFlightChunks() == 0);
        assertEquals(1, releaseCallback.getCount());
        assertEquals(1, releaseWorker.getCount());
      } finally {
        releaseCallback.countDown();
        releaseWorker.countDown();
        if (terminal != null) terminal.join(3000);
      }
    }
  }

  @Test
  void successfulAsyncCollectionStillOwnsItsLeaseUntilExplicitIdempotentClose() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 16, 8L << 20);
        var world = world(engine)) {
      var result = world.collectRegionAsync(ONE).get(3, TimeUnit.SECONDS);
      var chunk = result.chunks().getFirst();
      assertEquals(1, result.summary().progress().completed());
      assertTrue(engine.metrics().collectionBytes() > 0);
      result.close();
      result.close();
      assertEquals(0, engine.metrics().collectionBytes());
      for (int z = 0; z < 2; z++)
        for (int y = 0; y < 2; y++)
          for (int x = 0; x < 2; x++) assertEquals(0, chunk.getVoxelTypeId(x, y, z));
    }
  }

  @Test
  void withdrawnCollectionDoesNotCancelAnotherSubscriberToItsQueuedChunk() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var release = holdWorker(engine);
      try {
        var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0));
        var collection = world.collectRegionAsync(ONE);
        await(() -> world.sharedRequests() > 0 && engine.metrics().collectionBytes() > 0);
        assertTrue(collection.completeExceptionally(new IllegalStateException("withdrawn")));
        await(() -> engine.metrics().collectionBytes() == 0);
        assertFalse(peer.isDone());
        assertEquals(1, world.inFlightChunks());
        release.countDown();
        assertTrue(peer.get(3, TimeUnit.SECONDS).isEmpty());
        assertEquals(1, engine.metrics().completed());
      } finally {
        release.countDown();
      }
    }
  }
}
