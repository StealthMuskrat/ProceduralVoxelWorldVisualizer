package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NavigationEligibilityTest {
  private static EditorEvents.Target target(EditorState state) {
    return new EditorEvents.Target(
        state.project().project.uuid,
        state.project().noise2D.getLast().uuid,
        null,
        null,
        "",
        false);
  }

  @Test
  void aBlockedApplyOrDiscardRetiresOnlyTheNavigationAndKeepsPrivateInput() {
    for (var decision :
        new SearchNavigation.Decision[] {
          SearchNavigation.Decision.APPLY, SearchNavigation.Decision.DISCARD
        }) {
      try (var state = SearchNavigationTest.state()) {
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var draft = state.draft;
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var navigation = new SearchNavigation(state);
        navigation.request(target(state));
        var calls = new AtomicInteger();
        assertEquals(
            SearchNavigation.Result.UNAVAILABLE,
            navigation.decide(
                decision,
                () -> {
                  calls.incrementAndGet();
                  return false;
                },
                () -> {
                  fail("Blocked decision reached Apply completion");
                  return true;
                }));
        assertFalse(navigation.pending());
        assertSame(draft, state.draft);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        assertEquals("private", ((Noise) state.draft).id);
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(revision, state.revision);
        assertEquals(history, state.document.historyBytes());
        assertEquals(
            SearchNavigation.Result.UNAVAILABLE,
            navigation.decide(
                decision,
                () -> {
                  fail("Consumed decision rechecked eligibility");
                  return true;
                },
                () -> true));
        assertEquals(1, calls.get());
      }
    }
  }

  @Test
  void cancelDoesNotConsultEitherEligibilityCallback() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "private";
      var navigation = new SearchNavigation(state);
      navigation.request(target(state));
      assertEquals(
          SearchNavigation.Result.CANCELLED,
          navigation.decide(
              SearchNavigation.Decision.CANCEL,
              () -> {
                throw new IllegalStateException("before");
              },
              () -> {
                throw new IllegalStateException("after");
              }));
      assertFalse(navigation.pending());
      assertEquals("private", ((Noise) state.draft).id);
    }
  }

  @Test
  void blockingAfterApplyKeepsExactlyOneCommittedEditAndOneUndo() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "applied";
      var selected = state.selected;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      var navigation = new SearchNavigation(state);
      navigation.request(target(state));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          navigation.decide(SearchNavigation.Decision.APPLY, () -> true, () -> false));
      assertFalse(navigation.pending());
      assertEquals(selected, state.selected);
      assertEquals("applied", state.project().noise2D.getFirst().id);
      assertEquals("applied", ((Noise) state.draft).id);
      assertEquals(revision + 1, state.revision);
      navigation.decide(SearchNavigation.Decision.APPLY);
      assertEquals(revision + 1, state.revision);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void aSuccessorOpenedByTheBeforeGateKeepsItsDecision() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "private";
      var navigation = new SearchNavigation(state);
      var destination = target(state);
      navigation.request(destination);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          navigation.decide(
              SearchNavigation.Decision.APPLY,
              () -> {
                assertEquals(
                    SearchNavigation.Result.CANCELLED,
                    navigation.decide(SearchNavigation.Decision.CANCEL));
                assertEquals(
                    SearchNavigation.Result.NEEDS_DECISION, navigation.request(destination));
                return true;
              },
              () -> true));
      assertTrue(navigation.pending());
      assertEquals("editing", state.project().noise2D.getFirst().id);
      assertEquals("private", ((Noise) state.draft).id);
      assertEquals(
          SearchNavigation.Result.CANCELLED, navigation.decide(SearchNavigation.Decision.CANCEL));
    }
  }

  @Test
  void aSuccessorOpenedByTheAfterGateKeepsItsNewPrivateDraft() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "applied";
      var navigation = new SearchNavigation(state);
      var destination = target(state);
      navigation.request(destination);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          navigation.decide(
              SearchNavigation.Decision.APPLY,
              () -> true,
              () -> {
                ((Noise) state.draft).id = "successor";
                assertEquals(
                    SearchNavigation.Result.NEEDS_DECISION, navigation.request(destination));
                return true;
              }));
      assertTrue(navigation.pending());
      assertEquals("applied", state.project().noise2D.getFirst().id);
      assertEquals("successor", ((Noise) state.draft).id);
      navigation.decide(SearchNavigation.Decision.CANCEL);
      assertEquals("successor", ((Noise) state.draft).id);
    }
  }

  @Test
  void changesToThePrivateDraftDuringTheBeforeGateCannotBeAppliedOrDiscarded() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "opening";
      var navigation = new SearchNavigation(state);
      navigation.request(target(state));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          navigation.decide(
              SearchNavigation.Decision.DISCARD,
              () -> {
                ((Noise) state.draft).id = "newer";
                return true;
              },
              () -> true));
      assertFalse(navigation.pending());
      assertEquals("newer", ((Noise) state.draft).id);
      assertEquals("editing", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void rejectedApplyKeepsThePendingRequestWithoutRunningTheAfterGate() {
    try (var state = SearchNavigationTest.state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var navigation = new SearchNavigation(state);
      navigation.request(target(state));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          navigation.decide(
              SearchNavigation.Decision.APPLY,
              () -> true,
              () -> {
                fail("Rejected Apply ran completion");
                return true;
              }));
      assertTrue(navigation.pending());
      assertEquals("-", state.seedInputs.text("seedOffset"));
      navigation.decide(SearchNavigation.Decision.CANCEL);
    }
  }

  @Test
  void aReferenceGuardDoesNotTreatItsOwnPendingRequestAsAPrivateEditor() {
    try (var state = SearchNavigationTest.state()) {
      ((Noise) state.draft).id = "applied";
      long revision = state.revision;
      var properties = new PropertyPanel(state);
      var destination = target(state);
      properties.probeOpenReference(destination.resource());
      assertTrue(properties.searchBlocked());
      assertEquals(
          SearchNavigation.Result.NAVIGATED,
          properties.decideReferenceNavigation(SearchNavigation.Decision.APPLY));
      assertFalse(properties.searchBlocked());
      assertEquals(destination.resource(), state.selected);
      assertEquals("applied", state.project().noise2D.getFirst().id);
      assertEquals(revision + 1, state.revision);
    }
  }

  @Test
  void aReferenceGuardRetainsRejectedRawInputUntilExplicitCancel() {
    try (var state = SearchNavigationTest.state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      var properties = new PropertyPanel(state);
      properties.probeOpenReference(target(state).resource());
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          properties.decideReferenceNavigation(SearchNavigation.Decision.APPLY));
      assertTrue(properties.searchBlocked());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(
          SearchNavigation.Result.CANCELLED,
          properties.decideReferenceNavigation(SearchNavigation.Decision.CANCEL));
      assertFalse(properties.searchBlocked());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }
}
