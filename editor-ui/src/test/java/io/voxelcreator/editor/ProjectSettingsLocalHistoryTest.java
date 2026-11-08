package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred raw-text/gesture history; only accepted Apply changes the project. */
class ProjectSettingsLocalHistoryTest {
  @TempDir Path directory;

  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> {
      var noise = new Noise();
      noise.id = "committed";
      noise.algorithm.key = "CONSTANT";
      noise.algorithm.parameters.clear();
      noise.algorithm.parameters.put("value", .125);
      p.noise2D.add(noise);
    }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private-name";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static Map<String, String> text(ProjectSettingsSession edit) {
    var values = new LinkedHashMap<String, String>();
    ProjectSettingsSession.FIELDS.forEach(field -> values.put(field, edit.text(field)));
    return Map.copyOf(values);
  }

  private record Before(JsonNode project, JsonNode draft, JsonNode user, Object owner,
      UUID selected, Set<UUID> selection, long revision, long history, boolean dirty) {
    Before(EditorState state) {
      this(ProjectIO.JSON.valueToTree(state.project()), ProjectIO.JSON.valueToTree(state.draft),
          ProjectIO.JSON.valueToTree(state.user), state.draft, state.selected,
          Set.copyOf(state.multiSelection), state.revision, state.document.historyBytes(), state.document.dirty());
    }

    void unchanged(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertSame(owner, state.draft);
      assertEquals(selected, state.selected);
      assertEquals(selection, state.multiSelection);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void invalidAndNoncanonicalRawFieldsRoundTripWithoutTouchingParentState() {
    try (var state = state()) {
      var before = new Before(state);
      var edit = new ProjectSettingsSession(state);
      var initial = text(edit);
      edit.edit("seed", "-");
      edit.edit("minY", "+0002");
      edit.edit("maxY", "-0003");
      var changed = text(edit);
      assertFalse(edit.validate().isEmpty());
      for (int i = 0; i < 3; i++) assertTrue(edit.undo());
      assertEquals(initial, text(edit));
      assertTrue(edit.validate().isEmpty());
      for (int i = 0; i < 3; i++) assertTrue(edit.redo());
      assertEquals(changed, text(edit));
      before.unchanged(state);
      edit.cancel();
    }
  }

  @Test
  void oneTypingGestureAndOneSliderGestureAreSeparateLogicalActions() {
    try (var state = state()) {
      var edit = new ProjectSettingsSession(state);
      edit.edit("seed", "1", "seed:text");
      edit.edit("seed", "12", "seed:text");
      edit.edit("seed", "123", "seed:text");
      edit.focusEdit("seed:slider");
      edit.edit("seed", "124", "seed:slider");
      edit.edit("seed", "125", "seed:slider");
      edit.finishEdit();
      assertTrue(edit.undo());
      assertEquals("123", edit.text("seed"));
      assertTrue(edit.undo());
      assertEquals("0", edit.text("seed"));
      assertFalse(edit.canUndo());
      assertTrue(edit.redo());
      assertEquals("123", edit.text("seed"));
      assertTrue(edit.redo());
      assertEquals("125", edit.text("seed"));
      edit.cancel();
    }
  }

  @Test
  void noopsInvalidArgumentsAndNetZeroGesturesPreserveRedoAndCachedValidation() {
    try (var state = state()) {
      var edit = new ProjectSettingsSession(state);
      edit.edit("seed", "17");
      assertTrue(edit.undo());
      long version = edit.inputVersion();
      var feedback = edit.validate();
      edit.edit("seed", "0");
      assertThrows(IllegalArgumentException.class, () -> edit.edit("unknown", "2"));
      assertThrows(IllegalArgumentException.class, () -> edit.edit("seed", "1".repeat(129)));
      assertFalse(edit.undo());
      assertEquals(version, edit.inputVersion());
      assertSame(feedback, edit.validate());
      edit.edit("seed", "+", "seed:text");
      edit.edit("seed", "0", "seed:text");
      edit.finishEdit();
      assertFalse(edit.modified());
      assertTrue(edit.canRedo());
      assertTrue(edit.redo());
      assertEquals("17", edit.text("seed"));
      edit.cancel();
    }
  }

  @Test
  void rejectedApplyCanBeUndoneAndFinalApplyHasOneProjectTransaction() {
    try (var state = state()) {
      var before = new Before(state);
      var edit = new ProjectSettingsSession(state);
      edit.edit("seed", "17");
      edit.edit("minY", "-2");
      edit.edit("maxY", "3");
      state.document.setValidator(p -> List.of(Diagnostic.error("REJECTED", "/project", "reject")));
      assertFalse(edit.apply(state));
      assertEquals(1, edit.issueCount());
      assertTrue(edit.undo());
      assertEquals(0, edit.issueCount());
      assertTrue(edit.redo());
      before.unchanged(state);
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(edit.apply(state));
      assertFalse(edit.canUndo());
      assertFalse(edit.canRedo());
      assertEquals(before.revision() + 1, state.revision);
      assertSame(before.owner(), state.draft);
      assertEquals("private-name", ((Noise) state.draft).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      var accepted = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(accepted, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void localUndoRetiresQueuedOrReadyApplyAndOnlyAnExplicitRetryPublishes() {
    for (boolean ready : List.of(false, true))
      try (var state = state()) {
        var before = new Before(state);
        var work = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var edit = new ProjectSettingsSession(state, work::add, ui::add, () -> false);
        edit.edit("seed", "17");
        assertFalse(edit.apply(state));
        var oldWork = work.remove();
        Runnable oldReceipt = null;
        if (ready) {
          oldWork.run();
          oldReceipt = ui.remove();
          oldReceipt.run();
        }
        assertTrue(edit.undo());
        oldWork.run();
        if (oldReceipt != null) oldReceipt.run();
        assertFalse(edit.acceptPrepared(text(edit)));
        assertFalse(edit.applying());
        before.unchanged(state);
        assertTrue(edit.redo());
        assertFalse(edit.apply(state));
        if (oldReceipt != null) oldReceipt.run();
        work.remove().run();
        ui.remove().run();
        assertTrue(edit.acceptPrepared(text(edit)));
        assertFalse(edit.canUndo());
        state.document.undo();
        assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void closureAndSameUuidReplacementDisableAnEarlierHistoryAndNewSessionsStartEmpty() throws Exception {
    for (boolean apply : List.of(false, true))
      try (var state = state()) {
        var edit = new ProjectSettingsSession(state);
        edit.edit("seed", "17");
        if (apply) assertTrue(edit.apply(state));
        else edit.cancel();
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        assertThrows(IllegalStateException.class, edit::redo);
      }
    try (var state = state()) {
      var edit = new ProjectSettingsSession(state);
      edit.edit("seed", "17");
      var file = directory.resolve("same-project.json");
      state.document.save(file);
      assertTrue(state.document.open(file).isEmpty());
      assertFalse(edit.canUndo());
      assertThrows(IllegalStateException.class, edit::undo);
      var fresh = new ProjectSettingsSession(state);
      assertFalse(fresh.canUndo());
      assertFalse(fresh.canRedo());
      fresh.cancel();
      edit.cancel();
    }
  }
}
