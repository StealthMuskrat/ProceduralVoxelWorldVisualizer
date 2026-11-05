package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.storage.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred private-outcome observation; the host owns its stage and original Throwable. */
class AcknowledgementOutcomeTest {
  private static final VoxelChunk CHUNK = VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 7);
  private static Throwable failure(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); }
      public String toString() { throw new AssertionError("host toString"); }
    } : new RuntimeException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("reader"); }
      public String toString() { throw new AssertionError("host toString"); }
    };
  }
  @SuppressWarnings("unchecked")
  private static CompletionStage<Void> opaque(Consumer<BiFunction<Void, Throwable, Object>> registration) {
    return (CompletionStage<Void>) Proxy.newProxyInstance(AcknowledgementOutcomeTest.class.getClassLoader(), new Class<?>[] {CompletionStage.class},
        (proxy, method, args) -> {
          if (method.getName().equals("toCompletableFuture")) throw new UnsupportedOperationException("opaque");
          if (method.getName().equals("handle")) { registration.accept((BiFunction<Void, Throwable, Object>) args[0]); return CompletableFuture.completedFuture(null); }
          throw new AssertionError("Unexpected stage method " + method.getName());
        });
  }
  private static Throwable deliver(CompletionStage<Void> host, CancellationToken token) {
    try { ConsumerAcknowledgement.deliver(chunk -> host, CHUNK, token); return null; }
    catch (CompletionException failure) { return failure.getCause(); }
    catch (CancellationException cancelled) { return cancelled; }
  }

  @Test
  void completedRawFailedFuturePreservesPrimaryThrowableWithoutReadingItsText() {
    for (boolean error : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var failure = failure(error, reads); var host = CompletableFuture.<Void>failedFuture(failure);
      assertSame(failure, deliver(host, new CancellationToken())); assertEquals(0, reads.get());
      assertTrue(host.isCompletedExceptionally()); assertFalse(host.isCancelled()); assertEquals(0, failure.getSuppressed().length);
      assertEquals(7, CHUNK.getVoxelTypeId(0, 0, 0));
    }
  }

  @Test
  void delayedConvertibleAndOpaqueFailuresPreservePrimaryCauseWithoutTextAccess() throws Exception {
    for (boolean opaque : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
      var entered = new CountDownLatch(1); var handler = new AtomicReference<BiFunction<Void, Throwable, Object>>();
      var reads = new AtomicInteger(); var failure = failure(error, reads);
      var host = new CompletableFuture<Void>() {
        public <U> CompletableFuture<U> handle(BiFunction<? super Void, Throwable, ? extends U> action) {
          var result = super.handle(action); entered.countDown(); return result;
        }
      };
      CompletionStage<Void> stage = opaque ? opaque(action -> { handler.set(action); entered.countDown(); }) : host;
      try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        var delivered = tasks.submit(() -> deliver(stage, new CancellationToken()));
        try {
          assertTrue(entered.await(3, TimeUnit.SECONDS)); assertFalse(delivered.isDone());
          if (opaque) handler.get().apply(null, failure); else host.completeExceptionally(failure);
          assertSame(failure, delivered.get(3, TimeUnit.SECONDS)); assertEquals(0, reads.get()); assertFalse(host.isCancelled());
        } finally { host.complete(null); if (opaque && handler.get() != null) handler.get().apply(null, null); }
      }
    }
  }

  @Test
  void cancellationWaitUsesOnePendingRegistrationAndNeverCallsHostGetJoinOrCancel() throws Exception {
    var entered = new CountDownLatch(1); var polled = new CountDownLatch(4); var registrations = new AtomicInteger();
    var host = new CompletableFuture<Void>() {
      public <U> CompletableFuture<U> handle(BiFunction<? super Void, Throwable, ? extends U> action) {
        registrations.incrementAndGet(); var result = super.handle(action); entered.countDown(); return result;
      }
      public boolean isDone() { polled.countDown(); return super.isDone(); }
      public Void get(long timeout, TimeUnit unit) { throw new AssertionError("Host get called"); }
      public Void join() { throw new AssertionError("Host join called"); }
      public boolean cancel(boolean interrupt) { throw new AssertionError("Host cancel called"); }
    };
    var token = new CancellationToken();
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var delivered = tasks.submit(() -> deliver(host, token));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(polled.await(3, TimeUnit.SECONDS));
        assertEquals(1, registrations.get()); token.cancel(); assertInstanceOf(CancellationException.class, delivered.get(3, TimeUnit.SECONDS));
        assertFalse(host.isDone()); assertEquals(1, registrations.get()); assertTrue(host.complete(null));
      } finally { token.cancel(); host.complete(null); }
    }
  }

  @Test
  void callbackRegisteredAfterEngineObservationCannotHideACompletedFutureOutcome() throws Exception {
    var entered = new CountDownLatch(1); var blocked = new CountDownLatch(1); var release = new CountDownLatch(1); var registrations = new AtomicInteger();
    var reads = new AtomicInteger(); var failure = failure(true, reads);
    var host = new CompletableFuture<Void>() {
      public <U> CompletableFuture<U> handle(BiFunction<? super Void, Throwable, ? extends U> action) {
        registrations.incrementAndGet(); var result = super.handle(action); entered.countDown(); return result;
      }
    };
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var delivered = tasks.submit(() -> deliver(host, new CancellationToken()));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        host.handle((value, error) -> {
          blocked.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } return null;
        });
        var publisher = tasks.submit(() -> host.completeExceptionally(failure));
        assertTrue(blocked.await(3, TimeUnit.SECONDS)); assertSame(failure, delivered.get(3, TimeUnit.SECONDS));
        assertEquals(1, release.getCount()); assertEquals(3, registrations.get()); assertEquals(0, reads.get());
        release.countDown(); assertTrue(publisher.get(3, TimeUnit.SECONDS));
      } finally { release.countDown(); host.complete(null); }
    }
  }

  @Test
  void duplicateHostNotificationsCannotReplaceTheFirstObservedOutcome() {
    for (boolean failed : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var failure = failure(true, reads);
      var host = opaque(handler -> {
        handler.apply(null, failed ? failure : null); handler.apply(null, failed ? null : failure);
      });
      if (failed) assertSame(failure, deliver(host, new CancellationToken())); else assertNull(deliver(host, new CancellationToken()));
      assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void convertedRegistrationUnsupportedAndNullResultsAreSingleFaultsWithoutFallbackRetry() {
    for (boolean missing : new boolean[] {false, true}) {
      var calls = new AtomicInteger(); var failure = new UnsupportedOperationException("registration");
      var host = new CompletableFuture<Void>() {
        public <U> CompletableFuture<U> handle(BiFunction<? super Void, Throwable, ? extends U> handler) {
          calls.incrementAndGet(); if (!missing) throw failure; return null;
        }
      };
      if (missing) assertThrows(IllegalStateException.class, () -> deliver(host, new CancellationToken()));
      else assertSame(failure, assertThrows(UnsupportedOperationException.class, () -> deliver(host, new CancellationToken())));
      assertEquals(1, calls.get()); assertFalse(host.isDone()); assertFalse(host.isCancelled());
    }
  }

  @Test
  void registrationFaultAfterCallbackRetainsFaultPriorityAndDoesNotMutateTheHostFailure() {
    for (boolean callbackFailure : new boolean[] {false, true}) {
      var callback = new AssertionError("callback outcome"); var registration = new AssertionError("registration");
      var host = opaque(handler -> { handler.apply(null, callbackFailure ? callback : null); throw registration; });
      assertSame(registration, deliver(host, new CancellationToken())); assertEquals(0, callback.getSuppressed().length);
      assertEquals(0, registration.getSuppressed().length);
    }
  }

  @Test
  void rawFailedAcknowledgementsReachBothRegionPoliciesWithOriginalSingleReadDetails() throws Exception {
    var bounds = new RegionBounds(new BlockPos(0, 0, 0), new BlockPos(5, 1, 1));
    for (var policy : RegionRequest.FailurePolicy.values()) for (var order : RegionRequest.DeliveryOrder.values())
      for (boolean error : new boolean[] {false, true}) for (boolean unreadable : new boolean[] {false, true})
        try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
            var world = engine.openWorld(new CompiledProject(new ProjectData()), new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0))) {
          var reads = new AtomicInteger(); var calls = new AtomicInteger(); var events = new CopyOnWriteArrayList<Diagnostic>(); engine.addDiagnosticListener(events::add);
          Throwable failure = error ? new AssertionError() {
            public String getMessage() { reads.incrementAndGet(); if (unreadable) throw new AssertionError("reader"); return "original"; }
          } : new RuntimeException() {
            public String getMessage() { reads.incrementAndGet(); if (unreadable) throw new AssertionError("reader"); return "original"; }
          };
          var host = CompletableFuture.<Void>failedFuture(failure);
          var task = world.streamRegion(new RegionRequest(bounds, policy, order, 1), chunk -> {
            assertTrue(chunk.isEmpty()); return calls.incrementAndGet() == 1 ? host : CompletableFuture.completedFuture(null);
          });
          var summary = task.completion().get(3, TimeUnit.SECONDS); boolean continuing = policy == RegionRequest.FailurePolicy.CONTINUE;
          assertEquals(new TaskProgress(continuing ? "COMPLETED_WITH_ERRORS" : "CANCELLED", 0, continuing ? 2 : 0, 1, continuing ? 0 : 2), summary.progress());
          String expected = unreadable ? failure.getClass().getName() : "original";
          assertEquals(expected, summary.failures().getFirst().message()); assertEquals(1, reads.get()); assertEquals(continuing ? 3 : 1, calls.get());
          assertEquals(expected, events.stream().filter(d -> d.code().equals("CHUNK_DELIVERY_FAILED")).findFirst().orElseThrow().message());
          assertEquals(0, world.inFlightChunks()); assertTrue(host.isCompletedExceptionally()); assertFalse(host.isCancelled()); assertEquals(0, failure.getSuppressed().length);
        }
  }
}
