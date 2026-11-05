package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/** Deferred terminal collection observation without host failure-wrapper text side effects. */
class CollectionFailureObservationTest {
  private static WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(new CompiledProject(new ProjectData()), new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }
  private record HeldWorker(CountDownLatch release) implements AutoCloseable {
    public void close() { release.countDown(); }
  }
  private static HeldWorker hold(NoiseGeneratorEngine engine) throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    engine.workers.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
    assertTrue(entered.await(3, TimeUnit.SECONDS)); return new HeldWorker(release);
  }
  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(2);
    assertTrue(condition.getAsBoolean());
  }
  private static Throwable fault(String kind, AtomicInteger reads) {
    return switch (kind) {
      case "ERROR" -> new AssertionError() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      case "CANCELLATION" -> new CancellationException() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      case "COMPLETION" -> new CompletionException("host envelope", new IllegalStateException("cause")) {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      default -> new IllegalStateException() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
    };
  }
  private static RegionRequest request(RegionRequest.DeliveryOrder order) {
    return new RegionRequest(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1)), RegionRequest.FailurePolicy.CONTINUE, order, 1);
  }

  @Test
  void rawExceptionalAndObtrudedCollectionOutcomesWithdrawWithoutReadingHostText() throws Exception {
    for (var order : RegionRequest.DeliveryOrder.values()) for (String kind : List.of("RUNTIME", "ERROR", "CANCELLATION", "COMPLETION"))
      for (boolean obtrude : new boolean[] {false, true})
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine); var held = hold(engine)) {
          var reads = new AtomicInteger(); var failure = fault(kind, reads); var future = world.collectRegionAsync(request(order));
          await(() -> engine.metrics().collectionBytes() > 0 && world.inFlightChunks() == 1);
          if (obtrude) future.obtrudeException(failure); else assertTrue(future.completeExceptionally(failure));
          assertSame(failure, future.handle((value, error) -> error).get(3, TimeUnit.SECONDS));
          await(() -> engine.metrics().collectionBytes() == 0 && world.inFlightChunks() == 0 && engine.metrics().queued() == 0);
          assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length); assertEquals(kind.equals("CANCELLATION"), future.isCancelled());
          assertEquals(1, held.release.getCount()); assertFalse(future.complete(null));
          assertSame(failure, future.handle((value, error) -> error).get(3, TimeUnit.SECONDS)); assertEquals(0, reads.get());
        }
  }

  @Test
  void blockedHostCallbackCannotKeepTheLeaseOrChangeAHealthySharedSubscriber() throws Exception {
    for (var order : RegionRequest.DeliveryOrder.values()) for (String kind : List.of("RUNTIME", "ERROR")) {
      VoxelChunk retained;
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine); var held = hold(engine);
          var publishers = Executors.newVirtualThreadPerTaskExecutor()) {
        var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0)); var future = world.collectRegionAsync(request(order));
        await(() -> world.sharedRequests() == 1 && engine.metrics().collectionBytes() > 0);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var reads = new AtomicInteger(); var failure = fault(kind, reads);
        future.handle((value, error) -> {
          assertSame(failure, error); entered.countDown();
          try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } return null;
        });
        var publisher = publishers.submit(() -> future.completeExceptionally(failure));
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS)); await(() -> engine.metrics().collectionBytes() == 0);
          assertFalse(peer.isDone()); assertEquals(1, world.inFlightChunks()); assertEquals(1, engine.metrics().queued());
          assertEquals(1, held.release.getCount()); assertEquals(1, release.getCount()); assertFalse(publisher.isDone()); assertEquals(0, reads.get());
          held.close(); retained = peer.get(3, TimeUnit.SECONDS); assertTrue(retained.isEmpty()); assertEquals(0, world.inFlightChunks());
          release.countDown(); assertTrue(publisher.get(3, TimeUnit.SECONDS)); assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
          assertSame(failure, future.handle((value, error) -> error).get(3, TimeUnit.SECONDS));
        } finally { release.countDown(); held.close(); }
      }
      for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) assertEquals(0, retained.getVoxelTypeId(x, y, z));
    }
  }
}
