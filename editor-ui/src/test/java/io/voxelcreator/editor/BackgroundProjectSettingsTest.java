package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred exact raw settings, background full-project Apply and separate Properties ownership. */
class BackgroundProjectSettingsTest {
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
              p.project.seed = 5;
              p.project.minY = -2;
              p.project.maxY = 2;
              var n = new Noise();
              n.id = "ambient";
              n.algorithm.key = "CONSTANT";
              n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", 0.);
              var i = ProcessingRegistry.create("EXPRESSION", "context", 2);
              i.parameters.put(
                  "expression",
                  ProjectIO.JSON.valueToTree("x+projectSeed+worldMinY+worldMaxY+worldHeight"));
              n.processing.add(i);
              n.outputBinding = Binding.earlier(i.uuid);
              p.noise2D.add(n);
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).id = "private";
    s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    return s;
  }

  static ProjectSettingsSession session(EditorState state, Executor worker, Queue<Runnable> ui) {
    var s = new ProjectSettingsSession(state, worker, ui::add, () -> false);
    edit(s);
    return s;
  }

  static void edit(ProjectSettingsSession s) {
    s.edit("seed", "+000017");
    s.edit("minY", "-1");
    s.edit("maxY", "+1");
  }

  static Map<String, String> shown(ProjectSettingsSession s) {
    return Map.of("seed", s.text("seed"), "minY", s.text("minY"), "maxY", s.text("maxY"));
  }

  static void deliver(Workers workers, Queue<Runnable> ui, ProjectSettingsSession s) {
    workers.run();
    ui.remove().run();
    s.acceptPrepared(shown(s));
  }

  @Test
  void fullValidationRunsOffThreadAndPublicationWaitsForOwnerAcceptance() throws Exception {
    try (var state = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var queue = new LinkedBlockingQueue<Runnable>();
      var called = new AtomicReference<Thread>();
      var uiThread = Thread.currentThread();
      state.document.setValidator(
          p -> {
            called.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var s = session(state, worker, queue);
      var draft = state.draft;
      var privateValues = ProjectIO.JSON.valueToTree(draft);
      assertFalse(s.apply(state, shown(s)));
      var callback = queue.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(uiThread, called.get());
      assertTrue(s.applying());
      assertEquals(5, state.project().project.seed);
      assertEquals("+000017", s.text("seed"));
      callback.run();
      assertFalse(s.closed());
      assertTrue(s.applying());
      assertEquals(5, state.project().project.seed);
      s.acceptPrepared(shown(s));
      assertTrue(s.closed());
      assertEquals(17, state.project().project.seed);
      assertEquals(-1, state.project().project.minY);
      assertEquals(1, state.project().project.maxY);
      assertSame(draft, state.draft);
      assertEquals(privateValues, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void invalidOrMismatchedDisplayedRawFieldsCannotQueueAnEarlierParsedValue() {
    try (var state = state()) {
      var worker = new Workers();
      var s = session(state, worker, new ArrayDeque<>());
      var wrong = new HashMap<>(shown(s));
      wrong.put("seed", "18");
      assertThrows(IllegalStateException.class, () -> s.apply(state, wrong));
      wrong.remove("maxY");
      assertThrows(IllegalStateException.class, () -> s.apply(state, wrong));
      s.edit("seed", "-");
      assertFalse(s.apply(state, shown(s)));
      assertEquals(1, s.issueCount());
      assertTrue(worker.tasks.isEmpty());
      assertEquals(5, state.project().project.seed);
      assertThrows(IllegalArgumentException.class, () -> s.edit("seed", "1".repeat(129)));
    }
  }

  @Test
  void repeatedRejectionKeepsBoundedOwnedDiagnosticsAndRawCorrectionForRetry() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      var issues = new ArrayList<Diagnostic>();
      for (int i = 0; i < 300; i++)
        issues.add(Diagnostic.error("FIXTURE", "/fixture/" + i, "Rejected"));
      state.document.setValidator(p -> issues);
      for (int i = 0; i < 2; i++) {
        s.apply(state, shown(s));
        deliver(worker, queue, s);
        assertFalse(s.closed());
        assertEquals(300, s.issueCount());
        assertEquals(256, s.applyIssues().size());
      }
      issues.clear();
      assertEquals(256, s.applyIssues().size());
      assertEquals("+000017", s.text("seed"));
      assertEquals(5, state.project().project.seed);
      state.document.setValidator(NoiseCompiler::validate);
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertTrue(s.closed());
    }
  }

  @Test
  void editingEvenAnEquivalentRawSpellingRetiresTheCapturedApplyWithoutOverwritingText() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      s.apply(state, shown(s));
      worker.run();
      long version = s.workVersion();
      s.edit("seed", "+17");
      assertTrue(s.workVersion() > version);
      queue.remove().run();
      s.acceptPrepared(shown(s));
      assertFalse(s.closed());
      assertFalse(s.applying());
      assertEquals("+17", s.text("seed"));
      assertEquals(5, state.project().project.seed);
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertEquals(17, state.project().project.seed);
    }
  }

  @Test
  void unchangedInputAndDuplicateApplyDoNotCancelOrQueueAnotherWorker() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      s.apply(state, shown(s));
      long version = s.workVersion();
      s.edit("seed", s.text("seed"));
      assertFalse(s.apply(state, shown(s)));
      assertEquals(1, worker.tasks.size());
      assertEquals(version, s.workVersion());
      deliver(worker, queue, s);
      assertTrue(s.closed());
    }
  }

  @Test
  void managedHiddenRawAndLifetimeChangesRejectTheOldCandidateAndPreserveItsPrivateText()
      throws Exception {
    for (int mode = 0; mode < 4; mode++)
      try (var state = state()) {
        var path = directory.resolve("source-" + mode + ".json");
        state.document.save(path);
        var worker = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var s = session(state, worker, queue);
        s.apply(state, shown(s));
        worker.run();
        if (mode == 0) assertTrue(state.change(p -> p.project.seed = 6));
        else if (mode == 1)
          state.project().noise2D.getFirst().algorithm.parameters.put("value", .25);
        else if (mode == 2) state.newSession();
        else assertTrue(state.document.open(path).isEmpty());
        var values = ProjectIO.JSON.valueToTree(state.project());
        queue.remove().run();
        s.acceptPrepared(shown(s));
        assertFalse(s.closed());
        assertEquals(values, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals("+000017", s.text("seed"));
        if (mode == 1) {
          state.project().noise2D.getFirst().algorithm.parameters.put("value", 0.);
          assertFalse(s.current(state));
        }
      }
  }

  @Test
  void propertiesDraftSeedAndSelectionChangesRemainIndependentAndAreNeverRefreshed() {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var worker = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var s = session(state, worker, queue);
        s.apply(state, shown(s));
        worker.run();
        if (mode == 0) ((Noise) state.draft).id = "later-private";
        else if (mode == 1) state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        else state.refreshDraft();
        var draft = state.draft;
        var value = ProjectIO.JSON.valueToTree(draft);
        long version = state.seedInputs.version(state);
        queue.remove().run();
        s.acceptPrepared(shown(s));
        assertTrue(s.closed());
        assertSame(draft, state.draft);
        assertEquals(value, ProjectIO.JSON.valueToTree(state.draft));
        assertEquals(version, state.seedInputs.version(state));
      }
  }

  @Test
  void savePolicyAndHistoryChangesSupersedePreparationButKeepCorrectionAvailable()
      throws Exception {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var path = directory.resolve("checkpoint-" + mode + ".json");
        state.document.save(path);
        var worker = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var s = session(state, worker, queue);
        s.apply(state, shown(s));
        worker.run();
        if (mode == 0) state.document.save(path);
        else if (mode == 1) state.document.setValidator(p -> List.of());
        else {
          state.document.undo();
          state.document.redo();
        }
        var values = ProjectIO.JSON.valueToTree(state.project());
        queue.remove().run();
        s.acceptPrepared(shown(s));
        assertFalse(s.closed());
        assertEquals(values, ProjectIO.JSON.valueToTree(state.project()));
        assertFalse(s.applying());
        if (mode < 2) {
          assertTrue(s.current(state));
          s.apply(state, shown(s));
          deliver(worker, queue, s);
          assertTrue(s.closed());
        }
      }
  }

  @Test
  void admissionAndCompletionGatesCanCancelWithoutPublishingOrRecursivelyQueueingWork() {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var worker = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var holder = new ProjectSettingsSession[1];
        int action = mode;
        var s =
            new ProjectSettingsSession(
                state,
                worker,
                queue::add,
                () -> {
                  int call = calls.incrementAndGet();
                  if (action == 0 && call == 1 || action == 1 && call == 2) holder[0].cancel();
                  if (action == 2 && call == 1)
                    assertFalse(holder[0].apply(state, shown(holder[0])));
                  return false;
                });
        holder[0] = s;
        edit(s);
        s.apply(state, shown(s));
        if (mode == 0) {
          assertTrue(s.closed());
          assertTrue(worker.tasks.isEmpty());
        } else {
          deliver(worker, queue, s);
          assertEquals(mode == 2 ? 17 : 5, state.project().project.seed);
        }
      }
  }

  @Test
  void schedulingHandoffAndExternalTaskFailureCannotTrapAPendingApply() {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var worker = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        int action = mode;
        Executor executor =
            task -> {
              if (action == 0 && fail.get())
                throw new RejectedExecutionException("scheduler fixture");
              worker.execute(task);
            };
        var s =
            new ProjectSettingsSession(
                state,
                executor,
                task -> {
                  if (action == 1 && fail.get()) throw new AssertionError("handoff fixture");
                  queue.add(task);
                },
                () -> false);
        edit(s);
        s.apply(state, shown(s));
        if (mode == 2) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        else if (mode == 1) worker.run();
        assertFalse(s.applying());
        assertFalse(s.applyFailure().isBlank());
        assertEquals(0, s.issueCount());
        assertEquals(5, state.project().project.seed);
        if (mode == 2) worker.run();
        fail.set(false);
        s.apply(state, shown(s));
        deliver(worker, queue, s);
        assertTrue(s.closed());
      }
  }

  @Test
  void fatalValidationClearsOldAttemptDetailsAndExplicitRetryUsesCurrentPolicy() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      state.document.setValidator(p -> List.of(Diagnostic.error("FIRST", "/fixture", "Rejected")));
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertEquals(1, s.issueCount());
      state.document.setValidator(
          p -> {
            throw new AssertionError("validator fixture");
          });
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertEquals(0, s.issueCount());
      assertTrue(s.applyIssues().isEmpty());
      assertFalse(s.applyFailure().isBlank());
      state.document.setValidator(NoiseCompiler::validate);
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertTrue(s.closed());
    }
  }

  @Test
  void newerSharedValidationResultsCannotBeOverwrittenByALateSettingsAttempt() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      s.apply(state, shown(s));
      worker.run();
      var newer = List.of(Diagnostic.error("NEWER", "/fixture", "New result"));
      state.issues = newer;
      queue.remove().run();
      s.acceptPrepared(shown(s));
      assertSame(newer, state.issues);
      assertFalse(s.closed());
      assertEquals(5, state.project().project.seed);
    }
  }

  @Test
  void cancelledClosedAndReopenedPanelsRejectLateCallbacksWithoutTouchingSuccessors() {
    try (var state = state()) {
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var panel = new ProjectSettingsPanel(state, worker, queue::add, () -> false);
      assertTrue(panel.open(null));
      for (String field : ProjectSettingsSession.FIELDS)
        panel.probeText(field, Map.of("seed", "17", "minY", "-1", "maxY", "1").get(field));
      var first = panel.probeSession();
      first.apply(state, shown(first));
      worker.run();
      panel.cancel();
      assertTrue(panel.open(null));
      var successor = panel.probeSession();
      queue.remove().run();
      successor.acceptPrepared(shown(successor));
      assertSame(successor, panel.probeSession());
      assertFalse(successor.closed());
      assertEquals("5", panel.text("seed"));
      assertEquals(5, state.project().project.seed);
      panel.close();
      assertFalse(panel.open(null));
    }
  }

  @Test
  void oldCompiledAmbientFieldsStayImmutableAndSuccessfulApplyIsOneUndoRedoStep() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var old = new CompiledProject(state.project()).noise2D("ambient", 5);
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = session(state, worker, queue);
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertEquals(10, old.sample(0, 0));
      assertEquals(20, new CompiledProject(state.project()).noise2D("ambient", 17).sample(0, 0));
      var accepted = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(accepted, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void equivalentRawNoOpConsumesTheDraftWithoutClearingRedoOrChangingPrivateProperties() {
    try (var state = state()) {
      assertTrue(state.change(p -> p.project.seed = 6));
      state.document.undo();
      var draft = state.draft;
      long bytes = state.document.historyBytes();
      var worker = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var s = new ProjectSettingsSession(state, worker, queue::add, () -> false);
      s.edit("seed", "+0005");
      s.apply(state, shown(s));
      deliver(worker, queue, s);
      assertTrue(s.closed());
      assertTrue(state.document.canRedo());
      assertEquals(bytes, state.document.historyBytes());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }
}
