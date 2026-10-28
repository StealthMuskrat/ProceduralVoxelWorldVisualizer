package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
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

  private static Curve source(EditorState s) {
    return ProjectIO.JSON.convertValue(item(s).parameters.get("curve"), Curve.class);
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

  @Test
  void acceptedEqualValueCurveStillConsumesItsSourceToken() {
    try (var state = state()) {
      var edit = edit(state);
      var before = ProjectIO.JSON.valueToTree(state.draft);
      var curve = source(state);
      edit.stage(state, curve);
      assertTrue(edit.closed());
      assertFalse(edit.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      edit.cancel();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void cancelConsumesSourceWithoutChangingPrivateOrCommittedValues() {
    try (var state = state()) {
      var edit = edit(state);
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var committed = ProjectIO.JSON.valueToTree(state.project());
      var curve = source(state);
      edit.cancel();
      edit.cancel();
      assertTrue(edit.closed());
      assertFalse(edit.current(state));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void sameValueSourceReplacementRequiresReopeningInsteadOfRetargeting() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = source(state);
      var source = item(state).parameters.get("curve");
      item(state).parameters.put("curve", source.deepCopy());
      assertFalse(edit.current(state));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      assertEquals(source, item(state).parameters.get("curve"));
      assertFalse(edit.closed());
    }
  }

  @Test
  void sourceValueMutationIsRejectedAtPublicationEvenWithTheSameNode() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = source(state);
      ((com.fasterxml.jackson.databind.node.ObjectNode) item(state).parameters.get("curve"))
          .put("displayMax", 9);
      var changed = ProjectIO.JSON.valueToTree(state.draft);
      assertTrue(edit.current(state)); // Frame-time identity check deliberately avoids full copies.
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      assertEquals(changed, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void changedOrderedSelectionKindOrLogicalIdentityInvalidatesTheOpening() {
    for (int mode = 0; mode < 5; mode++)
      try (var state = state()) {
        var edit = edit(state);
        var curve = source(state);
        switch (mode) {
          case 0 -> state.multiSelection.clear();
          case 1 -> state.selectedKind = "noise3D";
          case 2 -> item(state).uuid = UUID.randomUUID();
          case 3 -> noise(state).uuid = UUID.randomUUID();
          case 4 -> state.project().project.uuid = UUID.randomUUID();
        }
        var draft = ProjectIO.JSON.valueToTree(state.draft);
        assertFalse(edit.current(state));
        assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
        assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      }
  }

  @Test
  void unsupportedProjectMutationCannotBeOverwrittenByPrivateStaging() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = source(state);
      state.project().project.seed++;
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      assertTrue(edit.current(state));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void unrelatedPendingFieldsAndRawSeedEditsSurviveSingleUseStaging() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = source(state);
      curve.points.getLast().y = 3;
      noise(state).id = "pending-name";
      assertTrue(state.seedInputs.edit(state, "seedOffset", "Seed offset", "-").isEmpty());
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      assertTrue(edit.current(state));
      edit.stage(state, curve);
      curve.points.getLast().y = 99;
      assertEquals(3, source(state).points.getLast().y);
      assertEquals("pending-name", noise(state).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertFalse(state.applyDraft());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void curveIdentityReplacementRejectsBeforeMutationAndOriginalIdentityCanRetry() {
    try (var state = state()) {
      var edit = edit(state);
      var curve = source(state);
      var identity = curve.uuid;
      var before = ProjectIO.JSON.valueToTree(state.draft);
      curve.uuid = UUID.randomUUID();
      assertThrows(IllegalArgumentException.class, () -> edit.stage(state, curve));
      assertFalse(edit.closed());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      curve.uuid = identity;
      edit.stage(state, curve);
      assertTrue(edit.closed());
    }
  }

  @Test
  void siblingReorderingAndASeparateEditorCannotReceiveAnOlderPublication() {
    try (var state = state();
        var other = state()) {
      var selected = state.selected;
      assertTrue(
          state.change(
              p -> {
                var sibling = new Noise();
                sibling.id = "sibling";
                p.noise2D.add(sibling);
              }));
      state.select("noise2D", selected);
      state.multiSelection.add(state.project().noise2D.getLast().uuid);
      var edit = edit(state);
      var curve = source(state);
      assertFalse(edit.current(other));
      assertThrows(IllegalStateException.class, () -> edit.stage(other, curve));
      assertTrue(edit.current(state));
      state.multiSelection.remove(selected);
      state.multiSelection.add(selected);
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      assertFalse(edit.current(state));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, curve));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
    }
  }
}
