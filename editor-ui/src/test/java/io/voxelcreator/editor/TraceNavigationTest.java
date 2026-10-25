package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TraceNavigationTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(TraceWorkflowProbe::fixture));
    return state;
  }

  private TraceSnapshot snapshot(EditorState state) {
    try (var world = state.engine.openWorld(new CompiledProject(state.project()))) {
      return new TraceSnapshot(
          ProjectIO.copy(state.project()), state.revision, world.trace(new BlockPos(2, 0, 3)));
    }
  }

  private EditorEvents.Target threshold(TraceSnapshot trace, int step) {
    return trace.rows(step).stream()
        .filter(r -> r.label().equals("Compare threshold"))
        .findFirst()
        .orElseThrow()
        .target();
  }

  @Test
  void finalVoxelLinkAndLastDecidingStageRetainTheOriginalInspectorBehavior() {
    try (var state = state()) {
      var trace = snapshot(state);
      assertEquals(state.project().voxelTypes.getFirst().uuid, trace.finalTypeTarget().resource());
      assertTrue(trace.available(state, trace.finalTypeTarget()));
      assertTrue(trace.summary().contains("Last deciding stage: layer / selected (SET)"));
    }
  }

  @Test
  void provenanceTargetsTheLiveDefaultAndExplicitOverrideWithoutHistoricalIndices() {
    try (var state = state()) {
      var trace = snapshot(state);
      var layer = state.project().worldGenerator.layers.getFirst();
      assertEquals(layer.uuid, threshold(trace, 0).resource());
      assertEquals(layer.operations.getLast().uuid, threshold(trace, 1).resource());
      assertEquals("settings/threshold", threshold(trace, 0).parameter());
      assertEquals("", threshold(trace, 1).pointer());
    }
  }

  @Test
  void immutableRowsAndSnapshotLabelsSurviveRename() {
    try (var state = state()) {
      var trace = snapshot(state);
      state.change(p -> p.voxelTypes.getFirst().id = "renamed");
      assertEquals("solid", trace.type(1));
      assertTrue(trace.details().contains("22.0 >= 30.0 : rejected"));
      assertThrows(UnsupportedOperationException.class, () -> trace.rows(0).clear());
    }
  }

  @Test
  void editedOrDeletedSourcesRejectNavigationWhileOtherSourcesRemainAvailable() {
    try (var state = state()) {
      var trace = snapshot(state);
      var target = threshold(trace, 1);
      assertTrue(trace.available(state, target));
      state.change(
          p ->
              p.worldGenerator
                  .layers
                  .getFirst()
                  .operations
                  .getLast()
                  .overrides
                  .put("threshold", ProjectIO.JSON.valueToTree(31)));
      assertFalse(trace.available(state, target));
      assertTrue(
          trace.available(
              state,
              trace.rows(0).stream()
                  .filter(r -> r.label().equals("Primary field"))
                  .findFirst()
                  .orElseThrow()
                  .target()));
    }
  }

  @Test
  void foreignProjectIdentityCannotNavigateEvenWithMatchingResourceIds() {
    try (var state = state()) {
      var trace = snapshot(state);
      var target = threshold(trace, 0);
      state.change(p -> p.project.uuid = UUID.randomUUID());
      assertFalse(trace.available(state, target));
    }
  }

  @Test
  void noiseItemTargetsUseActualObservedUuidsAndNeverSampleArrayPositions() {
    try (var state = state()) {
      var trace = snapshot(state);
      var expression = state.project().noise2D.getFirst().processing.getFirst();
      var row =
          trace.rows(0).stream()
              .filter(
                  r ->
                      r.label().startsWith("Noise PROCESSING")
                          && r.value().startsWith("EXPRESSION"))
              .findFirst()
              .orElseThrow();
      assertEquals(expression.uuid, row.target().control());
      assertEquals("expression", row.target().parameter());
      assertTrue(trace.available(state, row.target()));
    }
  }

  @Test
  void searchingAndSourceCheckingDoNotMutateTheDocumentOrHistory() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      var trace = snapshot(state);
      for (var row : trace.rows(0)) trace.available(state, row.target());
      trace.details();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void crossResourceGuardCancelPreservesInvalidTextAndSelection() {
    try (var state = state()) {
      var trace = snapshot(state);
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      draft.id = " invalid ";
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      var target = threshold(trace, 0);
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          guard.request(target, () -> trace.available(state, target)));
      assertEquals(
          SearchNavigation.Result.CANCELLED, guard.decide(SearchNavigation.Decision.CANCEL));
      assertSame(draft, state.draft);
      assertEquals(n.uuid, state.selected);
      assertEquals(" invalid ", draft.id);
    }
  }

  @Test
  void rejectedGuardApplyAndThenDiscardKeepCorrectTransactionOwnership() {
    try (var state = state()) {
      var trace = snapshot(state);
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      draft.id = " invalid ";
      long history = state.document.historyBytes();
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      guard.request(threshold(trace, 0));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION, guard.decide(SearchNavigation.Decision.APPLY));
      assertSame(draft, state.draft);
      assertEquals(history, state.document.historyBytes());
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.DISCARD));
      assertEquals("field", state.project().noise2D.getFirst().id);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void successfulGuardApplyCreatesOneUndoAndNavigationCreatesNone() {
    try (var state = state()) {
      var trace = snapshot(state);
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      ((Noise) state.draft).id = "renamed";
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      guard.request(threshold(trace, 0));
      assertEquals(
          SearchNavigation.Result.NAVIGATED, guard.decide(SearchNavigation.Decision.APPLY));
      assertEquals(threshold(trace, 0).resource(), state.selected);
      state.document.undo();
      assertEquals("field", state.project().noise2D.getFirst().id);
    }
  }

  @Test
  void sourceFreshnessIsRecheckedWhileAValidGuardIsPending() {
    try (var state = state()) {
      var trace = snapshot(state);
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      ((Noise) state.draft).id = "pending";
      Object draft = state.draft;
      var target = threshold(trace, 0);
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      assertEquals(
          SearchNavigation.Result.NEEDS_DECISION,
          guard.request(target, () -> trace.available(state, target)));
      state.change(p -> p.worldGenerator.layers.getFirst().defaults.threshold = 99);
      assertEquals(
          SearchNavigation.Result.UNAVAILABLE, guard.decide(SearchNavigation.Decision.DISCARD));
      assertSame(draft, state.draft);
      assertEquals(n.uuid, state.selected);
    }
  }

  @Test
  void sameOwnerNoiseNavigationPreservesRejectedDraftObject() {
    try (var state = state()) {
      var trace = snapshot(state);
      var n = state.project().noise2D.getFirst();
      state.select("noise2D", n.uuid);
      var draft = (Noise) state.draft;
      draft.id = " invalid ";
      var target =
          trace.rows(0).stream()
              .filter(r -> r.label().equals("Primary field"))
              .findFirst()
              .orElseThrow()
              .target();
      var guard = new GuardedNavigation(state, new PropertyPanel(state));
      assertEquals(
          SearchNavigation.Result.NAVIGATED,
          guard.request(target, () -> trace.available(state, target)));
      assertSame(draft, state.draft);
    }
  }

  @Test
  void payloadRetentionEvictsOldestAndRejectsOversizeWithoutLosingExistingEntries() {
    try (var state = state()) {
      var trace = snapshot(state);
      var history = new TraceHistory(2, trace.retainedBytes() * 2);
      assertTrue(history.put(1, trace));
      assertTrue(history.put(2, trace));
      assertTrue(history.put(3, trace));
      assertEquals(2, history.size());
      assertEquals(1, history.evicted());
      assertTrue(history.get(1).isEmpty());
      assertEquals(trace.retainedBytes() * 2, history.bytes());
      var small = new TraceHistory(1, trace.retainedBytes() - 1);
      assertFalse(small.put(1, trace));
      assertEquals(0, small.size());
    }
  }

  @Test
  void replacingAnExistingPayloadBalancesAccountingAndClearReleasesIt() {
    try (var state = state()) {
      var trace = snapshot(state);
      var history = new TraceHistory(2, trace.retainedBytes() * 2);
      history.put(1, trace);
      history.put(1, trace);
      assertEquals(1, history.size());
      assertEquals(trace.retainedBytes(), history.bytes());
      history.clear();
      assertEquals(0, history.bytes());
      assertThrows(IllegalArgumentException.class, () -> new TraceHistory(0, 1));
    }
  }

  @Test
  void logsRetainTheSameImmutableSnapshotAndPublishProjectScopedInformationalEvents() {
    try (var state = state()) {
      var trace = snapshot(state);
      long revision = state.revision, history = state.document.historyBytes();
      long id = state.publishTrace(trace);
      assertSame(trace, state.traces.get(id).orElseThrow());
      var event = state.events.get(id).orElseThrow();
      assertEquals("GENERATION_TRACE", event.code());
      assertEquals(state.project().project.uuid, event.target().project());
      assertFalse(event.actionRequired());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void newSessionClearsDetailedPayloadsWithoutReinterpretingHistoricalIds() {
    try (var state = state()) {
      var trace = snapshot(state);
      long id = state.publishTrace(trace);
      state.newSession();
      assertTrue(state.traces.get(id).isEmpty());
      assertFalse(trace.available(state, threshold(trace, 0)));
    }
  }
}
