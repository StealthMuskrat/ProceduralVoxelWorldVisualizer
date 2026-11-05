package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred real-region failure-policy parity for synchronous and acknowledged host Errors. */
class RegionDeliveryErrorTest {
  private static final RegionBounds THREE =
      new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1));

  private WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  @SuppressWarnings("unchecked")
  private CompletionStage<Void> stage(
      String mode, AssertionError failure, CompletableFuture<Void> host) {
    if (mode.equals("FAILED_FUTURE")) return CompletableFuture.failedFuture(failure);
    return (CompletionStage<Void>)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {CompletionStage.class},
            (proxy, method, args) -> {
              if (method.getName().equals("toCompletableFuture")) {
                if (mode.equals("CONVERSION")) throw failure;
                throw new UnsupportedOperationException("No host conversion");
              }
              if (method.getName().equals("handle")) throw failure;
              try {
                return method.invoke(host, args);
              } catch (InvocationTargetException wrapped) {
                throw wrapped.getCause();
              }
            });
  }

  @Test
  void directAcknowledgedAndStageMethodErrorsFollowBothPoliciesAndOrdersWithoutRetry()
      throws Exception {
    for (var order : RegionRequest.DeliveryOrder.values())
      for (var policy : RegionRequest.FailurePolicy.values())
        for (String mode : List.of("DIRECT", "FAILED_FUTURE", "CONVERSION", "OBSERVER"))
          try (var engine = new NoiseGeneratorEngine(2, 32, 8L << 20);
              var world = world(engine)) {
            var events = new CopyOnWriteArrayList<Diagnostic>();
            engine.addDiagnosticListener(events::add);
            var failure = new AssertionError("delivery-error");
            var host = new CompletableFuture<Void>();
            var chunks = new ArrayList<VoxelChunk>();
            var calls = new LinkedHashMap<ChunkPos, Integer>();
            var task =
                world.streamRegion(
                    new RegionRequest(THREE, policy, order, 2),
                    chunk -> {
                      calls.merge(chunk.position(), 1, Integer::sum);
                      chunks.add(chunk);
                      if (chunks.size() == 1) {
                        if (mode.equals("DIRECT")) throw failure;
                        return stage(mode, failure, host);
                      }
                      return CompletableFuture.completedFuture(null);
                    });
            var summary = task.completion().get(3, TimeUnit.SECONDS);
            boolean keepGoing = policy == RegionRequest.FailurePolicy.CONTINUE;
            assertEquals(
                new TaskProgress(
                    keepGoing ? "COMPLETED_WITH_ERRORS" : "CANCELLED",
                    0,
                    keepGoing ? 2 : 0,
                    1,
                    keepGoing ? 0 : 2),
                summary.progress());
            assertEquals(summary.progress(), task.progress());
            assertEquals(keepGoing ? 3 : 1, chunks.size());
            assertTrue(calls.values().stream().allMatch(count -> count == 1));
            assertEquals(1, summary.failures().size());
            assertEquals(chunks.getFirst().position(), summary.failures().getFirst().chunk());
            assertEquals("DELIVERY", summary.failures().getFirst().phase());
            assertEquals("delivery-error", summary.failures().getFirst().message());
            assertEquals(
                1,
                events.stream()
                    .filter(event -> event.code().equals("CHUNK_DELIVERY_FAILED"))
                    .count());
            assertFalse(host.isDone());
            assertEquals(0, failure.getSuppressed().length);
            assertEquals(0, world.inFlightChunks());
            for (var chunk : chunks) assertTrue(chunk.isEmpty());
            assertTrue(
                world
                    .generateChunkAsync(new ChunkPos(-1, 0, 0))
                    .get(3, TimeUnit.SECONDS)
                    .isEmpty());
          }
  }

  @Test
  void repeatedIdenticalErrorsRetainPerChunkFailuresWithoutMutatingTheError() throws Exception {
    for (boolean acknowledged : new boolean[] {false, true})
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var failure = new AssertionError("same-object");
        var chunks = new ArrayList<VoxelChunk>();
        var task =
            world.streamRegion(
                new RegionRequest(
                    THREE,
                    RegionRequest.FailurePolicy.CONTINUE,
                    RegionRequest.DeliveryOrder.COORDINATE,
                    1),
                chunk -> {
                  chunks.add(chunk);
                  if (acknowledged) return CompletableFuture.failedFuture(failure);
                  throw failure;
                });
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(new TaskProgress("COMPLETED_WITH_ERRORS", 0, 0, 3, 0), summary.progress());
        assertEquals(3, summary.failures().size());
        assertEquals(
            List.of(new ChunkPos(0, 0, 0), new ChunkPos(1, 0, 0), new ChunkPos(2, 0, 0)),
            chunks.stream().map(VoxelChunk::position).toList());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(3, engine.metrics().completed());
        assertEquals(0, world.inFlightChunks());
      }
  }

  @Test
  void synchronousErrorDetailsRemainBoundedWhileCountsRemainExact() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20);
        var world = world(engine)) {
      var failure = new AssertionError("bounded-detail");
      var seen = new HashSet<ChunkPos>();
      var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(2199, 1, 1));
      var task =
          world.streamRegion(
              new RegionRequest(
                  bounds,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COMPLETION,
                  32),
              chunk -> {
                assertTrue(seen.add(chunk.position()), "Consumer was retried");
                throw failure;
              });
      var summary = task.completion().get(15, TimeUnit.SECONDS);
      assertEquals(new TaskProgress("COMPLETED_WITH_ERRORS", 0, 0, 1100, 0), summary.progress());
      assertEquals(1100, seen.size());
      assertEquals(1024, summary.failures().size());
      assertTrue(
          summary.failures().stream()
              .allMatch(
                  item ->
                      item.phase().equals("DELIVERY") && item.message().equals("bounded-detail")));
      assertEquals(0, failure.getSuppressed().length);
      assertEquals(0, world.inFlightChunks());
    }
  }

  @Test
  void consumerCancellationThenErrorRecordsTheAttemptAndCancelsRemainingWork() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var owner = new AtomicReference<RegionTask>();
      var published = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var failure = new AssertionError("cancelled-attempt");
      var task =
          world.streamRegion(
              new RegionRequest(
                  THREE,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COORDINATE,
                  1),
              chunk -> {
                calls.incrementAndGet();
                try {
                  assertTrue(published.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new AssertionError(interrupted);
                }
                assertTrue(owner.get().cancel());
                throw failure;
              });
      owner.set(task);
      published.countDown();
      var summary = task.completion().get(3, TimeUnit.SECONDS);
      assertEquals(new TaskProgress("CANCELLED", 0, 0, 1, 2), summary.progress());
      assertEquals("DELIVERY", summary.failures().getFirst().phase());
      assertEquals("cancelled-attempt", summary.failures().getFirst().message());
      assertEquals(1, calls.get());
      assertFalse(task.cancel());
    }
  }
}
