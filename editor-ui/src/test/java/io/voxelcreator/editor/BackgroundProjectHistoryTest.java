package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred owner-safe worker history, discard decisions, final refresh fencing and retry. */
class BackgroundProjectHistoryTest {
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

  static EditorState state(boolean dirty) {
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
    assertTrue(s.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .625)));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    if (dirty) {
      ((Noise) s.draft).id = "private";
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    }
    return s;
  }

  static ProjectHistorySession history(EditorState s, Executor workers, Queue<Runnable> ui) {
    return new ProjectHistorySession(
        s, () -> false, () -> s.reconcileSelection(s.multiSelection, s.selected), workers, ui::add);
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  static void confirm(ProjectHistorySession h) {
    assertEquals(Result.NEEDS_DECISION, h.request(Direction.UNDO));
    assertEquals(Result.PREPARING, h.decide(Decision.DISCARD));
  }

  @Test
  void cleanUndoAndRedoValidateOffThreadAndWaitForOwnerDelivery() throws Exception {
    try (var state = state(false);
        var workers = Executors.newSingleThreadExecutor()) {
      var queue = new LinkedBlockingQueue<Runnable>();
      var called = new AtomicReference<Thread>();
      var ownerThread = Thread.currentThread();
      state.document.setValidator(
          p -> {
            called.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      long bytes = state.document.historyBytes();
      try (var h = history(state, workers, queue)) {
        assertEquals(Result.PREPARING, h.request(Direction.UNDO));
        var callback = queue.poll(10, TimeUnit.SECONDS);
        assertNotNull(callback);
        assertNotSame(ownerThread, called.get());
        assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertTrue(h.preparing());
        callback.run();
        assertFalse(h.pending());
        assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertEquals(bytes, state.document.historyBytes());
        assertEquals(Result.PREPARING, h.request(Direction.REDO));
        var redo = queue.poll(10, TimeUnit.SECONDS);
        assertNotNull(redo);
        redo.run();
        assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
    }
  }

  @Test
  void discardRetainsPrivateInputUntilSuccessfulHistoryPublicationAndNeverAppliesIt() {
    try (var state = state(true)) {
      var workers = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var draft = state.draft;
      long bytes = state.document.historyBytes();
      try (var h = history(state, workers, queue)) {
        confirm(h);
        assertSame(draft, state.draft);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        workers.run();
        assertSame(draft, state.draft);
        queue.remove().run();
        assertNotSame(draft, state.draft);
        assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertEquals("field", ((Noise) state.draft).id);
        assertEquals(bytes, state.document.historyBytes());
        assertTrue(state.document.canRedo());
      }
    }
  }

  @Test
  void queuedAndCompletedCancellationPreservePrivateDraftAndBothHistoryStacks() {
    for (boolean completed : List.of(false, true))
      try (var state = state(true)) {
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var owner = new ProjectFileOwner(state);
        long bytes = state.document.historyBytes();
        try (var h = history(state, workers, queue)) {
          confirm(h);
          if (completed) workers.run();
          assertEquals(Result.CANCELLED, h.decide(Decision.CANCEL));
          if (completed) queue.remove().run();
          else workers.run();
          assertFalse(h.pending());
          assertTrue(owner.valuesCurrent(state));
          assertEquals(bytes, state.document.historyBytes());
          assertFalse(state.document.canRedo());
        }
      }
  }

  @Test
  void changedPrivateSeedSelectionAndRawOrTrackedProjectsCannotAdoptOldResults() {
    for (int mode = 0; mode < 5; mode++)
      try (var state = state(true)) {
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        try (var h = history(state, workers, queue)) {
          confirm(h);
          workers.run();
          if (mode == 0) ((Noise) state.draft).id = "new-private";
          else if (mode == 1) state.seedInputs.edit(state, "seedOffset", "Seed", "+");
          else if (mode == 2) state.refreshDraft();
          else if (mode == 3) state.project().project.seed = 18;
          else assertTrue(state.change(p -> p.project.seed = 18));
          var owner = new ProjectFileOwner(state);
          queue.remove().run();
          assertFalse(h.pending());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void savePolicyNewAndIdenticalOpenSupersedeTheCapturedHistoryEntry() throws Exception {
    for (int mode = 0; mode < 4; mode++)
      try (var state = state(true)) {
        var path = directory.resolve("source-" + mode + ".json");
        state.document.save(path);
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        try (var h = history(state, workers, queue)) {
          confirm(h);
          workers.run();
          if (mode == 0) state.document.save(path);
          else if (mode == 1) state.document.setValidator(p -> List.of());
          else if (mode == 2) state.newSession();
          else assertTrue(state.document.open(path).isEmpty());
          var owner = new ProjectFileOwner(state);
          queue.remove().run();
          assertFalse(h.pending());
          assertTrue(owner.valuesCurrent(state));
        }
      }
  }

  @Test
  void rejectedAndThrowingValidationRetainPrivateInputAndAllowAFreshExplicitRequest() {
    for (boolean fatal : List.of(false, true))
      try (var state = state(true)) {
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var owner = new ProjectFileOwner(state);
        long bytes = state.document.historyBytes();
        state.document.setValidator(
            p -> {
              if (fatal) throw new AssertionError("history fixture");
              return List.of(Diagnostic.error("HISTORY", "/fixture", "Rejected"));
            });
        try (var h = history(state, workers, queue)) {
          confirm(h);
          deliver(workers, queue);
          assertFalse(h.pending());
          assertTrue(owner.valuesCurrent(state));
          assertEquals(bytes, state.document.historyBytes());
          state.document.setValidator(NoiseCompiler::validate);
          confirm(h);
          deliver(workers, queue);
          assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        }
      }
  }

  @Test
  void schedulingAndUiHandoffFailureCannotLeaveAPermanentPendingDirection() {
    for (boolean scheduling : List.of(false, true))
      try (var state = state(false)) {
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        var owner = new ProjectFileOwner(state);
        Executor executor =
            task -> {
              if (scheduling && fail.get())
                throw new RejectedExecutionException("scheduler fixture");
              workers.execute(task);
            };
        try (var h =
            new ProjectHistorySession(
                state,
                () -> false,
                () -> state.refreshDraft(),
                executor,
                task -> {
                  if (!scheduling && fail.get()) throw new AssertionError("handoff fixture");
                  queue.add(task);
                })) {
          h.request(Direction.UNDO);
          if (!scheduling) workers.run();
          assertFalse(h.pending());
          assertTrue(owner.valuesCurrent(state));
          fail.set(false);
          assertEquals(Result.PREPARING, h.request(Direction.UNDO));
          deliver(workers, queue);
          assertFalse(h.pending());
          assertTrue(state.document.canRedo());
        }
      }
  }

  @Test
  void duplicateRequestsAndDiscardReleasesCannotQueueASecondHistoryPreparation() {
    try (var state = state(true)) {
      var workers = new Workers();
      var queue = new ArrayDeque<Runnable>();
      try (var h = history(state, workers, queue)) {
        confirm(h);
        assertEquals(Result.UNAVAILABLE, h.request(Direction.REDO));
        assertEquals(Result.UNAVAILABLE, h.decide(Decision.DISCARD));
        assertEquals(1, workers.tasks.size());
        deliver(workers, queue);
        assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
    }
  }

  @Test
  void lateBlockersAndReentrantCancellationCannotPublishACompletedCandidate() {
    for (boolean cancel : List.of(false, true))
      try (var state = state(true)) {
        var workers = new Workers();
        var queue = new ArrayDeque<Runnable>();
        var calls = new AtomicInteger();
        var holder = new ProjectHistorySession[1];
        var owner = new ProjectFileOwner(state);
        try (var h =
            new ProjectHistorySession(
                state,
                () -> {
                  if (calls.incrementAndGet() == 3) {
                    if (cancel) {
                      holder[0].clear();
                      assertEquals(Result.NEEDS_DECISION, holder[0].request(Direction.UNDO));
                    }
                    return true;
                  }
                  return false;
                },
                () -> state.refreshDraft(),
                workers,
                queue::add)) {
          holder[0] = h;
          confirm(h);
          deliver(workers, queue);
          assertEquals(cancel, h.pending());
          assertTrue(owner.valuesCurrent(state));
          if (cancel) assertEquals(Result.CANCELLED, h.decide(Decision.CANCEL));
        }
      }
  }

  @Test
  void finalRefreshRemainsFencedAndFailureDoesNotReplayTheCompletedMovement() {
    try (var state = state(false)) {
      var workers = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var holder = new ProjectHistorySession[1];
      var refreshed = new AtomicInteger();
      try (var h =
          new ProjectHistorySession(
              state,
              () -> false,
              () -> {
                assertTrue(holder[0].busy());
                assertEquals(Result.UNAVAILABLE, holder[0].request(Direction.REDO));
                refreshed.incrementAndGet();
                throw new AssertionError("refresh fixture");
              },
              workers,
              queue::add)) {
        holder[0] = h;
        assertEquals(Result.PREPARING, h.request(Direction.UNDO));
        workers.run();
        var late = queue.remove();
        late.run();
        late.run();
        assertFalse(h.pending());
        assertFalse(h.busy());
        assertEquals(1, refreshed.get());
        assertEquals(.125, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertTrue(state.document.canRedo());
      }
    }
  }

  @Test
  void selectionReconciliationAfterSuccessfulUndoClearsDeletedResourceAndSeedOwnership() {
    try (var state = state(false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "temporary";
                p.noise2D.add(n);
              }));
      state.select("noise2D", state.project().noise2D.getLast().uuid);
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var workers = new Workers();
      var queue = new ArrayDeque<Runnable>();
      try (var h = history(state, workers, queue)) {
        confirm(h);
        deliver(workers, queue);
        assertNull(state.selected);
        assertNull(state.draft);
        assertTrue(state.multiSelection.isEmpty());
        assertFalse(state.seedInputs.pending(state));
      }
    }
  }

  @Test
  void closureRejectsLateCompletionsAndAReopenedControllerOwnsOnlyItsOwnRequest() {
    try (var state = state(false)) {
      var workers = new Workers();
      var queue = new ArrayDeque<Runnable>();
      var first = history(state, workers, queue);
      assertEquals(Result.PREPARING, first.request(Direction.UNDO));
      workers.run();
      var late = queue.remove();
      first.close();
      try (var second = history(state, workers, queue)) {
        assertEquals(Result.UNAVAILABLE, first.request(Direction.UNDO));
        assertEquals(Result.PREPARING, second.request(Direction.UNDO));
        late.run();
        assertTrue(second.preparing());
        assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        deliver(workers, queue);
        assertTrue(state.document.canRedo());
      }
    }
  }
}
