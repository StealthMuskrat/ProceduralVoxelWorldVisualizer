package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred worker-only choice/reload planning and exact owner-thread result adoption. */
class ImportPreviewPlanningTest {
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.removeFirst().run();
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

  static ProjectData incoming() {
    var p = new ProjectData();
    p.noise2D.add(noise("shared", .25));
    p.noise2D.add(noise("added", .75));
    return p;
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

  static UUID id(ImportEditSession s) {
    return s.preview().rows().getFirst().uuid();
  }

  static ImportEditSession session(EditorState state, Executor worker, Queue<Runnable> ui) {
    return new ImportEditSession(state, incoming(), worker, ui::add, () -> false);
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  @Test
  void choiceWorkRunsOffThreadAndCompletionWaitsForUiAndAFreshDisplayedPlan() throws Exception {
    try (var state = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var entered = new AtomicReference<Thread>();
      var ownerThread = Thread.currentThread();
      var s =
          session(
              state,
              task ->
                  worker.execute(
                      () -> {
                        entered.set(Thread.currentThread());
                        task.run();
                      }),
              ui);
      var old = s.preview();
      s.markShown(old);
      var owner = new ProjectFileOwner(state);
      s.choose(id(s), ResourceImport.Choice.REUSE);
      var completion = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(completion);
      assertNotSame(ownerThread, entered.get());
      assertTrue(s.planning());
      assertSame(old, s.preview());
      s.markShown(old);
      assertFalse(s.canApply());
      assertEquals(ImportEditSession.Result.NOT_REVIEWED, s.apply());
      completion.run();
      var next = s.preview();
      assertNotSame(old, next);
      assertFalse(s.canApply());
      assertEquals(old.rows().get(1).targetUuid(), next.rows().get(1).targetUuid());
      s.markShown(old);
      assertFalse(s.canApply());
      s.markShown(next);
      assertTrue(s.canApply());
      assertTrue(owner.valuesCurrent(state));
      assertEquals(ImportEditSession.Result.APPLIED, s.apply());
      assertEquals(3, state.project().noise2D.size());
      assertEquals("private", ((Noise) state.draft).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      state.document.undo();
      assertEquals(2, state.project().noise2D.size());
      state.document.redo();
      assertEquals(3, state.project().noise2D.size());
    }
  }

  @Test
  void rapidChoicesRetireQueuedAndCompletedWorkWithoutLosingDesiredChoicesOrCopyIds() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = session(state, worker, ui);
      var old = s.preview();
      UUID shared = id(s), added = old.rows().get(1).uuid();
      s.choose(shared, ResourceImport.Choice.REUSE);
      worker.run();
      var late = ui.remove();
      s.choose(added, ResourceImport.Choice.SKIP);
      s.choose(added, ResourceImport.Choice.COPY);
      late.run();
      assertSame(old, s.preview());
      worker.run();
      assertTrue(ui.isEmpty());
      deliver(worker, ui);
      assertEquals(ResourceImport.Choice.REUSE, s.choice(shared));
      assertEquals(ResourceImport.Choice.COPY, s.choice(added));
      assertEquals(old.rows().get(1).targetUuid(), s.preview().rows().get(1).targetUuid());
      assertFalse(s.canApply());
    }
  }

  @Test
  void unchangedAndInvalidChoicesDoNotRetireAReviewedPlanOrEnqueueWork() {
    try (var state = state()) {
      var worker = new Workers();
      var s = session(state, worker, new ArrayDeque<>());
      var old = s.preview();
      s.markShown(old);
      s.choose(id(s), ResourceImport.Choice.COPY);
      assertTrue(worker.tasks.isEmpty());
      assertTrue(s.canApply());
      assertThrows(
          IllegalArgumentException.class,
          () -> s.choose(UUID.randomUUID(), ResourceImport.Choice.COPY));
      assertThrows(IllegalArgumentException.class, () -> s.choose(id(s), null));
      assertSame(old, s.preview());
      assertTrue(s.canApply());
    }
  }

  @Test
  void reloadUsesAnOwnedCurrentSnapshotRetainsChoicesAndRequiresAnotherReview() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = session(state, worker, ui);
      s.choose(id(s), ResourceImport.Choice.REUSE);
      deliver(worker, ui);
      var old = s.preview();
      s.markShown(old);
      assertTrue(state.change(p -> p.project.seed = 18));
      s.reload();
      assertSame(old, s.preview());
      assertFalse(s.canApply());
      deliver(worker, ui);
      assertTrue(s.current());
      assertEquals(ResourceImport.Choice.REUSE, s.choice(id(s)));
      assertNotEquals(old.rows().get(1).targetUuid(), s.preview().rows().get(1).targetUuid());
      assertEquals(18, s.preview().candidate().project.seed);
      assertFalse(s.canApply());
    }
  }

  @Test
  void rawCommittedAndManagedEditsRetireACompletedPlanAndRequireExplicitReload() {
    for (boolean raw : List.of(false, true))
      try (var state = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = session(state, worker, ui);
        var old = s.preview();
        s.choose(id(s), ResourceImport.Choice.REUSE);
        worker.run();
        if (raw) state.project().project.seed = 18;
        else assertTrue(state.change(p -> p.project.seed = 18));
        ui.remove().run();
        assertSame(old, s.preview());
        assertFalse(s.current());
        assertFalse(s.canApply());
        s.reload();
        deliver(worker, ui);
        assertEquals(18, s.preview().candidate().project.seed);
        assertTrue(s.current());
      }
  }

  @Test
  void privateInputAndSelectionChangesDiscardResultsWithoutChangingProjectOrDraft() {
    for (int mode = 0; mode < 3; mode++)
      try (var state = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = session(state, worker, ui);
        var before = ProjectIO.JSON.valueToTree(state.project());
        s.choose(id(s), ResourceImport.Choice.REUSE);
        worker.run();
        if (mode == 0) ((Noise) state.draft).id = "new-private";
        else if (mode == 1) state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        else state.select("noise2D", state.project().noise2D.getLast().uuid);
        var owner = new ProjectFileOwner(state);
        ui.remove().run();
        assertFalse(s.canApply());
        assertTrue(s.canRetryPlan());
        assertTrue(owner.valuesCurrent(state));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        s.retryPlan();
        deliver(worker, ui);
        assertFalse(s.canApply());
        assertTrue(owner.valuesCurrent(state));
      }
  }

  @Test
  void cancelAndNewDocumentRejectLateResultsAndCannotReopenTheSession() {
    for (boolean replace : List.of(false, true))
      try (var state = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = session(state, worker, ui);
        var old = s.preview();
        s.choose(id(s), ResourceImport.Choice.REUSE);
        worker.run();
        if (replace) state.newSession();
        else s.cancel();
        ui.remove().run();
        assertSame(old, s.preview());
        assertFalse(s.canApply());
        assertFalse(s.canReload());
        if (!replace) assertEquals(ImportEditSession.Result.CLOSED, s.apply());
      }
  }

  @Test
  void schedulerFailureRetainsDesiredChoicesAndExplicitRetryRebuilds() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var reject = new AtomicBoolean(true);
      var s =
          session(
              state,
              task -> {
                if (reject.get()) throw new RejectedExecutionException("fixture scheduler");
                worker.execute(task);
              },
              ui);
      var old = s.preview();
      s.choose(id(s), ResourceImport.Choice.REUSE);
      assertSame(old, s.preview());
      assertTrue(s.planningMessage().contains("fixture scheduler"));
      assertTrue(s.canRetryPlan());
      assertFalse(s.canApply());
      reject.set(false);
      s.retryPlan();
      deliver(worker, ui);
      assertNotSame(old, s.preview());
      assertFalse(s.canApply());
    }
  }

  @Test
  void uiHandoffFailureAndCancelledWorkerDoNotLeavePermanentRunningState() {
    for (boolean cancelled : List.of(false, true))
      try (var state = state()) {
        var worker = new Workers();
        var s =
            new ImportEditSession(
                state,
                incoming(),
                worker,
                task -> {
                  throw new AssertionError("fixture handoff");
                },
                () -> false);
        s.choose(id(s), ResourceImport.Choice.REUSE);
        if (cancelled) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        else worker.run();
        assertFalse(s.planning());
        assertTrue(s.canRetryPlan());
        assertFalse(s.canApply());
        assertEquals(2, state.project().noise2D.size());
      }
  }

  @Test
  void publicationGateReentrancyAndBlockingCannotInstallARejectedPlan() {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var holder = new ImportEditSession[1];
        var s =
            new ImportEditSession(
                state,
                incoming(),
                worker,
                ui::add,
                () -> {
                  if (cancel) holder[0].cancel();
                  return true;
                });
        holder[0] = s;
        var old = s.preview();
        s.choose(id(s), ResourceImport.Choice.REUSE);
        deliver(worker, ui);
        assertSame(old, s.preview());
        assertFalse(s.canApply());
        assertEquals(2, state.project().noise2D.size());
        assertEquals(cancel, s.closed());
      }
  }

  @Test
  void reloadRechecksReuseCompatibilityAndCopyRepairRetainsTheNewAllocation() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = session(state, worker, ui);
      s.choose(id(s), ResourceImport.Choice.REUSE);
      deliver(worker, ui);
      assertTrue(state.change(p -> p.noise2D.getLast().algorithm.parameters.put("value", .5)));
      s.reload();
      deliver(worker, ui);
      assertFalse(s.preview().valid());
      var allocation = s.preview().rows().get(1).targetUuid();
      s.choose(id(s), ResourceImport.Choice.COPY);
      deliver(worker, ui);
      assertTrue(s.preview().valid());
      assertEquals(allocation, s.preview().rows().get(1).targetUuid());
      s.markShown(s.preview());
      assertEquals(ImportEditSession.Result.APPLIED, s.apply());
      assertEquals(.5, state.project().noise2D.get(1).algorithm.parameters.get("value"));
    }
  }

  @Test
  void panelCloseCancelsPendingPlanningAndSuccessorKeepsItsOwnReview() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {}, worker, ui::add);
      assertTrue(panel.open(incoming()));
      var first = panel.session();
      first.choose(id(first), ResourceImport.Choice.REUSE);
      worker.run();
      panel.close();
      assertFalse(panel.pending());
      assertTrue(first.closed());
      assertTrue(panel.open(incoming()));
      var successor = panel.session();
      var plan = successor.preview();
      ui.remove().run();
      assertSame(successor, panel.session());
      assertSame(plan, successor.preview());
      assertFalse(successor.canApply());
      panel.close();
    }
  }
}
