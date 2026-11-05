package io.voxelcreator.editor;

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

/** Deferred complete planning/Import Apply handoffs, original-request feedback and fresh review. */
class ResourceReviewCompletionTest {
  private static final long TIMEOUT = 10;
  private enum Kind { IMPORT_PLAN, EXPORT_PLAN, IMPORT_APPLY }

  private static final class Form implements AutoCloseable {
    final Kind kind;
    final EditorState state;
    final ImportEditSession importing;
    final ExportEditSession exporting;
    final AtomicReference<Error> validationFailure = new AtomicReference<>();
    final AtomicReference<List<Diagnostic>> rejection = new AtomicReference<>(List.of());
    final Object oldRows, oldCatalog;
    final Queue<Runnable> bootstrap = new ArrayDeque<>(), bootstrapUi = new ArrayDeque<>();
    Domain domain;
    boolean initializing = true, blocked;

    Form(Kind kind, Executor worker, Consumer<Runnable> ui) {
      this.kind = kind; state = kind == Kind.EXPORT_PLAN ? ExportPreviewPlanningTest.state() : ImportPreviewPlanningTest.state();
      state.document.setValidator(p -> {
        var failure = validationFailure.get(); if (failure != null) throw failure;
        return rejection.get().isEmpty() ? NoiseCompiler.validate(p) : rejection.get();
      });
      Executor execute = task -> { if (initializing) bootstrap.add(task); else worker.execute(task); };
      Consumer<Runnable> post = callback -> { if (initializing) bootstrapUi.add(callback); else ui.accept(callback); };
      importing = kind == Kind.EXPORT_PLAN ? null : new ImportEditSession(state,
          kind == Kind.IMPORT_PLAN ? ImportPreviewPlanningTest.incoming() : BackgroundImportApplyTest.incoming(), execute, post, () -> blocked);
      exporting = kind == Kind.EXPORT_PLAN ? new ExportEditSession(state, ExportPreviewPlanningTest.derived(state), execute, post, () -> blocked) : null;
      if (exporting != null) { bootstrap.remove().run(); bootstrapUi.remove().run(); exporting.acceptPlanning(); exporting.markShown(exporting.preview()); }
      else importing.markShown(importing.preview());
      oldRows = rows(); oldCatalog = exporting == null ? null : exporting.catalog(); domain = new Domain(state); initializing = false;
    }
    Object rows() { return exporting != null ? exporting.preview() : importing.preview(); }
    Object token() { return exporting != null ? exporting.planningToken() : kind == Kind.IMPORT_PLAN ? importing.planningToken() : importing.applyVersion(); }
    Object taskOwner() { return exporting != null ? exporting : importing; }
    String taskField() { return exporting != null ? "task" : kind == Kind.IMPORT_PLAN ? "planningTask" : "applyTask"; }
    void start() {
      if (exporting != null) exporting.select(exporting.selected().equals(ExportPreviewPlanningTest.base(state))
          ? ExportPreviewPlanningTest.derived(state) : ExportPreviewPlanningTest.base(state));
      else if (kind == Kind.IMPORT_PLAN) {
        var id = importing.preview().rows().getFirst().uuid();
        importing.choose(id, importing.choice(id) == ResourceImport.Choice.REUSE ? ResourceImport.Choice.COPY : ResourceImport.Choice.REUSE);
      } else { importing.markShown(importing.preview()); importing.apply(); }
    }
    void startBroken() {
      if (exporting != null) { state.project().project.rootOrder = null; domain = new Domain(state); exporting.reload(); }
      else if (kind == Kind.IMPORT_PLAN) { state.project().noise2D.add(null); domain = new Domain(state); importing.reload(); }
      else { validationFailure.set(new AssertionError("captured validation")); start(); }
    }
    void retry() {
      if (exporting != null) exporting.retryPlan(); else if (kind == Kind.IMPORT_PLAN) importing.retryPlan(); else start();
    }
    void restart() {
      if (kind != Kind.IMPORT_APPLY) { start(); return; }
      // Retire the active transaction through its public stale-input boundary before explicit retry.
      String scope = state.validationScope; state.validationScope = scope + " changed"; importing.applyPhase(); state.validationScope = scope;
      start();
    }
    void accept() { if (exporting != null) exporting.acceptPlanning(); else if (kind == Kind.IMPORT_PLAN) importing.acceptPlanning(); else importing.acceptPrepared(); }
    boolean pending() { return exporting != null ? exporting.planning() : kind == Kind.IMPORT_PLAN ? importing.planning() : importing.busy(); }
    boolean closed() { return exporting != null ? exporting.closed() : importing.closed(); }
    String message() { return exporting != null ? exporting.planningMessage() : kind == Kind.IMPORT_PLAN ? importing.planningMessage() : importing.applyMessage(); }
    long failures() {
      String code = exporting != null ? "EXPORT_PREVIEW_FAILED" : kind == Kind.IMPORT_PLAN ? "IMPORT_PREVIEW_FAILED" : "IMPORT_APPLY_FAILED";
      return state.events.snapshot().stream().filter(e -> e.code().equals(code)).mapToLong(EditorEvents.Entry::count).sum();
    }
    void cancel() { if (exporting != null) exporting.cancel(); else importing.cancel(); }
    void retained() {
      domain.retained(state); assertSame(oldRows, rows()); if (exporting != null) assertSame(oldCatalog, exporting.catalog());
    }
    void adopted() {
      assertFalse(pending());
      if (kind == Kind.IMPORT_APPLY) {
        assertTrue(importing.closed()); assertEquals(ImportEditSession.ApplyPhase.APPLIED, importing.applyPhase());
        assertEquals(domain.revision + 1, state.revision); assertEquals(3, state.project().noise2D.size()); domain.privateRetained(state);
        assertEquals(.75, state.project().noise2D.getLast().algorithm.parameters.get("value"));
      } else {
        domain.retained(state); assertNotSame(oldRows, rows());
        if (exporting != null) { assertFalse(exporting.canExport()); assertSame(oldCatalog, exporting.catalog()); }
        else assertFalse(importing.canApply());
      }
    }
    public void close() { cancel(); state.close(); }
  }
  private static final class Domain {
    final ProjectFileOwner owner;
    final JsonNode project, user, privateValues;
    final Object draft;
    final UUID selected;
    final List<UUID> selection;
    final List<Diagnostic> issues;
    final String scope;
    final long history, revision, inputs;
    final boolean undo, redo;
    Domain(EditorState s) {
      owner = new ProjectFileOwner(s); project = ProjectIO.JSON.valueToTree(s.project()); user = ProjectIO.JSON.valueToTree(s.user);
      draft = s.draft; privateValues = ProjectIO.JSON.valueToTree(s.draft); selected = s.selected; selection = List.copyOf(s.multiSelection);
      issues = s.issues; scope = s.validationScope; history = s.document.historyBytes(); revision = s.revision; inputs = s.seedInputs.version(s);
      undo = s.document.canUndo(); redo = s.document.canRedo();
    }
    void privateRetained(EditorState s) {
      assertSame(draft, s.draft); assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft)); assertEquals(selected, s.selected);
      assertEquals(selection, List.copyOf(s.multiSelection)); assertEquals(inputs, s.seedInputs.version(s)); assertEquals(user, ProjectIO.JSON.valueToTree(s.user));
    }
    void retained(EditorState s) {
      assertTrue(owner.valuesCurrent(s)); assertEquals(project, ProjectIO.JSON.valueToTree(s.project())); privateRetained(s);
      assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertEquals(history, s.document.historyBytes()); assertEquals(revision, s.revision);
      assertEquals(undo, s.document.canUndo()); assertEquals(redo, s.document.canRedo());
    }
  }
  private static void await(CountDownLatch latch) {
    boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try {
        assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS), "handoff fixture timeout"); return;
      } catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }
  private static Runnable receipt(BlockingQueue<Runnable> ui) throws InterruptedException {
    var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); return callback;
  }
  private static void replaceTask(Form form, FutureTask<Void> replacement) throws Exception {
    var owner = form.taskOwner(); Field field = owner.getClass().getDeclaredField(form.taskField()); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false); field.set(owner, replacement);
  }

  @Test
  void realReceiptKeepsRowsOrTransactionPrivateUntilTheEntireWorkerReturns() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var ticket = form.token(); var callback = receipt(ui); callback.run(); form.accept();
          assertTrue(form.pending()); assertEquals(ticket, form.token()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); callback.run(); form.accept(); form.adopted();
          if (kind == Kind.IMPORT_APPLY) { form.state.document.undo(); assertEquals(form.domain.project, ProjectIO.JSON.valueToTree(form.state.project()));
            form.state.document.redo(); assertEquals(3, form.state.project().noise2D.size()); }
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void latePostingFailureKeepsOldRowsOrDocumentAndRequiresExplicitRetry() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
      })) {
        try {
          form.start(); var old = receipt(ui); old.run(); form.accept(); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept();
          assertFalse(form.pending()); form.retained(); assertEquals(1, form.failures());
          broken.set(false); form.retry(); old.run(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.accept(); form.adopted(); assertEquals(1, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void closureDuringHeldSuccessOrCapturedFailureRejectsLatePostingAndFeedback() throws Exception {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.startBroken(); else form.start(); var callback = receipt(ui); callback.run(); form.accept(); form.cancel();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); form.accept();
          assertTrue(form.closed()); assertFalse(form.pending()); form.retained(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForBothPostingAndSuccessfulSubmission() {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>(); var observations = new AtomicInteger();
      try (var form = new Form(kind, task -> {
        task.run(); owner.get().accept(); owner.get().retained(); observations.incrementAndGet();
      }, callback -> {
        callback.run(); owner.get().accept(); owner.get().retained(); observations.incrementAndGet();
      })) {
        owner.set(form); form.start(); assertEquals(2, observations.get()); assertTrue(form.pending()); form.retained(); form.accept(); form.adopted();
      }
    }
  }

  @Test
  void inlineAcceptanceCannotHideAFollowingPostingFailure() {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); owner.get().retained(); throw new AssertionError("poster failed");
      })) {
        owner.set(form); form.start(); form.accept(); assertFalse(form.pending()); form.retained(); assertEquals(1, form.failures());
      }
    }
  }

  @Test
  void executorRejectionWinsOverUnacceptedSuccessOrCapturedPreparationFailure() {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, task -> { task.run(); throw new AssertionError("executor failed"); }, callback -> {
        callback.run(); owner.get().accept(); owner.get().retained();
      })) {
        owner.set(form); if (failed) form.startBroken(); else form.start(); form.accept();
        assertFalse(form.pending()); form.retained(); assertEquals(1, form.failures()); assertTrue(form.message().contains("executor failed"));
      }
    }
  }

  @Test
  void capturedPreparationFailureWaitsForCompleteHandoffAndAdmission() {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); owner.get().retained(); assertEquals(0, owner.get().failures());
      })) {
        owner.set(form); form.startBroken(); assertTrue(form.pending()); assertEquals(0, form.failures());
        form.accept(); assertFalse(form.pending()); form.retained(); assertEquals(1, form.failures());
      }
    }
  }

  @Test
  void normalWorkerCompletionCannotBypassAQueuedReadinessReceipt() {
    for (var kind : Kind.values()) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, ui::add)) {
        form.start(); tasks.remove().run(); form.accept(); assertTrue(form.pending()); form.retained();
        ui.remove().run(); form.accept(); form.adopted();
      }
    }
  }

  @Test
  void statusGetAndInterruptedTerminalReadFaultsRemainOwnedWithoutAdoption() throws Exception {
    for (var kind : Kind.values()) for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status fault"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read interrupted"); throw new AssertionError("get fault");
          }
        });
        form.accept(); assertFalse(form.pending()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.retained(); assertEquals(1, form.failures());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalReadCannotConsumeOrReportAgainstTheReadyReplacementItCreates() throws Exception {
    for (var kind : Kind.values()) for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int outcome = mode;
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            form.restart(); tasks.remove().run();
            if (outcome == 1) throw new AssertionError("obsolete read");
            if (outcome == 2) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        form.accept(); assertTrue(form.pending()); form.retained(); assertEquals(0, form.failures()); form.accept(); form.adopted();
      }
    }
  }

  @Test
  void failureDescriptionCannotOverwriteAReadySuccessorOrClosedReview() throws Exception {
    for (var kind : Kind.values()) for (boolean close : new boolean[] {false, true}) {
      try (var form = new Form(kind, Runnable::run, Runnable::run)) {
        form.start(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            throw new AssertionError() {
              public String getMessage() { if (close) form.cancel(); else form.retry(); return "obsolete feedback"; }
            };
          }
        });
        form.accept(); form.retained(); assertEquals(0, form.failures()); assertFalse(form.message().contains("obsolete feedback"));
        if (close) assertTrue(form.closed()); else { assertTrue(form.pending()); form.accept(); form.adopted(); }
      }
    }
  }

  @Test
  void finalClosePrecedesFutureCancellationThatAttemptsToStartMoreWork() throws Exception {
    for (var kind : Kind.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            try { form.start(); } catch (IllegalStateException closed) { /* Explicitly rejected. */ }
            return super.cancel(interrupt);
          }
        });
        form.cancel(); assertTrue(form.closed()); assertTrue(tasks.isEmpty()); form.accept(); form.retained();
      }
    }
  }

  @Test
  void oldReceiptsCannotConsumeAReplacementWhileThePredecessorIsReturning() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var old = receipt(ui); old.run(); form.restart(); var ticket = form.token(); old.run(); form.accept();
          assertEquals(ticket, form.token()); form.retained(); release.countDown(); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); old.run(); form.accept(); form.adopted(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void aNewBlockerBeforeFullCompletionRetiresTheResultAndRequiresExplicitRetry() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); receipt(ui).run(); form.blocked = true; form.accept(); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept();
          assertFalse(form.pending()); form.retained(); assertEquals(0, form.failures());
          form.blocked = false; form.retry(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); form.adopted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void changedPrivateValuesDuringPostingWinOverTheOldPlanOrTransaction() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var callback = receipt(ui); callback.run(); ((ProjectData.Noise) form.state.draft).id = "new private";
          var newer = new Domain(form.state); form.accept(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); assertFalse(form.pending()); newer.retained(form.state); assertSame(form.oldRows, form.rows()); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void newPlansStillNeedFreshDisplayBeforeExportOrExplicitImportApply() {
    for (var kind : new Kind[] {Kind.IMPORT_PLAN, Kind.EXPORT_PLAN}) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, ui::add)) {
        form.start(); tasks.remove().run(); ui.remove().run(); form.accept(); form.adopted();
        if (form.exporting != null) {
          var preview = form.exporting.preview(); assertThrows(IllegalStateException.class, () -> form.exporting.exportSnapshot(preview));
          form.exporting.markShown(preview); assertTrue(form.exporting.canExport()); var resources = form.exporting.exportSnapshot(preview);
          assertEquals(1, resources.noise2D.size()); assertEquals(.125, new NoiseCompiler(resources, 17).compile(resources.noise2D.getFirst().uuid).sample(-7, 0, 3));
          form.domain.retained(form.state);
        } else {
          form.importing.markShown(form.importing.preview()); assertEquals(ImportEditSession.Result.STARTED, form.importing.apply());
          tasks.remove().run(); ui.remove().run(); form.importing.acceptPrepared(); assertEquals(3, form.state.project().noise2D.size());
          form.state.document.undo(); assertEquals(form.domain.project, ProjectIO.JSON.valueToTree(form.state.project()));
          form.state.document.redo(); assertEquals(3, form.state.project().noise2D.size());
        }
      }
    }
  }

  @Test
  void rejectedImportFeedbackWaitsForFullPostingAndRetryCommitsOnlyOnce() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(Kind.IMPORT_APPLY, worker, callback -> { ui.add(callback); await(release); })) {
      try {
        var issue = Diagnostic.error("REJECT", "/", "rejected"); form.rejection.set(List.of(issue)); form.start(); receipt(ui).run(); form.accept();
        form.retained(); assertTrue(form.importing.applyIssues().isEmpty()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        form.accept(); assertEquals(ImportEditSession.ApplyPhase.REJECTED, form.importing.applyPhase());
        assertEquals(List.of(issue), form.state.issues); assertEquals(form.domain.project, ProjectIO.JSON.valueToTree(form.state.project()));
        form.rejection.set(List.of()); form.retry(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); form.adopted();
      } finally { release.countDown(); }
    }
  }

  @Test
  void committedImportIsClosedBeforeCompletedTaskCancellationCanReloadTheReview() throws Exception {
    var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var admitted = new AtomicBoolean();
    try (var form = new Form(Kind.IMPORT_APPLY, tasks::add, ui::add)) {
      form.start(); tasks.remove().run(); ui.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { return null; }
        public boolean cancel(boolean interrupt) {
          try { form.importing.reload(); admitted.set(true); } catch (IllegalStateException closed) { /* Consumed owner. */ }
          return super.cancel(interrupt);
        }
      });
      form.accept(); form.adopted(); assertFalse(admitted.get()); assertTrue(tasks.isEmpty()); form.accept(); assertEquals(3, form.state.project().noise2D.size());
    }
  }

  @Test
  void planningConsumptionCannotOverwriteAReadySuccessorCreatedByCancellation() throws Exception {
    for (var kind : new Kind[] {Kind.IMPORT_PLAN, Kind.EXPORT_PLAN}) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) {
            form.restart(); tasks.remove().run(); return super.cancel(interrupt);
          }
        });
        form.accept(); assertTrue(form.pending()); form.retained(); assertEquals(0, form.failures()); form.accept(); form.adopted();
      }
    }
  }

  @Test
  void obsoletePlanningFeedbackCannotConsumeAReadyImportTransactionCreatedByItsDescription() throws Exception {
    var tasks = new ArrayDeque<Runnable>();
    try (var form = new Form(Kind.IMPORT_PLAN, tasks::add, Runnable::run)) {
      form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() {
          throw new AssertionError() {
            public String getMessage() {
              form.retry(); tasks.remove().run(); form.accept(); form.importing.markShown(form.importing.preview());
              form.importing.apply(); tasks.remove().run(); return "obsolete planning failure";
            }
          };
        }
      });
      form.accept(); assertTrue(form.importing.busy()); form.domain.retained(form.state); assertEquals(0, form.failures());
      form.importing.acceptPrepared(); assertTrue(form.importing.closed()); assertEquals(3, form.state.project().noise2D.size());
    }
  }
}
