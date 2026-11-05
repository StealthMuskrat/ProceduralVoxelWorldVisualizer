package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred exact ticket/Future observation and complete legacy-success ownership. */
class InspectorObservationOwnershipTest {
  private static final long TIMEOUT = 10;
  private static final BlockPos POINT = ReadyInspectorTaskTest.POINT;
  private enum Observer { COMPLETED, FAILURE, SUCCESS }

  private static Object observe(InspectorTask task, InspectorRequests.Request request, Observer observer) {
    return switch (observer) {
      case COMPLETED -> task.completed();
      case FAILURE -> task.failure();
      case SUCCESS -> task.finishSuccessful(request);
    };
  }
  private static void unobserved(Object value, Observer observer) {
    if (observer == Observer.FAILURE) assertNull(value); else assertEquals(false, value);
  }
  private static void await(CountDownLatch latch) {
    boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try { assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)); return; }
      catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }
  private static FutureTask<Void> readyFuture() {
    var future = new FutureTask<Void>(() -> null); future.run(); return future;
  }

  @Test
  void realPreparedAndDeliveredTraceCannotFinishSuccessfullyBeforeWorkerReturn() throws Exception {
    var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
    try (var worker = Executors.newSingleThreadExecutor(); var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      var result = ReadyInspectorTaskTest.result(request); var ui = new LinkedBlockingQueue<Runnable>();
      var future = new FutureTask<Void>(() -> {
        assertTrue(task.publish(ticket, result)); ui.add(() -> task.delivered(ticket)); entered.countDown(); await(release); return null;
      });
      task.attach(request, future); worker.execute(future);
      try {
        assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
        assertTrue(task.ready()); assertFalse(task.completed()); assertFalse(task.finishSuccessful(request)); assertNull(task.failure());
        assertNull(task.accept(ticket, () -> true)); assertSame(ticket, task.ticket()); assertEquals(POINT, task.coordinate());
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        assertSame(result, task.accept(ticket, () -> true)); callback.run(); assertNull(task.accept(ticket, () -> true)); assertFalse(task.pending());
      } finally { release.countDown(); }
    }
  }

  @Test
  void lateRealPostingFailureOverridesPreparedSuccessWithoutPrematureLegacyConsumption() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var failure = new AssertionError("late posting");
    try (var worker = Executors.newSingleThreadExecutor(); var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      var future = new FutureTask<Void>(() -> {
        task.publish(ticket, ReadyInspectorTaskTest.result(request)); ui.add(() -> task.delivered(ticket)); await(release); throw failure;
      });
      task.attach(request, future); worker.execute(future);
      try {
        var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
        assertFalse(task.finishSuccessful(request)); assertTrue(task.pending()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        assertSame(failure, task.failure()); assertFalse(task.finishSuccessful(request)); assertSame(failure, task.accept(ticket, () -> true).failure());
        callback.run(); assertNull(task.accept(ticket, () -> true)); assertFalse(task.pending());
      } finally { release.countDown(); }
    }
  }

  @Test
  void allObserversLeaveUnfinishedFutureUnreadEvenAfterDelivery() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket(); var reads = new AtomicInteger();
      var future = new FutureTask<Void>(() -> null) {
        public Void get() throws InterruptedException, ExecutionException { reads.incrementAndGet(); return super.get(); }
      };
      task.attach(request, future); var value = ReadyInspectorTaskTest.result(request); task.publish(ticket, value); task.delivered(ticket);
      for (var observer : Observer.values()) { unobserved(observe(task, request, observer), observer); assertSame(ticket, task.ticket()); }
      assertNull(task.accept(ticket, () -> true)); assertEquals(0, reads.get()); future.run(); assertSame(value, task.accept(ticket, () -> true));
    }
  }

  @Test
  void acceptanceCannotUseOldTerminalReadForSameTicketPendingFuture() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      var next = new FutureTask<Void>(() -> null); var value = ReadyInspectorTaskTest.result(request);
      task.attach(request, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { task.attach(request, next); task.publish(ticket, value); task.delivered(ticket); return null; }
      });
      assertNull(task.accept(ticket, () -> true)); assertTrue(task.current(ticket)); assertTrue(task.ready()); assertFalse(next.isCancelled());
      next.run(); assertSame(value, task.accept(ticket, () -> true));
    }
  }

  @Test
  void acceptanceCannotConsumeEvenAnAlreadyCompleteSameTicketFutureReplacement() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, true); task.begin(request, null); var ticket = task.ticket(); var value = InspectorTask.Result.miss();
      task.attach(request, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { task.attach(request, readyFuture()); task.publish(ticket, value); task.delivered(ticket); return null; }
      });
      assertNull(task.accept(ticket, () -> true)); assertTrue(task.current(ticket)); assertSame(value, task.accept(ticket, () -> true));
    }
  }

  @Test
  void ownershipCallbackFutureReplacementCannotBeConsumedOrFailedByItsPredecessor() {
    for (int outcome = 0; outcome < 3; outcome++) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); var ticket = ReadyInspectorTaskTest.prepared(task, request, ReadyInspectorTaskTest.result(request));
      var value = ReadyInspectorTaskTest.result(request); final int action = outcome;
      assertNull(task.accept(ticket, () -> {
        task.attach(request, readyFuture()); task.publish(ticket, value); task.delivered(ticket);
        if (action == 2) throw new AssertionError("obsolete callback"); return action == 1;
      }));
      assertTrue(task.current(ticket)); assertSame(value, task.accept(ticket, () -> true));
    }
  }

  @Test
  void statusAndGetCannotAttributeOldObservationsToSameTicketReplacementFuture() {
    for (var observer : Observer.values()) for (boolean status : new boolean[] {false, true}) for (boolean fault : new boolean[] {false, true})
      try (var task = new InspectorTask()) {
        var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
        var value = ReadyInspectorTaskTest.result(request); var changed = new AtomicBoolean();
        Runnable replace = () -> {
          if (changed.compareAndSet(false, true)) { task.attach(request, readyFuture()); task.publish(ticket, value); task.delivered(ticket); }
        };
        task.attach(request, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (status) { replace.run(); if (fault) throw new AssertionError("obsolete status"); } return true; }
          public Void get() { if (!status) { replace.run(); if (fault) throw new AssertionError("obsolete read"); } return null; }
        });
        unobserved(observe(task, request, observer), observer); assertTrue(task.current(ticket)); assertSame(value, task.accept(ticket, () -> true));
      }
  }

  @Test
  void statusAndGetCannotAttributeOldObservationsToNewTicketEvenForReusedRequestIdentity() {
    for (var observer : Observer.values()) for (boolean same : new boolean[] {false, true}) for (boolean fault : new boolean[] {false, true})
      try (var task = new InspectorTask()) {
        var requests = new InspectorRequests(); var request = requests.begin(1, false); task.begin(request, POINT); var old = task.ticket();
        var next = same ? request : requests.begin(1, false); var value = ReadyInspectorTaskTest.result(next); var ticket = new AtomicReference<InspectorTask.Ticket>();
        task.attach(request, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            ticket.set(ReadyInspectorTaskTest.prepared(task, next, value)); if (fault) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        unobserved(observe(task, request, observer), observer); assertFalse(task.current(old)); assertTrue(task.current(ticket.get()));
        assertSame(value, task.accept(ticket.get(), () -> true));
      }
  }

  @Test
  void closureDuringTerminalObservationCannotReportCompletionFailureOrSuccess() {
    for (var observer : Observer.values()) for (boolean status : new boolean[] {false, true})
      try (var task = new InspectorTask()) {
        var request = new InspectorRequests().begin(1, false); task.begin(request, POINT);
        task.attach(request, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (status) task.close(); return true; }
          public Void get() { if (!status) task.close(); throw new AssertionError("retired read"); }
        });
        unobserved(observe(task, request, observer), observer); assertFalse(task.pending()); assertNull(task.ticket());
      }
  }

  @Test
  void legacySuccessConsumesOnlyNormalCompleteWorkAndCannotReplayIt() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var future = readyFuture(); task.attach(request, future);
      assertTrue(task.finishSuccessful(request)); assertFalse(task.finishSuccessful(request)); assertFalse(future.isCancelled()); assertFalse(task.pending());
    }
  }

  @Test
  void legacySuccessCannotTreatAnUnattachedTaskAsCompleteWhileExplicitFinishStillRetiresIt() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      assertFalse(task.completed()); assertNull(task.failure()); assertFalse(task.finishSuccessful(request)); assertTrue(task.current(ticket));
      assertTrue(task.finish(request)); assertFalse(task.pending());
    }
  }

  @Test
  void currentTerminalFaultIdentityAndInterruptedStatusStillReachOwnedAcceptance() {
    for (boolean interrupted : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket();
      var failure = new AssertionError("current read");
      task.attach(request, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() throws InterruptedException { if (interrupted) throw new InterruptedException("current read"); throw failure; }
      });
      assertTrue(task.completed()); assertEquals(interrupted, Thread.currentThread().isInterrupted());
      var found = task.failure(); if (interrupted) assertInstanceOf(InterruptedException.class, found); else assertSame(failure, found);
      assertFalse(task.finishSuccessful(request)); assertTrue(task.current(ticket)); var value = task.accept(ticket, () -> true);
      assertNotNull(value); if (interrupted) assertInstanceOf(InterruptedException.class, value.failure()); else assertSame(failure, value.failure());
      assertFalse(task.pending());
    } finally { Thread.interrupted(); }
  }

  @Test
  void beginCannotReopenAnOldIntentAfterCancellationCallbackCompletesOrCancelsItsSuccessor() {
    for (boolean consume : new boolean[] {false, true}) try (var task = new InspectorTask()) {
      var requests = new InspectorRequests(); var request = requests.begin(1, false); task.begin(request, POINT);
      var next = requests.begin(1, false); var value = ReadyInspectorTaskTest.result(next); var accepted = new AtomicReference<InspectorTask.Result>();
      task.attach(request, new FutureTask<Void>(() -> null) {
        public boolean cancel(boolean interrupt) {
          var ticket = ReadyInspectorTaskTest.prepared(task, next, value);
          if (consume) accepted.set(task.accept(ticket, () -> true)); else task.cancel(); return super.cancel(interrupt);
        }
      });
      task.begin(requests.begin(1, false), POINT); assertFalse(task.pending()); assertNull(task.ticket());
      assertEquals(consume, accepted.get() == value); assertNull(task.request()); assertNull(task.coordinate());
    }
  }

  @Test
  void cancellationOrClosureOfRealHeldPayloadSuppressesItsLaterFailureAndDelivery() throws Exception {
    for (boolean close : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var task = new InspectorTask()) {
        var request = new InspectorRequests().begin(1, false); task.begin(request, POINT); var ticket = task.ticket(); var value = ReadyInspectorTaskTest.result(request);
        var future = new FutureTask<Void>(() -> { task.publish(ticket, value); ui.add(() -> task.delivered(ticket)); await(release); throw new AssertionError("retired post"); });
        task.attach(request, future); worker.execute(future);
        try {
          var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); callback.run();
          if (close) task.close(); else task.cancel(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); assertNull(task.accept(ticket, () -> true)); assertNull(task.failure()); assertFalse(task.completed()); assertFalse(task.pending());
          assertFalse(task.publish(ticket, value));
        } finally { release.countDown(); }
      }
    }
  }
}
