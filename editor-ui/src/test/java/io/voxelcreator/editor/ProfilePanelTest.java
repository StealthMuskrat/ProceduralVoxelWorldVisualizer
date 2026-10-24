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
}
