package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred explicit Properties Apply, private ownership, diagnostics and guarded history. */
class PropertiesApplySessionTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.removeFirst().run();
    }
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(
        s.change(
            p -> {
              p.project.seed = 17;
              var n = new Noise();
              n.id = "field";
              n.algorithm.key = "CONSTANT";
              n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", .125);
              p.noise2D.add(n);
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static PropertiesApplySession session(EditorState s, Executor workers, Queue<Runnable> ui) {
    return new PropertiesApplySession(s, workers, ui::add, () -> false);
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  @Test
  void compilationRunsOffThreadAndOnlyUiDeliveryPublishesOneUndoableEdit() throws Exception {
    try (var state = state();
        var workers = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var called = new AtomicReference<Thread>();
      var ownerThread = Thread.currentThread();
      state.document.setValidator(
          p -> {
            called.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      try (var apply = session(state, workers, ui)) {
        assertTrue(apply.start());
        var completion = ui.poll(10, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertNotSame(ownerThread, called.get());
        assertTrue(apply.running());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertSame(draft, state.draft);
        completion.run();
        assertEquals(PropertiesApplySession.Phase.APPLIED, apply.view().phase());
        assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertNotSame(draft, state.draft);
        state.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        state.document.redo();
        assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
    }
  }

  @Test
  void queuedAndCompletedCancellationRetainDraftAndRejectLateCallbacks() {
    for (boolean completed : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var owner = new ProjectFileOwner(state);
        try (var apply = session(state, workers, ui)) {
          assertTrue(apply.start());
          if (completed) workers.run();
          apply.cancel();
          if (completed) ui.remove().run();
          else workers.run();
          assertEquals(PropertiesApplySession.Phase.CANCELLED, apply.view().phase());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void privateRawSeedSelectionAndCommittedChangesCannotPublishAnOlderDraft() {
    for (int mode = 0; mode < 5; mode++)
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var apply = session(state, workers, ui)) {
          assertTrue(apply.start());
          workers.run();
          if (mode == 0) ((Noise) state.draft).algorithm.parameters.put("value", .75);
          else if (mode == 1) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          else if (mode == 2) state.refreshDraft();
          else if (mode == 3) assertTrue(state.change(p -> p.project.seed = 18));
          else state.project().project.seed = 18;
          var owner = new ProjectFileOwner(state);
          ui.remove().run();
          assertEquals(PropertiesApplySession.Phase.STALE, apply.view().phase());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void savePolicyNewAndSameUuidOpenInvalidatePreparationWithoutOverwritingNewState()
      throws Exception {
    for (int mode = 0; mode < 5; mode++)
      try (var state = state()) {
        var path = directory.resolve("source-" + mode + ".json");
        state.document.save(path);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        if (mode == 4)
          state.document.setValidator(
              p -> List.of(Diagnostic.error("OLD_POLICY", "/fixture", "Rejected")));
        try (var apply = session(state, workers, ui)) {
          assertTrue(apply.start());
          workers.run();
          if (mode == 0) state.document.save(path);
          else if (mode == 1) state.document.setValidator(p -> List.of());
          else if (mode == 2) state.newSession();
          else if (mode == 3) assertTrue(state.document.open(path).isEmpty());
          else state.document.setValidator(NoiseCompiler::validate);
          var owner = new ProjectFileOwner(state);
          ui.remove().run();
          assertEquals(PropertiesApplySession.Phase.STALE, apply.view().phase());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void repeatedRejectionsKeepCompilerDiagnosticsAndPrivateValuesUntilCorrectedRetry() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(state.project());
      var issues = new ArrayList<Diagnostic>();
      for (int i = 0; i < 300; i++)
        issues.add(Diagnostic.error("FIXTURE", "/fixture/" + i, "Rejected"));
      state.document.setValidator(p -> issues);
      try (var apply = session(state, workers, ui)) {
        for (int i = 0; i < 2; i++) {
          assertTrue(apply.start());
          deliver(workers, ui);
          assertEquals(PropertiesApplySession.Phase.REJECTED, apply.view().phase());
          assertEquals(300, state.issues.size());
          assertSame(draft, state.draft);
        }
        issues.clear();
        assertEquals(300, state.issues.size());
        assertEquals(values, ProjectIO.JSON.valueToTree(state.project()));
        state.document.setValidator(NoiseCompiler::validate);
        assertTrue(apply.start());
        deliver(workers, ui);
        assertEquals(PropertiesApplySession.Phase.APPLIED, apply.view().phase());
        assertTrue(state.issues.isEmpty());
      }
    }
  }

  @Test
  void schedulingWorkerAndHandoffErrorsOfferExplicitRetryAndPreserveSource() {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        var values = ProjectIO.JSON.valueToTree(state.project());
        int fault = mode;
        state.document.setValidator(
            p -> {
              if (fault == 1 && fail.get()) throw new AssertionError("worker fixture");
              return NoiseCompiler.validate(p);
            });
        Executor execution =
            task -> {
              if (fault == 0 && fail.get())
                throw new RejectedExecutionException("scheduler fixture");
              workers.execute(task);
            };
        try (var apply =
            new PropertiesApplySession(
                state,
                execution,
                task -> {
                  if (fault == 2 && fail.get()) throw new AssertionError("handoff fixture");
                  ui.add(task);
                },
                () -> false)) {
          apply.start();
          if (mode != 0) {
            workers.run();
            if (!ui.isEmpty()) ui.remove().run();
          }
          assertEquals(PropertiesApplySession.Phase.FAILED, apply.view().phase());
          assertEquals(values, ProjectIO.JSON.valueToTree(state.project()));
          fail.set(false);
          assertTrue(apply.start());
          deliver(workers, ui);
          assertEquals(PropertiesApplySession.Phase.APPLIED, apply.view().phase());
        }
      }
  }

  @Test
  void invalidSeedAndMultipleSelectionRejectBeforeScheduling() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var apply = session(state, workers, ui)) {
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        assertFalse(apply.start());
        assertFalse(state.issues.isEmpty());
        assertTrue(workers.tasks.isEmpty());
        state.refreshDraft();
        state.multiSelection.add(UUID.randomUUID());
        assertFalse(apply.start());
        assertTrue(workers.tasks.isEmpty());
      }
    }
  }

  @Test
  void admissionAndCompletionGateCancellationRetireOnlyTheirOwnRequest() {
    for (boolean admission : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var holder = new PropertiesApplySession[1];
        var owner = new ProjectFileOwner(state);
        try (var apply =
            new PropertiesApplySession(
                state,
                workers,
                ui::add,
                () -> {
                  if (calls.incrementAndGet() == (admission ? 1 : 2)) holder[0].cancel();
                  return false;
                })) {
          holder[0] = apply;
          boolean accepted = apply.start();
          if (admission) assertFalse(accepted);
          else deliver(workers, ui);
          assertEquals(PropertiesApplySession.Phase.CANCELLED, apply.view().phase());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void newerAndInPlaceDiagnosticChangesCannotBeOverwrittenByACompletedApply() {
    for (boolean inplace : List.of(false, true))
      try (var state = state()) {
        state.issues = new ArrayList<>();
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var apply = session(state, workers, ui)) {
          assertTrue(apply.start());
          workers.run();
          var later = Diagnostic.error("NEWER", "/fixture", "New result");
          if (inplace) state.issues.add(later);
          else state.issues = List.of(later);
          ui.remove().run();
          assertEquals(PropertiesApplySession.Phase.STALE, apply.view().phase());
          assertEquals(List.of(later), state.issues);
          assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        }
      }
  }

  @Test
  void noOpApplyKeepsRedoAndPriorCompiledSnapshotsRemainIndependent() {
    try (var state = state()) {
      var previous = new CompiledProject(state.project()).noise2D("field", 17);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var apply = session(state, workers, ui)) {
        assertTrue(apply.start());
        deliver(workers, ui);
        assertEquals(.125, previous.sample(1, 2));
        assertEquals(.625, new CompiledProject(state.project()).noise2D("field", 17).sample(1, 2));
        state.document.undo();
        state.refreshDraft();
        long bytes = state.document.historyBytes();
        assertTrue(apply.start());
        deliver(workers, ui);
        assertTrue(state.document.canRedo());
        assertEquals(bytes, state.document.historyBytes());
      }
    }
  }

  @Test
  void duplicateStartsAndClosureCannotQueueOrPublishAnotherApply() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var apply = session(state, workers, ui);
      var owner = new ProjectFileOwner(state);
      assertTrue(apply.start());
      assertFalse(apply.start());
      assertEquals(1, workers.tasks.size());
      workers.run();
      apply.close();
      ui.remove().run();
      assertEquals(PropertiesApplySession.Phase.CLOSED, apply.view().phase());
      assertFalse(apply.start());
      assertTrue(owner.valuesCurrent(state));
      apply.close();
    }
  }

  @Test
  void closingTheProductionPanelRetiresItsApplyAndSuccessorsKeepTheirOwnDrafts() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new PropertyPanel(state, workers, ui::add);
      assertTrue(panel.probeApplySession().start());
      workers.run();
      panel.close();
      var successor = new PropertyPanel(state, workers, ui::add);
      ui.remove().run();
      assertEquals(PropertiesApplySession.Phase.IDLE, successor.probeApplySession().view().phase());
      assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      successor.close();
    }
  }
}
