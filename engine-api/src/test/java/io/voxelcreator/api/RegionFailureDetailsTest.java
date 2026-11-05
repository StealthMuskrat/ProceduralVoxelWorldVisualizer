package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred real-region policy/count parity under host-defined diagnostic accessors. */
class RegionFailureDetailsTest {
  private static final RegionBounds THREE = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1));
  private static WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(new CompiledProject(new ProjectData()), new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }
  private static TaskProgress expected(RegionRequest.FailurePolicy policy) {
    return policy == RegionRequest.FailurePolicy.CONTINUE ? new TaskProgress("COMPLETED_WITH_ERRORS", 0, 2, 1, 0)
        : new TaskProgress("CANCELLED", 0, 0, 1, 2);
  }
  private static RegionTask start(WorldContext world, RegionRequest.FailurePolicy policy, RegionRequest.DeliveryOrder order,
      Throwable failure, boolean acknowledged, List<VoxelChunk> delivered) {
    return world.streamRegion(new RegionRequest(THREE, policy, order, 1), chunk -> {
      delivered.add(chunk);
      if (delivered.size() == 1) {
        if (acknowledged) return CompletableFuture.failedFuture(new CompletionException("host envelope", failure));
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
      }
      return CompletableFuture.completedFuture(null);
    });
  }

  @Test
  void oneFrozenMessageIsSharedBySummaryAndDiagnosticUnderBothPoliciesOrdersAndForms() throws Exception {
    for (var policy : RegionRequest.FailurePolicy.values()) for (var order : RegionRequest.DeliveryOrder.values())
      for (boolean acknowledged : new boolean[] {false, true}) try (var engine = new NoiseGeneratorEngine(2, 32, 8L << 20); var world = world(engine)) {
        var calls = new AtomicInteger(); var delivered = new ArrayList<VoxelChunk>(); var events = new CopyOnWriteArrayList<Diagnostic>();
        engine.addDiagnosticListener(events::add);
        var failure = new RuntimeException() { public String getMessage() { return "message-" + calls.incrementAndGet(); } };
        var task = start(world, policy, order, failure, acknowledged, delivered); var summary = task.completion().get(3, TimeUnit.SECONDS);
        assertEquals(expected(policy), summary.progress()); assertEquals(summary.progress(), task.progress()); assertEquals(1, calls.get());
        assertEquals("message-1", summary.failures().getFirst().message());
        var reported = events.stream().filter(d -> d.code().equals("CHUNK_DELIVERY_FAILED")).toList(); assertEquals(1, reported.size());
        assertEquals(summary.failures().getFirst().message(), reported.getFirst().message()); assertEquals(0, world.inFlightChunks());
        assertTrue(delivered.stream().allMatch(VoxelChunk::isEmpty)); assertEquals(0, failure.getSuppressed().length);
      }
  }

  @Test
  void unreadableMessagesCannotTurnContinueOrFailFastIntoExceptionalCoordinatorFailure() throws Exception {
    for (var policy : RegionRequest.FailurePolicy.values()) for (var order : RegionRequest.DeliveryOrder.values())
      for (boolean acknowledged : new boolean[] {false, true}) for (boolean fatal : new boolean[] {false, true})
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine)) {
        Throwable failure = fatal ? new AssertionError() { public String getMessage() { throw new AssertionError("unreadable"); } }
            : new RuntimeException() { public String getMessage() { throw new AssertionError("unreadable"); } };
        var delivered = new ArrayList<VoxelChunk>(); var summary = start(world, policy, order, failure, acknowledged, delivered).completion().get(3, TimeUnit.SECONDS);
        assertEquals(expected(policy), summary.progress()); assertEquals(failure.getClass().getName(), summary.failures().getFirst().message());
        assertEquals(policy == RegionRequest.FailurePolicy.CONTINUE ? 3 : 1, delivered.size()); assertEquals(0, world.inFlightChunks());
        assertTrue(world.generateChunkAsync(new ChunkPos(-1, 0, 0)).get(3, TimeUnit.SECONDS).isEmpty()); assertEquals(0, failure.getSuppressed().length);
      }
  }

  @Test
  void nullBlankAndOversizedMessagesStayUsableWithoutChangingPolicyOrCounts() throws Exception {
    for (int mode = 0; mode < 3; mode++) try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine)) {
      final int kind = mode; var delivered = new ArrayList<VoxelChunk>(); var events = new CopyOnWriteArrayList<Diagnostic>(); engine.addDiagnosticListener(events::add);
      var failure = new RuntimeException() { public String getMessage() { return kind == 0 ? null : kind == 1 ? " " : "😀".repeat(2000); } };
      var summary = start(world, RegionRequest.FailurePolicy.CONTINUE, RegionRequest.DeliveryOrder.COORDINATE, failure, false, delivered).completion().get(3, TimeUnit.SECONDS);
      String detail = summary.failures().getFirst().message(); assertFalse(detail.isBlank()); assertTrue(detail.length() <= 2048);
      assertEquals(expected(RegionRequest.FailurePolicy.CONTINUE), summary.progress());
      assertEquals(detail, events.stream().filter(d -> d.code().equals("CHUNK_DELIVERY_FAILED")).findFirst().orElseThrow().message());
    }
  }

  @Test
  void unreadableEnvelopeCauseAndThrowingDiagnosticListenerCannotAbortThePolicy() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine)) {
      var events = new CopyOnWriteArrayList<Diagnostic>(); engine.addDiagnosticListener(d -> { throw new AssertionError("listener"); }); engine.addDiagnosticListener(events::add);
      var cause = new RuntimeException("cause"); var calls = new AtomicInteger();
      var failure = new CompletionException("safe envelope", cause) { public Throwable getCause() { calls.incrementAndGet(); throw new AssertionError("cause reader"); } };
      var delivered = new ArrayList<VoxelChunk>();
      var summary = start(world, RegionRequest.FailurePolicy.CONTINUE, RegionRequest.DeliveryOrder.COORDINATE, failure, false, delivered).completion().get(3, TimeUnit.SECONDS);
      assertEquals(expected(RegionRequest.FailurePolicy.CONTINUE), summary.progress()); assertEquals(1, calls.get());
      assertEquals("safe envelope", summary.failures().getFirst().message()); assertEquals(1, events.stream().filter(d -> d.code().equals("CHUNK_DELIVERY_FAILED")).count());
      assertEquals(0, cause.getSuppressed().length);
    }
  }

  @Test
  void messageCallbackCancellationRecordsOneAttemptAndDoesNotRetryConsumerSideEffects() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20); var world = world(engine)) {
      var owner = new AtomicReference<RegionTask>(); var published = new CountDownLatch(1); var calls = new AtomicInteger(); var messages = new AtomicInteger();
      var failure = new RuntimeException() { public String getMessage() { messages.incrementAndGet(); owner.get().cancel(); return "cancelled during description"; } };
      var task = world.streamRegion(new RegionRequest(THREE, RegionRequest.FailurePolicy.CONTINUE, RegionRequest.DeliveryOrder.COORDINATE, 1), chunk -> {
        calls.incrementAndGet(); try { assertTrue(published.await(3, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        throw failure;
      });
      owner.set(task); published.countDown(); var summary = task.completion().get(3, TimeUnit.SECONDS);
      assertEquals(new TaskProgress("CANCELLED", 0, 0, 1, 2), summary.progress()); assertEquals(1, calls.get()); assertEquals(1, messages.get());
      assertEquals("cancelled during description", summary.failures().getFirst().message()); assertEquals(0, world.inFlightChunks());
    }
  }

  @Test
  void detailCapDoesNotDuplicateMessageObservationOrSuppressLaterDiagnostics() throws Exception {
    try (var engine = new NoiseGeneratorEngine(2, 64, 8L << 20); var world = world(engine)) {
      var messages = new AtomicInteger(); var events = new CopyOnWriteArrayList<Diagnostic>(); engine.addDiagnosticListener(events::add);
      var failure = new RuntimeException() { public String getMessage() { return "message-" + messages.incrementAndGet(); } };
      var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(2199, 1, 1));
      var task = world.streamRegion(new RegionRequest(bounds, RegionRequest.FailurePolicy.CONTINUE, RegionRequest.DeliveryOrder.COORDINATE, 16), chunk -> { throw failure; });
      var summary = task.completion().get(15, TimeUnit.SECONDS); assertEquals(new TaskProgress("COMPLETED_WITH_ERRORS", 0, 0, 1100, 0), summary.progress());
      assertEquals(1100, messages.get()); assertEquals(1024, summary.failures().size());
      var reported = events.stream().filter(d -> d.code().equals("CHUNK_DELIVERY_FAILED")).toList(); assertEquals(1100, reported.size());
      for (int i = 0; i < 1024; i++) assertEquals(summary.failures().get(i).message(), reported.get(i).message());
      assertEquals("message-1100", reported.getLast().message()); assertEquals(0, world.inFlightChunks()); assertEquals(0, failure.getSuppressed().length);
    }
  }
}
