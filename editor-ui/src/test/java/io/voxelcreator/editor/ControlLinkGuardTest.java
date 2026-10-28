package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import org.junit.jupiter.api.Test;

/** Deferred definitions for public control links sharing the reference navigation guard. */
class ControlLinkGuardTest {
  @Test
  void crossOwnerControlLinkKeepsCanonicalAndRawPrivateInputUntilDecision() {
    try (var state = SearchNavigationTest.state()) {
      var draft = (Noise) state.draft;
      draft.id = "pending";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      var panel = new PropertyPanel(state);
      var destination = state.project().noise2D.getLast();
      panel.goToControl(destination.uuid, destination.processing.getFirst().uuid);
      assertTrue(panel.searchBlocked());
      assertSame(draft, state.draft);
      assertEquals(draft.uuid, state.selected);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void sameOwnerControlLinkAndUnavailableControlNeverReplaceTheDraft() {
    try (var state = SearchNavigationTest.state()) {
      var draft = (Noise) state.draft;
      draft.id = "pending";
      var panel = new PropertyPanel(state);
      panel.goToControl(draft.uuid, draft.processing.getFirst().uuid);
      assertSame(draft, state.draft);
      assertFalse(panel.searchBlocked());
      panel.goToControl(state.project().noise2D.getLast().uuid, java.util.UUID.randomUUID());
      assertSame(draft, state.draft);
      assertFalse(panel.searchBlocked());
    }
  }
}
