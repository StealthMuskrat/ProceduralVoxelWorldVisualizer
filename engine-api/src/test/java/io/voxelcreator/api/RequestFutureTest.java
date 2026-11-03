package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred retirement-before-publication contracts; no engine or native dependencies. */
class RequestFutureTest {
  @Test
  void everySynchronousTerminalOperationRetiresBeforeDependentCallbacks() {
    List<Consumer<CompletableFuture<String>>> operations =
        List.of(
            f -> f.complete("value"),
            f -> f.completeExceptionally(new IllegalStateException("failure")),
            f -> f.cancel(false),
            f -> f.obtrudeValue("value"),
            f -> f.obtrudeException(new IllegalStateException("failure")),
            f -> f.completeAsync(() -> "value", Runnable::run),
            f ->
                f.completeAsync(
                    () -> {
                      throw new IllegalStateException("supplier");
                    },
                    Runnable::run));
    for (var operation : operations) {
      var retired = new AtomicInteger();
      var future = new RequestFuture<String>(retired::incrementAndGet);
      var observed = new AtomicInteger();
      future.whenComplete((value, failure) -> observed.set(retired.get()));
      operation.accept(future);
      assertTrue(future.isDone());
      assertEquals(1, observed.get());
      future.cancel(false);
      future.complete("ignored");
      future.obtrudeValue("forced");
      assertEquals("forced", future.join());
      assertEquals(1, retired.get());
    }
  }

