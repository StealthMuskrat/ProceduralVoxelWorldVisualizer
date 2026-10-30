package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class GuardedNavigationOwnershipTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (String id : new String[] {"first", "second", "third"}) {
                var n = new Noise();
                n.id = id;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .125);
                p.noise2D.add(n);
              }
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    return state;
  }

  private EditorEvents.Target target(EditorState state, int index) {
    return new EditorEvents.Target(
        state.project().project.uuid,
        state.project().noise2D.get(index).uuid,
        null,
        null,
        "",
        false);
  }

  @Test
  void secondRequestCannotReplaceThePendingFreshnessSupplier() {
    try (var state = state()) {
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var fresh = new AtomicBoolean(true);
      var draft = state.draft;
      var project = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, guard.request(target(state, 1), fresh::get));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.request(target(state, 2), () -> true));
      fresh.set(false);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.DISCARD));
      assertFalse(guard.pending());
      assertSame(draft, state.draft);
      assertEquals("private", ((Noise) draft).id);
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void rejectedRequestDoesNotEvaluateItsSupplierOrRetargetTheOriginalDecision() {
    try (var state = state()) {
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var original = target(state, 1);
      var calls = new AtomicInteger();
      assertEquals(SearchNavigation.Result.NEEDS_DECISION, guard.request(original));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          guard.request(
              target(state, 2),
              () -> {
                calls.incrementAndGet();
                return false;
              }));
      assertEquals(0, calls.get());
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(original.resource(), state.selected);
      assertEquals("first", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void cancelDoesNotConsultAChangedSourceAndKeepsRawSeedInput() {
    try (var state = state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var calls = new AtomicInteger();
      var draft = state.draft;
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          guard.request(target(state, 1), () -> calls.incrementAndGet() == 1));
      assertEquals(SearchNavigation.Result.UNAVAILABLE, guard.request(target(state, 2)));
      assertEquals(
          SearchNavigation.Result.CANCELLED, guard.decide(SearchNavigation.Decision.CANCEL));
      assertEquals(1, calls.get());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void failedApplyKeepsOriginalFreshnessWhenTheSourceLaterChanges() {
    try (var state = state()) {
      ((Noise) state.draft).id = " invalid ";
      var fresh = new AtomicBoolean(true);
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var draft = state.draft;
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, guard.request(target(state, 1), fresh::get));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, guard.decide(SearchNavigation.Decision.APPLY));
      assertTrue(guard.pending());
      assertEquals(SearchNavigation.Result.UNAVAILABLE, guard.request(target(state, 2)));
      fresh.set(false);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertSame(draft, state.draft);
      assertEquals(" invalid ", ((Noise) draft).id);
    }
  }

  @Test
  void newRequestAfterCancelOwnsItsOwnFreshnessAndDestination() {
    try (var state = state()) {
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var firstFresh = new AtomicBoolean(true);
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, guard.request(target(state, 1), firstFresh::get));
      assertEquals(
          SearchNavigation.Result.CANCELLED, guard.decide(SearchNavigation.Decision.CANCEL));
      firstFresh.set(false);
      var destination = target(state, 2);
      assertEquals(SearchNavigation.Result.NEEDS_DECISION, guard.request(destination, () -> true));
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(destination.resource(), state.selected);
    }
  }
}
