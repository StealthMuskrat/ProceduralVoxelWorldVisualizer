package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred host-failure identity and retirement contracts for asynchronous publication. */
class RequestAsyncFailureTest {
  private static Throwable unreadable(String kind, AtomicInteger reads) {
    return switch (kind) {
      case "ERROR" -> new AssertionError() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      case "CANCELLATION" -> new CancellationException() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      case "COMPLETION" -> new CompletionException(null, new IllegalStateException("original cause")) {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      case "CHECKED" -> new Exception() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
      default -> new IllegalStateException() {
        public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
        public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
      };
    };
  }

  @SuppressWarnings("unchecked")
  private static <E extends Throwable> String raise(Throwable failure) throws E { throw (E) failure; }

  @Test
  void supplierFaultsRetireOnceAndPublishOriginalCausesWithoutHostTextForInlineAndDefaultExecutors() throws Exception {
    for (String kind : List.of("RUNTIME", "ERROR", "CANCELLATION", "COMPLETION", "CHECKED"))
      for (boolean background : new boolean[] {false, true}) {
        var reads = new AtomicInteger(); var retired = new AtomicInteger(); var failure = unreadable(kind, reads);
        var future = new RequestFuture<String>(retired::incrementAndGet);
        var observed = future.handle((value, error) -> { assertEquals(1, retired.get()); assertNull(value); return error; });
        if (background) assertSame(future, future.completeAsync(() -> RequestAsyncFailureTest.<RuntimeException>raise(failure)));
        else assertSame(future, future.completeAsync(() -> RequestAsyncFailureTest.<RuntimeException>raise(failure), Runnable::run));
        var outcome = assertInstanceOf(CompletionException.class, observed.get(3, TimeUnit.SECONDS));
        if (kind.equals("COMPLETION")) assertSame(failure, outcome); else assertSame(failure, outcome.getCause());
        assertFalse(future.isCancelled()); assertFalse(future.complete("late")); assertEquals(1, retired.get());
        assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
      }
  }

  @Test
  void retirementOnlyFaultDiscardsSupplierSuccessAndPreservesItsOriginalCauseWithoutText() throws Exception {
    for (String kind : List.of("RUNTIME", "ERROR", "CANCELLATION", "COMPLETION")) {
      var reads = new AtomicInteger(); var retired = new AtomicInteger(); var failure = unreadable(kind, reads);
      var future = new RequestFuture<String>(() -> { retired.incrementAndGet(); RequestAsyncFailureTest.<RuntimeException>raise(failure); });
      var observed = future.handle((value, error) -> { assertNull(value); assertEquals(1, retired.get()); return error; });
      future.completeAsync(() -> "unpublished", Runnable::run);
      var outcome = assertInstanceOf(CompletionException.class, observed.get(3, TimeUnit.SECONDS));
      assertSame(failure, outcome.getCause()); assertNotSame(failure, outcome);
      assertFalse(future.isCancelled()); assertEquals(0, reads.get()); assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void combinedSupplierAndRetirementFaultsPreservePriorityAndNeverMutateEitherHostThrowable() throws Exception {
    for (boolean shared : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var retired = new AtomicInteger(); var requested = unreadable("ERROR", reads);
      var cleanup = shared ? requested : unreadable("RUNTIME", reads);
      var future = new RequestFuture<String>(() -> { retired.incrementAndGet(); RequestAsyncFailureTest.<RuntimeException>raise(cleanup); });
      var observed = future.handle((value, error) -> error);
      future.completeAsync(() -> RequestAsyncFailureTest.<RuntimeException>raise(requested), Runnable::run);
      var outcome = assertInstanceOf(CompletionException.class, observed.get(3, TimeUnit.SECONDS));
      assertSame(requested, outcome.getCause());
      assertArrayEquals(shared ? new Throwable[0] : new Throwable[] {cleanup}, outcome.getSuppressed());
      assertEquals(1, retired.get()); assertEquals(0, reads.get());
      assertEquals(0, requested.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
    }
  }

  @Test
  void runningSupplierThatLosesPublicationDoesNotReadFaultTextOrRetireTwice() throws Exception {
    var reads = new AtomicInteger(); var retired = new AtomicInteger(); var callbacks = new AtomicInteger();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var queued = new AtomicReference<Runnable>();
    var failure = unreadable("ERROR", reads); var future = new RequestFuture<String>(retired::incrementAndGet);
    var observed = future.handle((value, error) -> { callbacks.incrementAndGet(); assertNull(error); assertEquals(1, retired.get()); return value; });
    future.completeAsync(() -> {
      entered.countDown();
      try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
      return RequestAsyncFailureTest.<RuntimeException>raise(failure);
    }, queued::set);
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var publisher = tasks.submit(() -> queued.get().run());
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS)); assertTrue(future.complete("winner"));
        assertEquals("winner", observed.get(3, TimeUnit.SECONDS)); release.countDown(); publisher.get(3, TimeUnit.SECONDS);
        assertEquals("winner", future.join()); assertEquals(1, retired.get()); assertEquals(1, callbacks.get()); assertEquals(0, reads.get());
        assertEquals(0, failure.getSuppressed().length);
      } finally { release.countDown(); }
    }
  }

  @Test
  void mutableSupplierMessageIsReservedForTheExplicitHostReadAfterPublication() {
    var reads = new AtomicInteger(); var retired = new AtomicInteger();
    var failure = new RuntimeException() { public String getMessage() { return "message-" + reads.incrementAndGet(); } };
    var future = new RequestFuture<String>(retired::incrementAndGet);
    future.completeAsync(() -> { throw failure; }, Runnable::run);
    var observed = assertThrows(CompletionException.class, future::join);
    assertSame(failure, observed.getCause()); assertNull(observed.getMessage()); assertEquals(0, reads.get());
    assertEquals("message-1", failure.getMessage()); assertEquals(1, reads.get()); assertEquals(1, retired.get());
  }
}
