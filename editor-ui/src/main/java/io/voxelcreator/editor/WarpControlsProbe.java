package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Native shared warp controls, real algorithm/mode/numeric editing, private Apply and Undo/Redo.
 */
public final class WarpControlsProbe {
  private static Runnable draw;

  public static void main(String[] args) throws Exception {
    var output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1500, 1200);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  try {
                    var fixture =
                        ProjectIO.JSON.readValue(
                            Path.of("fixtures/warp-fields.json").toFile(), ProjectData.class);
                    p.noise2D.addAll(fixture.noise2D);
                    p.noise3D.addAll(fixture.noise3D);
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                }),
            "Fixture validation");
        var properties = new PropertyPanel(state);
        var noise = state.project().noise2D.getFirst();
        var maximum = noise.processing.getLast();
        properties.goToControl(noise.uuid, maximum.uuid);
        draw = properties::draw;
        frames(5);
        double before = sample(state.project());
        require(before == 55, "Independent simultaneous warp oracle");
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var center = properties.probeParameter(maximum.uuid, "amplitude");
        require(center != null, "Production amplitude control not submitted");
        click(center);
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.A, true);
        frame();
        io.addKeyEvent(ImGuiKey.A, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frame();
        io.addInputCharactersUTF8("2");
        frame();
        io.addKeyEvent(ImGuiKey.Enter, true);
        frame();
        io.addKeyEvent(ImGuiKey.Enter, false);
        frames(2);
        var privateNoise = (Noise) state.draft;
        var edited =
            privateNoise.processing.stream()
                .filter(i -> i.uuid.equals(maximum.uuid))
                .findFirst()
                .orElseThrow();
        require(
            edited.parameters.get("amplitude").doubleValue() == 2,
            "Actual numeric edit did not reach private draft");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Numeric edit committed prematurely");
        click(properties.probeParameter(maximum.uuid, "warpMode"));
        frames(3);
        io.addInputCharactersUTF8("SEQUENTIAL");
        frames(3);
        click(properties.probeSourceOption("SEQUENTIAL"));
        frames(2);
        require(
            edited.parameters.get("warpMode").asText().equals("SEQUENTIAL"),
            "Actual searchable mode selection");
        click(properties.probeParameter(maximum.uuid, "warpAlgorithm"));
        frames(3);
        io.addInputCharactersUTF8("CONSTANT");
        frames(3);
        click(properties.probeSourceOption("CONSTANT"));
        frames(2);
        require(
            properties.probeWarpControls(maximum.uuid).contains("source.value"),
            "Canonical constant source control hidden");
        require(
            !properties.probeWarpControls(maximum.uuid).contains("source.fractal"),
            "Unsupported source fractal visible");
        require(state.applyDraft(), "Parent Apply rejected");
        require(sample(state.project()) == 198, "Independent sequential warp oracle");
        state.document.undo();
        state.revision++;
        require(sample(state.project()) == 55, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project()) == 198, "One Redo");
        state.refreshDraft();
        int rendered = 0;
        for (var resource :
            List.of(
                state.project().noise2D.get(0),
                state.project().noise2D.get(1),
                state.project().noise3D.get(0),
                state.project().noise3D.get(1))) {
          var item =
              resource.coordinateTransforms.isEmpty()
                  ? resource.processing.getLast()
                  : resource.coordinateTransforms.getFirst();
          properties.goToControl(resource.uuid, item.uuid);
          frames(3);
          rendered++;
        }
        properties.goToControl(noise.uuid, maximum.uuid);
        frames(3);
        privateNoise = (Noise) state.draft;
        var rejected = privateNoise.processing.getLast();
        rejected.parameters.put("warpOctaves", ProjectIO.JSON.valueToTree(0));
        var invalid = ProjectIO.JSON.valueToTree(state.draft);
        require(!state.applyDraft(), "Invalid octave count accepted");
        require(invalid.equals(ProjectIO.JSON.valueToTree(state.draft)), "Rejected draft lost");
        require(
            state.issues.stream().anyMatch(issue -> "warpOctaves".equals(issue.parameterKey())),
            "Exact octave diagnostic");
        state.document.save(output.resolve("warp-project.json"));
        Files.writeString(
            output.resolve("warp-native-reference.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "renderedWarpPanels",
                    rendered,
                    "before",
                    55,
                    "after",
                    198,
                    "amplitude",
                    2,
                    "mode",
                    "SEQUENTIAL",
                    "algorithm",
                    "CONSTANT",
                    "oneUndoRedo",
                    true,
                    "preciseInvalidOctaves",
                    true)));
        System.out.println(
            "WARP_CONTROLS_NATIVE_PASS actual amplitude edit / searchable mode and algorithm /"
                + " private Apply / one Undo-Redo / 2D-3D stages / exact rejected octave control");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static double sample(ProjectData p) {
    return new NoiseCompiler(p, 0).compile(p.noise2D.getFirst().uuid).sample(2, 0, 3);
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1450, 1150, ImGuiCond.Always);
    draw.run();
    ImGui.render();
  }

  private static void click(float[] c) {
    var io = ImGui.getIO();
    io.addMousePosEvent(c[0], c[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }
}
