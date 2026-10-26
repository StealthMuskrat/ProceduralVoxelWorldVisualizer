package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

class ExpressionEditSessionTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new Noise();
              n.id = "formula";
              var item = ProcessingRegistry.create("EXPRESSION", "formula");
              item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+2"));
              n.processing.add(item);
              n.outputBinding = Binding.earlier(item.uuid);
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private static Noise noise(EditorState state) {
    return (Noise) state.draft;
  }

  private static Item item(EditorState state) {
    return noise(state).processing.getFirst();
  }

  private static ExpressionEditSession session(EditorState state) {
    return new ExpressionEditSession(state, noise(state), item(state));
  }

  @Test
  void cancelDoesNotAlterEitherDraftOrDocumentHistory() {
    try (var state = state()) {
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      var edit = session(state);
      edit.edit("x+\n9");
      assertTrue(edit.feedback(state).valid());
      edit.cancel();
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
      assertThrows(IllegalStateException.class, () -> edit.stage(state));
    }
  }

  @Test
  void stageChangesOnlyTextAndOrdinaryApplyMakesOneTransaction() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("x+\n6");
      noise(state).id = "pending-name";
      long history = state.document.historyBytes(), revision = state.revision;
      edit.stage(state);
      assertEquals("x+\n6", item(state).parameters.get("expression").asText());
      assertEquals(
          "x+2",
          state
              .project()
              .noise2D
              .getFirst()
              .processing
              .getFirst()
              .parameters
              .get("expression")
              .asText());
      assertEquals("pending-name", noise(state).id);
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
      assertTrue(state.applyDraft());
      var n = state.project().noise2D.getFirst();
      assertEquals(6, new NoiseCompiler(state.project(), 0).compile(n.uuid).sample(0, 0, 0));
      state.document.undo();
      assertEquals("formula", state.project().noise2D.getFirst().id);
      state.document.redo();
      assertEquals("pending-name", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void incompleteTextCanBeStagedButApplyPreservesRejectedDraft() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("x+\nunknown");
      var feedback = edit.feedback(state);
      assertFalse(feedback.valid());
      assertEquals(3, feedback.start());
      assertEquals(10, feedback.end());
      edit.stage(state);
      var draft = state.draft;
      assertFalse(state.applyDraft());
      assertSame(draft, state.draft);
      assertEquals("x+\nunknown", item(state).parameters.get("expression").asText());
      assertEquals(
          "x+2",
          state
              .project()
              .noise2D
              .getFirst()
              .processing
              .getFirst()
              .parameters
              .get("expression")
              .asText());
    }
  }

  @Test
  void changedExpressionOrRemovedItemCannotBeOverwritten() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("9");
      item(state).parameters.put("expression", ProjectIO.JSON.valueToTree("7"));
      assertThrows(IllegalStateException.class, () -> edit.stage(state));
      assertEquals("7", item(state).parameters.get("expression").asText());
      var removed = session(state);
      noise(state).processing.clear();
      assertThrows(IllegalStateException.class, () -> removed.stage(state));
    }
  }

  @Test
  void refreshCommitAndProjectReplacementRejectOldSessions() {
    try (var state = state()) {
      var refreshed = session(state);
      state.refreshDraft();
      assertThrows(IllegalStateException.class, () -> refreshed.stage(state));
      var committed = session(state);
      assertTrue(state.change(p -> p.project.seed++));
      assertThrows(IllegalStateException.class, () -> committed.stage(state));
      state.refreshDraft();
      var replaced = session(state);
      state.newSession();
      assertThrows(IllegalStateException.class, () -> replaced.stage(state));
    }
  }

  @Test
  void lengthLimitsAreInUtf16AndNeverTruncateOrPublish() {
    try (var state = state()) {
      var edit = session(state);
      String unicode = "あ".repeat(ExpressionEditSession.MAX_CHARACTERS);
      edit.edit(unicode);
      edit.stage(state);
      assertEquals(unicode, item(state).parameters.get("expression").asText());
      var tooLong = session(state);
      tooLong.edit(unicode + "あ");
      assertThrows(IllegalArgumentException.class, () -> tooLong.stage(state));
      assertEquals(unicode, item(state).parameters.get("expression").asText());
      item(state).parameters.put("expression", ProjectIO.JSON.valueToTree(unicode + "あ"));
      assertThrows(IllegalArgumentException.class, () -> session(state));
    }
  }
}
