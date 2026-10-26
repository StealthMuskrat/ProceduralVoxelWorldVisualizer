package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Native scalar manipulation editing, searchable modes, private Apply, and one Undo/Redo. */
public final class ScalarRemappingProbe {
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
                            Path.of("fixtures/scalar-remapping.json").toFile(), ProjectData.class);
                    p.noise2D.addAll(fixture.noise2D);
                    p.noise3D.addAll(fixture.noise3D);
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                }),
            "Fixture validation");
        var properties = new PropertyPanel(state);
        var noise = state.project().noise2D.getFirst();
        var maximum = noise.processing.getFirst();
        properties.goToControl(noise.uuid, maximum.uuid);
        draw = properties::draw;
        frames(5);
        double before = sample(state.project());
        require(before == .5, "Independent scalar chain oracle");
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var center = properties.probeParameter(maximum.uuid, "weight");
        require(center != null, "Production weight control not submitted");
        click(center);
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.A, true);
        frame();
        io.addKeyEvent(ImGuiKey.A, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frame();
        io.addInputCharactersUTF8("0.75");
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
            edited.parameters.get("weight").doubleValue() == .75,
            "Actual numeric edit did not reach private draft");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Numeric edit committed prematurely");
        click(properties.probeParameter(maximum.uuid, "mode"));
        frames(3);
        io.addInputCharactersUTF8("ADD");
        frames(3);
        click(properties.probeSourceOption("ADD"));
        frames(2);
        require(
            edited.parameters.get("mode").asText().equals("ADD"),
            "Actual searchable mode selection");
        require(state.applyDraft(), "Parent Apply rejected");
        require(sample(state.project()) == 3.25, "Independent authored chain oracle");
        state.document.undo();
        state.revision++;
        require(sample(state.project()) == .5, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project()) == 3.25, "One Redo");
        state.refreshDraft();
        int rendered = 0;
        for (var item : state.project().noise2D.getFirst().processing) {
          properties.goToControl(noise.uuid, item.uuid);
          frames(3);
          rendered++;
        }
        require(rendered == 4, "Production manipulation panels");
        privateNoise = (Noise) state.draft;
        var falloff =
            privateNoise.processing.stream()
                .filter(i -> i.kind.equals("FALLOFF"))
                .findFirst()
                .orElseThrow();
        falloff.parameters.put("extent", ProjectIO.JSON.valueToTree(0));
        var invalid = ProjectIO.JSON.valueToTree(state.draft);
        require(!state.applyDraft(), "Invalid extent accepted");
        require(invalid.equals(ProjectIO.JSON.valueToTree(state.draft)), "Rejected draft lost");
        require(
            state.issues.stream().anyMatch(issue -> "extent".equals(issue.parameterKey())),
            "Exact extent diagnostic");
        state.document.save(output.resolve("remapping-project.json"));
        Files.writeString(
            output.resolve("remapping-native-reference.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "renderedOperations",
                    rendered,
                    "before",
                    .5,
                    "after",
                    3.25,
                    "weight",
                    .75,
                    "mode",
                    "ADD",
                    "oneUndoRedo",
                    true,
                    "preciseInvalidExtent",
                    true)));
        System.out.println(
            "SCALAR_REMAPPING_NATIVE_PASS actual numeric edit / searchable mode / private draft /"
                + " Apply / one Undo-Redo / precise invalid extent / production previews");
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
