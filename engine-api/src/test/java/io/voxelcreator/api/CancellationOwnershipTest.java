package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred callback claims, finite listener faults and admitted subscriber isolation. */
class CancellationOwnershipTest {
  private WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private void ready(CountDownLatch latch) throws InterruptedException {
    assertTrue(latch.await(3, TimeUnit.SECONDS), "Deferred phase was not reached");
  }

  private record HeldWorker(CountDownLatch release) implements AutoCloseable {
    public void close() {
      release.countDown();
    }
  }

  private HeldWorker hold(NoiseGeneratorEngine engine) throws Exception {
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
    ready(entered);
    return new HeldWorker(release);
  }

  @Test
  void closingCapturedRegistrationsSuppressesUnclaimedCallbacksInEitherOrder() {
    var token = new CancellationToken();
    var first = new AtomicReference<CancellationToken.Registration>();
    var second = new AtomicReference<CancellationToken.Registration>();
    var calls = new AtomicInteger();
    first.set(
        token.onCancel(
            () -> {
              calls.incrementAndGet();
              second.get().close();
            }));
    second.set(
        token.onCancel(
            () -> {
              calls.incrementAndGet();
              first.get().close();
            }));
    assertTrue(token.cancel());
    assertEquals(1, calls.get());
    first.get().close();
    second.get().close();
    assertFalse(token.cancel());
    assertEquals(1, calls.get());
  }

  @Test
  void identicalRunnableInstancesStillHaveIndependentDisposableRegistrations() {
    var token = new CancellationToken();
    var calls = new AtomicInteger();
    Runnable callback = calls::incrementAndGet;
    var first = token.onCancel(callback);
    var second = token.onCancel(callback);
    first.close();
    first.close();
    assertTrue(token.cancel());
    second.close();
    assertEquals(1, calls.get());
    token.onCancel(callback).close();
    assertEquals(2, calls.get());
  }

  @Test
  void listenerErrorsDoNotAbortFanoutAndAllErrorsRemainObservable() {
    var token = new CancellationToken();
    var errors =
        List.of(new AssertionError("one"), new AssertionError("two"), new AssertionError("three"));
    var calls = new AtomicInteger();
    for (var error : errors)
      token.onCancel(
          () -> {
            calls.incrementAndGet();
            throw error;
          });
    token.onCancel(calls::incrementAndGet);
    token.onCancel(
        () -> {
          calls.incrementAndGet();
          throw new IllegalStateException("ignored runtime");
        });
    var observed = assertThrows(Error.class, token::cancel);
    var seen = new HashSet<Throwable>(Arrays.asList(observed.getSuppressed()));
    seen.add(observed);
    assertEquals(new HashSet<>(errors), seen);
    assertEquals(2, observed.getSuppressed().length);
    assertEquals(5, calls.get());
    assertTrue(token.isCancelled());
    assertFalse(token.cancel());
    assertEquals(5, calls.get());
  }

  @Test
  void repeatedErrorObjectsNeverSelfSuppressAndClosedFaultsDoNotRun() {
    var token = new CancellationToken();
    var shared = new AssertionError("same error");
    var calls = new AtomicInteger();
    for (int i = 0; i < 2; i++)
      token.onCancel(
          () -> {
            calls.incrementAndGet();
            throw shared;
          });
    var closed =
        token.onCancel(
            () -> {
              throw new AssertionError("closed callback ran");
            });
    closed.close();
    assertSame(shared, assertThrows(Error.class, token::cancel));
    assertEquals(0, shared.getSuppressed().length);
    assertEquals(2, calls.get());
  }

