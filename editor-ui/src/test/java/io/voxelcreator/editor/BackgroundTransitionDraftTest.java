package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred explicit Apply Draft preparation before New/Open/Exit and committed-save decisions. */
class BackgroundTransitionDraftTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }

  EditorState state(boolean saved) throws Exception {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      p.project.seed = 41;
      for (String id : List.of("owned", "other")) {
        var n = new Noise(); n.id = id; n.algorithm.key = "CONSTANT";
        n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
      }
    }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    if (saved) s.document.save(directory.resolve(UUID.randomUUID() + ".json"));
    ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static ProjectFileWorkflow workflow(EditorState s, Executor worker, Queue<Runnable> ui, BooleanSupplier blocked) {
    return new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), blocked, worker, ui::add);
  }
  static void start(ProjectFileWorkflow files, Intent intent) {
    files.request(intent); assertEquals(Phase.DRAFT, files.phase());
    files.decideDraft(DraftDecision.APPLY); assertEquals(Phase.APPLYING, files.phase());
  }
  static void deliver(Workers worker, Queue<Runnable> ui) { worker.run(); ui.remove().run(); }

  @Test
  void validationRunsOnWorkerAndDeliveryWaitsForOwnerDecisionBeforeOneUndoableApply() throws Exception {
    try (var s = state(true); var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>(); var called = new AtomicReference<Thread>();
      var owner = Thread.currentThread(); s.document.setValidator(p -> { called.set(Thread.currentThread()); return NoiseCompiler.validate(p); });
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft; long revision = s.revision;
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.OPEN); var completion = ui.poll(10, TimeUnit.SECONDS); assertNotNull(completion);
        assertNotSame(owner, called.get()); completion.run(); completion.run();
        assertEquals(Phase.APPLYING, files.phase()); assertSame(draft, s.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        files.acceptDraft(); files.acceptDraft(); completion.run();
        assertEquals(Phase.GUARD, files.phase()); assertEquals(revision + 1, s.revision);
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        files.decide(Decision.CANCEL); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo(); assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
    }
  }

  @Test
  void everyIntentCanCancelQueuedOrCompletionReadyApplyWithoutDiscardingTheDraft() throws Exception {
    for (var intent : Intent.values()) for (boolean ready : new boolean[] {true, false})
      try (var s = state(true)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        long revision = s.revision, history = s.document.historyBytes();
        var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
        try (var files = workflow(s, worker, ui, () -> false)) {
          start(files, intent); if (ready) deliver(worker, ui); files.cancel();
          if (!ready) worker.run(); while (!ui.isEmpty()) ui.remove().run(); files.acceptDraft();
          assertEquals(Phase.IDLE, files.phase()); assertSame(draft, s.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
          assertEquals(revision, s.revision); assertEquals(history, s.document.historyBytes()); assertFalse(s.document.dirty());
        }
      }
  }

  @Test
  void invalidRawInputRejectsBeforeWorkerAdmissionAndRemainsPrivate() throws Exception {
    try (var s = state(true)) {
      s.seedInputs.edit(s, "seedOffset", "Seed", "-"); var draft = s.draft;
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        files.request(Intent.EXIT); files.decideDraft(DraftDecision.APPLY);
        assertEquals(Phase.DRAFT, files.phase()); assertTrue(worker.tasks.isEmpty());
        assertSame(draft, s.draft); assertEquals("-", s.seedInputs.text("seedOffset"));
        assertFalse(s.document.dirty()); assertFalse(files.error().isBlank());
      }
    }
  }

  @Test
  void rejectedCanonicalDraftCanBeReviewedAndRetriedExplicitlyUnderCurrentPolicy() throws Exception {
    try (var s = state(true)) {
      ((Noise) s.draft).id = " invalid "; var draft = s.draft;
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.OPEN); deliver(worker, ui); files.acceptDraft();
        assertEquals(Phase.DRAFT, files.phase()); assertSame(draft, s.draft); assertFalse(s.issues.isEmpty());
        for (int i = 0; i < 12; i++) files.acceptDraft(); assertTrue(worker.tasks.isEmpty());
        // Changing private input retires the old transition owner. Request again after correction.
        ((Noise) s.draft).id = "owned"; files.decideDraft(DraftDecision.APPLY);
        assertEquals(Phase.IDLE, files.phase());
        start(files, Intent.OPEN); deliver(worker, ui); files.acceptDraft(); assertEquals(Phase.GUARD, files.phase());
      }
    }
  }

  @Test
  void capturedDiagnosticRejectionIsImmutableAndUnchangedRetryUsesNewFeedback() throws Exception {
    try (var s = state(true)) {
      var issues = new ArrayList<Diagnostic>();
      issues.add(Diagnostic.error("FIXTURE", "/noise2D", "first"));
      s.document.setValidator(p -> issues);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.NEW); deliver(worker, ui); files.acceptDraft();
        assertEquals(Phase.DRAFT, files.phase()); issues.clear(); assertEquals(1, s.issues.size());
        files.decideDraft(DraftDecision.APPLY); deliver(worker, ui); files.acceptDraft();
        assertEquals(Phase.GUARD, files.phase()); assertTrue(s.issues.isEmpty());
      }
    }
  }

  @Test
  void draftIdentityRawSelectionCommittedValuesPathAndLifetimeChangesRetirePreparedWork() throws Exception {
    for (int mutation = 0; mutation < 8; mutation++) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.EXIT); deliver(worker, ui);
        switch (mutation) {
          case 0 -> s.refreshDraft();
          case 1 -> ((Noise) s.draft).seedOffset = 9;
          case 2 -> s.seedInputs.edit(s, "seedOffset", "Seed", "-");
          case 3 -> s.multiSelection.add(s.project().noise2D.getLast().uuid);
          case 4 -> s.project().project.seed++;
          case 5 -> s.document.save(directory.resolve("different.json"));
          case 6 -> s.newSession();
          case 7 -> s.change(p -> p.project.seed++);
        }
        var current = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        files.acceptDraft(); assertEquals(Phase.IDLE, files.phase()); assertSame(draft, s.draft);
        assertEquals(current, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void equalCheckpointAndPolicyChangesAreRejectedByPreparedDocumentAuthority() throws Exception {
    for (boolean policy : new boolean[] {true, false}) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var before = ProjectIO.JSON.valueToTree(s.project());
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.OPEN); deliver(worker, ui);
        if (policy) s.document.setValidator(NoiseCompiler::validate); else s.document.save(s.document.path());
        files.acceptDraft(); assertEquals(Phase.IDLE, files.phase());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertFalse(s.document.dirty());
      }
    }
  }

  @Test
  void newSharedDiagnosticsOrScopeAreNeverOverwrittenByAnOlderResult() throws Exception {
    for (boolean scope : new boolean[] {true, false}) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.NEW); deliver(worker, ui);
        var newer = List.of(new Diagnostic("NEW", Diagnostic.Severity.WARNING, "newer", "/", null, null, null));
        if (scope) s.validationScope = "newer scope"; else s.issues = newer;
        files.acceptDraft(); assertEquals(Phase.IDLE, files.phase());
        if (scope) assertEquals("newer scope", s.validationScope); else assertSame(newer, s.issues);
      }
    }
  }

  @Test
  void appearingAndReentrantBlockersCannotGrantOlderApplyAuthority() throws Exception {
    for (boolean reentrant : new boolean[] {true, false}) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var armed = new AtomicBoolean();
      var holder = new ProjectFileWorkflow[1]; var before = ProjectIO.JSON.valueToTree(s.project());
      BooleanSupplier blocker = () -> {
        if (armed.compareAndSet(true, false)) {
          if (!reentrant) return true;
          holder[0].cancel(); holder[0].request(Intent.OPEN);
        }
        return false;
      };
      try (var files = workflow(s, worker, ui, blocker)) {
        holder[0] = files; start(files, Intent.EXIT); deliver(worker, ui);
        armed.set(true); files.acceptDraft();
        assertEquals(reentrant ? Phase.DRAFT : Phase.IDLE, files.phase());
        if (reentrant) assertEquals("Open Project", files.action());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void schedulerWorkerHandoffAndExternalTaskFaultsRequireExplicitApplyRetry() throws Exception {
    for (int mode = 0; mode < 4; mode++) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var broken = new AtomicBoolean(true); int fault = mode;
      s.document.setValidator(p -> { if (fault == 1 && broken.get()) throw new AssertionError("worker"); return NoiseCompiler.validate(p); });
      Executor execution = task -> { if (fault == 0 && broken.get()) throw new RejectedExecutionException("queue"); worker.execute(task); };
      try (var files = new ProjectFileWorkflow(s, () -> fail("replace"), () -> fail("exit"), () -> false,
          execution, action -> { if (fault == 2 && broken.get()) throw new AssertionError("handoff"); ui.add(action); })) {
        files.request(Intent.OPEN); files.decideDraft(DraftDecision.APPLY);
        if (mode == 3) ((Future<?>) worker.tasks.remove()).cancel(false);
        else if (mode != 0) { worker.run(); if (!ui.isEmpty()) ui.remove().run(); files.acceptDraft(); }
        assertEquals(Phase.DRAFT, files.phase()); assertTrue(files.error().contains("failed"));
        for (int i = 0; i < 12; i++) files.acceptDraft(); assertTrue(worker.tasks.isEmpty());
        broken.set(false); files.decideDraft(DraftDecision.APPLY); deliver(worker, ui); files.acceptDraft();
        assertEquals(Phase.GUARD, files.phase());
      }
    }
  }

  @Test
  void cancelledPredecessorDeliveryCannotPopulateAReopenedTransition() throws Exception {
    try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.EXIT); worker.run(); var old = ui.remove(); files.cancel();
        start(files, Intent.OPEN); old.run(); old.run(); files.acceptDraft(); assertEquals(Phase.APPLYING, files.phase());
        deliver(worker, ui); files.acceptDraft(); assertEquals(Phase.GUARD, files.phase());
      }
    }
  }

  @Test
  void closeRejectsReadyAndLateCpuHandoffsAndFurtherRequests() throws Exception {
    for (boolean ready : new boolean[] {true, false}) try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var before = ProjectIO.JSON.valueToTree(s.project());
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.NEW); worker.run(); if (ready) ui.remove().run(); files.close();
        while (!ui.isEmpty()) ui.remove().run(); files.acceptDraft(); files.request(Intent.EXIT);
        assertEquals(Phase.IDLE, files.phase()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void siblingSelectionAppliesOnlyTheActiveDraftAndPreservesSelectionOrder() throws Exception {
    try (var s = state(true)) {
      var first = s.selected; var second = s.project().noise2D.getLast().uuid; s.multiSelection.add(second);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        start(files, Intent.OPEN); deliver(worker, ui); files.acceptDraft();
        assertEquals(Phase.GUARD, files.phase()); assertEquals(first, s.selected);
        assertEquals(List.of(first, second), List.copyOf(s.multiSelection));
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertEquals(.125, s.project().noise2D.getLast().algorithm.parameters.get("value"));
      }
    }
  }

  @Test
  void laterSaveFailureAbortsExitAndRetryWritesOnlyTheExplicitlyAppliedDocument() throws Exception {
    try (var s = state(false)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var exits = new AtomicInteger();
      try (var files = new ProjectFileWorkflow(s, () -> fail("replace"), exits::incrementAndGet, () -> false, worker, ui::add)) {
        start(files, Intent.EXIT); deliver(worker, ui); files.acceptDraft();
        files.decide(Decision.SAVE); files.submit("\u0000"); assertEquals(Phase.SAVE, files.phase());
        var target = directory.resolve("retry.json"); files.submit(target.toString()); deliver(worker, ui); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertEquals(0, exits.get());
        assertEquals(.625, new ProjectIO().load(target).project().noise2D.getFirst().algorithm.parameters.get("value"));
        files.request(Intent.EXIT); assertEquals(1, exits.get());
      }
    }
  }

  @Test
  void postApplyGuardFailureKeepsOneCommittedEditAndCannotReplayTheTransition() throws Exception {
    try (var s = state(true)) {
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); long revision = s.revision;
      var before = ProjectIO.JSON.valueToTree(s.project());
      try (var files = workflow(s, worker, ui, () -> {
        if (s.revision > revision) throw new IllegalStateException("guard completion"); return false;
      })) {
        start(files, Intent.EXIT); worker.run(); var completion = ui.remove(); completion.run(); files.acceptDraft();
        completion.run(); files.acceptDraft(); assertEquals(Phase.IDLE, files.phase());
        assertTrue(files.error().startsWith("Properties applied.")); assertEquals(revision + 1, s.revision);
        s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void changingBackToEqualCanonicalValuesDoesNotReviveTransitionAuthorityOrLoseRedo() throws Exception {
    try (var s = state(true)) {
      s.change(p -> p.project.seed++); s.document.undo(); s.revision++; s.refreshDraft();
      ((Noise) s.draft).algorithm.parameters.put("value", .625);
      long bytes = s.document.historyBytes();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = workflow(s, worker, ui, () -> false)) {
        files.request(Intent.OPEN);
        ((Noise) s.draft).algorithm.parameters.put("value", .125);
        files.decideDraft(DraftDecision.APPLY);
        assertEquals(Phase.IDLE, files.phase()); assertTrue(s.document.canRedo());
        assertEquals(bytes, s.document.historyBytes()); assertTrue(worker.tasks.isEmpty());
      }
    }
  }
}
