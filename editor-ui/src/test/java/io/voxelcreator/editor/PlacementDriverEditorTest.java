package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

class PlacementDriverEditorTest {
  @Test
  void privateDriverEditIsOneApplyUndoAndRejectedDraftIsPreserved() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(state.change(target -> ResourceGraph.assign(target, p)));
      var layer = state.project().worldGenerator.layers.getFirst();
      state.select("layer", layer.uuid);
      var draft = (Layer) state.draft;
      draft.defaults.parameterDrivers.getFirst().outputMax = 4;
      assertEquals(2, layer.defaults.parameterDrivers.getFirst().outputMax);
      assertTrue(state.applyDraft());
      assertEquals(
          4,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .defaults
              .parameterDrivers
              .getFirst()
              .outputMax);
      state.document.undo();
      assertEquals(
          2,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .defaults
              .parameterDrivers
              .getFirst()
              .outputMax);
      state.document.redo();
      state.refreshDraft();
      ((Layer) state.draft).defaults.parameterDrivers.getFirst().noiseGeneratorUuid = null;
      var before = ProjectIO.JSON.valueToTree(state.draft);
      assertFalse(state.applyDraft());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.draft));
    }
  }

  @Test
  void coordinateDriverFieldsRemainInOwnedExportClosure() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
    var exported =
        ResourceGraph.selection(p, java.util.Set.of(p.worldGenerator.layers.getFirst().uuid), true);
    assertEquals(1, exported.noise3D.size());
    exported.worldGenerator.layers.getFirst().defaults.parameterDrivers.clear();
    assertEquals(3, p.worldGenerator.layers.getFirst().defaults.parameterDrivers.size());
  }
}
