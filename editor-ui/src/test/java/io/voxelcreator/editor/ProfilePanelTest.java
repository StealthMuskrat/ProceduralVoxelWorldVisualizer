package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

class ProfilePanelTest {
  @Test
  void filtersComposeWithoutChangingRetainedMeasurements() {
    try (var profiler = new Profiler()) {
      var c = profiler.start(Profiler.Options.defaults());
      profiler
          .scope(4, null, null)
          .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 2, "Editor queue");
      profiler
          .scope(5, null, null)
          .gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 10, "World cache");
      profiler.stop();
      var snapshot = c.snapshot();
      assertEquals(2, ProfilePanel.filter(snapshot, null, "").size());
      assertEquals(1, ProfilePanel.filter(snapshot, Profiler.Kind.QUEUE_DEPTH, "EDITOR").size());
      assertEquals(0, ProfilePanel.filter(snapshot, Profiler.Kind.QUEUE_DEPTH, "cache").size());
      assertEquals(1, ProfilePanel.filter(snapshot, null, " 5 ").size());
      assertEquals(2, snapshot.samples().size());
    }
  }

  @Test
  void sourceNavigationIsProjectScopedAndCaptureActionsNeverDirtyTheDocument() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var noise = new Noise();
      noise.id = "field";
      state.change(p -> p.noise2D.add(noise));
      state.select("noise2D", noise.uuid);
      String before = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision, history = state.document.historyBytes();
      var capture = state.engine.profiler().start(Profiler.Options.defaults());
      var scope = state.engine.profiler().scope(3, state.project().project.uuid, null);
      long stamp = scope.begin();
      scope.finish(stamp, Profiler.Kind.PREVIEW, noise.uuid, "Preview", Profiler.Outcome.SUCCESS);
      state.engine.profiler().stop();
      var target = ProfilePanel.target(capture.snapshot().series().getFirst());
      assertTrue(state.canNavigate(target));
      assertTrue(state.navigate(target));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      var other = new ProjectData();
      assertFalse(DiagnosticNavigation.available(other, null, null, target));
      state.engine.profiler().clear();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      state.project().noise2D.clear();
      assertFalse(state.canNavigate(target));
    }
  }

  @Test
  void workSeriesUseLayerUuidNavigationAndKeepTheCurrentDraft() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var layer = new Layer();
      layer.id = "base";
      assertTrue(state.change(p -> p.worldGenerator.layers.add(layer)));
      state.select("layer", layer.uuid);
      var draft = state.draft;
      ((Layer) draft).id = "private";
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var capture = state.engine.profiler().start(new Profiler.Options(10000, 1024, 64, 256, true));
      var scope = state.engine.profiler().scope(1, state.project().project.uuid, null);
      scope.finish(
          scope.begin(),
          Profiler.Kind.LAYER_WORK,
          layer.uuid,
          "Layer base",
          Profiler.Outcome.SUCCESS);
      state.engine.profiler().stop();
      var rows = ProfilePanel.filter(capture.snapshot(), Profiler.Kind.LAYER_WORK, "BASE");
      assertEquals(1, rows.size());
      var target = ProfilePanel.target(rows.getFirst());
      assertEquals(layer.uuid, target.resource());
      assertTrue(state.navigate(target));
      assertSame(draft, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }

  @Test
  void retainedWorkTotalSeparatesContextsAndMetricKindsExactly() {
    try (var profiler = new Profiler()) {
      var capture = profiler.start(new Profiler.Options(10000, 1024, 64, 256, true));
      var id = java.util.UUID.randomUUID();
      var project = java.util.UUID.randomUUID();
      var first = profiler.scope(1, project, null);
      var other = profiler.scope(2, project, null);
      first.recordDuration(
          first.begin(), Profiler.Kind.LAYER_WORK, id, "Layer base", 12, Profiler.Outcome.SUCCESS);
      first.recordDuration(
          first.begin(), Profiler.Kind.LAYER_WORK, id, "Layer base", 18, Profiler.Outcome.SUCCESS);
      other.recordDuration(
          other.begin(), Profiler.Kind.LAYER_WORK, id, "Layer base", 900, Profiler.Outcome.SUCCESS);
      first.recordDuration(
          first.begin(),
          Profiler.Kind.OPERATION_WORK,
          id,
          "Layer base",
          400,
          Profiler.Outcome.SUCCESS);
      var frozen = capture.snapshot();
      var row =
          frozen.series().stream()
              .filter(r -> r.kind() == Profiler.Kind.LAYER_WORK && r.contextId() == 1)
              .findFirst()
              .orElseThrow();
      assertEquals(30, ProfilePanel.workTotal(frozen, row));
      first.recordDuration(
          first.begin(), Profiler.Kind.LAYER_WORK, id, "Layer base", 2, Profiler.Outcome.SUCCESS);
      assertEquals(32, ProfilePanel.workTotal(capture.snapshot(), row));
      assertEquals(30, ProfilePanel.workTotal(frozen, row));
    }
  }
}
