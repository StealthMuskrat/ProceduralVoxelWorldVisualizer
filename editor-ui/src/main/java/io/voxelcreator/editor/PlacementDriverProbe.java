package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

public final class PlacementDriverProbe {
  private static Runnable draw;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1500, 1200);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        var fixture =
            ProjectIO.JSON.readValue(
                Path.of("fixtures/placement-driver-phases.json").toFile(), ProjectData.class);
        require(state.change(p -> ResourceGraph.assign(p, fixture)), "Fixture rejected");
        var layer = state.project().worldGenerator.layers.getFirst();
        state.select("layer", layer.uuid);
        var panel = new PropertyPanel(state);
        draw = panel::probeParameterDriverPanel;
        frames(5);
        require(sample(state.project()) == 0, "Original exact density selection");
        var before = ProjectIO.JSON.valueToTree(state.project());
        click(panel.probeParameterDriver(0, "outputMax"));
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.A, true);
        frame();
        io.addKeyEvent(ImGuiKey.A, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frame();
        io.addInputCharactersUTF8("4");
        frame();
        io.addKeyEvent(ImGuiKey.Enter, true);
        frame();
        io.addKeyEvent(ImGuiKey.Enter, false);
        frames(2);
        require(
            ((Layer) state.draft).defaults.parameterDrivers.getFirst().outputMax == 4,
            "Actual production numeric edit");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())), "Premature publication");
        require(state.applyDraft(), "Apply rejected");
        require(sample(state.project()) == 1, "Driven coordinate selection");
        state.document.undo();
        state.revision++;
        require(sample(state.project()) == 0, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project()) == 1, "One Redo");
        state.refreshDraft();
        ((Layer) state.draft).defaults.parameterDrivers.getFirst().noiseGeneratorUuid = null;
        var invalid = ProjectIO.JSON.valueToTree(state.draft);
        require(!state.applyDraft(), "Missing coordinate field accepted");
        require(invalid.equals(ProjectIO.JSON.valueToTree(state.draft)), "Rejected draft lost");
        require(
            state.issues.stream().anyMatch(d -> d.code().equals("MISSING_DRIVER_FIELD")),
            "Missing exact field issue");
        state.document.save(out.resolve("placement-driver-project.json"));
        Files.writeString(
            out.resolve("placement-driver-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "before",
                    0,
                    "after",
                    1,
                    "outputMaximum",
                    4,
                    "oneUndoRedo",
                    true,
                    "privateEdit",
                    true,
                    "missingFieldRejected",
                    true)));
        System.out.println(
            "PLACEMENT_DRIVER_NATIVE_PASS production numeric editing / private Apply / one"
                + " Undo-Redo / required-field rejection");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static int sample(ProjectData p) {
    try (var e = new NoiseGeneratorEngine();
        var w =
            e.openWorld(
                new CompiledProject(p),
                new WorldOptions(null, 4, WorldOptions.CachePolicy.NO_CACHE, 0))) {
      return w.getVoxelTypeId(new BlockPos(0, 0, 0));
    }
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1450, 1150, ImGuiCond.Always);
    ImGui.begin("Placement driver controls");
    draw.run();
    ImGui.end();
    ImGui.render();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void click(float[] p) {
    require(p != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }
}
