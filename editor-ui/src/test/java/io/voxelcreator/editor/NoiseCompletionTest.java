package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred complete-worker/admission boundaries; adoption, private Stage and parent Apply differ. */
class NoiseCompletionTest {
  private static final long TIMEOUT = 10;
  private static final class Form implements AutoCloseable {
    final EditorState state = BackgroundNoiseEditTest.state();
    final AtomicReference<Error> failure = new AtomicReference<>();
    final Queue<Runnable> initial = new ArrayDeque<>(), receipts = new ArrayDeque<>();
    final NoiseEditSession edit;
    final NoiseAuthoring.Plan old;
    final ProjectFileOwner owner;
    final com.fasterxml.jackson.databind.JsonNode project, draft, user;
    final long history, revision;
    boolean boot = true;
    Form(Executor worker, Consumer<Runnable> ui) {
      edit = new NoiseEditSession(state, BackgroundNoiseEditTest.noise(state), BackgroundNoiseEditTest.request(state),
          task -> { if (boot) initial.add(task); else worker.execute(task); },
          callback -> { if (boot) receipts.add(callback); else ui.accept(callback); },
          (p, n, r) -> { var rejected = failure.get(); if (rejected != null) throw rejected; return NoiseAuthoring.plan(p, n, r); });
      initial.remove().run(); receipts.remove().run(); edit.acceptPrepared(); old = edit.preview(); assertFalse(old.valid());
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project()); draft = ProjectIO.JSON.valueToTree(state.draft);
      user = ProjectIO.JSON.valueToTree(state.user); history = state.document.historyBytes(); revision = state.revision; boot = false;
    }
    void start() {
      if (edit.resolution() == Resolution.INLINE_PARAMETER)
        edit.choose(Resolution.REPLACE, BackgroundNoiseEditTest.noise(state).sharedParameters.getLast().uuid);
      else edit.choose(Resolution.INLINE_PARAMETER, null);
    }
    void retained() {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft)); assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(history, state.document.historyBytes()); assertEquals(revision, state.revision); assertSame(old, edit.preview());
    }
    void adopted() {
      assertTrue(edit.ready()); assertTrue(edit.canStage()); assertNotSame(old, edit.preview());
      assertTrue(owner.valuesCurrent(state)); assertEquals(history, state.document.historyBytes());
    }
    long failures() { return state.events.snapshot().stream().filter(e -> e.code().equals("NOISE_EDIT_PREVIEW_FAILED")).mapToLong(EditorEvents.Entry::count).sum(); }
    public void close() { edit.close(); state.close(); }
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
    Field field = NoiseEditSession.class.getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(form.edit)).cancel(false); field.set(form.edit, replacement);
  }

  @Test
  void realPostingMustReturnBeforeAdoptionAndStageStillRequiresParentApply() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(worker, callback -> { ui.add(callback); await(release); })) {
      try {
        form.start(); long version = form.edit.version(); var callback = receipt(ui); callback.run(); form.edit.acceptPrepared();
        assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase()); assertEquals(version, form.edit.version()); form.retained();
        assertThrows(IllegalStateException.class, form.edit::stage); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        form.edit.acceptPrepared(); callback.run(); form.edit.acceptPrepared(); form.adopted(); form.edit.stage();
        assertEquals(form.project, ProjectIO.JSON.valueToTree(form.state.project())); assertEquals(form.history, form.state.document.historyBytes());
        assertTrue(form.state.applyDraft()); assertEquals(12, new NoiseCompiler(form.state.project(), 0)
            .compile(BackgroundNoiseEditTest.noise(form.state).uuid).sample(-3, 0, 7));
        var committed = ProjectIO.JSON.valueToTree(form.state.project()); form.state.document.undo();
        assertEquals(form.project, ProjectIO.JSON.valueToTree(form.state.project())); form.state.document.redo();
        assertEquals(committed, ProjectIO.JSON.valueToTree(form.state.project()));
      } finally { release.countDown(); }
    }
  }

  @Test
  void latePosterFailureKeepsOldFeedbackAndExplicitRetryAdoptsOnlyOnce() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
    try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(worker, callback -> {
      ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
    })) {
      try {
        form.start(); var old = receipt(ui); old.run(); form.edit.acceptPrepared(); form.retained();
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.edit.acceptPrepared();
        assertTrue(form.edit.canRetry()); form.retained(); assertEquals(1, form.failures());
        broken.set(false); form.edit.retry(); old.run(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        form.edit.acceptPrepared(); form.adopted(); assertEquals(1, form.failures());
      } finally { release.countDown(); }
    }
  }

  @Test
  void closureOfHeldSuccessOrCapturedFailureSuppressesLaterPostingFault() throws Exception {
    for (boolean failed : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.failure.set(new AssertionError("planner")); form.start(); var callback = receipt(ui); callback.run();
          form.edit.acceptPrepared(); form.edit.cancel(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.edit.acceptPrepared(); assertTrue(form.edit.closed()); form.retained(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceCannotRunInsidePostingOrUnfinishedExecutorAdmission() {
    var ref = new AtomicReference<Form>(); var seen = new AtomicInteger();
    try (var form = new Form(task -> { task.run(); ref.get().edit.acceptPrepared(); ref.get().retained(); seen.incrementAndGet(); },
        callback -> { callback.run(); ref.get().edit.acceptPrepared(); ref.get().retained(); seen.incrementAndGet(); })) {
      ref.set(form); form.start(); assertEquals(2, seen.get()); assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase());
      form.edit.acceptPrepared(); form.adopted();
    }
  }

  @Test
  void laterPosterOrExecutorFaultCannotHideBehindInlineAcceptance() {
    for (boolean executor : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>();
      try (var form = new Form(task -> { task.run(); if (executor) throw new AssertionError("executor failed"); }, callback -> {
        callback.run(); ref.get().edit.acceptPrepared(); if (!executor) throw new AssertionError("poster failed");
      })) {
        ref.set(form); form.start(); form.edit.acceptPrepared(); form.retained(); assertTrue(form.edit.canRetry()); assertEquals(1, form.failures());
      }
    }
  }

  @Test
  void capturedPlannerFailureWaitsForCompletePostingAndAdmission() {
    var ref = new AtomicReference<Form>();
    try (var form = new Form(Runnable::run, callback -> {
      callback.run(); ref.get().edit.acceptPrepared(); assertEquals(0, ref.get().failures());
    })) {
      ref.set(form); form.failure.set(new AssertionError("planner failed")); form.start(); assertEquals(0, form.failures());
      assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase()); form.edit.acceptPrepared();
      form.retained(); assertEquals(1, form.failures()); assertTrue(form.edit.canRetry());
    }
  }

  @Test
  void completedWorkCannotBypassTheReadinessReceipt() {
    var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
    try (var form = new Form(tasks::add, ui::add)) {
      form.start(); tasks.remove().run(); form.edit.acceptPrepared(); form.retained();
      assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase()); ui.remove().run(); form.edit.acceptPrepared(); form.adopted();
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsAreContained() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
      try (var form = new Form(tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status fault"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read interrupted"); throw new AssertionError("get fault");
          }
        });
        form.edit.acceptPrepared(); assertTrue(form.edit.canRetry()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.retained(); assertEquals(1, form.failures());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalReadCannotConsumeOrFailItsReadyReplacementResolution() throws Exception {
    for (boolean failed : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { form.start(); tasks.remove().run(); if (failed) throw new AssertionError("obsolete read"); return null; }
        });
        form.edit.acceptPrepared(); form.retained(); assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase()); assertEquals(0, form.failures());
        form.edit.acceptPrepared(); form.adopted();
      }
    }
  }

  @Test
  void failureMessageGetterCannotOverwriteReadySuccessorOrClosedOwner() throws Exception {
    for (boolean close : new boolean[] {false, true}) try (var form = new Form(Runnable::run, Runnable::run)) {
      form.failure.set(new AssertionError() {
        public String getMessage() { form.failure.set(null); if (close) form.edit.cancel(); else form.edit.retry(); return "obsolete feedback"; }
      });
      form.start(); form.edit.acceptPrepared(); form.retained(); assertEquals(0, form.failures()); assertFalse(form.edit.message().contains("obsolete feedback"));
      if (close) assertTrue(form.edit.closed()); else { form.edit.acceptPrepared(); form.adopted(); }
    }
  }

  @Test
  void retiringAnAcceptedPlanCannotInstallItOverACancellationCreatedSuccessor() throws Exception {
    var tasks = new ArrayDeque<Runnable>();
    try (var form = new Form(tasks::add, Runnable::run)) {
      form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { return null; }
        public boolean cancel(boolean interrupt) { form.start(); tasks.remove().run(); return super.cancel(interrupt); }
      });
      form.edit.acceptPrepared(); form.retained(); assertEquals(NoiseEditSession.Phase.RUNNING, form.edit.phase());
      form.edit.acceptPrepared(); form.adopted(); assertEquals(0, form.failures());
    }
  }

  @Test
  void finalClosePrecedesCancellationCallbacksThatAttemptAnotherResolution() throws Exception {
    var tasks = new ArrayDeque<Runnable>();
    try (var form = new Form(tasks::add, Runnable::run)) {
      form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean cancel(boolean interrupt) {
          try { form.start(); } catch (IllegalStateException closed) { /* Rejected closed owner. */ }
          return super.cancel(interrupt);
        }
      });
      form.edit.cancel(); form.edit.acceptPrepared(); assertTrue(form.edit.closed()); assertTrue(tasks.isEmpty()); form.retained();
    }
  }
}
