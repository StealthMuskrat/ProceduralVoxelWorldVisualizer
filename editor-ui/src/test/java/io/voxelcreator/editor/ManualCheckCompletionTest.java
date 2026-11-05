package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred admission/handoff completion and safe Future observation; no real update transport. */
class ManualCheckCompletionTest {
  private static final long TIMEOUT = 10;

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try {
        assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS), "handoff fixture timeout");
        return;
      } catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private static Runnable receipt(BlockingQueue<Runnable> ui) throws InterruptedException {
    var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS);
    assertNotNull(callback);
    return callback;
  }

  @Test
  void validationWaitsForARealWorkersHandoffToReturnBeforePublishingDiagnostics() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
    var errors = new ArrayList<String>();
    try (var state = ReadyValidationTest.state(); var worker = Executors.newSingleThreadExecutor()) {
      var project = ProjectIO.JSON.valueToTree(state.project()); var draft = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      try (var check = new ProjectValidationSession(state, worker, callback -> {
        ui.add(callback); await(release);
      }, p -> List.of(ReadyValidationTest.RESULT), errors::add)) {
        try {
          assertTrue(check.start()); var request = check.requestToken(); receipt(ui).run();
          check.acceptPrepared();
          assertEquals(ProjectValidationSession.Phase.RUNNING, check.view().phase());
          assertSame(request, check.requestToken()); assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues);
          assertEquals("Rejected edit", state.validationScope); assertTrue(errors.isEmpty());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          check.acceptPrepared(); assertEquals(ProjectValidationSession.Phase.READY, check.view().phase());
          assertEquals(List.of(ReadyValidationTest.RESULT), state.issues);
          assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); assertSame(draft, state.draft);
          assertEquals(revision, state.revision); assertEquals(history, state.document.historyBytes());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void validationHandoffFailureAfterAReceiptCannotReplacePriorDiagnostics() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var errors = new ArrayList<String>();
    try (var state = ReadyValidationTest.state(); var worker = Executors.newSingleThreadExecutor();
        var check = new ProjectValidationSession(state, worker, callback -> {
          ui.add(callback); await(release); throw new AssertionError("handoff after receipt");
        }, p -> List.of(), errors::add)) {
      try {
        check.start(); receipt(ui).run(); check.acceptPrepared();
        assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); assertTrue(errors.isEmpty());
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        check.acceptPrepared(); assertEquals(ProjectValidationSession.Phase.FAILED, check.view().phase());
        assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("handoff after receipt"));
      } finally { release.countDown(); }
    }
  }

  @Test
  void cancellingValidationWhileHandoffReturnsSuppressesItsLateFailure() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var errors = new ArrayList<String>();
    try (var state = ReadyValidationTest.state(); var worker = Executors.newSingleThreadExecutor();
        var check = new ProjectValidationSession(state, worker, callback -> {
          ui.add(callback); await(release); throw new AssertionError("retired handoff");
        }, p -> List.of(), errors::add)) {
      try {
        check.start(); var callback = receipt(ui); callback.run(); check.acceptPrepared(); check.cancel();
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        callback.run(); check.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.CANCELLED, check.view().phase());
        assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); assertTrue(errors.isEmpty());
      } finally { release.countDown(); }
    }
  }

  @Test
  void updatesWaitForARealWorkersHandoffReturnBeforeExposingReviewedLinks() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var errors = new ArrayList<String>();
    try (var worker = Executors.newSingleThreadExecutor();
        var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", worker, callback -> {
          ui.add(callback); await(release);
        }, (endpoint, origins) -> ReadyUpdateCheckTest.RELEASE, errors::add)) {
      try {
        check.start(); var request = check.requestToken(); receipt(ui).run(); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase());
        assertSame(request, check.requestToken()); assertNull(check.view().result()); assertTrue(errors.isEmpty());
        assertThrows(IllegalStateException.class, () -> check.copy(null, text -> fail("unfinished link copied")));
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.READY, check.view().phase());
        assertEquals(UpdateCheckSession.Comparison.NEWER, check.view().result().comparison());
      } finally { release.countDown(); }
    }
  }

  @Test
  void updateHandoffFailureAfterReceiptCannotExposeReleaseAndAllowsExplicitRetry() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var errors = new ArrayList<String>();
    var broken = new AtomicBoolean(true); var calls = new AtomicInteger();
    try (var worker = Executors.newSingleThreadExecutor();
        var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", worker, callback -> {
          ui.add(callback); await(release); if (broken.get()) throw new AssertionError("update handoff");
        }, (endpoint, origins) -> { calls.incrementAndGet(); return ReadyUpdateCheckTest.RELEASE; }, errors::add)) {
      try {
        check.start(); receipt(ui).run(); check.acceptPrepared(); assertNull(check.view().result());
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase()); assertNull(check.view().result());
        assertEquals(1, errors.size()); assertEquals(1, calls.get());
        broken.set(false); assertTrue(check.start()); receipt(ui).run();
        worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.READY, check.view().phase()); assertEquals(2, calls.get());
      } finally { release.countDown(); }
    }
  }

  @Test
  void cancellingUpdateWhileHandoffReturnsRetiresReceiptAndLateError() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var errors = new ArrayList<String>();
    try (var worker = Executors.newSingleThreadExecutor();
        var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", worker, callback -> {
          ui.add(callback); await(release); throw new AssertionError("retired update handoff");
        }, (endpoint, origins) -> ReadyUpdateCheckTest.RELEASE, errors::add)) {
      try {
        check.start(); var callback = receipt(ui); callback.run(); check.acceptPrepared(); check.cancel();
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        callback.run(); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.CANCELLED, check.view().phase());
        assertNull(check.view().result()); assertTrue(errors.isEmpty());
      } finally { release.countDown(); }
    }
  }

  @Test
  void validationCannotAcceptInsideHandoffOrBeforeSuccessfulExecutorReturn() {
    var owner = new AtomicReference<ProjectValidationSession>(); var observations = new AtomicInteger();
    try (var state = ReadyValidationTest.state();
        var check = new ProjectValidationSession(state, task -> {
          task.run(); owner.get().acceptPrepared();
          assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); observations.incrementAndGet();
        }, callback -> {
          callback.run(); owner.get().acceptPrepared();
          assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); observations.incrementAndGet();
        }, p -> List.of(ReadyValidationTest.RESULT), text -> fail(text))) {
      owner.set(check); assertTrue(check.start()); assertEquals(2, observations.get());
      assertEquals(ProjectValidationSession.Phase.RUNNING, check.view().phase()); check.acceptPrepared();
      assertEquals(List.of(ReadyValidationTest.RESULT), state.issues);
    }
  }

  @Test
  void updatesCannotAcceptInsideHandoffOrBeforeSuccessfulExecutorReturn() {
    var owner = new AtomicReference<UpdateCheckSession>(); var observations = new AtomicInteger();
    try (var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", task -> {
      task.run(); owner.get().acceptPrepared(); assertNull(owner.get().view().result()); observations.incrementAndGet();
    }, callback -> {
      callback.run(); owner.get().acceptPrepared(); assertNull(owner.get().view().result()); observations.incrementAndGet();
    }, (endpoint, origins) -> ReadyUpdateCheckTest.RELEASE, text -> fail(text))) {
      owner.set(check); assertTrue(check.start()); assertEquals(2, observations.get());
      assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase()); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.READY, check.view().phase());
    }
  }

  @Test
  void validationCapturedFailureAlsoWaitsForCompleteHandoffAndAdmission() {
    var owner = new AtomicReference<ProjectValidationSession>(); var errors = new ArrayList<String>();
    try (var state = ReadyValidationTest.state();
        var check = new ProjectValidationSession(state, Runnable::run, callback -> {
          callback.run(); owner.get().acceptPrepared(); assertTrue(errors.isEmpty());
        }, p -> { throw new AssertionError("captured validator"); }, errors::add)) {
      owner.set(check); assertTrue(check.start()); assertTrue(errors.isEmpty());
      assertEquals(ProjectValidationSession.Phase.RUNNING, check.view().phase()); check.acceptPrepared();
      assertEquals(ProjectValidationSession.Phase.FAILED, check.view().phase()); assertEquals(1, errors.size());
      assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues);
    }
  }

  @Test
  void updateCapturedFailureAlsoWaitsForCompleteHandoffAndAdmission() {
    var owner = new AtomicReference<UpdateCheckSession>(); var errors = new ArrayList<String>();
    try (var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", Runnable::run, callback -> {
      callback.run(); owner.get().acceptPrepared(); assertTrue(errors.isEmpty());
    }, (endpoint, origins) -> { throw new AssertionError("captured fetch"); }, errors::add)) {
      owner.set(check); assertTrue(check.start()); assertTrue(errors.isEmpty());
      assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase()); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase()); assertEquals(1, errors.size());
      assertNull(check.view().result());
    }
  }

  /** Replace only the task boundary; the original queued computation is explicitly retired. */
  private static void brokenFuture(Object owner, int mode) throws Exception {
    Field field = owner.getClass().getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false);
    field.set(owner, new FutureTask<Void>(() -> null) {
      public boolean isDone() {
        if (mode == 0) throw new AssertionError("future status"); return true;
      }
      public Void get() throws InterruptedException {
        if (mode == 2) throw new InterruptedException("future get interrupted");
        throw new AssertionError("future get");
      }
    });
  }

  @Test
  void validationContainsFutureStatusGetAndInterruptedReadFaultsWithoutChangingRows() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var errors = new ArrayList<String>(); var work = new ArrayDeque<Runnable>();
      try (var state = ReadyValidationTest.state();
          var check = new ProjectValidationSession(state, work::add, Runnable::run, p -> List.of(), errors::add)) {
        check.start(); brokenFuture(check, mode);
        assertEquals(ProjectValidationSession.Phase.FAILED, check.view().phase());
        assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues); assertEquals(1, errors.size());
        check.view(); assertEquals(1, errors.size());
        assertEquals(mode == 2, Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void updatesContainFutureStatusGetAndInterruptedReadFaultsWithoutExposingLinks() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var errors = new ArrayList<String>(); var work = new ArrayDeque<Runnable>();
      try (var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", work::add, Runnable::run,
          (endpoint, origins) -> ReadyUpdateCheckTest.RELEASE, errors::add)) {
        check.start(); brokenFuture(check, mode);
        assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase());
        assertNull(check.view().result()); assertEquals(1, errors.size());
        check.view(); assertEquals(1, errors.size());
        assertEquals(mode == 2, Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void validationFailureMessageCannotOverwriteAReadySuccessorCreatedWhileReadingIt() {
    var owner = new AtomicReference<ProjectValidationSession>(); var first = new AtomicBoolean(true);
    var errors = new ArrayList<String>();
    var failure = new AssertionError() {
      public String getMessage() { assertTrue(owner.get().start()); return "retired validator failure"; }
    };
    try (var state = ReadyValidationTest.state();
        var check = new ProjectValidationSession(state, Runnable::run, Runnable::run,
            p -> { if (first.getAndSet(false)) throw failure; return List.of(ReadyValidationTest.RESULT); }, errors::add)) {
      owner.set(check); check.start(); check.acceptPrepared();
      assertEquals(ProjectValidationSession.Phase.RUNNING, check.view().phase()); assertTrue(errors.isEmpty());
      assertEquals(List.of(ReadyValidationTest.BEFORE), state.issues);
      assertFalse(check.view().message().contains("retired validator failure")); check.acceptPrepared();
      assertEquals(List.of(ReadyValidationTest.RESULT), state.issues);
    }
  }

  @Test
  void updateFailureMessageCannotOverwriteAReadySuccessorCreatedWhileReadingIt() {
    var owner = new AtomicReference<UpdateCheckSession>(); var first = new AtomicBoolean(true);
    var errors = new ArrayList<String>();
    var failure = new AssertionError() {
      public String getMessage() { assertTrue(owner.get().start()); return "retired update failure"; }
    };
    try (var check = new UpdateCheckSession(ReadyUpdateCheckTest.configured(), "1.0.0", Runnable::run, Runnable::run,
        (endpoint, origins) -> { if (first.getAndSet(false)) throw failure; return ReadyUpdateCheckTest.RELEASE; }, errors::add)) {
      owner.set(check); check.start(); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase()); assertTrue(errors.isEmpty());
      assertNull(check.view().result()); assertFalse(check.view().message().contains("retired update failure"));
      check.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.READY, check.view().phase());
    }
  }
}
