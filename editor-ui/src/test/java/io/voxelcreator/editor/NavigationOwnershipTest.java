package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions for exact navigation ownership and direct diagnostic entry points. */
class NavigationOwnershipTest {
  @Test
  void equalValueDraftReplacementCannotAuthorizeAnOlderDiscard() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "private";
      var nav = new SearchNavigation(state);
      var destination = state.project().noise2D.getLast();
      nav.request(
          new EditorEvents.Target(
              state.project().project.uuid, destination.uuid, null, null, "", false));
      state.draft =
          ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(state.draft), Noise.class);
      var replacement = state.draft;
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, nav.decide(SearchNavigation.Decision.DISCARD));
      assertSame(replacement, state.draft);
    }
  }

  @Test
  void pendingNavigationCannotBeReplacedByAnotherRequest() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "private";
      var nav = new SearchNavigation(state);
      var destination = state.project().noise2D.getLast();
      var target =
          new EditorEvents.Target(
              state.project().project.uuid, destination.uuid, null, null, "", false);
      nav.request(target);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          nav.request(new EditorEvents.Target(UUID.randomUUID(), null, null, null, "", false)));
      assertSame(target, nav.target());
      assertEquals(SearchNavigation.Result.CANCELLED, nav.decide(SearchNavigation.Decision.CANCEL));
    }
  }

  @Test
  void directDiagnosticNavigationQueuesADecisionAndKeepsRawPropertiesInput() {
    try (var state = SearchNavigationTest.state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      var destination = state.project().noise2D.getLast();
      var panel = new PropertyPanel(state);
      assertTrue(
          panel.goToProblem(
              new EditorEvents.Target(
                  state.project().project.uuid,
                  destination.uuid,
                  destination.processing.getFirst().uuid,
                  null,
                  "",
                  false)));
      assertTrue(panel.searchBlocked());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }
}
