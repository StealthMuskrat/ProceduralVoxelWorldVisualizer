package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/**
 * Production source controls: real named choice, numeric edit, Apply/Undo, and invalid-draft
 * preservation.
 */
public final class SourceAlgorithmProbe {
  private static PropertyPanel properties;

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
                  var n = new Noise();
                  n.id = "source";
                  n.algorithm.key = "PERLIN";
                  p.noise2D.add(n);
                }),
            "Fixture validation");
        var noise = state.project().noise2D.getFirst();
        state.select("noise2D", noise.uuid);
        properties = new PropertyPanel(state);
        frames(4);
        require(
            properties.probeSourceControls().equals(Set.of("frequency", "fractal")),
            "Inactive fractal controls visible");
        var committed = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes();
        click(properties.probeSourceControl("fractal"));
        frames(3);
        io.addInputCharactersUTF8("PING_PONG");
        frames(3);
        click(properties.probeSourceOption("3"));
        frames(2);
        require(
            ((Noise) state.draft).algorithm.parameters.get("fractal") == 3,
            "Actual named source mode selection");
        require(
            properties.probeSourceControls().contains("pingPongStrength"),
            "Ping-pong control hidden");
        click(properties.probeSourceControl("pingPongStrength"));
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.A, true);
        frame();
        io.addKeyEvent(ImGuiKey.A, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frame();
        io.addInputCharactersUTF8("3.5");
        frame();
        io.addKeyEvent(ImGuiKey.Enter, true);
        frame();
        io.addKeyEvent(ImGuiKey.Enter, false);
        frames(2);
        require(
            ((Noise) state.draft).algorithm.parameters.get("pingPongStrength") == 3.5,
            "Actual numeric source edit");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project()))
                && history == state.document.historyBytes(),
            "Private source editing committed");
        require(state.applyDraft(), "Source Apply");
        state.document.undo();
        state.revision++;
        require(
            !state.project().noise2D.getFirst().algorithm.parameters.containsKey("fractal"),
            "Source edit was not one Undo");
        state.document.redo();
        state.revision++;
        state.refreshDraft();
        var draft = (Noise) state.draft;
        draft.algorithm.parameters.put("frequency", 0.);
        require(!state.applyDraft(), "Invalid frequency accepted");
        require(
            state.draft == draft && draft.algorithm.parameters.get("frequency") == 0,
            "Rejected source draft lost");
        require(
            state.issues.stream()
                .anyMatch(d -> "algorithm/parameters/frequency".equals(d.parameterKey())),
            "Precise source diagnostic missing");
        state.refreshDraft();
        int visits = 0;
        for (int dimension : new int[] {2, 3})
          for (String key : NoiseAlgorithms.KEYS) {
            var isolated = new Noise();
            isolated.id = "preview";
            isolated.dimension = dimension;
            isolated.algorithm.key = key;
            state.draft = isolated;
            frames(2);
            var expected =
                NoiseAlgorithmRegistry.parameters(key, dimension).stream()
                    .filter(p -> p.visible(isolated.algorithm))
                    .map(NoiseAlgorithmRegistry.Parameter::key)
                    .collect(java.util.stream.Collectors.toSet());
            require(
                properties.probeSourceControls().equals(expected),
                "Wrong controls " + key + " / " + dimension);
            visits++;
          }
        state.refreshDraft();
        new ProjectIO().save(output.resolve("source-algorithm-project.json"), state.project());
        Files.writeString(
            output.resolve("source-algorithm-reference.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "algorithmDimensionVisits",
                    visits,
                    "namedFractalIndex",
                    3,
                    "pingPongStrength",
                    3.5,
                    "privateEdits",
                    true,
                    "oneUndoRedo",
                    true,
                    "preciseInvalidFrequency",
                    true)));
        System.out.println(
            "SOURCE_ALGORITHM_NATIVE_PASS 20 dimension/algorithm panels / actual searchable named"
                + " source-fractal choice / numeric ping-pong edit / private Apply / one Undo-Redo"
                + " / exact invalid parameter route");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void require(boolean b, String m) {
    if (!b) throw new AssertionError(m);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1450, 1150, ImGuiCond.Always);
    properties.draw();
    ImGui.render();
  }

  private static void click(float[] point) {
    require(point != null, "Native control not submitted");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }
}
