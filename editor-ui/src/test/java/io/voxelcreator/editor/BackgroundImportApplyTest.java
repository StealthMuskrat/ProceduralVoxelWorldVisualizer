package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred final-import worker/publication definitions; execution remains paused. */
class BackgroundImportApplyTest {
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("import queue full");
      tasks.add(task);
    }

    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
      tasks.remove().run();
    }
  }

  static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(
        s.change(
            p -> {
              p.project.seed = 17;
              p.noise2D.add(noise("existing", .125));
              p.noise2D.add(noise("shared", .25));
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).id = "private";
    s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    return s;
  }

  static ProjectData incoming() {
    var p = new ProjectData();
    p.noise2D.add(noise("added", .75));
    return p;
  }

  static ImportEditSession session(
      EditorState s, Executor worker, Queue<Runnable> ui, BooleanSupplier blocked) {
    var edit = new ImportEditSession(s, incoming(), worker, ui::add, blocked);
    edit.markShown(edit.preview());
    return edit;
  }

  static void deliver(Workers worker, Queue<Runnable> ui, ImportEditSession edit) {
    worker.run();
    ui.remove().run();
    edit.acceptPrepared();
  }

  @Test
  void actualWorkerPreparesValidationButOnlyOwnerAcceptanceCommitsOneOwnedTransaction()
      throws Exception {
    try (var s = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var entered = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      s.document.setValidator(
          p -> {
            entered.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var edit = session(s, worker, ui, () -> false);
      var draft = s.draft;
      var before = ProjectIO.JSON.valueToTree(s.project());
      var privateValues = ProjectIO.JSON.valueToTree(draft);
      var preferences = ProjectIO.JSON.valueToTree(s.user);
      long revision = s.revision, history = s.document.historyBytes();
      assertEquals(ImportEditSession.Result.STARTED, edit.apply());
      var completion = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(completion);
      assertNotSame(caller, entered.get());
      for (int i = 0; i < 12; i++) {
        assertTrue(edit.busy());
        assertFalse(edit.canApply());
        assertEquals(ImportEditSession.Result.BUSY, edit.apply());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(history, s.document.historyBytes());
      }
      completion.run();
      completion.run();
      assertTrue(edit.busy());
      assertFalse(edit.closed());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(history, s.document.historyBytes());
      edit.acceptPrepared();
      assertTrue(edit.closed());
      assertEquals(ImportEditSession.ApplyPhase.APPLIED, edit.applyPhase());
      assertEquals(revision + 1, s.revision);
      assertSame(draft, s.draft);
      assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft));
      assertEquals("-", s.seedInputs.text("seedOffset"));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(s.user));
      assertEquals(
          List.of("existing", "shared", "added"),
          s.project().noise2D.stream().map(n -> n.id).toList());
      assertEquals(
          .75,
          new NoiseCompiler(s.project(), 17)
              .compile(s.project().noise2D.getLast().uuid)
              .sample(-7, 0, 3));
      var after = ProjectIO.JSON.valueToTree(s.project());
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo();
      assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(ImportEditSession.Result.CLOSED, edit.apply());
    }
  }

  @Test
  void schedulingFailureOffersExplicitImportRetryWithoutChangingReviewedCopyAllocation() {
    try (var s = state()) {
      var worker = new Workers();
      worker.reject = true;
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui, () -> false);
      var plan = edit.preview();
      var id = plan.rows().getFirst().targetUuid();
      long history = s.document.historyBytes();
      assertEquals(ImportEditSession.Result.REJECTED, edit.apply());
      assertFalse(edit.busy());
      assertEquals(ImportEditSession.ApplyPhase.FAILED, edit.applyPhase());
      assertTrue(edit.canApply());
      for (int i = 0; i < 12; i++) {
        edit.preview();
        assertTrue(worker.tasks.isEmpty());
      }
      worker.reject = false;
      assertEquals(ImportEditSession.Result.STARTED, edit.apply());
      deliver(worker, ui, edit);
      assertTrue(edit.closed());
      assertSame(plan, edit.preview());
      assertEquals(id, s.project().noise2D.getLast().uuid);
      assertTrue(s.document.historyBytes() > history);
    }
  }

  @Test
  void workerErrorsAndHandoffFailuresEndPendingStateAndAllowExplicitRetry() {
    for (boolean handoff : new boolean[] {false, true})
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        s.document.setValidator(
            p -> {
              if (!handoff && fail.get()) throw new AssertionError("validator unavailable");
              return NoiseCompiler.validate(p);
            });
        var edit =
            new ImportEditSession(
                s,
                incoming(),
                worker,
                task -> {
                  if (handoff && fail.get()) throw new AssertionError("UI queue unavailable");
                  ui.add(task);
                },
                () -> false);
        edit.markShown(edit.preview());
        edit.apply();
        worker.run();
        if (!ui.isEmpty()) {
          ui.remove().run();
          edit.acceptPrepared();
        }
        assertFalse(edit.busy());
        assertEquals(ImportEditSession.ApplyPhase.FAILED, edit.applyPhase());
        assertTrue(edit.canApply());
        assertEquals(
            1,
            s.events.snapshot().stream()
                .filter(e -> e.code().equals("IMPORT_APPLY_FAILED"))
                .count());
        fail.set(false);
        edit.apply();
        deliver(worker, ui, edit);
        assertTrue(edit.closed());
        assertEquals(3, s.project().noise2D.size());
      }
  }

  @Test
  void externallyCancelledFutureCannotStrandImportPreparation() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui, () -> false);
      edit.apply();
      ((Future<?>) worker.tasks.getFirst()).cancel(false);
      assertFalse(edit.busy());
      assertEquals(ImportEditSession.ApplyPhase.FAILED, edit.applyPhase());
      edit.apply();
      deliver(worker, ui, edit);
      assertTrue(edit.closed());
    }
  }

  @Test
  void rejectedDiagnosticsRemainBoundedAndTheSameReviewedPlanCanBeRetried() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui, () -> false);
      var issues =
          java.util.stream.IntStream.range(0, 300)
              .mapToObj(i -> Diagnostic.error("IMPORT_REJECT", "/fixture/" + i, "Rejected"))
              .toList();
      s.document.setValidator(p -> issues);
      var before = ProjectIO.JSON.valueToTree(s.project());
      edit.apply();
      deliver(worker, ui, edit);
      assertEquals(ImportEditSession.ApplyPhase.REJECTED, edit.applyPhase());
      assertEquals(300, edit.issueCount());
      assertEquals(256, edit.applyIssues().size());
      assertEquals(issues, s.issues);
      assertEquals("Rejected import", s.validationScope);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertTrue(edit.canApply());
      s.document.setValidator(NoiseCompiler::validate);
      edit.apply();
      deliver(worker, ui, edit);
      assertTrue(edit.closed());
      assertEquals(0, edit.issueCount());
    }
  }

  @Test
  void cancellationAfterWorkerCompletionPreservesTheProjectAndRejectsLatePublication() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui, () -> false);
      var before = ProjectIO.JSON.valueToTree(s.project());
      long history = s.document.historyBytes();
      edit.apply();
      worker.run();
      var late = ui.remove();
      edit.cancel();
      late.run();
      late.run();
      assertTrue(edit.closed());
      assertEquals(ImportEditSession.ApplyPhase.CANCELLED, edit.applyPhase());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(history, s.document.historyBytes());
    }
  }

  @Test
  void aCancelledPredecessorCannotCommitOrCloseItsSuccessor() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var first = session(s, worker, ui, () -> false);
      first.apply();
      worker.run();
      var old = ui.remove();
      first.cancel();
      var next = session(s, worker, ui, () -> false);
      next.apply();
      old.run();
      assertTrue(next.busy());
      deliver(worker, ui, next);
      assertTrue(next.closed());
      assertEquals(3, s.project().noise2D.size());
    }
  }

  @Test
  void privateFieldRawInputSelectionAndKindChangesRetirePreparedResults() {
    for (int change = 0; change < 4; change++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui, () -> false);
        edit.apply();
        worker.run();
        switch (change) {
          case 0 -> ((Noise) s.draft).id = "new-private";
          case 1 -> s.seedInputs.edit(s, "seedOffset", "Seed", "+");
          case 2 -> s.select("noise2D", s.project().noise2D.getLast().uuid);
          case 3 -> s.selectedKind = "changed-kind";
        }
        var current = ProjectIO.JSON.valueToTree(s.draft);
        ui.remove().run();
        edit.acceptPrepared();
        assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
        assertEquals(2, s.project().noise2D.size());
        assertEquals(current, ProjectIO.JSON.valueToTree(s.draft));
        assertFalse(edit.closed());
      }
  }

  @Test
  void newerDiagnosticsAndInPlaceIssueListChangesAreNeverOverwritten() {
    for (boolean sameList : new boolean[] {false, true})
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var list = new ArrayList<Diagnostic>();
        s.issues = list;
        s.validationScope = "Existing validation";
        var edit = session(s, worker, ui, () -> false);
        edit.apply();
        worker.run();
        var newer = Diagnostic.error("NEWER", "/current", "Keep current validation");
        if (sameList) list.add(newer);
        else {
          s.issues = List.of(newer);
          s.validationScope = "Newer validation";
        }
        var values = List.copyOf(s.issues);
        String scope = s.validationScope;
        ui.remove().run();
        edit.acceptPrepared();
        assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
        assertEquals(values, s.issues);
        assertEquals(scope, s.validationScope);
        assertEquals(2, s.project().noise2D.size());
      }
  }

  @Test
  void destinationValueChangesRequireReloadAndFreshReview() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = session(s, worker, ui, () -> false);
      edit.apply();
      worker.run();
      s.project().project.seed = 18;
      ui.remove().run();
      edit.acceptPrepared();
      assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
      assertFalse(edit.current());
      assertEquals(ImportEditSession.Result.STALE, edit.apply());
      edit.reload();
      deliver(worker, ui, edit);
      assertFalse(edit.canApply());
      edit.markShown(edit.preview());
      edit.apply();
      deliver(worker, ui, edit);
      assertTrue(edit.closed());
      assertEquals(18, s.project().project.seed);
    }
  }

  @Test
  void changedCheckpointHistoryOrValidationPolicyCannotPublishPreparedImport(
      @TempDir Path directory) throws Exception {
    for (int change = 0; change < 3; change++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui, () -> false);
        edit.apply();
        worker.run();
        switch (change) {
          case 0 -> s.document.save(directory.resolve("checkpoint.json"));
          case 1 -> s.document.undo();
          case 2 -> s.document.setValidator(p -> List.of());
        }
        var current = ProjectIO.JSON.valueToTree(s.project());
        ui.remove().run();
        edit.acceptPrepared();
        assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
        assertEquals(current, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void newAndSameUuidOpenRejectPredecessorAuthority(@TempDir Path directory) throws Exception {
    for (boolean reopen : new boolean[] {false, true})
      try (var s = state()) {
        var path = directory.resolve("same.json");
        s.document.save(path);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edit = session(s, worker, ui, () -> false);
        edit.apply();
        worker.run();
        if (reopen) assertTrue(s.document.open(path).isEmpty());
        else s.newSession();
        var current = ProjectIO.JSON.valueToTree(s.project());
        ui.remove().run();
        edit.acceptPrepared();
        assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
        assertFalse(edit.canReload());
        assertEquals(current, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void blockerChangesAndReentrantCancellationAreCheckedBeforePublication() {
    for (int change = 0; change < 3; change++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var action = new AtomicReference<Runnable>();
        var blocked = new AtomicBoolean();
        var edit =
            session(
                s,
                worker,
                ui,
                () -> {
                  var next = action.getAndSet(null);
                  if (next != null) next.run();
                  return blocked.get();
                });
        edit.apply();
        worker.run();
        switch (change) {
          case 0 -> blocked.set(true);
          case 1 -> action.set(edit::cancel);
          case 2 ->
              action.set(
                  () -> {
                    throw new AssertionError("gate unavailable");
                  });
        }
        ui.remove().run();
        edit.acceptPrepared();
        assertEquals(2, s.project().noise2D.size());
        assertFalse(edit.busy());
        assertEquals(
            change == 0
                ? ImportEditSession.ApplyPhase.STALE
                : change == 1
                    ? ImportEditSession.ApplyPhase.CANCELLED
                    : ImportEditSession.ApplyPhase.FAILED,
            edit.applyPhase());
      }
  }

  @Test
  void allReuseSkipNoOpImportIsConsumedWithoutInventingAnUndoEntry() {
    try (var s = state()) {
      assertTrue(
          s.change(
              p ->
                  p.project.rootOrder.put(
                      "noise2D", p.noise2D.stream().map(n -> n.uuid).toList())));
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var incoming = incoming();
      incoming.noise2D.add(0, noise("shared", .25));
      var edit = new ImportEditSession(s, incoming, worker, ui::add, () -> false);
      edit.choose(incoming.noise2D.getFirst().uuid, ResourceImport.Choice.REUSE);
      deliver(worker, ui, edit);
      edit.choose(incoming.noise2D.getLast().uuid, ResourceImport.Choice.SKIP);
      deliver(worker, ui, edit);
      edit.markShown(edit.preview());
      var before = ProjectIO.JSON.valueToTree(s.project());
      long history = s.document.historyBytes();
      edit.apply();
      deliver(worker, ui, edit);
      assertTrue(edit.closed());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(history, s.document.historyBytes());
      assertEquals(ImportEditSession.Result.CLOSED, edit.apply());
    }
  }

  @Test
  void unreviewedAndBusyPlansNeverAuthorizeAnotherTransactionOrChoiceChange() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edit = new ImportEditSession(s, incoming(), worker, ui::add, () -> false);
      assertEquals(ImportEditSession.Result.NOT_REVIEWED, edit.apply());
      edit.markShown(edit.preview());
      edit.apply();
      assertEquals(ImportEditSession.Result.BUSY, edit.apply());
      assertFalse(edit.canReload());
      assertThrows(
          IllegalStateException.class,
          () -> edit.choose(incoming().noise2D.getFirst().uuid, ResourceImport.Choice.COPY));
      edit.cancel();
      assertTrue(edit.closed());
      assertEquals(2, s.project().noise2D.size());
    }
  }
}
