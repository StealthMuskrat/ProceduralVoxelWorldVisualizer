package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred semantic contracts for combined Properties/browser publication and rollback. */
class ResourceEditSessionTest {
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
              var folder = new Folder();
              folder.id = "folder";
              folder.rootKind = "noise2D";
              p.folders.add(folder);
              var layer = new Layer();
              layer.id = "layer";
              var op = new Operation();
              op.id = "operation";
              op.noiseGeneratorUuid = p.noise2D.get(1).uuid;
              layer.operations.add(op);
              p.worldGenerator.layers.add(layer);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private UUID id(EditorState state, int index) {
    return state.project().noise2D.get(index).uuid;
  }

  private void dirty(EditorState state) {
    ((Noise) state.draft).seedOffset = 17;
  }

  @Test
  void applyAndRenamePublishOneCandidateAndOneUndoRestoresBothChanges() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      var user = ProjectIO.JSON.valueToTree(state.user);
      dirty(state);
      long revision = state.revision;
      var edits = new ResourceEditSession(state);
      assertEquals(
          ResourceEditSession.Result.NEEDS_DECISION,
          edits.request("Rename", ResourceBrowserEdits.rename(state.selected, "renamed")));
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
      assertEquals(revision + 1, state.revision);
      assertEquals("renamed", state.project().noise2D.getFirst().id);
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
      assertEquals("renamed", ((Noise) state.draft).id);
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      var applied = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(applied, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void discardDropsPrivateRawTextOnlyAfterTheRequestedMutationSucceeds() {
    try (var state = state()) {
      var source = state.draft;
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var edits = new ResourceEditSession(state);
      edits.request("Rename", ResourceBrowserEdits.rename(id(state, 1), "other"));
      assertSame(source, state.draft);
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.DISCARD));
      assertEquals("other", state.project().noise2D.get(1).id);
      assertEquals(0, state.project().noise2D.getFirst().seedOffset);
      assertFalse(state.seedInputs.pending(state));
      assertNotSame(source, state.draft);
    }
  }

  @Test
  void actionFailureRollsBackAnOtherwiseValidApplyAndFailedDiscardRetainsTheDraft() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      dirty(state);
      var source = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      var edits = new ResourceEditSession(state);
      edits.request("Colliding rename", ResourceBrowserEdits.rename(id(state, 1), "a"));
      for (var decision :
          List.of(SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD)) {
        assertEquals(ResourceEditSession.Result.REJECTED, edits.decide(decision));
        assertTrue(edits.pending());
        assertSame(source, state.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(revision, state.revision);
        assertEquals(history, state.document.historyBytes());
      }
    }
  }

  @Test
  void invalidDraftCannotBeHiddenByApplyingADeleteButExplicitDiscardCanDeleteIt() {
    for (boolean raw : new boolean[] {false, true})
      try (var state = state()) {
        UUID source = state.selected;
        var before = ProjectIO.JSON.valueToTree(state.project());
        if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        else ((Noise) state.draft).id = " invalid ";
        var edits = new ResourceEditSession(state);
        edits.request("Delete", ResourceBrowserEdits.delete(source, null));
        assertEquals(
            ResourceEditSession.Result.REJECTED, edits.decide(SearchNavigation.Decision.APPLY));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(
            ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.DISCARD));
        assertNull(ResourceGraph.find(state.project(), source));
        assertNull(state.selected);
        assertNull(state.draft);
        assertFalse(state.seedInputs.pending(state));
      }
  }

  @Test
  void cancelAndRetargetAttemptsLeaveAllPrivateAndCommittedValuesUntouched() {
    try (var state = state()) {
      dirty(state);
      var source = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var edits = new ResourceEditSession(state);
      edits.request("Rename", ResourceBrowserEdits.rename(state.selected, "first"));
      assertEquals(
          ResourceEditSession.Result.UNAVAILABLE,
          edits.request("Delete", ResourceBrowserEdits.delete(state.selected, null)));
      assertEquals("Rename", edits.label());
      assertEquals(
          ResourceEditSession.Result.CANCELLED, edits.decide(SearchNavigation.Decision.CANCEL));
      assertSame(source, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void newerProjectDraftRawTextOrSelectionRejectsBothOldDecisionsWithoutExecuting() {
    for (var decision : List.of(SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD))
      for (int mutation = 0; mutation < 6; mutation++)
        try (var state = state()) {
          dirty(state);
          var edits = new ResourceEditSession(state);
          edits.request("Rename", ResourceBrowserEdits.rename(id(state, 1), "never"));
          switch (mutation) {
            case 0 -> state.change(p -> p.project.seed++);
            case 1 -> state.refreshDraft();
            case 2 -> ((Noise) state.draft).seedOffset++;
            case 3 -> state.seedInputs.edit(state, "seedOffset", "Seed", "-");
            case 4 -> state.multiSelection.add(id(state, 1));
            case 5 ->
                state.draft =
                    ProjectIO.JSON.convertValue(
                        ProjectIO.JSON.valueToTree(state.draft), Noise.class);
          }
          var before = ProjectIO.JSON.valueToTree(state.project());
          var source = state.draft;
          assertEquals(ResourceEditSession.Result.UNAVAILABLE, edits.decide(decision));
          assertSame(source, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertFalse(edits.pending());
        }
  }

  @Test
  void insertionOwnsItsDefinitionAndPublishesOnlyThePreparedIdentityAndValues() {
    try (var state = state()) {
      dirty(state);
      var noise = new Noise();
      noise.id = "prepared";
      noise.seedOffset = Long.MIN_VALUE;
      UUID made = noise.uuid;
      var edits = new ResourceEditSession(state);
      edits.request("Create", ResourceBrowserEdits.insert("noise2D", noise));
      noise.id = "mutated";
      noise.seedOffset = 999;
      noise.uuid = UUID.randomUUID();
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.DISCARD));
      var created = (Noise) EditorState.find(state.project(), made);
      assertEquals("prepared", created.id);
      assertEquals(Long.MIN_VALUE, created.seedOffset);
      assertEquals(made, state.selected);
      state.document.undo();
      assertNull(EditorState.find(state.project(), made));
    }
  }

  @Test
  void duplicateUsesTheAppliedCandidateAndOwnsSelection() {
    try (var state = state()) {
      UUID source = state.selected;
      var before = ProjectIO.JSON.valueToTree(state.project());
      dirty(state);
      var ids = new ArrayList<>(List.of(source));
      var edits = new ResourceEditSession(state);
      edits.request("Duplicate", ResourceBrowserEdits.duplicate(ids));
      ids.clear();
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
      assertNotEquals(source, state.selected);
      var clone = (Noise) EditorState.find(state.project(), state.selected);
      assertEquals(17, clone.seedOffset);
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
      assertNotEquals("a", clone.id);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void movingOneSiblingReconcilesSelectionWithoutLeavingMixedParentMembers() {
    try (var state = state()) {
      UUID a = id(state, 0), b = id(state, 1), folder = state.project().folders.getFirst().uuid;
      state.selectResources(List.of(a, b), b, "noise2D:null", false);
      var edits = new ResourceEditSession(state);
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          edits.request("Move", ResourceBrowserEdits.move(List.of(a), "noise2D", folder)));
      assertEquals(Set.of(b), state.multiSelection);
      assertEquals(b, state.selected);
      assertEquals(folder, state.project().noise2D.getFirst().parentFolderUuid);
    }
  }

  @Test
  void failedCutPasteRetainsLiveIdentityAndTheSamePayloadCanBeRequestedAgain() {
    try (var state = state()) {
      UUID folder = state.project().folders.getFirst().uuid;
      var cut = new ResourceClipboard(state.project(), List.of(folder), true);
      dirty(state);
      var source = state.draft;
      var edits = new ResourceEditSession(state);
      edits.request("Invalid move", ResourceBrowserEdits.paste(cut, "noise2D", folder));
      assertEquals(
          ResourceEditSession.Result.REJECTED, edits.decide(SearchNavigation.Decision.DISCARD));
      assertSame(source, state.draft);
      assertNull(state.project().folders.getFirst().parentFolderUuid);
      edits.decide(SearchNavigation.Decision.CANCEL);
      edits.request("Move", ResourceBrowserEdits.paste(cut, "noise2D", null));
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(folder, state.project().folders.getFirst().uuid);
    }
  }

  @Test
  void placementAndAppliedLayerDefaultsAreOneUndoableChangeWithLiveInheritance() {
    try (var state = state()) {
      var layer = state.project().worldGenerator.layers.getFirst();
      UUID noise = id(state, 0);
      state.select("layer", layer.uuid);
      ((Layer) state.draft).defaults.seedOffset = 43;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var edits = new ResourceEditSession(state);
      edits.request("Add Placement", ResourceBrowserEdits.placement(layer.uuid, noise));
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
      var applied = state.project().worldGenerator.layers.getFirst();
      assertEquals(2, applied.operations.size());
      assertEquals(43, applied.defaults.seedOffset);
      assertTrue(applied.operations.getLast().overrides.isEmpty());
      assertEquals(noise, applied.operations.getLast().noiseGeneratorUuid);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void replacementDeletionAndAppliedSourceAreOneTransactionAndKeepUnrelatedSelection() {
    try (var state = state()) {
      UUID a = id(state, 0), b = id(state, 1), c = id(state, 2);
      var before = ProjectIO.JSON.valueToTree(state.project());
      dirty(state);
      var edits = new ResourceEditSession(state);
      edits.request("Delete with replacement", ResourceBrowserEdits.delete(b, c));
      assertEquals(
          ResourceEditSession.Result.APPLIED, edits.decide(SearchNavigation.Decision.APPLY));
      assertNull(EditorState.find(state.project(), b));
      assertEquals(
          c,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .operations
              .getFirst()
              .noiseGeneratorUuid);
      assertEquals(a, state.selected);
      assertEquals(17, ((Noise) state.draft).seedOffset);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void thrownCandidateFailureNeverPublishesPartialWritesOrDropsPendingRawText() {
    try (var state = state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var source = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var edits = new ResourceEditSession(state);
      edits.request(
          "Fail",
          p -> {
            p.noise2D.clear();
            throw new IllegalArgumentException("fixture failure");
          });
      assertEquals(
          ResourceEditSession.Result.REJECTED, edits.decide(SearchNavigation.Decision.DISCARD));
      assertSame(source, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void duplicateMultipleSiblingsSelectsOnlyNewRootsAndNoOpReorderKeepsExactValues() {
    try (var state = state()) {
      var ids = List.of(id(state, 0), id(state, 2));
      var edits = new ResourceEditSession(state);
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          edits.request("Duplicate", ResourceBrowserEdits.duplicate(ids)));
      assertEquals(2, state.multiSelection.size());
      assertTrue(Collections.disjoint(ids, state.multiSelection));
      var before = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes();
      assertEquals(
          ResourceEditSession.Result.APPLIED,
          edits.request(
              "No-op", ResourceBrowserEdits.before(state.multiSelection, state.selected)));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
    }
  }
}
