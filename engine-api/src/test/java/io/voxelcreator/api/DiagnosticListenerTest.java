package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred observation isolation, ownership and real scheduler/region recovery contracts. */
class DiagnosticListenerTest {
  private static final Diagnostic EVENT = Diagnostic.error("TEST", "/runtime", "original");

  @Test
  void runtimeExceptionsAndErrorsDoNotStopOrderedFanoutOrMutateHostFailures() {
    try (var listeners = new DiagnosticListeners()) {
      var failure = new AssertionError("observer");
      var seen = new ArrayList<Diagnostic>();
      listeners.add(
          event -> {
            throw new IllegalArgumentException("observer");
          });
      listeners.add(seen::add);
      listeners.add(
          event -> {
            throw failure;
          });
      listeners.add(seen::add);
      assertDoesNotThrow(() -> listeners.report(EVENT));
      assertEquals(List.of(EVENT, EVENT), seen);
      assertSame(EVENT, seen.getFirst());
      assertEquals(0, failure.getSuppressed().length);
      assertNull(failure.getCause());
    }
  }

  @Test
  void removalUsesIdentityAndRevokesEveryDuplicateRegistration() {
    try (var listeners = new DiagnosticListeners()) {
      var calls = new AtomicInteger();
      Consumer<Diagnostic> host =
          new Consumer<>() {
            public void accept(Diagnostic event) {
              calls.incrementAndGet();
            }

            public boolean equals(Object other) {
              throw new AssertionError("Host equals invoked");
            }

            public int hashCode() {
              throw new AssertionError("Host hashCode invoked");
            }
          };
      listeners.add(host);
      listeners.add(host);
      listeners.report(EVENT);
      assertEquals(2, calls.get());
      assertTrue(listeners.remove(host));
      assertFalse(listeners.remove(host));
      listeners.report(EVENT);
      assertEquals(2, calls.get());
    }
  }

  @Test
  void registrationsAddedDuringDispatchStartWithTheNextReport() {
    try (var listeners = new DiagnosticListeners()) {
      var seen = new ArrayList<String>();
      Consumer<Diagnostic> added = event -> seen.add("added");
      listeners.add(
          event -> {
            seen.add("first");
            listeners.add(added);
          });
      listeners.report(EVENT);
      assertEquals(List.of("first"), seen);
      listeners.report(EVENT);
      assertEquals(List.of("first", "first", "added"), seen);
    }
  }

  @Test
  void removalSkipsALaterCapturedEntryAndCanReregisterTheSameInstance() {
    try (var listeners = new DiagnosticListeners()) {
      var calls = new AtomicInteger();
      Consumer<Diagnostic> later = event -> calls.incrementAndGet();
      var removed = new AtomicBoolean();
      Consumer<Diagnostic> remover =
          event -> {
            removed.set(listeners.remove(later));
            listeners.add(later);
          };
      listeners.add(remover);
      listeners.add(later);
      listeners.report(EVENT);
      assertTrue(removed.get());
      assertEquals(0, calls.get());
      assertTrue(listeners.remove(remover));
      listeners.report(EVENT);
      assertEquals(1, calls.get());
    }
  }

  @Test
  void callbackCanRemoveItselfWithoutAffectingOtherObservers() {
    try (var listeners = new DiagnosticListeners()) {
      var self = new AtomicReference<Consumer<Diagnostic>>();
      var removed = new AtomicBoolean();
      var calls = new AtomicInteger();
      self.set(
          event -> {
            calls.incrementAndGet();
            removed.set(listeners.remove(self.get()));
          });
      listeners.add(self.get());
      listeners.add(event -> calls.incrementAndGet());
      listeners.report(EVENT);
      listeners.report(EVENT);
      assertEquals(3, calls.get());
      assertTrue(removed.get());
    }
  }

  @Test
  void closingFromACallbackRevokesRemainingCapturedEntries() {
    var listeners = new DiagnosticListeners();
    var calls = new AtomicInteger();
    listeners.add(
        event -> {
          calls.incrementAndGet();
          listeners.close();
        });
    listeners.add(event -> calls.incrementAndGet());
    listeners.report(EVENT);
    listeners.report(EVENT);
    assertEquals(1, calls.get());
    assertDoesNotThrow(listeners::close);
    assertThrows(IllegalStateException.class, () -> listeners.add(event -> {}));
  }

