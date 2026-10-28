package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred contracts: browser gestures must not silently replace an owned property draft. */
class ResourceSelectionSessionTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (String id : List.of("a", "b", "c")) {
                var noise = new Noise();
                noise.id = id;
                p.noise2D.add(noise);
              }
              var voxel = new VoxelType();
              voxel.id = "stone";
              p.voxelTypes.add(voxel);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private UUID id(EditorState state, int index) {
    return state.project().noise2D.get(index).uuid;
  }

  @Test
  void sameOwnerClickKeepsEvenInvalidPrivateTextAndExactDraftIdentity() {
    try (var state = state()) {
      var draft = state.draft;
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var selection = new ResourceSelectionSession(state);
      assertEquals(
          ResourceSelectionSession.Result.SELECTED, selection.click(state.selected, false));
      assertFalse(selection.pending());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void dirtySingleSelectionWaitsAndCancelPreservesDocumentDraftAndSelection() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = (Noise) state.draft;
      draft.id = "pending";
      var selection = new ResourceSelectionSession(state);
      long revision = state.revision, history = state.document.historyBytes();
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION, selection.click(id(state, 1), false));
      assertEquals(Set.of(draft.uuid), state.multiSelection);
      assertSame(draft, state.draft);
      assertEquals(
          ResourceSelectionSession.Result.CANCELLED,
          selection.decide(SearchNavigation.Decision.CANCEL));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void applyIsOneParentTransactionAndSelectionIsNotPartOfUndoHistory() {
    try (var state = state()) {
      ((Noise) state.draft).seedOffset = Long.MIN_VALUE;
      var selection = new ResourceSelectionSession(state);
      UUID destination = id(state, 1);
      selection.click(destination, false);
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertEquals(destination, state.selected);
      assertEquals(Long.MIN_VALUE, state.project().noise2D.getFirst().seedOffset);
      state.document.undo();
      assertEquals(0, state.project().noise2D.getFirst().seedOffset);
      assertEquals(destination, state.selected);
      state.document.redo();
      assertEquals(Long.MIN_VALUE, state.project().noise2D.getFirst().seedOffset);
    }
  }

  @Test
  void discardNavigatesWithoutPublishingAndClearsOldSeedOwnership() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      state.seedInputs.edit(state, "seedOffset", "Seed", "+");
      var selection = new ResourceSelectionSession(state);
      selection.click(id(state, 1), false);
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void invalidSeedApplyRemainsPendingAndCannotSelectOrPublishFallback() {
    try (var state = state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      var selection = new ResourceSelectionSession(state);
      selection.click(id(state, 1), false);
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertTrue(selection.pending());
      assertSame(draft, state.draft);
      assertEquals(id(state, 0), state.selected);
      assertEquals("INVALID_SEED_TEXT", state.issues.getFirst().code());
      assertEquals(0, state.project().noise2D.getFirst().seedOffset);
    }
  }

  @Test
  void invalidModelApplyRemainsPendingForCancelOrExplicitDiscard() {
    try (var state = state()) {
      ((Noise) state.draft).id = " invalid ";
      var selection = new ResourceSelectionSession(state);
      selection.click(id(state, 1), false);
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.DISCARD));
      assertEquals("a", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void ctrlUsesOnlyCurrentSiblingsAndRemovingLastActiveSelectsRemainingOrNone() {
    try (var state = state()) {
      var selection = new ResourceSelectionSession(state);
      assertEquals(ResourceSelectionSession.Result.SELECTED, selection.click(id(state, 1), true));
      assertEquals(List.of(id(state, 0), id(state, 1)), List.copyOf(state.multiSelection));
      assertEquals(id(state, 1), state.selected);
      selection.click(id(state, 1), true);
      assertEquals(id(state, 0), state.selected);
      selection.click(id(state, 0), true);
      assertNull(state.selected);
      assertNull(state.draft);
      selection.click(id(state, 0), false);
      UUID voxel = state.project().voxelTypes.getFirst().uuid;
      selection.click(voxel, true);
      assertEquals(Set.of(voxel), state.multiSelection);
      assertEquals("voxel", state.selectedKind);
    }
  }

  @Test
  void hidingDraftInMultiSelectionRequiresExplicitDiscardEvenWithSameActiveOwner() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var selection = new ResourceSelectionSession(state);
      var ids = new ArrayList<>(List.of(id(state, 0), id(state, 1)));
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION, selection.request(ids, id(state, 0)));
      ids.clear();
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(2, state.multiSelection.size());
      assertEquals("a", ((Noise) state.draft).id);
    }
  }

  @Test
  void deselectingTheLastDirtyResourceRequiresADecisionAndCancelKeepsIt() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var selection = new ResourceSelectionSession(state);
      assertEquals(
          ResourceSelectionSession.Result.NEEDS_DECISION, selection.click(state.selected, true));
      selection.decide(SearchNavigation.Decision.CANCEL);
      assertEquals(Set.of(id(state, 0)), state.multiSelection);
      assertEquals("pending", ((Noise) state.draft).id);
    }
  }

  @Test
  void mixedScopesDuplicateMissingAndInvalidActiveRequestsNeverChangeSelection() {
    try (var state = state()) {
      var selection = new ResourceSelectionSession(state);
      var draft = state.draft;
      UUID a = id(state, 0), voxel = state.project().voxelTypes.getFirst().uuid;
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE, selection.request(List.of(a, voxel), a));
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE, selection.request(List.of(a, a), a));
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE,
          selection.request(List.of(UUID.randomUUID()), a));
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE, selection.request(List.of(a), null));
      assertSame(draft, state.draft);
      assertEquals(Set.of(a), state.multiSelection);
    }
  }

  @Test
  void pendingDecisionCannotBeRetargetedByAnotherGesture() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var selection = new ResourceSelectionSession(state);
      selection.click(id(state, 1), false);
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE, selection.click(id(state, 2), false));
      assertEquals(
          ResourceSelectionSession.Result.SELECTED,
          selection.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(id(state, 1), state.selected);
    }
  }

  @Test
  void newerDraftRawTextDocumentOrSelectionInvalidatesBothApplyAndDiscard() {
    for (var decision : List.of(SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD))
      for (int mutation = 0; mutation < 7; mutation++)
        try (var state = state()) {
          ((Noise) state.draft).id = "pending";
          var selection = new ResourceSelectionSession(state);
          selection.click(id(state, 1), false);
          switch (mutation) {
            case 0 -> ((Noise) state.draft).id = "newer";
            case 1 -> state.seedInputs.edit(state, "seedOffset", "Seed", "-");
            case 2 -> state.change(p -> p.project.seed++);
            case 3 -> state.refreshDraft();
            case 4 -> state.multiSelection.add(id(state, 2));
            case 5 -> state.change(p -> p.noise2D.remove(1));
            case 6 -> {
              var copied =
                  ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(state.draft), Noise.class);
              state.draft = copied;
            }
          }
          var before = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          assertEquals(ResourceSelectionSession.Result.UNAVAILABLE, selection.decide(decision));
          assertFalse(selection.pending());
          assertSame(draft, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        }
  }

  @Test
  void independentParentApplyCanRemoveDestinationAndNeverInstallsTheOldSelection() {
    try (var state = state()) {
      UUID noise = id(state, 0);
      assertTrue(
          state.change(
              p -> {
                var layer = new Layer();
                layer.id = "layer";
                var operation = new Operation();
                operation.id = "operation";
                operation.noiseGeneratorUuid = noise;
                layer.operations.add(operation);
                p.worldGenerator.layers.add(layer);
              }));
      var layer = state.project().worldGenerator.layers.getFirst();
      UUID destination = layer.operations.getFirst().uuid;
      state.select("layer", layer.uuid);
      ((Layer) state.draft).operations.clear();
      var selection = new ResourceSelectionSession(state);
      selection.click(destination, false);
      assertEquals(
          ResourceSelectionSession.Result.UNAVAILABLE,
          selection.decide(SearchNavigation.Decision.APPLY));
      assertEquals(layer.uuid, state.selected);
      assertTrue(state.project().worldGenerator.layers.getFirst().operations.isEmpty());
      assertFalse(selection.pending());
      state.document.undo();
      assertEquals(
          destination, state.project().worldGenerator.layers.getFirst().operations.getFirst().uuid);
    }
  }
}
