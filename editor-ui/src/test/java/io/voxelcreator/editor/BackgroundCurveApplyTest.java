package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred worker/publication definitions; not executed during the owner's testing pause. */
class BackgroundCurveApplyTest {
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("curve queue full");
      tasks.add(task);
    }

    void run() {
      tasks.removeIf(r -> r instanceof Future<?> f && f.isCancelled());
      tasks.remove().run();
    }
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      var n = new Noise();
      n.id = "curve";
      var item = ProcessingRegistry.create("CURVE", "curve");
      var binding = Binding.constant(0);
      binding.sourceKind = "Ambient Value";
      binding.ambientKey = "x";
      item.inputs.put("a", binding);
      n.processing.add(item);
      n.outputBinding = Binding.earlier(item.uuid);
      p.noise2D.add(n);
    }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    return s;
  }

  static Noise noise(EditorState s) {
    return (Noise) s.draft;
  }

  static Item item(EditorState s) {
    return noise(s).processing.getFirst();
  }

  static Curve curve(EditorState s) {
    return ProjectIO.JSON.convertValue(item(s).parameters.get("curve"), Curve.class);
  }

  static CurveApplySession session(EditorState s, Curve local, Executor worker, Queue<Runnable> ui,
      Runnable accepted) {
    return new CurveApplySession(new CurveDraftEdit(s, noise(s), item(s)), s, accepted, worker,
        ui::add, () -> local);
  }

  @Test
  void actualWorkerPreparesAnOwnedSnapshotAndOnlyOwnerAcceptanceStages() throws Exception {
    try (var worker = Executors.newSingleThreadExecutor()) {
      var local = new Curve();
      var before = ProjectIO.JSON.valueToTree(local);
      var ui = new LinkedBlockingQueue<Runnable>();
      var entered = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      var stages = new AtomicInteger();
      var followUps = new AtomicInteger();
      try (var edit = new CurveApplySession(followUps::incrementAndGet, worker, ui::add, () -> true,
          () -> local, prepared -> stages.incrementAndGet(), input -> {
            entered.set(Thread.currentThread());
            assertEquals(before, input);
            return CurveDraftEdit.prepare(input);
          })) {
        assertFalse(edit.apply(local));
        var completion = ui.poll(10, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertNotSame(caller, entered.get());
        for (int i = 0; i < 8; i++) {
          assertTrue(edit.running());
          assertEquals(0, stages.get());
        }
        completion.run();
        completion.run();
        assertTrue(edit.ready());
        assertEquals(0, stages.get());
        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertTrue(edit.accept());
        assertTrue(edit.closed());
        assertEquals(1, stages.get());
        assertEquals(1, followUps.get());
        assertEquals(before, ProjectIO.JSON.valueToTree(local));
      }
    }
  }

  @Test
  void privateStagingPreservesRawSeedAndParentApplyIsOneMathematicalTransaction() {
    try (var s = state()) {
      var local = curve(s);
      local.points.getLast().y = 3;
      var before = ProjectIO.JSON.valueToTree(s.project());
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      long history = s.document.historyBytes(), revision = s.revision;
      try (var edit = session(s, local, worker, ui, () -> {})) {
        assertFalse(edit.apply(local));
        worker.run();
        noise(s).id = "private";
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        ui.remove().run();
        assertTrue(edit.accept());
        assertTrue(edit.closed());
        assertEquals(3, curve(s).points.getLast().y);
        assertEquals("private", noise(s).id);
        assertEquals("-", s.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(history, s.document.historyBytes());
        assertEquals(revision, s.revision);
        assertFalse(s.applyDraft());
        s.seedInputs.edit(s, "seedOffset", "Seed", "0");
        assertTrue(s.applyDraft());
        var compiled = new NoiseCompiler(s.project(), 0).compile(s.selected);
        assertEquals(.75, compiled.sample(.25, 0, 0));
        assertEquals(1.5, compiled.sample(.5, 0, 0));
        assertEquals(3, compiled.sample(2, 0, 0));
        var after = ProjectIO.JSON.valueToTree(s.project());
        s.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo();
        assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void editingSupersedesCompletedWorkAndTheCurrentCurveNeedsAnotherApply() {
    try (var s = state()) {
      var local = curve(s);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, local, worker, ui, () -> {})) {
        edit.apply(local);
        worker.run();
        var old = ui.remove();
        local.points.getLast().y = 5;
        long version = edit.version();
        assertFalse(edit.running());
        assertTrue(edit.version() > version);
        old.run();
        assertFalse(edit.closed());
        assertEquals(1, curve(s).points.getLast().y);
        edit.apply(local);
        worker.run();
        ui.remove().run();
        old.run();
        assertTrue(edit.accept());
        assertTrue(edit.closed());
        assertEquals(5, curve(s).points.getLast().y);
      }
    }
  }

  @Test
  void changedLocalValuesAreAlsoRejectedWithoutAnInterveningFrame() {
    try (var s = state()) {
      var local = curve(s);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, local, worker, ui, () -> {})) {
        edit.apply(local);
        worker.run();
        local.points.getFirst().y = 9;
        ui.remove().run();
        assertFalse(edit.closed());
        assertFalse(edit.running());
        assertEquals(0, curve(s).points.getFirst().y);
      }
    }
  }

  @Test
  void cancelApplyAndDialogCloseRejectLateAndDuplicateDelivery() {
    for (boolean close : new boolean[] {false, true})
      try (var s = state()) {
        var local = curve(s);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var followUps = new AtomicInteger();
        try (var edit = session(s, local, worker, ui, followUps::incrementAndGet)) {
          local.points.getLast().y = 7;
          edit.apply(local);
          worker.run();
          var old = ui.remove();
          if (close) edit.cancel();
          else edit.cancelApply();
          old.run();
          old.run();
          assertEquals(close, edit.closed());
          assertFalse(edit.running());
          assertEquals(7, local.points.getLast().y);
          assertEquals(1, curve(s).points.getLast().y);
          assertEquals(0, followUps.get());
          if (!close) {
            edit.apply(local);
            worker.run();
            ui.remove().run();
            assertTrue(edit.accept());
            assertEquals(1, followUps.get());
          }
        }
      }
  }

  @Test
  void schedulerAndExternalCancellationRequireExplicitRetry() {
    for (boolean rejected : new boolean[] {false, true})
      try (var s = state()) {
        var local = curve(s);
        var worker = new Workers();
        worker.reject = rejected;
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, local, worker, ui, () -> {})) {
          edit.apply(local);
          if (!rejected) ((Future<?>) worker.tasks.getFirst()).cancel(false);
          assertFalse(edit.running());
          assertFalse(edit.error().isEmpty());
          long version = edit.version();
          for (int i = 0; i < 8; i++) assertFalse(edit.running());
          assertEquals(version, edit.version());
          worker.reject = false;
          edit.apply(local);
          worker.run();
          ui.remove().run();
          assertTrue(edit.accept());
          assertTrue(edit.closed());
        }
      }
  }

  @Test
  void workerAndUiHandoffErrorsRemainRecoverable() {
    for (boolean handoff : new boolean[] {false, true}) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var broken = new AtomicBoolean(true);
      var local = new Curve();
      var stages = new AtomicInteger();
      try (var edit = new CurveApplySession(() -> {}, worker, action -> {
        if (handoff && broken.get()) throw new AssertionError("handoff");
        ui.add(action);
      }, () -> true, () -> local, value -> stages.incrementAndGet(), input -> {
        if (!handoff && broken.get()) throw new AssertionError("compiler");
        return CurveDraftEdit.prepare(input);
      })) {
        edit.apply(local);
        worker.run();
        if (!ui.isEmpty()) {
          ui.remove().run();
          edit.accept();
        }
        assertFalse(edit.running());
        assertFalse(edit.error().isEmpty());
        assertEquals(0, stages.get());
        broken.set(false);
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertTrue(edit.accept());
        assertTrue(edit.closed());
        assertEquals(1, stages.get());
      }
    }
  }

  @Test
  void malformedCurveCanBeCorrectedWithoutLosingPrivateInput() {
    try (var s = state()) {
      var local = curve(s);
      local.points.getLast().x = 0;
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, local, worker, ui, () -> {})) {
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertTrue(edit.ready());
        assertTrue(edit.error().isEmpty());
        assertFalse(edit.accept());
        assertFalse(edit.closed());
        assertFalse(edit.error().isEmpty());
        assertEquals(0, local.points.getLast().x);
        local.points.getLast().x = 1;
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertTrue(edit.accept());
        assertTrue(edit.closed());
      }
    }
  }

  @Test
  void sourceNodeMutationReplacementAndDocumentChangesNeverPublish() {
    for (int mode = 0; mode < 6; mode++)
      try (var s = state()) {
        var local = curve(s);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, local, worker, ui, () -> {})) {
          edit.apply(local);
          worker.run();
          switch (mode) {
            case 0 -> item(s).parameters.put("curve", item(s).parameters.get("curve").deepCopy());
            case 1 -> ((com.fasterxml.jackson.databind.node.ObjectNode)
                item(s).parameters.get("curve")).put("displayMax", 10);
            case 2 -> s.project().project.seed++;
            case 3 -> s.refreshDraft();
            case 4 -> s.multiSelection.clear();
            case 5 -> s.newSession();
          }
          var before = ProjectIO.JSON.valueToTree(s.project());
          var draft = ProjectIO.JSON.valueToTree(s.draft);
          ui.remove().run();
          assertFalse(edit.accept());
          assertFalse(edit.closed());
          assertFalse(edit.running());
          assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
          assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
        }
      }
  }

  @Test
  void preparedPayloadCannotBeChangedThroughCapturedInputOrPublishedDraft() {
    try (var s = state()) {
      var local = curve(s);
      local.points.getLast().y = 3;
      var input = ProjectIO.JSON.valueToTree(local);
      var prepared = CurveDraftEdit.prepare(input);
      ((com.fasterxml.jackson.databind.node.ObjectNode) input).put("displayMax", 99);
      var first = new CurveDraftEdit(s, noise(s), item(s));
      first.stagePrepared(s, prepared);
      assertEquals(1, curve(s).displayMax);
      var staged = (com.fasterxml.jackson.databind.node.ObjectNode) item(s).parameters.get("curve");
      staged.put("displayMax", 7);
      var next = new CurveDraftEdit(s, noise(s), item(s));
      next.stagePrepared(s, prepared);
      assertEquals(1, curve(s).displayMax);
      assertEquals(3, curve(s).points.getLast().y);
    }
  }

  @Test
  void acceptedFollowUpFailureIsConsumedAndNeverRepeatsStaging() {
    try (var s = state()) {
      var local = curve(s);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var failure = new IllegalStateException("refresh");
      var attempts = new AtomicInteger();
      try (var edit = session(s, local, worker, ui, () -> {
        attempts.incrementAndGet();
        throw failure;
      })) {
        edit.apply(local);
        worker.run();
        var completion = ui.remove();
        completion.run();
        completion.run();
        assertTrue(edit.accept());
        assertTrue(edit.closed());
        assertSame(failure, edit.followUpFailure());
        assertEquals(1, attempts.get());
        assertThrows(IllegalStateException.class, () -> edit.apply(local));
      }
    }
  }

  @Test
  void readyResultStillChecksLocalAndParentValuesAtTheActionDecision() {
    for (boolean parent : new boolean[] {false, true})
      try (var s = state()) {
        var local = curve(s);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        try (var edit = session(s, local, worker, ui, () -> {})) {
          edit.apply(local);
          worker.run();
          ui.remove().run();
          assertTrue(edit.ready());
          if (parent) s.project().project.seed++;
          else local.points.getLast().y = 4;
          var draft = ProjectIO.JSON.valueToTree(s.draft);
          assertFalse(edit.accept());
          assertFalse(edit.closed());
          assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
        }
      }
  }

  @Test
  void cancellationOfReadyResultWinsBeforeItsFinalActionDecision() {
    try (var s = state()) {
      var local = curve(s);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var accepted = new AtomicInteger();
      try (var edit = session(s, local, worker, ui, accepted::incrementAndGet)) {
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertTrue(edit.ready());
        edit.cancelApply();
        assertFalse(edit.accept());
        assertFalse(edit.closed());
        assertEquals(0, accepted.get());
      }
    }
  }

  @Test
  void mismatchedCurveIdentityIsRejectedAndCorrectIdentityCanRetry() {
    try (var s = state()) {
      var local = curve(s);
      var original = local.uuid;
      local.uuid = UUID.randomUUID();
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = session(s, local, worker, ui, () -> {})) {
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertFalse(edit.accept());
        assertFalse(edit.closed());
        assertFalse(edit.error().isEmpty());
        assertEquals(original, curve(s).uuid);
        local.uuid = original;
        edit.apply(local);
        worker.run();
        ui.remove().run();
        assertTrue(edit.accept());
      }
    }
  }

}
