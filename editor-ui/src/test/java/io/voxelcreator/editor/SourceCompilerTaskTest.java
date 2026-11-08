package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred CPU definitions against the production compiler owner and owned-source mailbox. */
class SourceCompilerTaskTest {
  private static final class Source implements AutoCloseable {
    final int[] samples;
    int closes;
    Runnable closing;
    Throwable failure;

    Source(int... samples) { this.samples = samples.clone(); }

    public void close() {
      closes++;
      if (closing != null) closing.run();
      if (failure != null) raise(failure);
    }
  }

  private static final class Job extends CompletableFuture<Void> {
    Runnable cancelling;
    Throwable failure;
    boolean before;
    int cancellations, interruptions;

    public boolean cancel(boolean interrupt) {
      cancellations++;
      if (interrupt) interruptions++;
      if (cancelling != null) cancelling.run();
      if (before && failure != null) raise(failure);
      boolean result = super.cancel(interrupt);
      if (failure != null) raise(failure);
      return result;
    }
  }

  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  private static Throwable fault(boolean error, AtomicInteger reads, Runnable reading) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
    };
  }

  private static void accept(
      SourceCompilerTask<Source> task, SourceCompilerTask.Ticket owner, Job job, Source source) {
    assertTrue(task.current(owner)); assertSame(job, task.future()); assertTrue(task.pending());
    assertNull(task.take()); job.complete(null); assertTrue(task.complete(owner, job));
    assertSame(source, task.take()); assertNull(task.take()); task.finish(owner);
    assertFalse(task.current(owner)); assertFalse(task.eligible(owner)); assertEquals("", task.key());
    assertEquals(0, source.closes); source.close(); assertEquals(1, source.closes);
  }

  @Test
  void preparedSourceWaitsForItsExactFutureAndTransfersOnce() {
    try (var task = new SourceCompilerTask<Source>()) {
      var owner = task.begin("world").ticket(); var job = new Job(); var other = new Job();
      var source = new Source(7, 8, 17, 18, 107, 108, 117, 118);
      assertNull(task.attach(owner, job)); assertTrue(task.publish(owner, source)); assertTrue(task.pendingResult());
      assertFalse(task.complete(owner, other)); assertSame(job, task.future());
      accept(task, owner, job, source);
      assertArrayEquals(new int[] {7, 8, 17, 18, 107, 108, 117, 118}, source.samples);
      var late = new Source(-1); assertFalse(task.publish(owner, late)); assertEquals(1, late.closes);
    }
  }

  @Test
  void inlinePublicationWaitsForSubmissionReturnAndCallerFailureNeedsExplicitSettlement() {
    try (var task = new SourceCompilerTask<Source>()) {
      var owner = task.begin("inline").ticket(); var source = new Source(7, 17, 107); var job = new Job();
      assertTrue(task.publish(owner, source)); assertTrue(task.pending()); assertNull(task.future());
      assertNull(task.take()); assertFalse(task.complete(owner, null)); task.finish(owner);
      assertTrue(task.current(owner)); assertEquals(0, source.closes);
      assertNull(task.attach(owner, job)); accept(task, owner, job, source);
      var failed = task.begin("copy failed").ticket(); var failureResult = new Source(-99);
      task.publish(failed, failureResult); assertNull(task.take()); assertTrue(task.noSubmission(failed));
      assertFalse(task.noSubmission(failed)); assertFalse(task.pending()); assertSame(failureResult, task.take());
      task.finish(failed); assertEquals(0, failureResult.closes); failureResult.close();
    }
  }

  @Test
  void replacementRevokesMailboxAndFutureBeforeEitherCleanupCallback() {
    for (boolean error : new boolean[] {false, true}) for (boolean before : new boolean[] {false, true}) {
      try (var task = new SourceCompilerTask<Source>()) {
        var owner = task.begin("old").ticket(); var job = new Job(); var old = new Source(-7);
        var settled = new AtomicInteger(); var reads = new AtomicInteger();
        old.closing = () -> {
          assertFalse(task.eligible(owner)); assertNull(task.future()); assertFalse(task.pendingResult());
          settled.incrementAndGet();
        };
        old.failure = fault(!error, reads, () -> {});
        job.cancelling = () -> {
          assertFalse(task.current(owner)); assertFalse(task.eligible(owner)); assertNull(task.future());
          assertEquals(1, old.closes); settled.incrementAndGet();
        };
        job.failure = fault(error, reads, () -> {}); job.before = before;
        task.attach(owner, job); task.publish(owner, old);
        var opening = task.begin("new"); var next = opening.ticket();
        assertEquals(2, settled.get()); assertSame(job.failure, opening.cancellation());
        assertEquals(!before, job.isCancelled()); assertEquals(1, job.cancellations); assertEquals(1, job.interruptions);
        assertEquals(0, reads.get()); assertEquals(0, job.failure.getSuppressed().length);
        assertEquals(0, old.failure.getSuppressed().length); assertEquals("new", task.key());
        var fresh = new Source(23, 24, 33, 34); var newJob = new Job(); task.attach(next, newJob);
        assertTrue(task.publish(next, fresh)); accept(task, next, newJob, fresh);
        assertArrayEquals(new int[] {23, 24, 33, 34}, fresh.samples);
      }
    }
  }

  @Test
  void sourceDisposalReentryKeepsItsReplacementOrClosureBeforeCapturedFutureCancellation() {
    for (boolean close : new boolean[] {false, true}) try (var task = new SourceCompilerTask<Source>()) {
      var owner = task.begin("same").ticket(); var oldJob = new Job(); var old = new Source(-7);
      var successor = new AtomicReference<SourceCompilerTask.Ticket>(); var newJob = new Job(); var fresh = new Source(23);
      old.closing = () -> {
        if (close) task.close();
        else {
          successor.set(task.begin("same").ticket()); task.attach(successor.get(), newJob);
          task.publish(successor.get(), fresh);
        }
        throw new AssertionError("old source cleanup");
      };
      task.attach(owner, oldJob); task.publish(owner, old);
      var opening = task.begin("interrupted opening");
      assertNull(opening.ticket()); assertNull(opening.cancellation()); assertEquals(1, old.closes);
      assertEquals(1, oldJob.cancellations); assertEquals(0, newJob.cancellations);
      if (close) { assertNull(task.ticket()); assertFalse(task.eligible(owner)); }
      else { assertSame(successor.get(), task.ticket()); accept(task, successor.get(), newJob, fresh); }
    }
  }

  @Test
  void futureCancellationReentryCannotAuthorizeAStaleReturnedSubmission() {
    for (boolean close : new boolean[] {false, true}) try (var task = new SourceCompilerTask<Source>()) {
      var old = task.begin("same").ticket(); var oldJob = new Job(); var newJob = new Job();
      var successor = new AtomicReference<SourceCompilerTask.Ticket>(); var fresh = new Source(23);
      oldJob.cancelling = () -> {
        if (close) task.close();
        else {
          successor.set(task.begin("same").ticket()); task.attach(successor.get(), newJob);
          task.publish(successor.get(), fresh);
        }
      };
      oldJob.failure = new AssertionError("cancel after callback"); task.attach(old, oldJob);
      var opening = task.begin("same"); assertSame(oldJob.failure, opening.cancellation());
      assertFalse(task.current(opening.ticket())); var returned = new Job();
      assertNull(task.attach(opening.ticket(), returned)); assertEquals(1, returned.cancellations);
      var stale = new Source(-7); assertFalse(task.publish(opening.ticket(), stale)); assertEquals(1, stale.closes);
      assertEquals(1, oldJob.cancellations); assertEquals(0, newJob.cancellations);
      if (!close) accept(task, successor.get(), newJob, fresh);
    }
  }

  @Test
  void stoppingDetachesBeforeSourceAndFutureCallbacksAndPreservesTheirSuccessor() {
    for (boolean duringSource : new boolean[] {false, true}) try (var task = new SourceCompilerTask<Source>()) {
      var old = task.begin("old").ticket(); var oldJob = new Job(); var source = new Source(-7);
      var next = new AtomicReference<SourceCompilerTask.Ticket>(); var newJob = new Job(); var fresh = new Source(23);
      var entered = new AtomicInteger();
      Runnable replacing = () -> {
        assertFalse(task.current(old)); assertNull(task.future()); assertFalse(task.eligible(old));
        next.set(task.begin("new").ticket()); task.attach(next.get(), newJob); task.publish(next.get(), fresh);
        entered.incrementAndGet();
      };
      if (duringSource) source.closing = replacing; else oldJob.cancelling = replacing;
      source.failure = new AssertionError("source cleanup"); oldJob.failure = new IllegalStateException("future cleanup");
      task.attach(old, oldJob); task.publish(old, source); var retired = task.stop();
      assertEquals(1, entered.get()); assertEquals(1, source.closes); assertEquals(1, oldJob.cancellations);
      assertSame(oldJob.failure, retired.cancellation()); assertFalse(task.current(retired.generation()));
      assertEquals(0, newJob.cancellations); accept(task, next.get(), newJob, fresh);
    }
  }

  @Test
  void staleFutureCancellationMayReenterAgainWithoutClearingTheNewestOwner() {
    try (var task = new SourceCompilerTask<Source>()) {
      var old = task.begin("old").ticket(); var middle = task.begin("middle").ticket(); var middleJob = new Job();
      task.attach(middle, middleJob); var returned = new Job(); var newest = new AtomicReference<SourceCompilerTask.Ticket>();
      var fresh = new Source(23, 33, 123); var newJob = new Job();
      returned.cancelling = () -> {
        newest.set(task.begin("newest").ticket()); task.attach(newest.get(), newJob); task.publish(newest.get(), fresh);
      };
      returned.failure = new AssertionError("stale submission cancellation");
      assertSame(returned.failure, task.attach(old, returned)); assertEquals(1, returned.cancellations);
      assertEquals(1, middleJob.cancellations); assertEquals(0, newJob.cancellations);
      accept(task, newest.get(), newJob, fresh); assertArrayEquals(new int[] {23, 33, 123}, fresh.samples);
    }
  }

  @Test
  void guardedCancellationTextCannotReportAcrossReplacementOrClose() {
    for (boolean close : new boolean[] {false, true}) try (var task = new SourceCompilerTask<Source>()) {
      var old = task.begin("old").ticket(); var job = new Job(); var reads = new AtomicInteger();
      var next = new AtomicReference<SourceCompilerTask.Ticket>(); var fresh = new Source(23); var newJob = new Job();
      job.failure = fault(true, reads, () -> {
        if (close) task.close();
        else { next.set(task.begin("next").ticket()); task.attach(next.get(), newJob); task.publish(next.get(), fresh); }
      });
      task.attach(old, job); var opening = task.begin("replacement"); var events = new ArrayList<String>();
      PreviewNotifications.report(opening.cancellation(), () -> task.current(opening.ticket()), events::add);
      assertEquals(1, reads.get()); assertTrue(events.isEmpty()); assertEquals(0, job.failure.getSuppressed().length);
      PreviewNotifications.report(opening.cancellation(), () -> task.current(opening.ticket()), events::add);
      assertEquals(1, reads.get()); if (!close) accept(task, next.get(), newJob, fresh);
    }
  }

  @Test
  void admissionRollbackClosesInlinePreparedSourceAndLeavesExplicitRetryHealthy() {
    try (var task = new SourceCompilerTask<Source>()) {
      var opening = task.begin("inline").ticket(); var inline = new Source(-7);
      assertTrue(task.publish(opening, inline)); var retired = task.stop();
      assertTrue(task.current(retired.generation())); assertNull(retired.cancellation());
      assertEquals(1, inline.closes); assertFalse(task.eligible(opening)); assertFalse(task.pendingResult());
      var retry = task.begin("inline").ticket(); assertNotSame(opening, retry);
      var job = new Job(); var fresh = new Source(23); task.attach(retry, job); task.publish(retry, fresh);
      accept(task, retry, job, fresh);
    }
  }

  @Test
  void terminalFailureCanReplacePreparedSourceWithoutDestroyingACleanupAdmittedSuccessor() {
    try (var task = new SourceCompilerTask<Source>()) {
      var old = task.begin("old").ticket(); var oldJob = new Job(); var prepared = new Source(-7);
      var next = new AtomicReference<SourceCompilerTask.Ticket>(); var newJob = new Job(); var fresh = new Source(23);
      prepared.closing = () -> {
        next.set(task.begin("next").ticket()); task.attach(next.get(), newJob); task.publish(next.get(), fresh);
      };
      task.attach(old, oldJob); task.publish(old, prepared); var primary = new AssertionError("worker finish");
      oldJob.completeExceptionally(primary); var completion = BackgroundFailure.completed(oldJob);
      assertSame(primary, completion.failure()); assertTrue(task.complete(old, oldJob));
      var failedResult = new Source(-99); task.publish(old, failedResult);
      assertEquals(1, prepared.closes); assertEquals(1, failedResult.closes); assertFalse(task.current(old));
      assertFalse(task.complete(old, oldJob)); assertEquals(0, newJob.cancellations); accept(task, next.get(), newJob, fresh);
      assertEquals(0, primary.getSuppressed().length);
    }
  }

  @Test
  void closedCompilerRejectsARealLateWorkerEvenWhenInterruptionIsIgnored() throws Exception {
    var task = new SourceCompilerTask<Source>();
    try (var workers = Executors.newSingleThreadExecutor()) {
      var owner = task.begin("held").ticket(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      var late = new Source(7, 8, 17, 18); var accepted = new AtomicReference<Boolean>();
      var worker = workers.submit(() -> {
        entered.countDown(); boolean waiting = true;
        while (waiting) {
          try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker release"); waiting = false; }
          catch (InterruptedException ignored) { Thread.interrupted(); }
        }
        accepted.set(task.publish(owner, late));
      });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS)); var job = new Job();
        job.cancelling = () -> worker.cancel(true); job.failure = new AssertionError("cancel"); task.attach(owner, job);
        assertSame(job.failure, assertThrows(AssertionError.class, task::close));
        assertFalse(task.eligible(owner)); assertFalse(task.pending()); assertFalse(task.pendingResult());
        assertThrows(IllegalStateException.class, () -> task.begin("closed")); task.close();
        release.countDown(); workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals(Boolean.FALSE, accepted.get()); assertEquals(1, late.closes); assertNull(task.take());
        assertEquals(1, job.cancellations); assertArrayEquals(new int[] {7, 8, 17, 18}, late.samples);
      } finally { release.countDown(); task.close(); }
    }
  }
}
