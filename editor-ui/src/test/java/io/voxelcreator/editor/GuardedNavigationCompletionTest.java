package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Layer;
import io.voxelcreator.storage.BlockPos;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class GuardedNavigationCompletionTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(TraceWorkflowProbe::fixture));
    state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
    return state;
  }

  private static TraceSnapshot capture(EditorState state) {
    var source = ProjectIO.copy(state.project());
    try (var world = state.engine.openWorld(new CompiledProject(source))) {
      return new TraceSnapshot(
          state.document.sessionToken(),
          source,
          state.revision,
          world.trace(new BlockPos(2, 0, 3)));
    }
  }

  private static EditorEvents.Target child(TraceSnapshot trace) {
    return trace.rows(1).stream()
        .filter(row -> row.label().equals("Compare threshold"))
        .findFirst()
        .orElseThrow()
        .target();
  }

  private static EditorEvents.Target noise(EditorState state) {
    return new EditorEvents.Target(
        state.project().project.uuid,
        state.project().noise2D.getFirst().uuid,
        null,
        null,
        "",
        false);
  }

  @Test
  void applyingAParentThatChangesTheDestinationCommitsOnceWithoutFollowingTheOldTraceLink() {
    try (var state = state()) {
      var trace = capture(state);
      var target = child(trace);
      var parent = state.selected;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      ((Layer) state.draft)
          .operations
          .getLast()
          .overrides
          .put("threshold", ProjectIO.JSON.valueToTree(20));
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          guard.request(target, () -> trace.available(state, target)));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertFalse(guard.pending());
      assertEquals(parent, state.selected);
      assertEquals(revision + 1, state.revision);
      assertEquals(
          20,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .operations
              .getLast()
              .overrides
              .get("threshold")
              .intValue());
      assertFalse(trace.available(state, target));
      assertTrue(state.status.startsWith("Draft applied."));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertEquals(revision + 1, state.revision);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void anUnchangedDestinationStillNavigatesAfterOneAppliedEdit() {
    try (var state = state()) {
      var trace = capture(state);
      var target = noise(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      ((Layer) state.draft).id = "applied-layer";
      var reveals = new AtomicInteger();
      var guard =
          new GuardedNavigation(
              state,
              new PropertyPanel(state),
              value -> {
                reveals.incrementAndGet();
                return false;
              });
      guard.request(target, () -> trace.available(state, target));
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.APPLY));
      assertEquals(target.resource(), state.selected);
      assertEquals(1, reveals.get());
      assertEquals(revision + 1, state.revision);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void aPostApplyCheckFailurePreservesTheCommitAndCannotReplayIt() {
    try (var state = state()) {
      ((Layer) state.draft).id = "applied-layer";
      var parent = state.selected;
      long revision = state.revision;
      var calls = new AtomicInteger();
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      guard.request(
          noise(state),
          () -> {
            if (calls.incrementAndGet() == 3)
              throw new IllegalStateException("post apply boundary");
            return true;
          });
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertFalse(guard.pending());
      assertEquals(parent, state.selected);
      assertEquals(revision + 1, state.revision);
      assertEquals("applied-layer", state.project().worldGenerator.layers.getFirst().id);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertEquals(3, calls.get());
      assertEquals(revision + 1, state.revision);
      var errors = state.events.view().entries();
      assertEquals(1, errors.size());
      assertEquals("SOURCE_NAVIGATION_FAILED", errors.getFirst().code());
      assertTrue(errors.getFirst().message().startsWith("Draft applied;"));
    }
  }

  @Test
  void revealFailureCannotReplayAnAppliedEditAndAnotherRequestCanRetryTheReveal() {
    try (var state = state()) {
      ((Layer) state.draft).id = "applied-layer";
      var fail = new AtomicBoolean(true);
      var calls = new AtomicInteger();
      long revision = state.revision;
      var target = noise(state);
      var guard =
          new GuardedNavigation(
              state,
              new PropertyPanel(state),
              value -> {
                calls.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("reveal boundary");
                return false;
              });
      guard.request(target);
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.APPLY));
      assertFalse(guard.pending());
      assertEquals(target.resource(), state.selected);
      assertEquals(revision + 1, state.revision);
      assertEquals("SOURCE_REVEAL_FAILED", state.events.view().entries().getFirst().code());
      fail.set(false);
      assertEquals(SearchNavigation.Result.NAVIGATED, guard.request(target));
      assertEquals(2, calls.get());
      assertEquals(revision + 1, state.revision);
    }
  }

  @Test
  void aPreDecisionCheckFailureKeepsThePrivateDraftAndDoesNotCommit() {
    try (var state = state()) {
      ((Layer) state.draft).id = "private-layer";
      var draft = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      var calls = new AtomicInteger();
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      guard.request(
          noise(state),
          () -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("pre apply boundary");
            return true;
          });
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertFalse(guard.pending());
      assertSame(draft, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      guard.decide(SearchNavigation.Decision.APPLY);
      assertEquals(2, calls.get());
      assertEquals(1, state.events.view().entries().size());
    }
  }

  @Test
  void aSuccessorOpenedByThePostApplyCheckKeepsItsNewPrivateDraftAndDecision() {
    try (var state = state()) {
      ((Layer) state.draft).id = "applied-layer";
      var parent = state.selected;
      var destination = noise(state);
      var calls = new AtomicInteger();
      var holder = new GuardedNavigation[1];
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      holder[0] = guard;
      guard.request(
          destination,
          () -> {
            if (calls.incrementAndGet() == 3) {
              ((Layer) state.draft).id = "successor-private";
              assertEquals(SearchNavigation.Result.NEEDS_DECISION, holder[0].request(destination));
            }
            return true;
          });
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertTrue(guard.pending());
      assertEquals(parent, state.selected);
      assertEquals("applied-layer", state.project().worldGenerator.layers.getFirst().id);
      assertEquals("successor-private", ((Layer) state.draft).id);
      assertEquals(
          SearchNavigation.Result.CANCELLED, guard.decide(SearchNavigation.Decision.CANCEL));
      assertEquals("successor-private", ((Layer) state.draft).id);
      assertEquals(3, calls.get());
    }
  }

  @Test
  void aRequestCheckFailureKeepsThePrivateDraftWithoutOpeningADecision() {
    try (var state = state()) {
      ((Layer) state.draft).id = "private-layer";
      var draft = state.draft;
      long revision = state.revision;
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          guard.request(
              noise(state),
              () -> {
                throw new IllegalStateException("request boundary");
              }));
      assertFalse(guard.pending());
      assertSame(draft, state.draft);
      assertEquals("private-layer", ((Layer) state.draft).id);
      assertEquals("layer", state.project().worldGenerator.layers.getFirst().id);
      assertEquals(revision, state.revision);
      assertEquals("SOURCE_NAVIGATION_FAILED", state.events.view().entries().getFirst().code());
    }
  }

  @Test
  void aRequestCheckCanOpenASuccessorBeforeFailingWithoutClearingIt() {
    try (var state = state()) {
      ((Layer) state.draft).id = "private-layer";
      var destination = noise(state);
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE,
          guard.request(
              destination,
              () -> {
                assertEquals(SearchNavigation.Result.NEEDS_DECISION, guard.request(destination));
                throw new IllegalStateException("old request boundary");
              }));
      assertTrue(guard.pending());
      assertEquals(
          SearchNavigation.Result.CANCELLED, guard.decide(SearchNavigation.Decision.CANCEL));
      assertEquals("private-layer", ((Layer) state.draft).id);
      assertEquals("layer", state.project().worldGenerator.layers.getFirst().id);
    }
  }

  @Test
  void aPostApplyCheckCannotNavigateOverChangesToTheRefreshedPrivateDraft() {
    try (var state = state()) {
      ((Layer) state.draft).id = "applied-layer";
      var parent = state.selected;
      long revision = state.revision;
      var calls = new AtomicInteger();
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      guard.request(
          noise(state),
          () -> {
            if (calls.incrementAndGet() == 3) ((Layer) state.draft).id = "new-private-layer";
            return true;
          });
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.APPLY));
      assertFalse(guard.pending());
      assertEquals(parent, state.selected);
      assertEquals("new-private-layer", ((Layer) state.draft).id);
      assertEquals("applied-layer", state.project().worldGenerator.layers.getFirst().id);
      assertEquals(revision + 1, state.revision);
    }
  }
}
