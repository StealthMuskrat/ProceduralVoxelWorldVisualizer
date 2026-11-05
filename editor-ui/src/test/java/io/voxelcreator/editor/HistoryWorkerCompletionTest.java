package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred full worker/posting/admission boundaries; no history work runs during the pause. */
class HistoryWorkerCompletionTest {
  private static final long TIMEOUT = 10;

  private static Direction opposite(Direction direction) {
    return direction == Direction.UNDO ? Direction.REDO : Direction.UNDO;
  }

  private static final class Form implements AutoCloseable {
    final EditorState state = BackgroundProjectHistoryTest.state(false);
    final AtomicReference<Error> failure = new AtomicReference<>();
    final AtomicBoolean reject = new AtomicBoolean();
    final AtomicReference<RuntimeException> refreshFailure = new AtomicReference<>();
    final AtomicInteger refreshes = new AtomicInteger();
    final Direction direction;
    final ProjectHistorySession history;
    final ProjectFileOwner owner;
    final JsonNode project, draft, user;
    final long bytes, revision, raw;
    final boolean undo, redo;
    Form(Direction direction, boolean dirty, Executor worker, Consumer<Runnable> ui) {
      this.direction = direction;
      if (direction == Direction.REDO) { state.document.undo(); state.refreshDraft(); }
      if (dirty) {
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      }
      state.document.setValidator(p -> {
        var fault = failure.get(); if (fault != null) throw fault;
        return reject.get() ? List.of(Diagnostic.error("REJECT", "/", "history rejection")) : NoiseCompiler.validate(p);
      });
      history = new ProjectHistorySession(state, () -> false, () -> {
        refreshes.incrementAndGet(); state.reconcileSelection(state.multiSelection, state.selected);
        var fault = refreshFailure.get(); if (fault != null) throw fault;
      }, worker, ui);
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project());
      draft = ProjectIO.JSON.valueToTree(state.draft); user = ProjectIO.JSON.valueToTree(state.user);
      bytes = state.document.historyBytes(); revision = state.revision; raw = state.seedInputs.version(state);
      undo = state.document.canUndo(); redo = state.document.canRedo();
    }
    Result start() { return start(direction); }
    Result start(Direction desired) {
      var result = history.request(desired);
      return result == Result.NEEDS_DECISION ? history.decide(Decision.DISCARD) : result;
    }
    long failures(String code) {
      return state.events.snapshot().stream().filter(e -> e.code().equals(code)).mapToLong(EditorEvents.Entry::count).sum();
    }
    void retained() {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft)); assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(bytes, state.document.historyBytes()); assertEquals(revision, state.revision);
      assertEquals(raw, state.seedInputs.version(state)); assertEquals(undo, state.document.canUndo()); assertEquals(redo, state.document.canRedo());
      assertEquals(0, refreshes.get());
    }
    void moved() {
      assertFalse(history.pending()); assertFalse(history.busy()); assertEquals(1, refreshes.get());
      assertEquals(revision + 1, state.revision); assertEquals(bytes, state.document.historyBytes());
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals("field", ((Noise) state.draft).id); assertFalse(state.seedInputs.pending(state));
      double expected = direction == Direction.UNDO ? .125 : .625;
      assertEquals(expected, new NoiseCompiler(state.project(), 0).compile(state.selected).sample(-3, 0, 7));
      assertTrue(direction == Direction.UNDO ? state.document.canRedo() : state.document.canUndo());
    }
    public void close() { history.close(); state.close(); }
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try { assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)); return; }
      catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }
  private static Runnable receipt(BlockingQueue<Runnable> ui) throws InterruptedException {
    var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); return callback;
  }
  private static void replaceTask(Form form, FutureTask<Void> replacement) throws Exception {
    Field field = ProjectHistorySession.class.getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(form.history)).cancel(false); field.set(form.history, replacement);
  }

  @Test
  void realPostingMustFinishBeforeOneHistoryStepAndItsExplicitInverse() throws Exception {
    for (var direction : Direction.values()) for (boolean dirty : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(direction, dirty, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          assertEquals(Result.PREPARING, form.start()); long work = form.history.workVersion(), request = form.history.requestVersion();
          var callback = receipt(ui); callback.run(); form.history.acceptPrepared();
          assertTrue(form.history.preparing()); assertEquals(work, form.history.workVersion()); assertEquals(request, form.history.requestVersion()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared();
          callback.run(); form.history.acceptPrepared(); form.moved();
          assertEquals(Result.PREPARING, form.start(opposite(direction))); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared();
          assertEquals(form.project, ProjectIO.JSON.valueToTree(form.state.project())); assertEquals(form.bytes, form.state.document.historyBytes());
          assertEquals(form.revision + 2, form.state.revision); assertEquals(2, form.refreshes.get());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void latePostingFaultRetainsBothStacksAndOnlyAnExplicitRetryMoves() throws Exception {
    for (var direction : Direction.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(direction, true, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late history post");
      })) {
        try {
          assertEquals(Result.PREPARING, form.start()); var old = receipt(ui); old.run(); form.history.acceptPrepared(); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared();
          assertFalse(form.history.pending()); form.retained(); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED"));
          broken.set(false); assertEquals(Result.PREPARING, form.start()); old.run(); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared(); form.moved();
          assertEquals(1, form.failures("PROJECT_HISTORY_FAILED"));
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForPostingAndExecutorAdmissionIncludingCapturedFailure() {
    for (var direction : Direction.values()) for (boolean failed : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>(); var seen = new AtomicInteger();
      try (var form = new Form(direction, true, task -> {
        task.run(); ref.get().history.acceptPrepared(); ref.get().retained(); seen.incrementAndGet();
      }, callback -> {
        callback.run(); ref.get().history.acceptPrepared(); ref.get().retained(); seen.incrementAndGet();
      })) {
        ref.set(form); if (failed) form.failure.set(new AssertionError("validator"));
        assertEquals(Result.PREPARING, form.start()); assertEquals(2, seen.get()); assertTrue(form.history.preparing());
        assertEquals(0, form.failures("PROJECT_HISTORY_FAILED")); form.history.acceptPrepared();
        if (failed) { assertFalse(form.history.pending()); form.retained(); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED")); }
        else form.moved();
      }
    }
  }

  @Test
  void inlineAcceptanceCannotHideLaterPostingOrAdmissionFailure() {
    for (var direction : Direction.values()) for (boolean executor : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>();
      try (var form = new Form(direction, false, task -> { task.run(); if (executor) throw new AssertionError("admission"); }, callback -> {
        callback.run(); ref.get().history.acceptPrepared(); if (!executor) throw new AssertionError("posting");
      })) {
        ref.set(form); assertEquals(executor ? Result.REJECTED : Result.PREPARING, form.start()); form.history.acceptPrepared();
        assertFalse(form.history.pending()); form.retained(); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED"));
      }
    }
  }

  @Test
  void closingHeldSuccessOrCapturedFailureSuppressesLatePostingFault() throws Exception {
    for (var direction : Direction.values()) for (boolean failed : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(direction, true, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.failure.set(new AssertionError("validator")); assertEquals(Result.PREPARING, form.start());
          var old = receipt(ui); old.run(); form.history.acceptPrepared(); form.history.close();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); old.run(); form.history.acceptPrepared();
          assertFalse(form.history.pending()); assertEquals(Result.UNAVAILABLE, form.start()); form.retained(); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void completeWorkerWithoutReadinessCannotAuthorizeMovementOrCapturedFeedback() {
    for (var direction : Direction.values()) for (boolean failed : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(direction, false, tasks::add, ui::add)) {
        if (failed) form.failure.set(new AssertionError("validator")); assertEquals(Result.PREPARING, form.start()); tasks.remove().run();
        form.history.acceptPrepared(); assertTrue(form.history.pending()); form.retained(); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
        ui.remove().run(); form.history.acceptPrepared();
        if (failed) { assertFalse(form.history.pending()); form.retained(); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED")); }
        else form.moved();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsAreContained() throws Exception {
    for (var direction : Direction.values()) for (int mode = 0; mode < 3; mode++) {
      final int fault = mode; var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(direction, true, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read"); throw new AssertionError("get");
          }
        });
        form.history.acceptPrepared(); assertFalse(form.history.pending()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.retained(); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED"));
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void eitherTerminalReadCannotConsumeOrFailItsReadySuccessor() throws Exception {
    for (var direction : Direction.values()) for (int at : new int[] {1, 2}) for (int mode = 0; mode < 3; mode++) {
      final int fault = mode, replaceAt = at; var tasks = new ArrayDeque<Runnable>(); var reads = new AtomicInteger();
      try (var form = new Form(direction, true, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            if (reads.incrementAndGet() != replaceAt) return null;
            form.history.clear(); assertEquals(Result.PREPARING, form.start()); tasks.remove().run();
            if (fault == 1) throw new AssertionError("obsolete read");
            if (fault == 2) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        form.history.acceptPrepared(); form.retained(); assertTrue(form.history.preparing()); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
        form.history.acceptPrepared(); form.moved();
      }
    }
  }

  @Test
  void failureDescriptionCannotOverwriteReadyRetryOrClosedController() {
    for (var direction : Direction.values()) for (boolean close : new boolean[] {false, true})
      try (var form = new Form(direction, true, Runnable::run, Runnable::run)) {
        form.failure.set(new AssertionError() {
          public String getMessage() {
            form.failure.set(null); if (close) form.history.close(); else assertEquals(Result.PREPARING, form.start());
            form.state.status = "newer owner"; return "obsolete feedback";
          }
        });
        assertEquals(Result.PREPARING, form.start()); form.history.acceptPrepared(); form.retained();
        assertEquals("newer owner", form.state.status); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
        if (close) assertEquals(Result.UNAVAILABLE, form.start());
        else { assertTrue(form.history.preparing()); form.history.acceptPrepared(); form.moved(); }
      }
  }

  @Test
  void finalClosePrecedesCancellationCallbacksTryingAnotherRequest() throws Exception {
    for (var direction : Direction.values()) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(direction, false, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { assertEquals(Result.UNAVAILABLE, form.start()); denied.incrementAndGet(); return super.cancel(interrupt); }
        });
        form.history.close(); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); form.retained(); assertFalse(form.history.pending());
      }
    }
  }

  @Test
  void finalMovementFencePrecedesCompletedTaskRetirementAndRefresh() throws Exception {
    for (var direction : Direction.values()) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(direction, true, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) {
            assertTrue(form.history.busy()); assertFalse(form.history.pending()); assertEquals(Result.UNAVAILABLE, form.start());
            denied.incrementAndGet(); return super.cancel(interrupt);
          }
        });
        form.history.acceptPrepared(); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); form.moved();
      }
    }
  }

  @Test
  void closureDuringConsumedTaskRetirementPreventsHistoryPublication() throws Exception {
    for (var direction : Direction.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(direction, true, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) { form.history.close(); return super.cancel(interrupt); }
        });
        form.history.acceptPrepared(); assertFalse(form.history.busy()); assertEquals(Result.UNAVAILABLE, form.start());
        form.retained(); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
      }
    }
  }

  @Test
  void staleRetirementCannotOverwriteAReadyRequestForNewPrivateInput() throws Exception {
    for (var direction : Direction.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(direction, true, tasks::add, Runnable::run)) {
        assertEquals(Result.PREPARING, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            assertEquals(Result.PREPARING, form.start()); tasks.remove().run(); form.state.status = "successor status"; return super.cancel(interrupt);
          }
        });
        ((Noise) form.state.draft).id = "new private"; var newer = new ProjectFileOwner(form.state);
        form.history.acceptPrepared(); assertTrue(newer.valuesCurrent(form.state)); assertTrue(form.history.preparing());
        assertEquals("successor status", form.state.status); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED"));
        form.history.acceptPrepared(); form.moved();
      }
    }
  }

  @Test
  void rejectedCandidateFeedbackWaitsForCompletePostingAndRequiresAFreshRequest() throws Exception {
    for (var direction : Direction.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(direction, true, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.reject.set(true); assertEquals(Result.PREPARING, form.start()); receipt(ui).run(); form.history.acceptPrepared();
          form.retained(); assertEquals(0, form.failures("PROJECT_HISTORY_FAILED")); release.countDown();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared();
          assertFalse(form.history.pending()); assertEquals(1, form.failures("PROJECT_HISTORY_FAILED")); form.retained();
          form.reject.set(false); assertEquals(Result.PREPARING, form.start()); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.history.acceptPrepared(); form.moved();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void consumedRefreshFailureStillReportsOnceAndCannotReplayTheMovement() {
    for (var direction : Direction.values()) {
      var tasks = new ArrayDeque<Runnable>(); var busy = new AtomicBoolean(); var attempted = new AtomicReference<Result>();
      try (var form = new Form(direction, false, tasks::add, Runnable::run)) {
        form.refreshFailure.set(new IllegalStateException() {
          public String getMessage() { busy.set(form.history.busy()); attempted.set(form.start(opposite(direction))); return "consumed refresh"; }
        });
        assertEquals(Result.PREPARING, form.start()); tasks.remove().run(); form.history.acceptPrepared(); form.moved();
        assertTrue(busy.get()); assertEquals(Result.UNAVAILABLE, attempted.get());
        form.history.acceptPrepared(); assertEquals(1, form.refreshes.get()); assertEquals(1, form.failures("PROJECT_HISTORY_REFRESH_FAILED"));
        form.refreshFailure.set(null); assertEquals(Result.PREPARING, form.start(opposite(direction))); tasks.remove().run(); form.history.acceptPrepared();
        assertEquals(form.project, ProjectIO.JSON.valueToTree(form.state.project())); assertEquals(form.revision + 2, form.state.revision);
      }
    }
  }
}
