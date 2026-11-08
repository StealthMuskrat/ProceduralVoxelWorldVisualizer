package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred exact private choices, worker supersession and host decision ownership. */
class NoiseResolutionHistoryTest {
  @TempDir Path directory;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(BackgroundNoiseEditProbe::fixture));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private Noise noise(EditorState state) {
    return (Noise) state.draft;
  }

  private Request request(EditorState state) {
    return Request.simple(Action.DELETE_PARAMETER, noise(state).sharedParameters.getFirst().uuid);
  }

  private static final class Workers implements Executor {
    final Queue<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("history queue full");
      tasks.add(task);
    }

    void run() {
      tasks.removeIf(task -> task instanceof Future<?> future && future.isCancelled());
      tasks.remove().run();
    }
  }

  @Test
  void exactChoicesStayPrivateAndFinalStagingStillNeedsOneParentApply() {
    try (var state = state(); var edit = new NoiseEditSession(state, noise(state), request(state))) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var user = ProjectIO.JSON.valueToTree(state.user);
      long revision = state.revision, bytes = state.document.historyBytes();
      edit.choose(Resolution.INLINE_PARAMETER, null);
      long choices = edit.choiceVersion(), work = edit.version();
      var preview = edit.preview();
      edit.choose(Resolution.INLINE_PARAMETER, null);
      assertSame(preview, edit.preview());
      assertEquals(choices, edit.choiceVersion());
      assertEquals(work, edit.version());
      assertTrue(edit.undo());
      assertEquals(Resolution.REJECT, edit.resolution());
      assertFalse(edit.preview().valid());
      assertTrue(edit.redo());
      assertEquals(Resolution.INLINE_PARAMETER, edit.resolution());
      assertTrue(edit.preview().valid());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      edit.stage();
      assertFalse(edit.canUndo());
      assertFalse(edit.canRedo());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertTrue(state.applyDraft());
      assertEquals(12., new NoiseCompiler(state.project(), 0).compile(state.selected).sample(-17, 0, 23));
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void replacementPairsBranchExactlyAndRetainOnlyOneHundredChanges() {
    try (var state = state(); var edit = new NoiseEditSession(state, noise(state), request(state))) {
      var replacements = new ArrayList<UUID>();
      for (int i = 0; i < 105; i++) {
        UUID replacement = UUID.randomUUID();
        replacements.add(replacement);
        edit.choose(Resolution.REPLACE, replacement);
      }
      int count = 0;
      while (edit.undo()) count++;
      assertEquals(100, count);
      assertEquals(Resolution.REPLACE, edit.resolution());
      assertEquals(replacements.get(4), edit.replacement());
      assertTrue(edit.redo());
      assertEquals(replacements.get(5), edit.replacement());
      edit.choose(Resolution.REPLACE, replacements.get(5));
      assertTrue(edit.canRedo());
      edit.choose(Resolution.INLINE_PARAMETER, null);
      assertFalse(edit.canRedo());
      assertTrue(edit.undo());
      assertEquals(replacements.get(5), edit.replacement());
    }
  }

  @Test
  void synchronousFailureAndReentrantPlanningDoNotConsumeOrOverwriteNewerHistory() {
    try (var state = state()) {
      var fail = new AtomicBoolean();
      var reenter = new AtomicBoolean();
      var active = new AtomicReference<NoiseEditSession>();
      try (var edit = new NoiseEditSession(state, noise(state), request(state), null, null,
          (project, owner, request) -> {
            if (fail.get()) throw new IllegalStateException("planner failure");
            if (reenter.getAndSet(false)) active.get().choose(Resolution.INLINE_PARAMETER, null);
            return NoiseAuthoring.plan(project, owner, request);
          })) {
        active.set(edit);
        edit.choose(Resolution.INLINE_PARAMETER, null);
        long version = edit.choiceVersion();
        var preview = edit.preview();
        fail.set(true);
        assertThrows(IllegalStateException.class, edit::undo);
        assertSame(preview, edit.preview());
        assertEquals(version, edit.choiceVersion());
        assertTrue(edit.canUndo());
        fail.set(false);
        assertTrue(edit.undo());
        reenter.set(true);
        assertThrows(IllegalStateException.class, () -> edit.choose(Resolution.REPLACE, UUID.randomUUID()));
        assertEquals(Resolution.INLINE_PARAMETER, edit.resolution());
        assertTrue(edit.undo());
        assertEquals(Resolution.REJECT, edit.resolution());
        assertFalse(edit.canUndo());
      }
    }
  }

  @Test
  void queuedAndDeliveredPreviewsAreSupersededBeforeHistoryRestoration() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = new NoiseEditSession(state, noise(state), request(state), worker, ui::add)) {
        var original = ProjectIO.JSON.valueToTree(state.draft);
        long work = edit.version();
        assertFalse(edit.undo());
        assertEquals(work, edit.version());
        worker.run();
        Runnable obsolete = ui.remove();
        edit.choose(Resolution.INLINE_PARAMETER, null);
        worker.run();
        Runnable deliveredChoice = ui.remove();
        assertTrue(edit.undo());
        assertTrue(edit.redo());
        obsolete.run();
        deliveredChoice.run();
        edit.acceptPrepared();
        assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase());
        assertFalse(edit.canStage());
        assertEquals(original, ProjectIO.JSON.valueToTree(state.draft));
        worker.run();
        ui.remove().run();
        edit.acceptPrepared();
        assertTrue(edit.canStage());
        assertEquals(original, ProjectIO.JSON.valueToTree(state.draft));
        assertTrue(edit.undo());
        worker.run();
        ui.remove().run();
        edit.acceptPrepared();
        assertFalse(edit.canStage());
        assertEquals(Resolution.REJECT, edit.resolution());
      }
    }
  }

  @Test
  void schedulerFailureRetainsChoiceHistoryAndRetryDoesNotCreateAHistoryStep() {
    try (var state = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = new NoiseEditSession(state, noise(state), request(state), worker, ui::add)) {
        worker.reject = true;
        edit.choose(Resolution.INLINE_PARAMETER, null);
        assertEquals(NoiseEditSession.Phase.FAILED, edit.phase());
        assertTrue(edit.canUndo());
        long choices = edit.choiceVersion();
        worker.reject = false;
        edit.retry();
        assertEquals(choices, edit.choiceVersion());
        worker.run();
        ui.remove().run();
        edit.acceptPrepared();
        assertTrue(edit.canStage());
        assertTrue(edit.undo());
        assertEquals(Resolution.REJECT, edit.resolution());
        assertFalse(edit.canUndo());
        assertTrue(edit.message().contains("Checking"));
      }
    }
  }

  @Test
  void changedSourcesSameUuidReopenAndClosureCannotRestoreHistory() throws Exception {
    try (var state = state()) {
      var edit = new NoiseEditSession(state, noise(state), request(state));
      edit.choose(Resolution.INLINE_PARAMETER, null);
      noise(state).id = "changed";
      assertThrows(IllegalStateException.class, edit::undo);
      state.refreshDraft();
      edit.close();
      assertFalse(edit.canUndo());
      assertThrows(IllegalStateException.class, edit::redo);
      var replacement = new NoiseEditSession(state, noise(state), request(state));
      replacement.choose(Resolution.INLINE_PARAMETER, null);
      var file = directory.resolve("same-uuid.json");
      new ProjectIO().save(file, state.project());
      assertTrue(state.document.open(file).isEmpty());
      assertFalse(replacement.canUndo());
      assertThrows(IllegalStateException.class, replacement::undo);
      replacement.close();
    }
  }

  @Test
  void hostDecisionsRejectOldChoiceRoundTripsAndCancelWinsOverHistory() {
    try (var state = state(); var dialog = new NoiseEditDialog(state, noise(state), request(state), id -> {})) {
      dialog.choose(Resolution.INLINE_PARAMETER, null);
      var old = dialog.capture();
      assertTrue(dialog.session().undo());
      assertTrue(dialog.session().redo());
      long choices = dialog.session().choiceVersion();
      assertFalse(dialog.decide(old, true, false, false, false));
      assertFalse(dialog.decide(old, false, false, false, false, true, false));
      assertEquals(choices, dialog.session().choiceVersion());
      assertFalse(dialog.decide(dialog.capture(), true, false, false, false, true, false));
      assertEquals(Resolution.REJECT, dialog.session().resolution());
      assertEquals(2, noise(state).sharedParameters.size());
      assertTrue(dialog.decide(dialog.capture(), true, true, false, false, false, true));
      assertEquals(2, noise(state).sharedParameters.size());
      assertFalse(dialog.session().canRedo());
    }
  }
}
