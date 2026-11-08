package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred bounded delivery, nested posting, closure and producer-concurrency contracts. */
class UiDispatchQueueTest {
  private int drain(UiDispatchQueue queue) {
    var failures = new ArrayList<Throwable>();
    int delivered = queue.drain(failures::add);
    assertTrue(failures.isEmpty(), () -> "Unexpected callback failures: " + failures);
    return delivered;
  }

  @Test
  void fifoBudgetDefersNestedPostsAndNestedDrainsWithoutDroppingAcceptedWork() {
    var queue = new UiDispatchQueue(2, 1);
    var seen = new ArrayList<String>();
    queue.post(() -> {
      seen.add("first");
      queue.post(() -> seen.add("nested"));
      assertEquals(0, drain(queue));
    });
    queue.post(() -> seen.add("second"));
    assertFalse(queue.offer(() -> fail("Full queue accepted extra work")));
    assertThrows(RejectedExecutionException.class, () -> queue.post(() -> fail("Rejected callback ran")));
    assertEquals(1, drain(queue));
    assertEquals(List.of("first"), seen);
    assertEquals(2, queue.size());
    assertEquals(1, drain(queue));
    assertEquals(List.of("first", "second"), seen);
    assertEquals(1, drain(queue));
    assertEquals(List.of("first", "second", "nested"), seen);
    assertEquals(0, drain(queue));
  }

  @Test
  void oneDrainOnlyRunsItsInitialBatchAndFailureReportingCannotStrandPeers() {
    var queue = new UiDispatchQueue(8, 8);
    var seen = new ArrayList<String>();
    var faults = new ArrayList<Throwable>();
    var first = new IllegalStateException("first");
    var second = new AssertionError("second");
    queue.post(() -> { queue.post(() -> seen.add("later")); throw first; });
    queue.post(() -> { throw second; });
    queue.post(() -> seen.add("healthy"));
    assertEquals(3, queue.drain(failure -> {
      faults.add(failure);
      throw new AssertionError("reporting failed");
    }));
    assertEquals(List.of(first, second), faults);
    assertEquals(List.of("healthy"), seen);
    assertEquals(1, queue.size());
    assertEquals(1, drain(queue));
    assertEquals(List.of("healthy", "later"), seen);
  }

  @Test
  void closureInsideDeliveryDiscardsRemainingBatchAndQueuedWorkAndRejectsPosting() {
    var queue = new UiDispatchQueue(3, 2);
    var calls = new AtomicInteger();
    queue.post(() -> { calls.incrementAndGet(); queue.close(); });
    queue.post(() -> fail("Closed batch callback ran"));
    queue.post(() -> fail("Closed queued callback ran"));
    assertEquals(1, drain(queue));
    assertEquals(1, calls.get());
    assertTrue(queue.isEmpty());
    assertFalse(queue.offer(calls::incrementAndGet));
    assertThrows(RejectedExecutionException.class, () -> queue.post(calls::incrementAndGet));
    queue.close();
    assertEquals(0, drain(queue));
  }

  @Test
  void simultaneousProducersRespectCapacityAndCallbacksRunOnlyOnTheDrainThread() throws Exception {
    var queue = new UiDispatchQueue(8, 3);
    var accepted = new AtomicInteger();
    var executed = new AtomicInteger();
    var wrongThread = new AtomicBoolean();
    var caller = Thread.currentThread();
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var attempts = new ArrayList<Future<?>>();
      for (int i = 0; i < 4; i++) attempts.add(pool.submit(() -> {
        try {
          assertTrue(start.await(5, TimeUnit.SECONDS));
          for (int j = 0; j < 32; j++)
            if (queue.offer(() -> {
              wrongThread.compareAndSet(false, Thread.currentThread() != caller);
              executed.incrementAndGet();
            })) accepted.incrementAndGet();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError(interrupted);
        }
      }));
      start.countDown();
      for (var attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
    }
    assertEquals(8, accepted.get());
    assertEquals(0, executed.get());
    assertEquals(3, drain(queue));
    assertEquals(3, drain(queue));
    assertEquals(2, drain(queue));
    assertEquals(8, executed.get());
    assertFalse(wrongThread.get());
    queue.close();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var attempts = new ArrayList<Future<?>>();
      for (int i = 0; i < 16; i++) attempts.add(pool.submit(() ->
          assertThrows(RejectedExecutionException.class, () -> queue.post(executed::incrementAndGet))));
      for (var attempt : attempts) attempt.get(5, TimeUnit.SECONDS);
    }
    assertTrue(queue.isEmpty());
    assertEquals(8, executed.get());
  }
}
