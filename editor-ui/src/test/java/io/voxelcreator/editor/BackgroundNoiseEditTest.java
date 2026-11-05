package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred owned structural worker definitions; no execution during the owner's pause. */
class BackgroundNoiseEditTest {
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("noise edit queue full");
      tasks.add(task);
    }

    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
      tasks.remove().run();
    }
  }

  static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(BackgroundNoiseEditProbe::fixture));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  static Noise noise(EditorState s) {
    return (Noise) s.draft;
  }

  static Request request(EditorState s) {
    return Request.simple(Action.DELETE_PARAMETER, noise(s).sharedParameters.getFirst().uuid);
  }

  static NoiseEditSession session(EditorState s, Executor worker, Queue<Runnable> ui) {
    return new NoiseEditSession(s, noise(s), request(s), worker, ui::add);
  }

  static void deliver(Workers worker, Queue<Runnable> ui, NoiseEditSession edit) {
    worker.run();
    ui.remove().run();
    edit.acceptPrepared();
  }

  @Test
  void actualWorkerUsesOwnedCopiesAndCannotPublishBeforeUiDelivery() throws Exception {
    try (var s = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var entered = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      var committed = ProjectIO.JSON.valueToTree(s.project());
      var draft = ProjectIO.JSON.valueToTree(s.draft);
      try (var edit =
          new NoiseEditSession(
              s,
              noise(s),
              request(s),
              worker,
              ui::add,
              (p, n, r) -> {
                entered.set(Thread.currentThread());
                assertNotSame(s.project(), p);
                assertNotSame(s.draft, n);
                return NoiseAuthoring.plan(p, n, r);
              })) {
        var completion = ui.poll(10, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertNotSame(caller, entered.get());
        for (int i = 0; i < 12; i++) {
          assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase());
          assertNull(edit.preview());
          assertFalse(edit.canStage());
        }
        long version = edit.version();
        completion.run();
        completion.run();
        assertEquals(version, edit.version());
        assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase());
        assertNull(edit.preview());
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        edit.acceptPrepared();
        assertTrue(edit.ready());
        assertFalse(edit.preview().valid());
        assertEquals(committed, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
      }
    }
  }

  @Test
  void resolvedInlineStagesOnlyTheExistingDraftAndOneParentTransactionPreservesMath() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var owner = noise(s);
      owner.id = "private";
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(s.project());
      long history = s.document.historyBytes(), revision = s.revision;
      try (var edit = session(s, worker, ui)) {
        deliver(worker, ui, edit);
        assertFalse(edit.canStage());
        edit.choose(Resolution.INLINE_PARAMETER, null);
        assertFalse(edit.canStage());
        deliver(worker, ui, edit);
        assertTrue(edit.canStage());
        edit.stage();
        assertTrue(edit.closed());
        assertSame(owner, s.draft);
        assertEquals("private", owner.id);
        assertEquals("Constant", owner.processing.getFirst().inputs.get("b").sourceKind);
        assertEquals(2, owner.processing.getFirst().inputs.get("b").value.asDouble());
        assertEquals("-", s.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(history, s.document.historyBytes());
        assertEquals(revision, s.revision);
        assertFalse(s.applyDraft());
        s.seedInputs.edit(s, "seedOffset", "Seed", "0");
        assertTrue(s.applyDraft());
        assertEquals(12, new NoiseCompiler(s.project(), 0).compile(owner.uuid).sample(-7, 0, 3));
        var after = ProjectIO.JSON.valueToTree(s.project());
        s.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo();
        assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void rapidChoicesRetireOlderCompletedPlansAndCannotStageLastVisibleFeedback() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, worker, ui)) {
        deliver(worker, ui, edit);
        var initial = edit.preview();
        edit.choose(Resolution.INLINE_PARAMETER, null);
        worker.run();
        var old = ui.remove();
        long version = edit.version();
        edit.choose(Resolution.REPLACE, UUID.randomUUID());
        assertTrue(edit.version() > version);
        old.run();
        assertSame(initial, edit.preview());
        assertFalse(edit.canStage());
        assertThrows(IllegalStateException.class, edit::stage);
        deliver(worker, ui, edit);
        assertFalse(edit.preview().valid());
        edit.choose(Resolution.INLINE_PARAMETER, null);
        deliver(worker, ui, edit);
        assertTrue(edit.canStage());
      }
    }
  }

  @Test
  void unchangedChoicesDoNotResubmitAndInvalidResolutionRemainsCorrectable() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, worker, ui)) {
        long version = edit.version();
        edit.choose(Resolution.REJECT, null);
        assertEquals(version, edit.version());
        deliver(worker, ui, edit);
        var plan = edit.preview();
        edit.choose(Resolution.REJECT, null);
        assertSame(plan, edit.preview());
        edit.choose(Resolution.REPLACE, UUID.randomUUID());
        deliver(worker, ui, edit);
        assertTrue(edit.ready());
        assertFalse(edit.canStage());
        edit.choose(Resolution.INLINE_PARAMETER, null);
        deliver(worker, ui, edit);
        edit.stage();
        assertTrue(edit.closed());
      }
    }
  }

  @Test
  void schedulerFailureNeedsExplicitRetryAndNeverRepeatsUnchangedFrames() {
    try (var s = state()) {
      var worker = new Workers();
      worker.reject = true;
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, worker, ui)) {
        assertTrue(edit.canRetry());
        long version = edit.version();
        for (int i = 0; i < 12; i++) {
          edit.preview();
          edit.choose(Resolution.REJECT, null);
        }
        assertEquals(version, edit.version());
        assertTrue(worker.tasks.isEmpty());
        worker.reject = false;
        edit.retry();
        deliver(worker, ui, edit);
        assertTrue(edit.ready());
        assertFalse(edit.canRetry());
      }
    }
  }

  @Test
  void workerAndHandoffErrorsKeepChoicesAndExposeExplicitRecovery() {
    for (boolean handoff : new boolean[] {false, true})
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var broken = new AtomicBoolean(true);
        try (var edit =
            new NoiseEditSession(
                s,
                noise(s),
                request(s),
                worker,
                task -> {
                  if (handoff && broken.get()) throw new AssertionError("handoff failed");
                  ui.add(task);
                },
                (p, n, r) -> {
                  if (!handoff && broken.get()) throw new AssertionError("compiler failed");
                  return NoiseAuthoring.plan(p, n, r);
                })) {
          worker.run();
          if (!ui.isEmpty()) ui.remove().run();
          edit.acceptPrepared();
          assertTrue(edit.canRetry());
          assertEquals(Resolution.REJECT, edit.resolution());
          assertFalse(edit.canStage());
          broken.set(false);
          edit.retry();
          deliver(worker, ui, edit);
          assertTrue(edit.ready());
          assertEquals(
              1,
              s.events.snapshot().stream()
                  .filter(e -> e.code().equals("NOISE_EDIT_PREVIEW_FAILED"))
                  .count());
        }
      }
  }

  @Test
  void externallyCancelledTaskDoesNotLeaveAPlanPendingForever() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, worker, ui)) {
        ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertTrue(edit.canRetry());
        edit.retry();
        deliver(worker, ui, edit);
        assertTrue(edit.ready());
      }
    }
  }

  @Test
  void changedDraftRawInputAndSelectionRejectCompletedPlansBeforeAdoption() {
    for (int change = 0; change < 4; change++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, worker, ui)) {
          worker.run();
          switch (change) {
            case 0 -> noise(s).id = "changed";
            case 1 -> s.seedInputs.edit(s, "seedOffset", "Seed", "+");
            case 2 -> s.refreshDraft();
            case 3 -> s.multiSelection.clear();
          }
          var before = ProjectIO.JSON.valueToTree(s.draft);
          ui.remove().run();
          edit.acceptPrepared();
          assertEquals(NoiseEditSession.Phase.STALE, edit.phase());
          assertFalse(edit.canStage());
          assertEquals(before, ProjectIO.JSON.valueToTree(s.draft));
        }
      }
  }

  @Test
  void inPlaceCommittedValueAndUuidChangesCannotBeAdoptedOrRetried() {
    for (boolean identity : new boolean[] {false, true})
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, worker, ui)) {
          worker.run();
          if (identity) s.project().project.uuid = UUID.randomUUID();
          else s.project().project.seed++;
          ui.remove().run();
          edit.acceptPrepared();
          assertEquals(NoiseEditSession.Phase.STALE, edit.phase());
          assertThrows(
              IllegalStateException.class, () -> edit.choose(Resolution.INLINE_PARAMETER, null));
          assertFalse(edit.canRetry());
        }
      }
  }

  @Test
  void newOrSameUuidOpenRetiresTheOriginalDocumentLifetime(@TempDir Path directory)
      throws Exception {
    for (boolean reopen : new boolean[] {false, true})
      try (var s = state()) {
        var path = directory.resolve("same.json");
        s.document.save(path);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, worker, ui)) {
          worker.run();
          if (reopen) assertTrue(s.document.open(path).isEmpty());
          else s.newSession();
          var before = ProjectIO.JSON.valueToTree(s.project());
          ui.remove().run();
          edit.acceptPrepared();
          assertFalse(edit.current());
          assertEquals(NoiseEditSession.Phase.STALE, edit.phase());
          assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        }
      }
  }

  @Test
  void cancellationAndDuplicateDeliveryCannotPublishIntoASuccessor() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var first = session(s, worker, ui);
      worker.run();
      var old = ui.remove();
      first.cancel();
      try (var next = session(s, worker, ui)) {
        old.run();
        old.run();
        assertEquals(NoiseEditSession.Phase.RUNNING, next.phase());
        assertNull(next.preview());
        deliver(worker, ui, next);
        assertTrue(next.ready());
        assertTrue(first.closed());
      }
    }
  }

  @Test
  void changedValuesAfterReadyAreCheckedAgainAtPrivateStage() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, worker, ui)) {
        deliver(worker, ui, edit);
        edit.choose(Resolution.INLINE_PARAMETER, null);
        deliver(worker, ui, edit);
        noise(s).sharedParameters.getFirst().value = ProjectIO.JSON.valueToTree(9);
        var changed = ProjectIO.JSON.valueToTree(s.draft);
        assertThrows(IllegalStateException.class, edit::stage);
        assertEquals(changed, ProjectIO.JSON.valueToTree(s.draft));
        assertFalse(edit.closed());
        assertEquals(NoiseEditSession.Phase.STALE, edit.phase());
        assertFalse(edit.canStage());
      }
    }
  }

  @Test
  void explicitSourceRequestKeepsItsDeclaredResolutionAndIndependentArithmetic() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var owner = noise(s);
      var request =
          new Request(Action.DELETE, owner.processing.getFirst().uuid, Resolution.SOURCE, null);
      try (var edit = new NoiseEditSession(s, owner, request, worker, ui::add)) {
        deliver(worker, ui, edit);
        assertTrue(edit.canStage());
        assertEquals(Resolution.SOURCE, edit.resolution());
        edit.stage();
        assertTrue(s.applyDraft());
        assertEquals(4, new NoiseCompiler(s.project(), 0).compile(owner.uuid).sample(-3, 0, 7));
      }
    }
  }

  @Test
  void duplicatePlanHasOwnedIdentitiesAndStagesOnlyTheDisplayedCandidate() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var owner = noise(s);
      var original = owner.sharedParameters.getFirst().uuid;
      try (var edit =
          new NoiseEditSession(
              s, owner, Request.simple(Action.DUPLICATE_PARAMETER, original), worker, ui::add)) {
        deliver(worker, ui, edit);
        var plan = edit.preview();
        var chosen = plan.selected();
        assertNotEquals(original, chosen);
        plan.candidate().sharedParameters.clear();
        assertEquals(chosen, edit.stage());
        assertEquals(3, owner.sharedParameters.size());
        assertEquals(original, owner.sharedParameters.getFirst().uuid);
        assertEquals(2, owner.sharedParameters.get(1).value.asDouble());
        assertEquals(2, s.project().noise2D.getFirst().sharedParameters.size());
      }
    }
  }
}
