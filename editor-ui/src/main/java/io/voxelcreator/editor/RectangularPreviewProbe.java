package io.voxelcreator.editor;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.render.GridExtent;
import java.nio.file.*;
import java.util.*;

/** Deferred real Viewports/background/GPU replay of three fixed rectangular mesh modes. */
final class RectangularPreviewProbe {
  static void run(Path out, long window) throws Exception {
    Files.createDirectories(out);
    try (var state = new EditorState(new UserConfig(), false)) {
      state.user.visualization.streaming = false;
      state.user.performance.previewCellBudget = 105;
      state.user.performance.maxPreviewDimension = 8;
      state.user.editor.opportunitiesPerSecond = 20;
      if (!state.change(
          p -> {
            for (String id : List.of("solid", "plane")) {
              var n = new Noise();
              n.id = id;
              n.dimension = 3;
              n.algorithm.key = "CONSTANT";
              n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", 1.);
              if (id.equals("plane")) {
                var item = ProcessingRegistry.create("EXPRESSION", "plane", 3);
                item.inputs.clear();
                item.parameters.put("expression", ProjectIO.JSON.valueToTree("x - 9.25"));
                n.processing.add(item);
                n.outputBinding = Binding.earlier(item.uuid);
              }
              p.noise3D.add(n);
            }
          })) throw new AssertionError(state.issues);
      new ProjectIO().save(out.resolve("before.json"), state.project());
      state.user.save(out.resolve("preferences-before.json"));
      state.select("noise3D", state.project().noise3D.getFirst().uuid);
      ((Noise) state.draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      Object draft = state.draft;
      var values = ProjectIO.JSON.valueToTree(draft);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var receipts = new ArrayList<Map<String, Object>>();
      try (var views = new Viewports(state, new PropertyPanel(state), window)) {
        for (String mode : List.of("Threshold Voxels", "Point Cloud", "Isosurface")) {
          var noise = state.project().noise3D.get(mode.equals("Isosurface") ? 1 : 0).uuid;
          views.probeFixedExtent(noise, mode, new GridExtent(3, 5, 7));
          long end = System.nanoTime() + 30_000_000_000L;
          while (!views.probeNoiseReady(1)) {
            glfwPollEvents();
            state.drain();
            ImGui.newFrame();
            ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
            ImGui.setNextWindowSize(1000, 800, ImGuiCond.Always);
            views.draw();
            ImGui.render();
            if (System.nanoTime() > end) throw new AssertionError("Rectangular preview timeout");
            Thread.yield();
          }
          var receipt = views.probeFixedExtentReceipt();
          if (!mode.equals(receipt.get("mode")))
            throw new AssertionError("Old mesh replaced current request");
          receipts.add(receipt);
        }
      }
      if (!before.equals(ProjectIO.JSON.valueToTree(state.project()))
          || state.draft != draft
          || !values.equals(ProjectIO.JSON.valueToTree(draft))
          || state.revision != revision
          || state.document.historyBytes() != history
          || !state.seedInputs.text("seedOffset").equals("-"))
        throw new AssertionError("Fixed extents changed project/private/history state");
      new ProjectIO().save(out.resolve("after.json"), state.project());
      state.user.save(out.resolve("preferences.json"));
      Files.writeString(
          out.resolve("rectangular-preview-native.json"),
          ProjectIO.JSON.writeValueAsString(
              Map.of(
                  "formatVersion",
                  1,
                  "receipts",
                  receipts,
                  "cellBudget",
                  105,
                  "privatePreserved",
                  true)));
      System.out.println(
          "RECTANGULAR_PREVIEW_NATIVE_PASS real fixed 3x5x7 voxel/point/isosurface requests under"
              + " 105-cell budget");
    }
  }
}