  @Test
  void timeoutsRetireButTimedReadsDoNot() throws Exception {
    var retired = new AtomicInteger();
    var future = new RequestFuture<String>(retired::incrementAndGet);
    assertThrows(TimeoutException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
    assertEquals(0, retired.get());
    var observed = future.handle((value, failure) -> retired.get());
    assertSame(future, future.orTimeout(0, TimeUnit.NANOSECONDS));
    assertEquals(1, observed.get(3, TimeUnit.SECONDS));
    assertInstanceOf(
        TimeoutException.class, assertThrows(CompletionException.class, future::join).getCause());
    var fallback = new RequestFuture<String>(retired::incrementAndGet);
    var count = fallback.thenApply(value -> retired.get());
    assertSame(fallback, fallback.completeOnTimeout("fallback", 0, TimeUnit.NANOSECONDS));
    assertEquals(2, count.get(3, TimeUnit.SECONDS));
    assertEquals("fallback", fallback.join());
  }

  @Test
  void competingPublishersWaitForRetirementWithoutHoldingCallbacksUnderItsGate() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var retired = new AtomicInteger();
    var future =
        new RequestFuture<String>(
            () -> {
              retired.incrementAndGet();
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
              }
            });
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = tasks.submit(() -> future.complete("first"));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var second = tasks.submit(() -> future.complete("second"));
        assertFalse(future.isDone());
        release.countDown();
        boolean firstWon = first.get(3, TimeUnit.SECONDS);
        assertNotEquals(firstWon, second.get(3, TimeUnit.SECONDS));
        assertEquals(1, retired.get());
      } finally {
        release.countDown();
      }
      var callback = new RequestFuture<String>(() -> {});
      var nested =
          callback.thenRun(
              () -> {
                try {
                  tasks.submit(() -> callback.complete("ignored")).get(3, TimeUnit.SECONDS);
                } catch (Exception failure) {
                  throw new AssertionError(failure);
                }
              });
      callback.complete("value");
      nested.join();
    }
  }

  @Test
  void failedRetirementIsPublishedAndNeverMutatesTheRequestedFailure() {
    var cleanup = new AssertionError("cleanup");
    var requested = new IllegalStateException("requested");
    var future =
        new RequestFuture<String>(
            () -> {
              throw cleanup;
            });
    future.completeExceptionally(requested);
    var observed = assertThrows(CompletionException.class, future::join);
    assertSame(requested, observed.getCause());
    assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
    assertEquals(0, requested.getSuppressed().length);
    future.obtrudeException(requested);
    assertSame(requested, assertThrows(CompletionException.class, future::join).getCause());
  }

  @Test
  void cleanupFailureCannotPublishASuccessOrPretendCancellationSucceeded() {
    var failure = new AssertionError("cleanup");
    var success =
        new RequestFuture<String>(
            () -> {
              throw failure;
            });
    assertTrue(success.complete("unpublished"));
    assertSame(failure, assertThrows(CompletionException.class, success::join).getCause());
    var cancelled =
        new RequestFuture<String>(
            () -> {
              throw failure;
            });
    assertFalse(cancelled.cancel(true));
    assertFalse(cancelled.isCancelled());
    assertSame(failure, assertThrows(CompletionException.class, cancelled::join).getCause());
    var forced =
        new RequestFuture<String>(
            () -> {
              throw failure;
            });
    forced.obtrudeValue("unpublished");
    assertSame(failure, assertThrows(CompletionException.class, forced::join).getCause());
    forced.obtrudeValue("later forced");
    assertEquals("later forced", forced.join());
  }

  @Test
  void invalidArgumentsAndRejectedExecutorsLeaveOwnershipPending() {
    var retired = new AtomicInteger();
    var future = new RequestFuture<String>(retired::incrementAndGet);
    assertThrows(NullPointerException.class, () -> future.completeExceptionally(null));
    assertThrows(NullPointerException.class, () -> future.obtrudeException(null));
    assertThrows(NullPointerException.class, () -> future.completeAsync(null));
    assertThrows(NullPointerException.class, () -> future.completeAsync(() -> "value", null));
    assertThrows(
        RejectedExecutionException.class,
        () ->
            future.completeAsync(
                () -> "value",
                task -> {
                  throw new RejectedExecutionException();
                }));
    assertEquals(0, retired.get());
    assertFalse(future.isDone());
    future.complete(null);
    assertNull(future.join());
    assertEquals(1, retired.get());
  }

  @Test
  void queuedAsyncSupplierKeepsOwnershipUntilItPublishes() {
    var retired = new AtomicInteger();
    var queued = new AtomicReference<Runnable>();
    var future = new RequestFuture<String>(retired::incrementAndGet);
    var observed = future.thenApply(value -> retired.get());
    assertSame(future, future.completeAsync(() -> "value", queued::set));
    assertEquals(0, retired.get());
    assertFalse(future.isDone());
    queued.get().run();
    assertEquals("value", future.join());
    assertEquals(1, observed.join());
  }

  @Test
  void asyncSupplierIsSkippedAfterAnotherPublisherWins() {
    var retired = new AtomicInteger();
    var supplied = new AtomicInteger();
    var queued = new AtomicReference<Runnable>();
    var future = new RequestFuture<String>(retired::incrementAndGet);
    future.completeAsync(
        () -> {
          supplied.incrementAndGet();
          return "late";
        },
        queued::set);
    future.complete("winner");
    queued.get().run();
    assertEquals("winner", future.join());
    assertEquals(0, supplied.get());
    assertEquals(1, retired.get());
  }

  @Test
  void defaultAsyncSupplierAndCancellationFailuresKeepJdkSemantics() throws Exception {
    var retired = new AtomicInteger();
    var normal = new RequestFuture<String>(retired::incrementAndGet);
    normal.completeAsync(() -> "value");
    assertEquals("value", normal.get(3, TimeUnit.SECONDS));
    var cancellation = new CancellationException("supplier cancellation");
    var failed = new RequestFuture<String>(retired::incrementAndGet);
    failed.completeAsync(
        () -> {
          throw cancellation;
        },
        Runnable::run);
    assertFalse(failed.isCancelled());
    assertSame(cancellation, assertThrows(CompletionException.class, failed::join).getCause());
    assertEquals(2, retired.get());
  }

  @Test
  void dependentAndCopiedObserversCannotRetireTheAdmittedFuture() {
    var retired = new AtomicInteger();
    var future = new RequestFuture<String>(retired::incrementAndGet);
    future.copy().cancel(false);
    future.thenApply(value -> value).complete("local");
    future.minimalCompletionStage().toCompletableFuture().obtrudeValue("local");
    assertFalse(future.isDone());
    assertEquals(0, retired.get());
    future.complete("real");
    assertEquals("real", future.join());
    assertEquals(1, retired.get());
  }
}
