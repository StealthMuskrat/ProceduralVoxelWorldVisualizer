package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

class CurveDraftEditTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new Noise();
              n.id = "curve";
              var item = ProcessingRegistry.create("CURVE", "curve");
              n.processing.add(item);
              n.outputBinding = Binding.earlier(item.uuid);
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private static Noise noise(EditorState s) {
    return (Noise) s.draft;
  }

  private static Item item(EditorState s) {
    return noise(s).processing.getFirst();
  }

  private static CurveDraftEdit edit(EditorState s) {
    return new CurveDraftEdit(s, noise(s), item(s));
  }

  @Test
  void stagingOwnsCurveAndPreservesOtherDraftFieldsWithoutHistory() {
    try (var state = state()) {
      item(state).parameters.remove("curve");
      var edit = edit(state);
      var curve = new Curve();
      curve.points.getLast().y = 3;
      noise(state).id = "pending";
      var committed = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      edit.stage(state, curve);
      curve.points.getLast().y = 9;
      assertEquals(
          3, item(state).parameters.get("curve").path("points").get(1).path("y").asDouble());
      assertEquals("pending", noise(state).id);
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertTrue(state.applyDraft());
      state.document.undo();
      assertEquals("curve", state.project().noise2D.getFirst().id);
      state.document.redo();
      assertEquals("pending", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void changedSourceAndReplacedOrRemovedOwnersRejectPublication() {
    for (int mode = 0; mode < 5; mode++)
      try (var state = state()) {
        var edit = edit(state);
        switch (mode) {
          case 0 -> item(state).parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
          case 1 -> state.refreshDraft();
          case 2 -> noise(state).processing.clear();
          case 3 -> state.change(p -> p.project.seed++);
          case 4 -> state.newSession();
        }
        var before = ProjectIO.JSON.valueToTree(state.project());
        assertThrows(IllegalStateException.class, () -> edit.stage(state, new Curve()));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void malformedCurvesNeverChangeTheParentDraft() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = new Curve();
      curve.points.getLast().x = 0;
      var before = ProjectIO.JSON.valueToTree(state.draft);
      assertThrows(IllegalArgumentException.class, () -> edit.stage(state, curve));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
    }
  }
}
