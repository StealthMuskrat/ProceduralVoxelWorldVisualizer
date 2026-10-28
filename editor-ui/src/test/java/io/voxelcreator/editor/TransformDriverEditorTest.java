package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TransformDriverEditorTest {
  @Test
  void transformDraftIsPrivateAtomicUndoableAndInvalidDeletionKeepsTheDraft() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/transform-drivers.json").toFile(), ProjectData.class);
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(state.change(target -> ResourceGraph.assign(target, p)));
      var layer = state.project().worldGenerator.layers.getFirst();
      state.select("layer", layer.uuid);
      var draft = (Layer) state.draft;
      draft.defaults.parameterDrivers.getFirst().outputMin =
          draft.defaults.parameterDrivers.getFirst().outputMax = 3;
      assertEquals(-8, layer.defaults.parameterDrivers.getFirst().outputMin);
      assertTrue(state.applyDraft());
      assertEquals(
          3,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .defaults
              .parameterDrivers
              .getFirst()
              .outputMin);
      state.document.undo();
      assertEquals(
          -8,
          state
              .project()
              .worldGenerator
              .layers
              .getFirst()
              .defaults
              .parameterDrivers
              .getFirst()
              .outputMin);
      state.document.redo();
      state.refreshDraft();
      ((Layer) state.draft).defaults.samplingTransforms.clear();
      var invalid = ProjectIO.JSON.valueToTree(state.draft);
      assertFalse(state.applyDraft());
      assertEquals(invalid, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(
          2, state.project().worldGenerator.layers.getFirst().defaults.samplingTransforms.size());
    }
  }

  @Test
  void exportClosureOwnsTheExplicitFieldAndPreservesTransformIndices() throws Exception {
    var p =
        ProjectIO.JSON.readValue(
            Path.of("../fixtures/transform-drivers.json").toFile(), ProjectData.class);
    var exported =
        ResourceGraph.selection(p, Set.of(p.worldGenerator.layers.getFirst().uuid), true);
    assertEquals(1, exported.noise3D.size());
    assertEquals(
        1,
        exported
            .worldGenerator
            .layers
            .getFirst()
            .defaults
            .parameterDrivers
            .getLast()
            .transformIndex);
    exported.worldGenerator.layers.getFirst().defaults.samplingTransforms.clear();
    assertEquals(2, p.worldGenerator.layers.getFirst().defaults.samplingTransforms.size());
  }
}
