package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred real-stream contracts for generic host stages, failure timing and ownership. */
class RegionAcknowledgementTest {
  private static final RegionBounds THREE =
      new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1));
  private static final RegionBounds ONE =
      new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));

  private WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private RegionRequest request(
      RegionBounds bounds, RegionRequest.FailurePolicy policy, RegionRequest.DeliveryOrder order) {
    return new RegionRequest(bounds, policy, order, 1);
  }

  private void ready(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(3, TimeUnit.SECONDS), "Deferred phase was not reached");
  }

  private static final class EnteringFuture extends CompletableFuture<Void> {
    final CountDownLatch waiting = new CountDownLatch(1);
    final AtomicReference<Thread> thread = new AtomicReference<>();

    @Override
    public Void get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
      thread.set(Thread.currentThread());
      waiting.countDown();
      return super.get(timeout, unit);
    }
  }

  @SuppressWarnings("unchecked")
  private CompletionStage<Void> opaque(
      CompletableFuture<Void> source, CountDownLatch observed, String mode) {
    return (CompletionStage<Void>)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {CompletionStage.class},
            (proxy, method, args) -> {
              if (method.getName().equals("equals")) return proxy == args[0];
              if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
              if (method.getName().equals("toCompletableFuture")) {
                if (mode.equals("NULL_FUTURE")) return null;
                if (mode.equals("BAD_CONVERSION"))
                  throw new IllegalStateException("conversion-boom");
                throw new UnsupportedOperationException("Host stage has no future conversion");
              }
              if (method.getName().equals("whenComplete") && mode.equals("BAD_OBSERVER"))
                throw new IllegalStateException("observe-boom");
              try {
                Object result = method.invoke(source, args);
                if (method.getName().equals("whenComplete")) observed.countDown();
                return result;
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
            });
  }

  @Test
  void nonConvertibleStagesRetainBackpressureUntilAcknowledgedInBothOrders() throws Exception {
    for (var order : RegionRequest.DeliveryOrder.values())
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var host = new CompletableFuture<Void>();
        var observed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var chunks = new ArrayList<VoxelChunk>();
        var task =
            world.streamRegion(
                request(THREE, RegionRequest.FailurePolicy.CONTINUE, order),
                chunk -> {
                  chunks.add(chunk);
                  return calls.incrementAndGet() == 1
                      ? opaque(host, observed, "OPAQUE")
                      : CompletableFuture.completedFuture(null);
                });
        try {
          ready(observed);
          assertEquals(1, calls.get());
          assertEquals(1, engine.metrics().completed());
          assertFalse(task.completion().isDone());
          assertFalse(host.isDone());
          host.complete(null);
          var summary = task.completion().get(3, TimeUnit.SECONDS);
          assertEquals(3, summary.progress().completed());
          assertEquals(0, summary.progress().failed());
          assertEquals(0, summary.progress().cancelled());
          assertEquals(3, calls.get());
          assertEquals(3, chunks.size());
          for (var chunk : chunks) assertTrue(chunk.isEmpty());
        } finally {
          host.complete(null);
          task.cancel();
        }
      }
  }

  @Test
  void failedOpaqueAcknowledgementsApplyContinueAndFailFastWithoutRetry() throws Exception {
    for (var policy : RegionRequest.FailurePolicy.values())
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var host = new CompletableFuture<Void>();
        var observed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var task =
            world.streamRegion(
                request(THREE, policy, RegionRequest.DeliveryOrder.COORDINATE),
                chunk ->
                    calls.incrementAndGet() == 1
                        ? opaque(host, observed, "OPAQUE")
                        : CompletableFuture.completedFuture(null));
        try {
          ready(observed);
          host.completeExceptionally(new IllegalArgumentException("ack-boom"));
          var summary = task.completion().get(3, TimeUnit.SECONDS);
          assertEquals(1, summary.progress().failed());
          assertEquals(
              policy == RegionRequest.FailurePolicy.CONTINUE ? 2 : 0,
              summary.progress().completed());
          assertEquals(
              policy == RegionRequest.FailurePolicy.FAIL_FAST ? 2 : 0,
              summary.progress().cancelled());
          assertEquals(policy == RegionRequest.FailurePolicy.CONTINUE ? 3 : 1, calls.get());
          assertEquals("DELIVERY", summary.failures().getFirst().phase());
          assertEquals("ack-boom", summary.failures().getFirst().message());
        } finally {
          task.cancel();
          host.complete(null);
        }
      }
  }

  @Test
  void directFutureFailuresHaveTheSameCauseBeforeAndDuringTheTimedWait() throws Exception {
    for (boolean alreadyFailed : List.of(false, true))
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var host = new EnteringFuture();
        var failure = new IllegalArgumentException("ack-boom");
        var reports = new CopyOnWriteArrayList<Diagnostic>();
        engine.addDiagnosticListener(reports::add);
        if (alreadyFailed) host.completeExceptionally(failure);
        var task = world.streamRegion(RegionRequest.of(ONE), chunk -> host);
        try {
          if (!alreadyFailed) {
            ready(host.waiting);
            host.completeExceptionally(failure);
          }
          var summary = task.completion().get(3, TimeUnit.SECONDS);
          assertEquals(1, summary.progress().failed());
          assertEquals("ack-boom", summary.failures().getFirst().message());
          assertEquals("DELIVERY", summary.failures().getFirst().phase());
          assertEquals("ack-boom", reports.getFirst().message());
          assertSame(failure, host.handle((value, error) -> error).join());
        } finally {
          host.complete(null);
          task.cancel();
        }
      }
  }

  @Test
  void explicitCancellationLeavesTheOpaqueHostStageAndIndependentContextAlone() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var peer = world(engine)) {
      var host = new CompletableFuture<Void>();
      var observed = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var task =
          world.streamRegion(
              request(
                  THREE,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COMPLETION),
              chunk -> {
                calls.incrementAndGet();
                return opaque(host, observed, "OPAQUE");
              });
      try {
        ready(observed);
        assertTrue(task.cancel());
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(3, summary.progress().cancelled());
        assertEquals(0, summary.progress().failed());
        assertEquals(0, summary.progress().completed());
        assertEquals(1, calls.get());
        assertFalse(host.isDone());
        assertEquals(0, world.inFlightChunks());
        assertTrue(
            peer.generateChunkAsync(new ChunkPos(0, 0, 0)).get(3, TimeUnit.SECONDS).isEmpty());
      } finally {
        host.complete(null);
        task.cancel();
      }
    }
  }

  @Test
  void contextClosureCancelsAnOpaqueWaitWithoutCompletingTheHostStage() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var peer = world(engine)) {
      var host = new CompletableFuture<Void>();
      var observed = new CountDownLatch(1);
      var task =
          world.streamRegion(
              request(
                  THREE,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COORDINATE),
              chunk -> opaque(host, observed, "OPAQUE"));
      try {
        ready(observed);
        world.close();
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(3, summary.progress().cancelled());
        assertEquals(0, summary.progress().failed());
        assertFalse(host.isDone());
        assertTrue(
            peer.generateChunkAsync(new ChunkPos(0, 0, 0)).get(3, TimeUnit.SECONDS).isEmpty());
      } finally {
        host.complete(null);
      }
    }
  }

  @Test
  void hostStageCancellationRemainsADeliveryFailureByTheSelectedPolicy() throws Exception {
    for (var policy : RegionRequest.FailurePolicy.values())
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var host = new CompletableFuture<Void>();
        host.cancel(false);
        var observed = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var task =
            world.streamRegion(
                request(THREE, policy, RegionRequest.DeliveryOrder.COORDINATE),
                chunk ->
                    calls.incrementAndGet() == 1
                        ? opaque(host, observed, "OPAQUE")
                        : CompletableFuture.completedFuture(null));
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(0, observed.getCount());
        assertEquals(1, summary.progress().failed());
        assertEquals("DELIVERY", summary.failures().getFirst().phase());
        assertEquals(
            policy == RegionRequest.FailurePolicy.CONTINUE ? 2 : 0, summary.progress().completed());
        assertEquals(
            policy == RegionRequest.FailurePolicy.FAIL_FAST ? 2 : 0,
            summary.progress().cancelled());
        assertTrue(host.isCancelled());
      }
  }

  @Test
  void interruptedDeliveryWaitCancelsTheStreamAndPreservesTheInterruptFlag() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var host = new EnteringFuture();
      var task =
          world.streamRegion(
              request(
                  THREE,
                  RegionRequest.FailurePolicy.CONTINUE,
                  RegionRequest.DeliveryOrder.COORDINATE),
              chunk -> host);
      var interrupted = new AtomicBoolean();
      var observed =
          task.completion()
              .whenComplete(
                  (summary, failure) -> interrupted.set(Thread.currentThread().isInterrupted()));
      try {
        ready(host.waiting);
        host.thread.get().interrupt();
        var summary = observed.get(3, TimeUnit.SECONDS);
        assertEquals(3, summary.progress().cancelled());
        assertEquals(0, summary.progress().failed());
        assertEquals(0, summary.progress().completed());
        assertTrue(interrupted.get());
        assertFalse(host.isDone());
      } finally {
        host.complete(null);
        task.cancel();
      }
    }
  }

  @Test
  void invalidConversionAndObservationAreSingleDeliveryFailuresWithoutHostMutation()
      throws Exception {
    for (String mode : List.of("NULL_STAGE", "NULL_FUTURE", "BAD_CONVERSION", "BAD_OBSERVER"))
      try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
          var world = world(engine)) {
        var host = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var task =
            world.streamRegion(
                RegionRequest.of(ONE),
                chunk -> {
                  calls.incrementAndGet();
                  return mode.equals("NULL_STAGE")
                      ? null
                      : opaque(host, new CountDownLatch(1), mode);
                });
        var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(1, summary.progress().failed());
        assertEquals(0, summary.progress().completed());
        assertEquals(1, calls.get());
        assertEquals("DELIVERY", summary.failures().getFirst().phase());
        assertFalse(host.isDone());
      }
  }

  @Test
  void ordinaryFutureCompletionDoesNotWaitForAnUnrelatedBlockedHostCallback() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var host = new EnteringFuture();
      var callbackEntered = new CountDownLatch(1);
      var callbackRelease = new CountDownLatch(1);
      host.whenComplete(
          (value, failure) -> {
            callbackEntered.countDown();
            try {
              callbackRelease.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      var task = world.streamRegion(RegionRequest.of(ONE), chunk -> host);
      Thread publisher = null;
      try {
        ready(host.waiting);
        publisher = Thread.ofVirtual().start(() -> host.complete(null));
        ready(callbackEntered);
        assertEquals(1, task.completion().get(3, TimeUnit.SECONDS).progress().completed());
        assertEquals(1, callbackRelease.getCount());
      } finally {
        callbackRelease.countDown();
        host.complete(null);
        task.cancel();
        if (publisher != null) publisher.join(3000);
      }
    }
  }
}