  @Test
  void closingAnAlreadyClaimedCallbackDoesNotWaitOrReplayIt() throws Exception {
    var token = new CancellationToken();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var registration =
        token.onCancel(
            () -> {
              calls.incrementAndGet();
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var cancellation =
          tasks.submit(
              () -> {
                return token.cancel();
              });
      try {
        ready(entered);
        tasks.submit(registration::close).get(3, TimeUnit.SECONDS);
        assertFalse(cancellation.isDone());
        assertEquals(1, calls.get());
      } finally {
        release.countDown();
      }
      assertTrue(cancellation.get(3, TimeUnit.SECONDS));
    }
    registration.close();
    assertFalse(token.cancel());
    assertEquals(1, calls.get());
  }

  @Test
  void concurrentCloseAndCancelClaimAtMostOnceAndPreserveIndependentListeners() throws Exception {
    try (var workers = Executors.newFixedThreadPool(2)) {
      for (int iteration = 0; iteration < 100; iteration++) {
        var token = new CancellationToken();
        var calls = new AtomicInteger();
        var peer = new AtomicInteger();
        var registration = token.onCancel(calls::incrementAndGet);
        token.onCancel(peer::incrementAndGet);
        var start = new CountDownLatch(1);
        var close =
            workers.submit(
                () -> {
                  start.await();
                  registration.close();
                  return null;
                });
        var cancel =
            workers.submit(
                () -> {
                  start.await();
                  return token.cancel();
                });
        start.countDown();
        close.get(3, TimeUnit.SECONDS);
        assertTrue(cancel.get(3, TimeUnit.SECONDS));
        assertTrue(calls.get() == 0 || calls.get() == 1);
        assertEquals(1, peer.get());
        assertFalse(token.cancel());
      }
    }
  }

  @Test
  void lateFailuresRemainInlineAndParentPollingDoesNotDrainStoredCallbacks() {
    var parent = new AtomicBoolean();
    var token = new CancellationToken(parent::get);
    var calls = new AtomicInteger();
    token.onCancel(calls::incrementAndGet);
    parent.set(true);
    assertTrue(token.isCancelled());
    assertEquals(0, calls.get());
    var runtime = new IllegalArgumentException("inline");
    assertSame(
        runtime,
        assertThrows(
            RuntimeException.class,
            () ->
                token.onCancel(
                    () -> {
                      calls.incrementAndGet();
                      throw runtime;
                    })));
    var fatal = new AssertionError("inline fatal");
    assertSame(
        fatal,
        assertThrows(
            Error.class,
            () ->
                token.onCancel(
                    () -> {
                      calls.incrementAndGet();
                      throw fatal;
                    })));
    assertEquals(2, calls.get());
    assertTrue(token.cancel());
    assertEquals(3, calls.get());
    parent.set(false);
    assertTrue(token.isCancelled());
  }

  @Test
  void parentFailureDuringRegistrationRetiresOnlyTheAdmittedWaiter() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine)) {
      var error = new AssertionError("registration parent");
      var token =
          new CancellationToken(
              () -> {
                if (world.inFlightChunks() > 0) throw error;
                return false;
              });
      var failed = world.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      assertSame(
          error,
          assertThrows(ExecutionException.class, () -> failed.get(3, TimeUnit.SECONDS)).getCause());
      assertEquals(0, world.inFlightChunks());
      assertEquals(0, engine.metrics().queued());
      assertTrue(
          world.generateChunkAsync(new ChunkPos(0, 0, 0)).get(3, TimeUnit.SECONDS).isEmpty());
    }
  }

  @Test
  void parentFailureDuringGenerationDoesNotStopTheSharedPeer() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var held = hold(engine)) {
      var error = new AssertionError("generation parent");
      var armed = new AtomicBoolean();
      var token =
          new CancellationToken(
              () -> {
                if (armed.get()) throw error;
                return false;
              });
      var bad = world.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      assertEquals(1, world.sharedRequests());
      armed.set(true);
      held.close();
      assertSame(
          error,
          assertThrows(ExecutionException.class, () -> bad.get(3, TimeUnit.SECONDS)).getCause());
      assertTrue(peer.get(3, TimeUnit.SECONDS).isEmpty());
      assertEquals(1, engine.metrics().completed());
      assertEquals(0, engine.metrics().stagingBytes());
      assertEquals(0, world.inFlightChunks());
    }
  }

  @Test
  void terminalParentFailureCannotStrandAnotherSubscriberAfterGenerationSucceeded()
      throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var held = hold(engine)) {
      var error = new AssertionError("terminal parent");
      long baseline = engine.metrics().completed();
      var token =
          new CancellationToken(
              () -> {
                if (engine.metrics().completed() > baseline) throw error;
                return false;
              });
      var bad = world.generateChunkAsync(new ChunkPos(0, 0, 0), token);
      var peer = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      held.close();
      assertSame(
          error,
          assertThrows(ExecutionException.class, () -> bad.get(3, TimeUnit.SECONDS)).getCause());
      var snapshot = peer.get(3, TimeUnit.SECONDS);
      assertTrue(snapshot.isEmpty());
      assertEquals(1, engine.metrics().completed());
      assertEquals(0, engine.metrics().stagingBytes());
      assertEquals(0, world.inFlightChunks());
      assertTrue(token.cancel());
      assertSame(snapshot, peer.get(3, TimeUnit.SECONDS));
    }
  }
}
