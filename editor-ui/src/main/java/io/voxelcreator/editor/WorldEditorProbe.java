package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production-panel input/layout definition; no normal startup invokes this fixture. */
public final class WorldEditorProbe {
  private static WorldEditorPanel panel;
  private static int width = 1280, height = 900;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(state.change(WorldEditorProbe::fixture), "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private-field";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(state.project());
        var privateBefore = ProjectIO.JSON.valueToTree(state.draft);
        Object draft = state.draft;
        UUID selected = state.selected;
        var resources = Set.copyOf(state.multiSelection);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var edits = new ArrayList<UUID>();
        var references = new ArrayList<UUID>();
        panel = new WorldEditorPanel(state, (target, fresh) -> {
          require(fresh.getAsBoolean(), "Callback lost exact local selection");
          edits.add(target.resource());
        }, references::add);
        frames(6);
        var operations = state.project().worldGenerator.layers.getFirst().operations;
        UUID first = operations.get(0).uuid, second = operations.get(1).uuid, third = operations.get(2).uuid;
        click(panel.control(first.toString()), false, false);
        click(panel.control(second.toString()), true, false);
        require(panel.selection.selected().equals(Set.of(first, second)), "Ctrl selection failed");
        click(panel.control("edit"), false, false);
        require(edits.isEmpty(), "Multi-selection enabled Properties edit");
        click(panel.control(first.toString()), false, false);
        click(panel.control(third.toString()), false, true);
        require(panel.selection.selected().equals(Set.of(first, second, third)), "Shift range failed");
        click(panel.control(second.toString()), false, false);
        click(panel.control("edit"), false, false);
        click(panel.control("references"), false, false);
        require(edits.equals(List.of(second)) && references.equals(List.of(second)), "Wrong explicit target");
        var held = panel.control("edit");
        io.addMousePosEvent(held[0], held[1]);
        frames(2);
        io.addMouseButtonEvent(0, true);
        frames(2);
        panel.selection.select(third, false, false, panel.selection.rows(""));
        frames(2);
        io.addMouseButtonEvent(0, false);
        frames(3);
        require(edits.equals(List.of(second)), "Held Properties action targeted a replacement selection");
        click(panel.control(second.toString()), false, false);
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1280, 900}, {500, 900}, {500, 1100}, {1600, 1500}, {2600, 2000}, {1000, 480}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, windows = {1, 1, 1, 1, 2.5f, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          io.setFontGlobalScale(globals[i]);
          state.user.appearance.windowScales.put("World Editor", windows[i]);
          frames(5);
          var receipt = new LinkedHashMap<>(panel.receipt());
          receipt.put("scenario", i);
          receipt.put("globalScale", globals[i]);
          receipt.put("windowScale", windows[i]);
          cases.add(receipt);
        }
        state.user.appearance.windowScales.remove("World Editor");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project()))
                && privateBefore.equals(ProjectIO.JSON.valueToTree(state.draft))
                && state.draft == draft && state.selected.equals(selected)
                && state.multiSelection.equals(resources)
                && state.revision == revision && state.document.historyBytes() == history
                && state.document.dirty() == dirty && state.seedInputs.text("seedOffset").equals("-"),
            "World selection escaped into project or Properties");
        Files.writeString(out.resolve("before.json"), ProjectIO.JSON.writeValueAsString(before));
        Files.writeString(out.resolve("after.json"), ProjectIO.JSON.writeValueAsString(state.project()));
        Files.writeString(out.resolve("world-editor-native.json"), ProjectIO.JSON.writeValueAsString(
            Map.of("formatVersion", 1, "cases", cases, "ctrlSelection", true,
                "rangeSelection", true, "privateInputPreserved", true, "explicitTarget", second,
                "editCallbacks", edits, "referenceCallbacks", references,
                "physicalInputVerified", false, "physicalDpiVerified", false)));
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) {
      ImGui.getIO().setDisplaySize(width, height);
      ImGui.newFrame();
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      panel.draw();
      ImGui.render();
    }
  }

  private static void click(float[] point, boolean ctrl, boolean shift) {
    require(point != null, "Missing production control");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, ctrl);
    io.addKeyEvent(ImGuiKey.ModShift, shift);
    io.addMousePosEvent(point[0], point[1]);
    frames(2);
    io.addMouseButtonEvent(0, true);
    frames(2);
    io.addMouseButtonEvent(0, false);
    frames(2);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.ModShift, false);
    frames(3);
  }

  private static void fixture(ProjectData p) {
    var noise = new Noise();
    noise.id = "shared-field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .25);
    p.noise2D.add(noise);
    for (int i = 0; i < 2; i++) {
      var layer = new Layer();
      layer.id = "layer-" + i;
      for (int j = 0; j < (i == 0 ? 300 : 3); j++) {
        var operation = new Operation();
        operation.id = "placement-" + j;
        operation.noiseGeneratorUuid = noise.uuid;
        layer.operations.add(operation);
      }
      p.worldGenerator.layers.add(layer);
    }
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
