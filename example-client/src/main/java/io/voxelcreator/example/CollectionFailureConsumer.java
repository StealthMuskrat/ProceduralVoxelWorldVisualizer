package io.voxelcreator.example;

import io.voxelcreator.api.*;
import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Deferred public engine-JAR collection withdrawal without host failure-text observation. */
public final class CollectionFailureConsumer {
  private static final class Gate implements BooleanSupplier, AutoCloseable {
    final Thread submitter = Thread.currentThread();
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public boolean getAsBoolean() {
      if (Thread.currentThread() != submitter) {
        entered.countDown();
        try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return true; }
      }
      return false;
    }
    public void close() { release.countDown(); }
  }

  static void run(CompiledProject compiled, Path out) throws Exception {
    Files.createDirectories(out); var retained = new ArrayList<VoxelChunk>(); long cases = 0, textReads = 0;
    for (int root : new int[] {2, 4}) for (var order : RegionRequest.DeliveryOrder.values())
      for (String kind : List.of("RUNTIME", "ERROR", "CANCELLATION", "COMPLETION")) for (boolean obtrude : new boolean[] {false, true})
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
            var world = engine.openWorld(compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0));
            var holder = engine.openWorld(compiled, new WorldOptions(null, root, WorldOptions.CachePolicy.NO_CACHE, 0)); var gate = new Gate()) {
          var held = holder.generateChunkAsync(new ChunkPos(-1, 0, 0), new CancellationToken(gate));
          require(gate.entered.await(3, TimeUnit.SECONDS), "Public worker gate was not entered");
          var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0));
          var request = new RegionRequest(new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(root - 1, root - 1, root - 1)),
              RegionRequest.FailurePolicy.CONTINUE, order, 1);
          var future = world.collectRegionAsync(request);
          await(() -> world.sharedRequests() == 1 && engine.metrics().collectionBytes() > 0);
          var reads = new AtomicInteger(); var failure = failure(kind, reads);
          if (obtrude) future.obtrudeException(failure); else require(future.completeExceptionally(failure), "Caller failure did not win collection publication");
          require(future.handle((value, error) -> error).get(3, TimeUnit.SECONDS) == failure, "Collection changed the caller's original fault");
          await(() -> engine.metrics().collectionBytes() == 0);
          require(reads.get() == 0 && failure.getSuppressed().length == 0 && future.isCancelled() == kind.equals("CANCELLATION"), "Internal observer read host text or changed the public outcome");
          require(!peer.isDone() && world.inFlightChunks() == 1 && engine.metrics().queued() == 1 && gate.release.getCount() == 1,
              "Collection withdrawal retired a healthy shared subscriber or awaited its worker");
          gate.close(); require(held.get(3, TimeUnit.SECONDS).isEmpty(), "Independent holder context was contaminated");
          retained.add(peer.get(3, TimeUnit.SECONDS)); require(world.inFlightChunks() == 0 && reads.get() == 0, "Collection retained ownership or read host text during cleanup");
          require(future.handle((value, error) -> error).get(3, TimeUnit.SECONDS) == failure, "Late engine publication replaced the caller's outcome");
          textReads += reads.get(); cases++;
        }
    long cells = 0;
    for (var chunk : retained) for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++) for (int x = 0; x < chunk.size(); x++) {
      require(chunk.getVoxelTypeId(x, y, z) == 0, "Collection withdrawal invalidated a retained peer snapshot"); cells++;
    }
    require(cases == 32 && textReads == 0 && retained.size() == 32 && cells == 1152, "Unexpected collection-failure replay dimensions");
    Files.writeString(out.resolve("collection-failure-report.json"), ProjectIO.JSON.writeValueAsString(
        Map.of("cases", cases, "hostTextReads", textReads, "sharedPeers", 32, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4))));
    System.out.println("COLLECTION_FAILURE_CONSUMER_PASS");
  }

  private static Throwable failure(String kind, AtomicInteger reads) {
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
  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(2);
    require(condition.getAsBoolean(), "Deferred collection ownership phase was not reached");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