  @Test
  void removalDoesNotWaitForAClaimedCallbackOrHoldTheRegistryGate() throws Exception {
    try (var listeners = new DiagnosticListeners();
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var calls = new AtomicInteger();
      var callbackFailure = new AtomicReference<Throwable>();
      Consumer<Diagnostic> host =
          event -> {
            calls.incrementAndGet();
            entered.countDown();
            try {
              if (!release.await(3, TimeUnit.SECONDS))
                callbackFailure.set(new AssertionError("Release timeout"));
            } catch (InterruptedException failure) {
              callbackFailure.set(failure);
            }
          };
      listeners.add(host);
      var reporting = executor.submit(() -> listeners.report(EVENT));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertTrue(executor.submit(() -> listeners.remove(host)).get(3, TimeUnit.SECONDS));
        executor.submit(listeners::close).get(3, TimeUnit.SECONDS);
        assertFalse(reporting.isDone());
      } finally {
        release.countDown();
      }
      reporting.get(3, TimeUnit.SECONDS);
      assertNull(callbackFailure.get());
      listeners.report(EVENT);
      assertEquals(1, calls.get());
    }
  }

  @Test
  void nullOwnershipArgumentsRejectAndPostCloseRemovalIsHarmless() {
    var listeners = new DiagnosticListeners();
    assertThrows(NullPointerException.class, () -> listeners.add(null));
    assertThrows(NullPointerException.class, () -> listeners.remove(null));
    Consumer<Diagnostic> host = event -> {};
    listeners.add(host);
    listeners.close();
    assertFalse(listeners.remove(host));
    assertThrows(IllegalStateException.class, () -> listeners.add(host));
  }

  private WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  @Test
  void observerErrorsCannotChangeRegionPolicyOrderFailureDetailsOrRecovery() throws Exception {
    for (var order : RegionRequest.DeliveryOrder.values())
      for (var policy : RegionRequest.FailurePolicy.values())
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
            var world = world(engine)) {
          var observer = new AssertionError("observer-only");
          var original = new AssertionError("delivery-original");
          var reports = new CopyOnWriteArrayList<Diagnostic>();
          Consumer<Diagnostic> faulty =
              event -> {
                throw observer;
              };
          engine.addDiagnosticListener(faulty);
          engine.addDiagnosticListener(reports::add);
          var calls = new AtomicInteger();
          var task =
              world.streamRegion(
                  new RegionRequest(
                      new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1)),
                      policy,
                      order,
                      1),
                  chunk -> {
                    if (calls.incrementAndGet() == 1) throw original;
                    return CompletableFuture.completedFuture(null);
                  });
          var summary = task.completion().get(3, TimeUnit.SECONDS);
          boolean continued = policy == RegionRequest.FailurePolicy.CONTINUE;
          assertEquals(
              new TaskProgress(
                  continued ? "COMPLETED_WITH_ERRORS" : "CANCELLED",
                  0,
                  continued ? 2 : 0,
                  1,
                  continued ? 0 : 2),
              summary.progress());
          assertEquals(continued ? 3 : 1, calls.get());
          assertEquals("delivery-original", summary.failures().getFirst().message());
          assertEquals("CHUNK_DELIVERY_FAILED", reports.getFirst().code());
          assertEquals(1, reports.size());
          assertEquals(0, original.getSuppressed().length);
          assertEquals(0, observer.getSuppressed().length);
          assertTrue(engine.removeDiagnosticListener(faulty));
          assertEquals(0, world.inFlightChunks());
          assertTrue(world.generateChunk(new ChunkPos(-1, 0, 0)).isEmpty());
        }
  }

  @Test
  void queueOverloadStillReturnsItsRejectedFutureWhenObserversThrowErrors() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 1, 8L << 20);
        var world = world(engine)) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var reports = new CopyOnWriteArrayList<Diagnostic>();
      engine.addDiagnosticListener(
          event -> {
            throw new AssertionError("observer");
          });
      engine.addDiagnosticListener(reports::add);
      engine.workers.execute(
          () -> {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var queued = world.generateChunkAsync(new ChunkPos(0, 0, 0));
        var rejected = assertDoesNotThrow(() -> world.generateChunkAsync(new ChunkPos(1, 0, 0)));
        var failure = assertThrows(CompletionException.class, rejected::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
        assertEquals("QUEUE_OVERLOAD", reports.getFirst().code());
        assertEquals(1, reports.size());
        queued.cancel(false);
        assertEquals(0, world.inFlightChunks());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void engineClosureReleasesObserversAndRejectsLaterRegistration() {
    var engine = new NoiseGeneratorEngine(1, 1, 8L << 20);
    var seen = new ArrayList<Diagnostic>();
    Consumer<Diagnostic> host = seen::add;
    engine.addDiagnosticListener(host);
    engine.close();
    assertFalse(engine.removeDiagnosticListener(host));
    assertThrows(IllegalStateException.class, () -> engine.addDiagnosticListener(host));
    engine.report("AFTER_CLOSE", "discarded");
    assertTrue(seen.isEmpty());
    assertDoesNotThrow(engine::close);
  }

  @Test
  void callbackCanCloseTheEngineWithoutInvokingLaterObservers() {
    try (var engine = new NoiseGeneratorEngine(1, 1, 8L << 20)) {
      var calls = new AtomicInteger();
      engine.addDiagnosticListener(
          event -> {
            calls.incrementAndGet();
            engine.close();
          });
      engine.addDiagnosticListener(event -> calls.incrementAndGet());
      engine.report("CLOSE", "close from observer");
      assertEquals(1, calls.get());
    }
  }
}
