package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import org.junit.jupiter.api.Test;

class ResourceShortcutTest {
  @Test
  void sharedCopyPasteDuplicateServicesKeepUuidAndUndoContracts() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var n = new Noise();
      n.id = "field";
      state.change(p -> p.noise2D.add(n));
      state.select("noise2D", n.uuid);
      var panel = new ResourcePanel(state, new PropertyPanel(state));
      panel.shortcut("Copy");
      panel.shortcut("Paste");
      assertEquals(2, state.project().noise2D.size());
      assertNotEquals(n.uuid, state.project().noise2D.getLast().uuid);
      state.document.undo();
      assertEquals(1, state.project().noise2D.size());
      panel.shortcut("Duplicate");
      assertEquals(2, state.project().noise2D.size());
      panel.reset();
      int count = state.project().noise2D.size();
      panel.shortcut("Paste");
      assertEquals(count, state.project().noise2D.size());
    }
  }

  @Test
  void placementClipboardPastesIntoTheSelectedLayerInsteadOfTheWorldRoot() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var n = new Noise();
      n.id = "field";
      var layer = new Layer();
      layer.id = "layer";
      var op = new Operation();
      op.id = "placement";
      op.noiseGeneratorUuid = n.uuid;
      layer.operations.add(op);
      state.change(
          p -> {
            p.noise2D.add(n);
            p.worldGenerator.layers.add(layer);
          });
      state.select("operation", op.uuid);
      var panel = new ResourcePanel(state, new PropertyPanel(state));
      panel.shortcut("Copy");
      state.select("layer", layer.uuid);
      panel.shortcut("Paste");
      var placements = state.project().worldGenerator.layers.getFirst().operations;
      assertEquals(2, placements.size());
      assertNotEquals(op.uuid, placements.getLast().uuid);
      assertEquals(n.uuid, placements.getLast().noiseGeneratorUuid);
    }
  }
}
