package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred contracts for owned dependency resolution and private-only structural publication. */
class NoiseEditSessionTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "field";
              noise.algorithm.key = "CONSTANT";
              noise.algorithm.parameters.clear();
              noise.algorithm.parameters.put("value", 1.);
              var gain = new Parameter();
              gain.id = "gain";
              gain.value = ProjectIO.JSON.valueToTree(2.);
              noise.sharedParameters.add(gain);
              var add = ProcessingRegistry.create("ADD", "add");
              var binding = Binding.constant(0);
              binding.sourceKind = "Shared Parameter";
              binding.parameterUuid = gain.uuid;
              add.inputs.put("b", binding);
              var multiply = ProcessingRegistry.create("MULTIPLY", "multiply");
              multiply.inputs.put("a", Binding.earlier(add.uuid));
              multiply.inputs.put("b", Binding.constant(4));
              noise.processing.addAll(List.of(add, multiply));
              noise.outputBinding = Binding.earlier(multiply.uuid);
              p.noise2D.add(noise);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private Noise noise(EditorState state) {
    return (Noise) state.draft;
  }

  private NoiseEditSession session(EditorState state) {
    return new NoiseEditSession(
        state,
        noise(state),
        Request.simple(Action.DELETE_PARAMETER, noise(state).sharedParameters.getFirst().uuid));
  }

  private double sample(EditorState state) {
    return new NoiseCompiler(state.project(), 0)
        .compile(state.project().noise2D.getFirst().uuid)
        .sample(-17, 0, 23);
  }

  @Test
  void explicitInlineStagesOnlyPropertiesThenParentApplyMakesOneUndoableEdit() {
    try (var state = state()) {
      noise(state).id = "pending-name";
      var owner = noise(state);
      var edit = session(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, bytes = state.document.historyBytes();
      assertFalse(edit.preview().valid());
      edit.choose(Resolution.INLINE_PARAMETER, null);
      assertTrue(edit.preview().valid());
      assertNull(edit.stage());
      assertTrue(edit.closed());
      assertSame(owner, state.draft);
      assertEquals("pending-name", owner.id);
      assertTrue(owner.sharedParameters.isEmpty());
      assertEquals("Constant", owner.processing.getFirst().inputs.get("b").sourceKind);
      assertEquals(2., owner.processing.getFirst().inputs.get("b").value.asDouble());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      assertTrue(state.applyDraft());
      assertEquals(12, sample(state));
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(12, sample(state));
    }
  }

  @Test
  void invalidResolutionPreservesDraftAndCanBeCorrectedWithoutReopening() {
    try (var state = state()) {
      var edit = session(state);
      var before = ProjectIO.JSON.valueToTree(state.draft);
      edit.choose(Resolution.REPLACE, UUID.randomUUID());
      assertFalse(edit.preview().valid());
      assertThrows(IllegalStateException.class, edit::stage);
      assertFalse(edit.closed());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
      edit.choose(Resolution.INLINE_PARAMETER, null);
      var plan = edit.preview();
      edit.choose(Resolution.INLINE_PARAMETER, null);
      assertSame(plan, edit.preview());
      edit.stage();
      assertTrue(edit.closed());
    }
  }

  @Test
  void changedDraftRejectsChoiceRecompilationAndStageWithoutReplacingPreview() {
    try (var state = state()) {
      var edit = session(state);
      var plan = edit.preview();
      noise(state).id = "changed-elsewhere";
      var changed = ProjectIO.JSON.valueToTree(state.draft);
      assertThrows(
          IllegalStateException.class, () -> edit.choose(Resolution.INLINE_PARAMETER, null));
      assertSame(plan, edit.preview());
      assertEquals(Resolution.REJECT, edit.resolution());
      assertThrows(IllegalStateException.class, edit::stage);
      assertEquals(changed, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void equalReplacementDraftChangedSelectionAndCommittedRevisionInvalidateOwnership() {
    for (int change = 0; change < 3; change++) {
      try (var state = state()) {
        var edit = session(state);
        edit.choose(Resolution.INLINE_PARAMETER, null);
        switch (change) {
          case 0 -> state.refreshDraft();
          case 1 -> state.multiSelection.clear();
          case 2 -> assertTrue(state.change(p -> p.project.seed++));
          default -> throw new AssertionError();
        }
        var draft = ProjectIO.JSON.valueToTree(state.draft);
        var committed = ProjectIO.JSON.valueToTree(state.project());
        assertFalse(edit.current());
        assertThrows(IllegalStateException.class, edit::stage);
        assertThrows(IllegalStateException.class, () -> edit.choose(Resolution.REJECT, null));
        assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
        assertEquals(committed, ProjectIO.JSON.valueToTree(state.project()));
      }
    }
  }

  @Test
  void changedRawSeedTextInvalidatesAnOpeningPlanWithoutUsingAnOlderParsedSeed() {
    try (var state = state()) {
      var edit = session(state);
      edit.choose(Resolution.INLINE_PARAMETER, null);
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      assertTrue(state.seedInputs.edit(state, "seedOffset", "Seed offset", "-").isEmpty());
      assertFalse(edit.current());
      assertThrows(IllegalStateException.class, edit::stage);
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void existingRawSeedTextSurvivesPrivateStagingAndStillBlocksParentApply() {
    try (var state = state()) {
      assertTrue(state.seedInputs.edit(state, "seedOffset", "Seed offset", "+").isEmpty());
      var edit = session(state);
      edit.choose(Resolution.INLINE_PARAMETER, null);
      edit.stage();
      assertEquals("+", state.seedInputs.text("seedOffset"));
      assertFalse(state.applyDraft());
      assertTrue(noise(state).sharedParameters.isEmpty());
      assertEquals(1, state.project().noise2D.getFirst().sharedParameters.size());
      assertEquals(12, sample(state));
    }
  }

  @Test
  void unsupportedInPlaceProjectMutationIsCaughtBeforeChoosingOrStaging() {
    try (var state = state()) {
      var edit = session(state);
      var plan = edit.preview();
      state.project().project.seed++;
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      assertThrows(
          IllegalStateException.class, () -> edit.choose(Resolution.INLINE_PARAMETER, null));
      assertSame(plan, edit.preview());
      assertThrows(IllegalStateException.class, edit::stage);
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void cancelAndSuccessfulStageConsumeSessionsAndOffscreenConstructionRejects() {
    try (var state = state()) {
      var cancelled = session(state);
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      cancelled.cancel();
      cancelled.cancel();
      assertTrue(cancelled.closed());
      assertFalse(cancelled.current());
      assertThrows(IllegalStateException.class, cancelled::stage);
      assertThrows(
          IllegalStateException.class, () -> cancelled.choose(Resolution.INLINE_PARAMETER, null));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      var owner = noise(state);
      var accepted =
          new NoiseEditSession(
              state,
              owner,
              new Request(
                  Action.DELETE_PARAMETER,
                  owner.sharedParameters.getFirst().uuid,
                  Resolution.INLINE_PARAMETER,
                  null));
      assertEquals(Resolution.INLINE_PARAMETER, accepted.resolution());
      accepted.stage();
      var staged = ProjectIO.JSON.valueToTree(state.draft);
      assertThrows(IllegalStateException.class, accepted::stage);
      assertThrows(IllegalStateException.class, () -> accepted.choose(Resolution.REJECT, null));
      assertEquals(staged, ProjectIO.JSON.valueToTree(state.draft));
      var copy = NoiseAuthoring.copy(owner);
      assertThrows(
          IllegalArgumentException.class,
          () ->
              new NoiseEditSession(
                  state, copy, Request.simple(Action.DELETE, copy.processing.getFirst().uuid)));
    }
  }
}
