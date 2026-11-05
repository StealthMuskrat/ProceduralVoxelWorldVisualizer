package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred complete-posting/admission definitions for member transactions and private curves. */
class ChildApplyCompletionTest {
  private static final long TIMEOUT = 10;
  private enum Path { SET, GROUP, CURVE }

  private static final class Form implements AutoCloseable {
    final EditorState state;
    final MembershipEditSession members;
    final CurveApplySession curve;
    final Curve local;
    final AtomicReference<Error> failure = new AtomicReference<>();
    final AtomicReference<RuntimeException> stageFailure = new AtomicReference<>();
    final AtomicBoolean reject = new AtomicBoolean();
    final AtomicInteger stages = new AtomicInteger(), followUps = new AtomicInteger();
    final ProjectFileOwner owner;
    final JsonNode project, draft, user, localBase;
    final long history, revision, raw;
    final String privateId, activeId;
    Form(Path path, Executor worker, Consumer<Runnable> ui) {
      state = path == Path.CURVE ? BackgroundCurveApplyTest.state()
          : BackgroundMembershipTest.state(path == Path.SET ? "set" : "group");
      state.document.setValidator(p -> {
        var fault = failure.get(); if (fault != null) throw fault;
        return reject.get() ? List.of(Diagnostic.error("REJECT", "/", "member rejection")) : NoiseCompiler.validate(p);
      });
      if (path == Path.CURVE) {
        BackgroundCurveApplyTest.noise(state).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        local = BackgroundCurveApplyTest.curve(state); local.points.getLast().y = 3;
        var source = new CurveDraftEdit(state, BackgroundCurveApplyTest.noise(state), BackgroundCurveApplyTest.item(state));
        curve = new CurveApplySession(followUps::incrementAndGet, worker, ui, () -> source.current(state), () -> local,
            value -> {
              var fault = stageFailure.get(); if (fault != null) throw fault;
              source.stagePrepared(state, value); stages.incrementAndGet();
            }, input -> {
              var fault = failure.get(); if (fault != null) throw fault;
              return CurveDraftEdit.prepare(input);
            });
        members = null; privateId = BackgroundCurveApplyTest.noise(state).id;
        activeId = state.project().noise2D.getFirst().id;
      } else {
        local = null; curve = null;
        members = new MembershipEditSession(state, (Membership) state.draft, worker, ui);
        members.set(BackgroundMembershipTest.type(state, 0), true); privateId = ((Membership) state.draft).id;
        activeId = ((Membership) ResourceGraph.find(state.project(), state.selected).resource()).id;
      }
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project());
      draft = ProjectIO.JSON.valueToTree(state.draft); user = ProjectIO.JSON.valueToTree(state.user);
      localBase = ProjectIO.JSON.valueToTree(local); history = state.document.historyBytes(); revision = state.revision;
      raw = state.seedInputs.version(state);
    }
    void start() { assertFalse(curve == null ? members.apply(state) : curve.apply(local)); }
    boolean accept() { return curve == null ? members.acceptPrepared() : curve.accept(); }
    boolean pending() { return curve == null ? members.applying() : curve.running(); }
    boolean closed() { return curve == null ? members.closed() : curve.closed(); }
    long version() { return curve == null ? members.workVersion() : curve.version(); }
    String error() { return curve == null ? members.applyFailure() : curve.error(); }
    String progress() { return curve == null ? members.progress() : curve.progress(); }
    void cancelWork() { if (curve == null) members.suspendApply(); else curve.cancelApply(); }
    void cancel() { if (curve == null) members.cancel(); else curve.cancel(); }
    Object actor() { return curve == null ? members : curve; }
    long failures() {
      return state.events.snapshot().stream().filter(e -> e.code().equals("MEMBERSHIP_APPLY_FAILED"))
          .mapToLong(EditorEvents.Entry::count).sum();
    }
    void retained() {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft)); assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(localBase, ProjectIO.JSON.valueToTree(local)); assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision); assertEquals(raw, state.seedInputs.version(state));
      assertEquals(0, stages.get()); assertEquals(0, followUps.get());
    }
    void accepted() {
      assertTrue(closed()); assertFalse(pending()); assertEquals("", error());
      assertEquals(raw, state.seedInputs.version(state)); assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      if (curve != null) {
        assertEquals(1, stages.get()); assertEquals(1, followUps.get());
        assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); assertEquals(history, state.document.historyBytes());
        assertEquals(3, BackgroundCurveApplyTest.curve(state).points.getLast().y);
        assertEquals(privateId, BackgroundCurveApplyTest.noise(state).id); assertFalse(state.applyDraft());
        state.seedInputs.edit(state, "seedOffset", "Seed", "0"); assertTrue(state.applyDraft());
        var field = new NoiseCompiler(state.project(), 0).compile(state.selected);
        assertEquals(.75, field.sample(.25, 0, 0)); assertEquals(1.5, field.sample(.5, 0, 0)); assertEquals(3, field.sample(2, 0, 0));
      } else {
        assertEquals(privateId, ((Membership) state.draft).id);
        var active = (Membership) ResourceGraph.find(state.project(), state.selected).resource();
        assertEquals(activeId, active.id); assertEquals(members.values(), active.voxelTypeUuids);
        assertEquals(active.voxelTypeUuids, ((Membership) state.draft).voxelTypeUuids); assertEquals(revision + 1, state.revision);
      }
      var after = ProjectIO.JSON.valueToTree(state.project()); state.document.undo();
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); state.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
    }
    public void close() { cancel(); state.close(); }
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
    Field field = form.actor().getClass().getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(form.actor())).cancel(false); field.set(form.actor(), replacement);
  }

  @Test
  void heldPostingRetainsEveryDomainUntilCompleteThenConsumesExactlyOnce() throws Exception {
    for (var path : Path.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(path, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); long version = form.version(); var callback = receipt(ui); callback.run(); assertFalse(form.accept());
          form.retained(); assertTrue(form.pending()); assertEquals(version, form.version());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          assertTrue(form.accept()); callback.run(); assertFalse(form.accept()); form.accepted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void latePostingFaultRetainsPrivateInputAndExplicitRetryConsumesOnlyOnce() throws Exception {
    for (var path : Path.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(path, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
      })) {
        try {
          form.start(); var old = receipt(ui); old.run(); assertFalse(form.accept()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); assertFalse(form.accept());
          assertFalse(form.pending()); assertTrue(form.error().contains("late post")); form.retained();
          assertEquals(path == Path.CURVE ? 0 : 1, form.failures()); broken.set(false); form.start(); old.run();
          receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); assertTrue(form.accept()); form.accepted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlinePostingAndUnfinishedAdmissionCannotAcceptEitherResultOrCapturedFailure() {
    for (var path : Path.values()) for (boolean failed : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>(); var seen = new AtomicInteger();
      try (var form = new Form(path, task -> {
        task.run(); assertFalse(ref.get().accept()); ref.get().retained(); seen.incrementAndGet();
      }, callback -> {
        callback.run(); assertFalse(ref.get().accept()); ref.get().retained(); seen.incrementAndGet();
      })) {
        ref.set(form); if (failed) form.failure.set(new AssertionError("planner"));
        form.start(); assertEquals(2, seen.get()); assertTrue(form.pending()); assertEquals("", form.error()); assertEquals(0, form.failures());
        if (failed) { assertFalse(form.accept()); assertFalse(form.pending()); form.retained(); assertTrue(form.error().contains("planner")); }
        else { assertTrue(form.accept()); form.accepted(); }
      }
    }
  }

  @Test
  void laterPosterAndExecutorFaultsCannotHideBehindInlineAcceptance() {
    for (var path : Path.values()) for (boolean executor : new boolean[] {false, true}) {
      var ref = new AtomicReference<Form>();
      try (var form = new Form(path, task -> { task.run(); if (executor) throw new AssertionError("admission fault"); }, callback -> {
        callback.run(); assertFalse(ref.get().accept()); if (!executor) throw new AssertionError("posting fault");
      })) {
        ref.set(form); form.start(); assertFalse(form.accept()); assertFalse(form.pending()); form.retained(); assertFalse(form.error().isEmpty());
        assertEquals(path == Path.CURVE ? 0 : 1, form.failures());
      }
    }
  }

  @Test
  void closingHeldSuccessOrFailureSuppressesLatePostingFaults() throws Exception {
    for (var path : Path.values()) for (boolean failed : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(path, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.failure.set(new AssertionError("planner")); form.start(); var old = receipt(ui); old.run();
          assertFalse(form.accept()); form.cancel(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          old.run(); assertFalse(form.accept()); assertTrue(form.closed()); assertEquals("", form.error()); assertEquals(0, form.failures()); form.retained();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void normalCompletionStillRequiresItsOwnReadinessReceipt() {
    for (var path : Path.values()) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(path, tasks::add, ui::add)) {
        form.start(); tasks.remove().run(); assertFalse(form.accept()); assertTrue(form.pending()); form.retained();
        ui.remove().run(); assertTrue(form.accept()); form.accepted();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadsAreContained() throws Exception {
    for (var path : Path.values()) for (int mode = 0; mode < 3; mode++) {
      final int fault = mode; var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read"); throw new AssertionError("get");
          }
        });
        assertFalse(form.accept()); assertFalse(form.pending()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); assertFalse(form.error().isEmpty()); form.retained(); assertEquals(path == Path.CURVE ? 0 : 1, form.failures());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalReadCannotConsumeOrFailTheReadySuccessorItCreates() throws Exception {
    for (var path : Path.values()) for (int mode = 0; mode < 3; mode++) {
      final int fault = mode; var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            form.cancelWork(); form.start(); tasks.remove().run();
            if (fault == 1) throw new AssertionError("obsolete get");
            if (fault == 2) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        assertFalse(form.accept()); form.retained(); assertTrue(form.pending()); assertEquals("", form.error()); assertEquals(0, form.failures());
        assertTrue(form.accept()); form.accepted();
      }
    }
  }

  @Test
  void failureMessageCannotOverwriteReadyRetryOrClosedOwner() {
    for (var path : Path.values()) for (boolean close : new boolean[] {false, true})
      try (var form = new Form(path, Runnable::run, Runnable::run)) {
        form.failure.set(new AssertionError() {
          public String getMessage() { form.failure.set(null); if (close) form.cancel(); else form.start(); return "obsolete feedback"; }
        });
        form.start(); assertFalse(form.accept()); form.retained(); assertEquals("", form.error()); assertEquals(0, form.failures());
        if (close) assertTrue(form.closed()); else { assertTrue(form.pending()); assertTrue(form.accept()); form.accepted(); }
      }
  }

  @Test
  void cancellationCleanupCannotOverwriteTheReadyRequestItCreates() throws Exception {
    for (var path : Path.values()) {
      var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { form.start(); tasks.remove().run(); progress.set(form.progress()); return super.cancel(interrupt); }
        });
        form.cancelWork(); assertEquals(progress.get(), form.progress()); form.retained(); assertTrue(form.pending());
        assertTrue(form.accept()); form.accepted();
      }
    }
  }

  @Test
  void finalClosurePrecedesCancellationCallbacksTryingAnotherApply() throws Exception {
    for (var path : Path.values()) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            try { form.start(); } catch (IllegalStateException closed) { denied.incrementAndGet(); } return super.cancel(interrupt);
          }
        });
        form.cancel(); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); assertTrue(form.closed()); form.retained();
      }
    }
  }

  @Test
  void successfulMemberCommitClosesBeforeItsCompletedTaskIsRetired() throws Exception {
    for (var path : List.of(Path.SET, Path.GROUP)) {
      var tasks = new ArrayDeque<Runnable>(); var denied = new AtomicInteger();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) {
            try { form.start(); } catch (IllegalStateException closed) { denied.incrementAndGet(); } return super.cancel(interrupt);
          }
        });
        assertTrue(form.accept()); assertEquals(1, denied.get()); assertTrue(tasks.isEmpty()); assertEquals(0, form.failures()); form.accepted();
      }
    }
  }

  @Test
  void memberDiagnosticsWaitForCompletePostingAndAllowExplicitRetry() throws Exception {
    for (var path : List.of(Path.SET, Path.GROUP)) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(path, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.reject.set(true); form.start(); receipt(ui).run(); assertFalse(form.accept()); assertEquals(0, form.members.issueCount()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); assertFalse(form.accept());
          assertFalse(form.pending()); assertTrue(form.members.issueCount() > 0); form.retained();
          form.reject.set(false); form.start(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          assertTrue(form.accept()); form.accepted();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void rejectionCleanupCannotInstallOldDiagnosticsOverReadyMemberRetry() throws Exception {
    for (var path : List.of(Path.SET, Path.GROUP)) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        form.reject.set(true); form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { return null; }
          public boolean cancel(boolean interrupt) { form.reject.set(false); form.start(); tasks.remove().run(); return super.cancel(interrupt); }
        });
        assertFalse(form.accept()); assertEquals(0, form.members.issueCount()); assertTrue(form.state.issues.isEmpty()); form.retained();
        assertTrue(form.pending()); assertTrue(form.accept()); form.accepted();
      }
    }
  }

  @Test
  void acceptedCurveRetirementCannotStageOverItsReadyReplacement() throws Exception {
    var tasks = new ArrayDeque<Runnable>();
    try (var form = new Form(Path.CURVE, tasks::add, Runnable::run)) {
      form.start(); tasks.remove().run(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { return null; }
        public boolean cancel(boolean interrupt) { form.start(); tasks.remove().run(); return super.cancel(interrupt); }
      });
      assertFalse(form.accept()); form.retained(); assertTrue(form.pending()); assertTrue(form.accept()); form.accepted();
    }
  }

  @Test
  void curveStageFailureDescriptionCannotClearReadyReplacementOrOverwriteClosure() {
    for (boolean close : new boolean[] {false, true}) try (var form = new Form(Path.CURVE, Runnable::run, Runnable::run)) {
      form.stageFailure.set(new IllegalStateException() {
        public String getMessage() {
          form.stageFailure.set(null); if (close) form.cancel(); else { form.cancelWork(); form.start(); } return "obsolete stage failure";
        }
      });
      form.start(); assertFalse(form.accept()); form.retained(); assertEquals("", form.error());
      if (close) assertTrue(form.closed()); else { assertTrue(form.pending()); assertTrue(form.accept()); form.accepted(); }
    }
  }

  @Test
  void memberChoiceRetirementCannotEraseRestoredReadyApply() throws Exception {
    for (var path : List.of(Path.SET, Path.GROUP)) {
      var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
      try (var form = new Form(path, tasks::add, Runnable::run)) {
        var id = BackgroundMembershipTest.type(form.state, 0); form.start(); tasks.remove();
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            form.members.set(id, true); form.start(); tasks.remove().run(); progress.set(form.progress()); return super.cancel(interrupt);
          }
        });
        form.members.set(id, false); assertEquals(progress.get(), form.progress()); form.retained();
        assertTrue(form.pending()); assertTrue(form.accept()); form.accepted();
      }
    }
  }

  @Test
  void changedCurveRetirementCannotOverwriteTheCurrentReadyApply() throws Exception {
    var tasks = new ArrayDeque<Runnable>(); var progress = new AtomicReference<String>();
    try (var form = new Form(Path.CURVE, tasks::add, Runnable::run)) {
      form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean cancel(boolean interrupt) {
          form.local.points.getLast().y = 3; form.start(); tasks.remove().run(); progress.set(form.progress()); return super.cancel(interrupt);
        }
      });
      form.local.points.getLast().y = 7; assertFalse(form.accept()); assertEquals(progress.get(), form.progress()); form.retained();
      assertTrue(form.pending()); assertTrue(form.accept()); form.accepted();
    }
  }
}
