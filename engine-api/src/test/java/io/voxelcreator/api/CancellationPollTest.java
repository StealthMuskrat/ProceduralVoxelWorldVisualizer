package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred same-poll ownership checks; latches arrange cancellation before a parent returns. */
class CancellationPollTest {
  private record Probe(CancellationToken token, AtomicInteger reads, AtomicInteger callbacks) {}

  private Probe selfCancelling() {
    var owner = new AtomicReference<CancellationToken>(); var armed = new AtomicBoolean();
    var reads = new AtomicInteger(); var callbacks = new AtomicInteger();
    var token = new CancellationToken(() -> {
      reads.incrementAndGet(); if (armed.get()) owner.get().cancel(); return false;
    });
    owner.set(token); token.onCancel(callbacks::incrementAndGet); reads.set(0); armed.set(true);
    return new Probe(token, reads, callbacks);
  }

  @Test
  void samePollSeesExplicitCancellationAndDoesNotPollOrNotifyAgain() {
    for (boolean throwing : new boolean[] {false, true}) {
      var probe = selfCancelling();
      if (throwing) assertThrows(CancellationException.class, probe.token::throwIfCancelled);
      else assertTrue(probe.token.isCancelled());
      assertEquals(1, probe.reads.get()); assertEquals(1, probe.callbacks.get());
      assertTrue(probe.token.isCancelled()); assertFalse(probe.token.cancel());
      probe.token.onCancel(probe.callbacks::incrementAndGet).close();
      assertEquals(1, probe.reads.get()); assertEquals(2, probe.callbacks.get());
    }
  }

  @Test
  void cancellationWhileAParentIsBlockedWinsBeforeItsFalseReturn() throws Exception {
    for (boolean throwing : new boolean[] {false, true}) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var armed = new AtomicBoolean(); var reads = new AtomicInteger(); var calls = new AtomicInteger();
      var token = new CancellationToken(() -> {
        if (armed.get()) {
          reads.incrementAndGet(); entered.countDown();
          try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("Parent was not released"); }
          catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
        return false;
      });
      token.onCancel(calls::incrementAndGet); armed.set(true);
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var query = workers.submit(() -> {
          if (throwing) { token.throwIfCancelled(); return false; }
          return token.isCancelled();
        });
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(token.cancel());
          token.onCancel(calls::incrementAndGet).close(); assertEquals(2, calls.get());
        } finally { release.countDown(); }
        if (throwing) assertInstanceOf(CancellationException.class,
            assertThrows(ExecutionException.class, () -> query.get(3, TimeUnit.SECONDS)).getCause());
        else assertTrue(query.get(3, TimeUnit.SECONDS));
        assertEquals(1, reads.get()); assertEquals(2, calls.get());
      }
    }
  }

  @Test
  void parentOnlyObservationsRemainUnlatchedAndDoNotDrainExistingListeners() {
    var signal = new AtomicBoolean(); var reads = new AtomicInteger(); var calls = new AtomicInteger();
    var token = new CancellationToken(() -> { reads.incrementAndGet(); return signal.get(); });
    token.onCancel(calls::incrementAndGet); signal.set(true);
    assertTrue(token.isCancelled()); assertEquals(0, calls.get());
    token.onCancel(calls::incrementAndGet).close(); assertEquals(1, calls.get());
    signal.set(false); assertFalse(token.isCancelled()); assertEquals(4, reads.get());
    assertTrue(token.cancel()); assertEquals(2, calls.get()); assertTrue(token.isCancelled());
    assertEquals(4, reads.get());
  }

  @Test
  void parentFailuresAfterExplicitCancellationKeepTheirOriginalIdentity() {
    for (Throwable failure : List.of(new IllegalStateException("parent"), new AssertionError("parent"))) {
      var owner = new AtomicReference<CancellationToken>(); var armed = new AtomicBoolean();
      var calls = new AtomicInteger(); var reads = new AtomicInteger();
      var token = new CancellationToken(() -> {
        reads.incrementAndGet();
        if (armed.get()) {
          owner.get().cancel();
          if (failure instanceof RuntimeException runtime) throw runtime;
          throw (Error) failure;
        }
        return false;
      });
      owner.set(token); token.onCancel(calls::incrementAndGet); armed.set(true); reads.set(0);
      assertSame(failure, assertThrows(Throwable.class, token::isCancelled));
      assertTrue(token.isCancelled()); assertEquals(1, calls.get()); assertEquals(1, reads.get());
      assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void cachedRequestsRejectCancellationObservedDuringInitialPollingWithoutNewAdmission() {
    for (int root : new int[] {2, 4}) for (var policy : List.of(WorldOptions.CachePolicy.MEMORY_CACHE,
        WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, WorldOptions.CachePolicy.USER_MANAGED)) {
      VoxelChunk retained;
      try (var engine = new NoiseGeneratorEngine(1, 8, 8L << 20);
          var world = engine.openWorld(new CompiledProject(new ProjectData()), new WorldOptions(null, root, policy, 1L << 20))) {
        var pos = new ChunkPos(-1, 0, 1); retained = world.generateChunk(pos);
        for (int route = 0; route < 3; route++) {
          var probe = selfCancelling(); long hits = world.cacheStats().hits();
          var request = route == 0 ? world.generateChunkAsync(pos, probe.token)
              : world.generateChunkAsync(pos, probe.token, route == 1 ? GenerationPriority.INTERACTIVE : GenerationPriority.BACKGROUND);
          assertThrows(CancellationException.class, request::join); assertTrue(request.isCancelled());
          assertEquals(1, probe.reads.get()); assertEquals(1, probe.callbacks.get());
          assertEquals(hits, world.cacheStats().hits()); assertEquals(1, engine.metrics().completed());
          assertEquals(0, world.inFlightChunks()); assertEquals(0, engine.metrics().queued());
          assertSame(retained, world.generateChunk(pos));
        }
      }
      for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++)
        assertEquals(0, retained.getVoxelTypeId(x, y, z));
    }
  }

  @Test
  void emptyAndNonemptyGridChecksRejectCancellationWithoutSamplingOrChangingDestination() {
    var calls = new AtomicInteger(); NoiseSampler2D two = (x, z) -> { calls.incrementAndGet(); return x + z; };
    NoiseSampler3D three = (x, y, z) -> { calls.incrementAndGet(); return x + y + z; };
    for (int dimension : new int[] {2, 3}) for (int axis = -1; axis < dimension; axis++) {
      var probe = selfCancelling();
      double[] destination = {-0., Double.longBitsToDouble(0x7ff8000000000042L), 17.};
      long[] before = Arrays.stream(destination).mapToLong(Double::doubleToRawLongBits).toArray();
      int width = axis == 0 ? 0 : 1, height = dimension == 3 && axis == 1 ? 0 : 1;
      int depth = axis == dimension - 1 ? 0 : 1;
      assertThrows(CancellationException.class, () -> {
        if (dimension == 2) two.sampleGrid(0, 0, width, depth, 1, 1, destination, 1, 1, probe.token);
        else three.sampleGrid(0, 0, 0, width, height, depth, 1, 1, 1, destination, 1, 1, 1, probe.token);
      });
      assertEquals(0, calls.get()); assertEquals(1, probe.reads.get()); assertEquals(1, probe.callbacks.get());
      assertArrayEquals(before, Arrays.stream(destination).mapToLong(Double::doubleToRawLongBits).toArray());
    }
  }
}
