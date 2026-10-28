package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SearchNavigationTest {
  @Test
  void newerCommittedSourceChangesInvalidatePendingApplyWithoutOverwritingThem() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var nav = new SearchNavigation(state);
      nav.request(target(state, state.project().noise2D.getLast().uuid, null, null));
      assertTrue(state.change(p -> p.noise2D.getFirst().id = "newer-commit"));
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, nav.decide(SearchNavigation.Decision.APPLY));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals("pending", ((Noise) state.draft).id);
    }
  }

  static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var a = new Noise();
              a.id = "editing";
              var expression = ProcessingRegistry.create("EXPRESSION", "formula");
              expression.parameters.put("expression", ProjectIO.JSON.valueToTree("x"));
              a.processing.add(expression);
              a.outputBinding = Binding.earlier(expression.uuid);
              p.noise2D.add(a);
              var b = new Noise();
              b.id = "destination";
              b.algorithm.key = "CONSTANT";
              b.algorithm.parameters.clear();
              b.algorithm.parameters.put("value", .625);
              var curve = ProcessingRegistry.create("CURVE", "response");
              curve.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
              b.processing.add(curve);
              p.noise2D.add(b);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private static EditorEvents.Target target(
      EditorState state, UUID owner, UUID control, String parameter) {
    return new EditorEvents.Target(
        state.project().project.uuid, owner, control, parameter, "", false);
  }

  @Test
  void sameOwnerNavigationPreservesRejectedDraftObjectAndHistory() {
    try (var state = state()) {
      var draft = (Noise) state.draft;
      draft.processing.getFirst().parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      long revision = state.revision, history = state.document.historyBytes();
      var nav = new SearchNavigation(state);
      assertEquals(
          SearchNavigation.Result.NAVIGATED,
          nav.request(
              target(
                  state, draft.uuid, draft.processing.getFirst().uuid, "parameters/expression")));
      assertSame(draft, state.draft);
      assertEquals("sin(", draft.processing.getFirst().parameters.get("expression").asText());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void leavingEditedResourceRequiresDecisionAndCancelKeepsEveryDraftValue() {
    try (var state = state()) {
      var draft = (Noise) state.draft;
      draft.id = "pending";
      var before = ProjectIO.JSON.valueToTree(state.project());
      var nav = new SearchNavigation(state);
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          nav.request(target(state, state.project().noise2D.getLast().uuid, null, null)));
      assertSame(draft, state.draft);
      assertEquals(SearchNavigation.Result.CANCELLED, nav.decide(SearchNavigation.Decision.CANCEL));
      assertEquals(draft.uuid, state.selected);
      assertEquals("pending", draft.id);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void explicitDiscardNavigatesWithoutCreatingDocumentChanges() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var destination = state.project().noise2D.getLast().uuid;
      var nav = new SearchNavigation(state);
      nav.request(target(state, destination, null, null));
      assertEquals(
          SearchNavigation.Result.NAVIGATED, nav.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(destination, state.selected);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void explicitApplyCommitsOneEditThenNavigatesAndOneUndoRestoresSource() {
    try (var state = state()) {
      ((Noise) state.draft).id = "accepted";
      var destination = state.project().noise2D.getLast().uuid;
      var nav = new SearchNavigation(state);
      nav.request(target(state, destination, null, null));
      assertEquals(SearchNavigation.Result.NAVIGATED, nav.decide(SearchNavigation.Decision.APPLY));
      assertEquals(destination, state.selected);
      assertEquals("accepted", state.project().noise2D.getFirst().id);
      state.document.undo();
      assertEquals("editing", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void failedApplyKeepsDecisionOpenAndCannotPublishInvalidExpression() {
    try (var state = state()) {
      var draft = (Noise) state.draft;
      draft.processing.getFirst().parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      var before = ProjectIO.JSON.valueToTree(state.project());
      var nav = new SearchNavigation(state);
      nav.request(target(state, state.project().noise2D.getLast().uuid, null, null));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, nav.decide(SearchNavigation.Decision.APPLY));
      assertTrue(nav.pending());
      assertSame(draft, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(SearchNavigation.Result.CANCELLED, nav.decide(SearchNavigation.Decision.CANCEL));
    }
  }

  @Test
  void staleChangedDraftDeletedTargetAndForeignProjectCannotDiscardNewerWork() {
    try (var state = state()) {
      ((Noise) state.draft).id = "first";
      var destination = state.project().noise2D.getLast().uuid;
      var nav = new SearchNavigation(state);
      nav.request(target(state, destination, null, null));
      ((Noise) state.draft).id = "newer";
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, nav.decide(SearchNavigation.Decision.DISCARD));
      assertEquals("newer", ((Noise) state.draft).id);
      nav.request(target(state, destination, null, null));
      assertTrue(state.change(p -> p.noise2D.removeIf(n -> n.uuid.equals(destination))));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, nav.decide(SearchNavigation.Decision.DISCARD));
      var foreign =
          new EditorEvents.Target(UUID.randomUUID(), state.selected, null, null, "", false);
      assertEquals(SearchNavigation.Result.UNAVAILABLE, nav.request(foreign));
    }
  }

  @Test
  void controlsDeletedOnlyInTheCurrentDraftAreUnavailableAndDoNotRefreshIt() {
    try (var state = state()) {
      var draft = (Noise) state.draft;
      var control = draft.processing.getFirst().uuid;
      draft.processing.clear();
      var nav = new SearchNavigation(state);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          nav.request(target(state, draft.uuid, control, null)));
      assertSame(draft, state.draft);
      assertTrue(draft.processing.isEmpty());
    }
  }

  @Test
  void curvePointNavigationSelectsExactPointInPrivateEditorWithoutChangingProject() {
    try (var state = state()) {
      state.select("noise2D", state.project().noise2D.getLast().uuid);
      var noise = (Noise) state.draft;
      var curve =
          ProjectIO.JSON.convertValue(
              noise.processing.getFirst().parameters.get("curve"), Curve.class);
      var point = curve.points.getLast().uuid;
      var before = ProjectIO.JSON.valueToTree(state.project());
      var properties = new PropertyPanel(state);
      assertTrue(properties.revealSearch(target(state, noise.uuid, point, "curve")));
      assertEquals(Set.of(point), properties.searchCurveSelection());
      assertTrue(properties.searchBlocked());
      properties.closeSearchCurve();
      assertFalse(properties.searchBlocked());
      assertSame(noise, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void openPrivateCurveBlocksDiagnosticAndControlLinksWithoutReplacingItsDraft() {
    try (var state = state()) {
      state.select("noise2D", state.project().noise2D.getLast().uuid);
      var noise = (Noise) state.draft;
      var item = noise.processing.getFirst();
      var properties = new PropertyPanel(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertTrue(properties.goToProblem(target(state, noise.uuid, item.uuid, "curve")));
      var dialog = properties.probeCurveDialog();
      assertNotNull(dialog);
      assertFalse(properties.goToProblem(target(state, noise.uuid, item.uuid, "curve/max")));
      var other = state.project().noise2D.getFirst();
      properties.goToControl(other.uuid, other.processing.getFirst().uuid);
      assertSame(noise, state.draft);
      assertEquals(noise.uuid, state.selected);
      assertSame(dialog, properties.probeCurveDialog());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      properties.closeSearchCurve();
      assertTrue(properties.goToProblem(target(state, noise.uuid, item.uuid, null)));
      assertNull(properties.probeCurveDialog());
    }
  }
}
