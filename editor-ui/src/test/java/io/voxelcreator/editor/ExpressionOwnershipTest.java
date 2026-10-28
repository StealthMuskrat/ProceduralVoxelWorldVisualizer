package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred expanded-expression ownership, live feedback and publication-boundary definitions. */
class ExpressionOwnershipTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "field";
              var item = ProcessingRegistry.create("EXPRESSION", "formula");
              item.inputs.put("bias", Binding.constant(2));
              item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+bias"));
              noise.processing.add(item);
              noise.outputBinding = Binding.earlier(item.uuid);
              p.noise2D.add(noise);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private Noise noise(EditorState state) {
    return (Noise) state.draft;
  }

  private Item item(EditorState state) {
    return noise(state).processing.getFirst();
  }

  private ExpressionEditSession session(EditorState state) {
    return new ExpressionEditSession(state, noise(state), item(state));
  }

  @Test
  void equalValueSourceNodeReplacementRequiresReopening() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("x+9");
      var original = item(state).parameters.get("expression");
      var replacement = ProjectIO.JSON.valueToTree(original.asText());
      assertNotSame(original, replacement);
      item(state).parameters.put("expression", replacement);
      assertFalse(edit.current(state));
      assertThrows(IllegalStateException.class, () -> edit.feedback(state));
      assertThrows(IllegalStateException.class, () -> edit.stage(state, "x+9"));
      assertEquals("x+9", edit.text());
      assertSame(replacement, item(state).parameters.get("expression"));
      assertFalse(edit.closed());
    }
  }

  @Test
  void logicalIdentitiesAndSelectionChangesCannotRetargetTheOpening() {
    for (int mode = 0; mode < 6; mode++)
      try (var state = state()) {
        var edit = session(state);
        edit.edit("x+9");
        switch (mode) {
          case 0 -> state.project().project.uuid = UUID.randomUUID();
          case 1 -> noise(state).uuid = UUID.randomUUID();
          case 2 -> item(state).uuid = UUID.randomUUID();
          case 3 -> state.selectedKind = "noise3D";
          case 4 -> state.multiSelection.clear();
          case 5 -> item(state).kind = "ADD";
        }
        var before = ProjectIO.JSON.valueToTree(state.draft);
        assertFalse(edit.current(state));
        assertThrows(IllegalStateException.class, () -> edit.stage(state));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
        assertEquals("x+9", edit.text());
      }
  }

  @Test
  void foreignEditorAndUnselectedDraftConstructionRejectWithoutMutation() {
    try (var state = state();
        var foreign = state()) {
      var edit = session(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertFalse(edit.current(foreign));
      assertThrows(IllegalStateException.class, () -> edit.stage(foreign));
      var offscreen =
          ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(noise(state)), Noise.class);
      assertThrows(
          IllegalArgumentException.class,
          () -> new ExpressionEditSession(state, offscreen, offscreen.processing.getFirst()));
      assertTrue(edit.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void unrevisionedProjectChangeIsDetectedBeforeStagingAndPermanentlyMarksStale() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("x+9");
      long seed = state.project().project.seed;
      state.project().project.seed++;
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      assertTrue(edit.current(state)); // Complete project values are checked at publication.
      assertThrows(IllegalStateException.class, () -> edit.stage(state));
      assertFalse(edit.current(state));
      state.project().project.seed = seed;
      assertThrows(IllegalStateException.class, () -> edit.stage(state));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals("x+9", edit.text());
    }
  }

  @Test
  void unrelatedPrivateFieldsAndRawSeedTextSurviveAcceptedStaging() {
    try (var state = state()) {
      var edit = session(state);
      edit.edit("x+\nbias*3");
      noise(state).id = "pending-id";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      assertTrue(edit.feedback(state).valid());
      edit.stage(state, "x+\nbias*3");
      assertTrue(edit.closed());
      assertEquals("pending-id", noise(state).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals("x+\nbias*3", item(state).parameters.get("expression").asText());
      assertFalse(state.applyDraft());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void equalValueStageAndCancelConsumeBeforeAnyFurtherPublication() {
    try (var state = state()) {
      var accepted = session(state);
      var before = ProjectIO.JSON.valueToTree(state.draft);
      accepted.stage(state, accepted.text());
      assertTrue(accepted.closed());
      assertFalse(accepted.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      assertThrows(IllegalStateException.class, () -> accepted.stage(state));
      assertThrows(IllegalStateException.class, () -> accepted.feedback(state));
      var cancelled = session(state);
      cancelled.cancel();
      cancelled.cancel();
      assertTrue(cancelled.closed());
      assertThrows(IllegalStateException.class, () -> cancelled.edit("9"));
      assertThrows(IllegalStateException.class, () -> cancelled.stage(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void unchangedFeedbackAndPrivateTextEditsReuseOneOwnedItemCandidate() {
    try (var state = state()) {
      var edit = session(state);
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var first = edit.feedback(state);
      assertTrue(first.valid());
      assertSame(first, edit.feedback(state));
      edit.edit(edit.text());
      assertSame(first, edit.feedback(state));
      edit.edit("x+bias+1");
      assertTrue(edit.feedback(state).valid());
      edit.edit("x+bias");
      assertSame(first, edit.feedback(state));
      assertEquals(1, edit.preparations());
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void namedInputEditsRefreshTheCandidateAndStagePreservesTheirLatestValues() {
    try (var state = state()) {
      var edit = session(state);
      assertTrue(edit.feedback(state).valid());
      item(state).inputs.get("bias").type = "Vector3";
      var bad = edit.feedback(state);
      assertFalse(bad.valid());
      assertEquals("bias", bad.input());
      assertEquals(2, edit.preparations());
      item(state).inputs.get("bias").type = "Scalar";
      item(state).inputs.get("bias").value = ProjectIO.JSON.valueToTree(7);
      assertTrue(edit.feedback(state).valid());
      assertEquals(3, edit.preparations());
      edit.edit("x+bias*3");
      edit.stage(state);
      assertEquals(7, item(state).inputs.get("bias").value.asInt());
      assertEquals("x+bias*3", item(state).parameters.get("expression").asText());
    }
  }

  @Test
  void sharedParameterContextRemainsLiveWithoutRebuildingAnUnchangedItem() {
    try (var state = state()) {
      var shared = new Parameter();
      shared.id = "shared";
      shared.value = ProjectIO.JSON.valueToTree(2);
      noise(state).sharedParameters.add(shared);
      var binding = item(state).inputs.get("bias");
      binding.sourceKind = "Shared Parameter";
      binding.parameterUuid = shared.uuid;
      var edit = session(state);
      var first = edit.feedback(state);
      assertTrue(first.valid());
      shared.type = "Boolean";
      assertFalse(edit.feedback(state).valid());
      assertEquals("bias", edit.feedback(state).input());
      shared.type = "Scalar";
      shared.value = ProjectIO.JSON.valueToTree(9);
      var changed = edit.feedback(state);
      assertTrue(changed.valid());
      assertNotSame(first, changed);
      assertEquals(1, edit.preparations());
      edit.stage(state);
      assertEquals(9, noise(state).sharedParameters.getFirst().value.asInt());
    }
  }

  @Test
  void displayedMismatchCannotPublishOldTextAndMissingSourceCanStageIncompleteText() {
    try (var state = state()) {
      item(state).parameters.remove("expression");
      var edit = session(state);
      assertEquals("", edit.text());
      edit.edit("sin(");
      var before = ProjectIO.JSON.valueToTree(state.draft);
      assertThrows(IllegalStateException.class, () -> edit.stage(state, "x+2"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      edit.stage(state, "sin(");
      assertTrue(edit.closed());
      assertEquals("sin(", item(state).parameters.get("expression").asText());
      assertFalse(state.applyDraft());
      assertEquals(
          "x+bias",
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
}
