package io.voxelcreator.editor;

import static io.voxelcreator.editor.ResourceEditSession.Result.*;
import static io.voxelcreator.editor.SearchNavigation.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred complete-worker/admission and retirement ownership for browser commands. */
class ResourceCommandWorkerCompletionTest {
  private static final long TIMEOUT = 10;
  private enum Mode { CLEAN, APPLY_DRAFT, DISCARD_DRAFT }

  private static final class Form implements AutoCloseable {
    final Mode mode;
    final EditorState state;
    final ResourceEditSession edit;
    final ResourceBrowserEdits.Edit command;
    final List<ResourceEditSession.Result> done = new ArrayList<>();
    final AtomicReference<Error> failure = new AtomicReference<>(), refreshFailure = new AtomicReference<>(), completionFailure = new AtomicReference<>();
    final AtomicBoolean reject = new AtomicBoolean();
    final AtomicInteger refreshes = new AtomicInteger();
    final ProjectFileOwner owner;
    final JsonNode project, draft, user;
    final long history, revision, raw;
    Form(Mode mode, Executor worker, Consumer<Runnable> ui) {
      this.mode = mode; state = BackgroundResourceEditTest.state(mode != Mode.CLEAN);
      if (mode == Mode.DISCARD_DRAFT) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      state.document.setValidator(p -> {
        var fault = failure.get(); if (fault != null) throw fault;
        return reject.get() ? List.of(Diagnostic.error("REJECT", "/", "command rejection")) : NoiseCompiler.validate(p);
      });
      command = BackgroundResourceEditTest.rename(state);
      edit = new ResourceEditSession(state, () -> {
        refreshes.incrementAndGet(); var fault = refreshFailure.get(); if (fault != null) throw fault;
      }, worker, ui, () -> false, result -> {
        done.add(result); var fault = completionFailure.get(); if (fault != null) throw fault;
      });
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project());
      draft = ProjectIO.JSON.valueToTree(state.draft); user = ProjectIO.JSON.valueToTree(state.user);
      history = state.document.historyBytes(); revision = state.revision; raw = state.seedInputs.version(state);
    }
    ResourceEditSession.Result begin(String label) {
      var result = edit.request(label, command);
      return result == NEEDS_DECISION && mode != Mode.CLEAN ? retry() : result;
    }
    ResourceEditSession.Result start() { return begin("Rename"); }
    ResourceEditSession.Result retry() { return edit.decide(mode == Mode.APPLY_DRAFT ? APPLY : DISCARD); }
    long failures(String code) {
      return state.events.snapshot().stream().filter(e -> e.code().equals(code)).mapToLong(EditorEvents.Entry::count).sum();
    }
    void retained() {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft)); assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(history, state.document.historyBytes()); assertEquals(revision, state.revision); assertEquals(raw, state.seedInputs.version(state));
      assertEquals(0, refreshes.get()); assertTrue(done.isEmpty());
    }
    void accepted() {
      assertEquals(List.of(APPLIED), done); assertFalse(edit.pending()); assertFalse(edit.preparing()); assertFalse(edit.busy());
      assertEquals(revision + 1, state.revision); assertEquals(1, refreshes.get()); assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals("renamed", state.project().noise2D.getFirst().id); assertFalse(state.seedInputs.pending(state));
      double expected = mode == Mode.APPLY_DRAFT ? .625 : .125;
      assertEquals(expected, new NoiseCompiler(state.project(), 0).compile(state.project().noise2D.getFirst().uuid).sample(-3, 0, 7));
      var after = ProjectIO.JSON.valueToTree(state.project()); state.document.undo();
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); state.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
    }
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
    Field field = ResourceEditSession.class.getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(form.edit)).cancel(false); field.set(form.edit, replacement);
  }

  @Test
  void heldPostingRetainsCleanApplyAndDiscardDomainsUntilOneCompleteAcceptance() throws Exception {
    for (var mode : Mode.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(mode, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          assertEquals(NEEDS_DECISION, form.start()); long work = form.edit.workVersion(); var callback = receipt(ui); callback.run(); form.edit.acceptPrepared();
          assertTrue(form.edit.preparing()); assertEquals(work, form.edit.workVersion()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.edit.acceptPrepared();
          callback.run(); form.edit.acceptPrepared(); form.accepted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void latePostingFaultRetainsInputAndOnlyExplicitRetryCanCommit() throws Exception {
    for (var mode : Mode.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(mode, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
      })) {
        try {
          assertEquals(NEEDS_DECISION, form.start()); var old = receipt(ui); old.run(); form.edit.acceptPrepared(); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.edit.acceptPrepared();
          assertFalse(form.edit.preparing()); assertTrue(form.edit.pending()); form.retained(); assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
          broken.set(false); assertEquals(NEEDS_DECISION, form.retry()); old.run(); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.edit.acceptPrepared(); form.accepted();
          assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlinePostingAndUnfinishedAdmissionCannotPublishSuccessOrCapturedFailure() {
    for (var mode : Mode.values()) for (boolean failed : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>(); var seen = new AtomicInteger();
      try (var form = new Form(mode, task -> {
        task.run(); ref.get().edit.acceptPrepared(); ref.get().retained(); seen.incrementAndGet();
      }, callback -> {
        callback.run(); ref.get().edit.acceptPrepared(); ref.get().retained(); seen.incrementAndGet();
      })) {
        ref.set(form); if (failed) form.failure.set(new AssertionError("validator"));
        assertEquals(NEEDS_DECISION, form.start()); assertEquals(2, seen.get()); assertTrue(form.edit.preparing());
        assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED")); form.edit.acceptPrepared();
        if (failed) { assertFalse(form.edit.preparing()); form.retained(); assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED")); }
        else form.accepted();
      }
    }
  }

  @Test
  void laterPostingAndExecutorFaultsCannotHideBehindInlineAcceptance() {
    for (var mode : Mode.values()) for (boolean executor : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>();
      try (var form = new Form(mode, task -> { task.run(); if (executor) throw new AssertionError("admission"); }, callback -> {
        callback.run(); ref.get().edit.acceptPrepared(); if (!executor) throw new AssertionError("posting");
      })) {
        ref.set(form); assertEquals(NEEDS_DECISION, form.start()); form.edit.acceptPrepared();
        assertFalse(form.edit.preparing()); assertTrue(form.edit.pending()); form.retained(); assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
      }
    }
  }

  @Test
  void clearOrCloseOfHeldSuccessAndCapturedFailureSuppressesLatePostingFaults() throws Exception {
    for (var mode : Mode.values()) for (boolean failed : new boolean[] {false, true}) for (boolean close : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(mode, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.failure.set(new AssertionError("validator")); assertEquals(NEEDS_DECISION, form.start());
          var old = receipt(ui); old.run(); form.edit.acceptPrepared(); if (close) form.edit.close(); else form.edit.clear();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); old.run(); form.edit.acceptPrepared();
          assertFalse(form.edit.pending()); assertFalse(form.edit.preparing()); form.retained(); assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void completeWorkWithoutAReceiptCannotAuthorizeCommandOrFailureFeedback() {
    for (var mode : Mode.values()) for (boolean failed : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(mode, tasks::add, ui::add)) {
        if (failed) form.failure.set(new AssertionError("validator")); assertEquals(NEEDS_DECISION, form.start()); tasks.remove().run();
        form.edit.acceptPrepared(); assertTrue(form.edit.preparing()); form.retained(); assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
        ui.remove().run(); form.edit.acceptPrepared();
        if (failed) { assertFalse(form.edit.preparing()); form.retained(); assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED")); }
        else form.accepted();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsAreContained() throws Exception {
    for (var mode : Mode.values()) for (int fault = 0; fault < 3; fault++) {
      final int action = fault; var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (action == 0) throw new AssertionError("status"); return true; }
          public Void get() throws InterruptedException {
            if (action == 2) throw new InterruptedException("read"); throw new AssertionError("get");
          }
        });
        form.edit.acceptPrepared(); assertFalse(form.edit.preparing()); assertEquals(fault == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.retained(); assertTrue(form.edit.pending()); assertEquals(1, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void eitherTerminalReadCannotConsumeOrFailItsReadySuccessorGesture() throws Exception {
    for (var mode : Mode.values()) for (int at : new int[] {1, 2}) for (int fault = 0; fault < 3; fault++) {
      final int action = fault, replaceAt = at; var tasks = new ArrayDeque<Runnable>(); var reads = new AtomicInteger();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            if (reads.incrementAndGet() != replaceAt) return null;
            form.edit.clear(); assertEquals(NEEDS_DECISION, form.begin("Successor")); tasks.remove().run();
            if (action == 1) throw new AssertionError("obsolete read");
            if (action == 2) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        form.edit.acceptPrepared(); form.retained(); assertEquals("Successor", form.edit.label()); assertTrue(form.edit.preparing());
        assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED")); form.edit.acceptPrepared(); form.accepted();
      }
    }
  }

  @Test
  void failureMessageCannotOverwriteReadyRetryOrClosedOwner() {
    for (var mode : Mode.values()) for (boolean close : new boolean[] {false, true})
      try (var form = new Form(mode, Runnable::run, Runnable::run)) {
        form.failure.set(new AssertionError() {
          public String getMessage() {
            form.failure.set(null); if (close) form.edit.close(); else assertEquals(NEEDS_DECISION, form.retry()); return "obsolete feedback";
          }
        });
        assertEquals(NEEDS_DECISION, form.start()); form.edit.acceptPrepared(); form.retained();
        assertFalse(form.edit.progress().contains("obsolete feedback")); assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
        if (close) assertFalse(form.edit.pending()); else { assertTrue(form.edit.preparing()); form.edit.acceptPrepared(); form.accepted(); }
      }
  }

  @Test
  void fullClearCannotEraseCancellationCreatedGestureMetadataOrProgress() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            assertEquals(NEEDS_DECISION, form.begin("Successor")); tasks.remove().run(); progress.set(form.edit.progress()); return super.cancel(interrupt);
          }
        });
        form.edit.clear(); assertEquals("Successor", form.edit.label()); assertEquals(progress.get(), form.edit.progress());
        assertEquals(mode != Mode.CLEAN, form.edit.privateDecision()); assertEquals(mode == Mode.APPLY_DRAFT, form.edit.preparingApply());
        form.retained(); form.edit.acceptPrepared(); form.accepted();
      }
    }
  }

  @Test
  void staleRetirementCannotOverwriteReadySuccessorOrSendObsoleteCompletion() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            assertEquals(NEEDS_DECISION, form.begin("Successor")); tasks.remove().run(); progress.set(form.edit.progress()); return super.cancel(interrupt);
          }
        });
        var newer = List.of(Diagnostic.error("NEWER", "/", "newer shared feedback")); form.state.issues = newer;
        form.edit.acceptPrepared(); assertSame(newer, form.state.issues); assertEquals("Successor", form.edit.label());
        assertEquals(progress.get(), form.edit.progress()); form.retained(); form.edit.acceptPrepared(); form.accepted();
      }
    }
  }

  @Test
  void failureCleanupCannotOverwriteReadyRetryCreatedByCancellation() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { throw new AssertionError("terminal read"); }
          public boolean cancel(boolean interrupt) {
            assertEquals(NEEDS_DECISION, form.retry()); tasks.remove().run(); progress.set(form.edit.progress()); return super.cancel(interrupt);
          }
        });
        form.edit.acceptPrepared(); assertEquals(progress.get(), form.edit.progress()); form.retained();
        assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED")); form.edit.acceptPrepared(); form.accepted();
      }
    }
  }

  @Test
  void finalClosePrecedesCancellationDrivenAdmissionAttempts() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { assertEquals(UNAVAILABLE, form.begin("Closed successor")); denied.incrementAndGet(); return super.cancel(interrupt); }
        });
        form.edit.close(); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); assertFalse(form.edit.pending()); form.retained();
      }
    }
  }

  @Test
  void committedTaskRetirementRemainsInsideThePublicationFence() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) {
            assertTrue(form.edit.busy()); assertEquals(UNAVAILABLE, form.begin("Consumed successor")); denied.incrementAndGet(); return super.cancel(interrupt);
          }
        });
        form.edit.acceptPrepared(); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); form.accepted();
      }
    }
  }

  @Test
  void rejectedDiagnosticsWaitForCompletePostingAndRequireExplicitRetry() throws Exception {
    for (var mode : Mode.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(mode, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.reject.set(true); assertEquals(NEEDS_DECISION, form.start()); receipt(ui).run(); form.edit.acceptPrepared();
          form.retained(); assertTrue(form.state.issues.isEmpty()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.edit.acceptPrepared(); assertFalse(form.edit.preparing()); assertFalse(form.state.issues.isEmpty()); form.retained();
          form.reject.set(false); assertEquals(NEEDS_DECISION, form.retry()); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.edit.acceptPrepared(); form.accepted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void rejectionCleanupCannotInstallOldDiagnosticsOverReadyRetry() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        form.reject.set(true); assertEquals(NEEDS_DECISION, form.start()); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) {
            form.reject.set(false); assertEquals(NEEDS_DECISION, form.retry()); tasks.remove().run(); return super.cancel(interrupt);
          }
        });
        form.edit.acceptPrepared(); assertTrue(form.state.issues.isEmpty()); form.retained(); assertTrue(form.edit.preparing());
        form.edit.acceptPrepared(); form.accepted();
      }
    }
  }

  @Test
  void consumedRefreshAndCompletionFailuresStillReportWithoutReplayingTheCommand() {
    for (var mode : Mode.values()) for (boolean completion : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var attempted = new AtomicReference<ResourceEditSession.Result>(); var busy = new AtomicBoolean();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        var fault = new AssertionError() {
          public String getMessage() { busy.set(form.edit.busy()); attempted.set(form.begin("Nested")); return "consumed follow-up"; }
        };
        if (completion) form.completionFailure.set(fault); else form.refreshFailure.set(fault);
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove().run(); form.edit.acceptPrepared();
        form.edit.acceptPrepared(); assertTrue(busy.get()); assertEquals(UNAVAILABLE, attempted.get());
        assertEquals(1, form.failures(completion ? "RESOURCE_EDIT_COMPLETION_FAILED" : "RESOURCE_EDIT_RECONCILE_FAILED")); form.accepted();
      }
    }
  }

  @Test
  void aNewRequestRechecksClosureAfterPendingObservationRunsFailureDescription() throws Exception {
    for (var mode : Mode.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(mode, tasks::add, Runnable::run)) {
        assertEquals(NEEDS_DECISION, form.start()); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { throw new AssertionError() { public String getMessage() { form.edit.close(); return "obsolete feedback"; } }; }
        });
        assertEquals(UNAVAILABLE, form.edit.request("After closure", form.command));
        assertFalse(form.edit.pending()); assertFalse(form.edit.preparing()); assertEquals("", form.edit.label());
        form.retained(); assertEquals(0, form.failures("RESOURCE_EDIT_PREPARE_FAILED"));
      }
    }
  }
}
