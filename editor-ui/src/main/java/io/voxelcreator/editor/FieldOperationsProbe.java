package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Native production Properties workflow for typed calculus and distance shapes. */
public final class FieldOperationsProbe {
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
                  addNoise(p, 2);
                  addNoise(p, 3);
                }),
            "Fixture validation");
        var properties = new PropertyPanel(state);
        var noise = state.project().noise2D.getFirst();
        var maximum =
            noise.processing.stream()
                .filter(i -> i.kind.equals("MAX_FILTER"))
                .findFirst()
                .orElseThrow();
        properties.goToControl(noise.uuid, maximum.uuid);
        draw = properties::draw;
        frames(5);
        var choices2 = properties.probeOperations(noise);
        require(
            choices2.stream().noneMatch(c -> c.key().equals("CYLINDER") || c.key().equals("TORUS")),
            "3D shapes exposed in 2D");
        require(
            choices2.stream()
                .anyMatch(c -> c.key().equals("GRADIENT") && c.detail().contains("Vector2")),
            "2D gradient signature");
        double before = sample(state.project());
        require(before == 57, "Independent max-neighborhood oracle");
        var committed = ProjectIO.JSON.valueToTree(state.project());
        var center = properties.probeParameter(maximum.uuid, "strength");
        require(center != null, "Production strength control not submitted");
        click(center);
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.A, true);
        frame();
        io.addKeyEvent(ImGuiKey.A, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frame();
        io.addInputCharactersUTF8("0.25");
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
            edited.parameters.get("strength").doubleValue() == .25,
            "Actual numeric edit did not reach private draft");
        require(
            committed.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Numeric edit committed prematurely");
        require(state.applyDraft(), "Parent Apply rejected");
        require(sample(state.project()) == 37.5, "Independent blend oracle:31+(57-31)/4");
        state.document.undo();
        state.revision++;
        require(sample(state.project()) == 57, "One Undo");
        state.document.redo();
        state.revision++;
        require(sample(state.project()) == 37.5, "One Redo");
        state.refreshDraft();
        int rendered = 0;
        for (var resource :
            List.of(state.project().noise2D.getFirst(), state.project().noise3D.getFirst()))
          for (var item : resource.processing)
            if (ProcessingExtensions.DETAILS.containsKey(item.kind)) {
              properties.goToControl(resource.uuid, item.uuid);
              frames(3);
              rendered++;
            }
        require(rendered == 22, "Dimension-specific operation count");
        state.document.save(output.resolve("field-operations-project.json"));
        Files.writeString(
            output.resolve("field-operations-reference.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "renderedOperations",
                    rendered,
                    "extensions",
                    12,
                    "before",
                    57,
                    "after",
                    37.5,
                    "undo",
                    57,
                    "redo",
                    37.5,
                    "gradient2D",
                    "Vector2",
                    "gradient3D",
                    "Vector3")));
        System.out.println(
            "FIELD_OPERATIONS_NATIVE_PASS 22 production item details / dimension-filtered choices /"
                + " actual numeric edit / private draft / parent Apply / Undo-Redo / exact 57 and"
                + " 37.5");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void addNoise(ProjectData p, int dimension) {
    var noise = new Noise();
    noise.id = "fields" + dimension;
    noise.dimension = dimension;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    var expression = ProcessingRegistry.create("EXPRESSION", "quadratic", dimension);
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree(dimension == 2 ? "x*x+3*z*z" : "x*x+2*y*y+3*z*z"));
    noise.processing.add(expression);
    for (var key : ProcessingExtensions.DETAILS.keySet()) {
      if (!ProcessingExtensions.supports(key, dimension)) continue;
      var item = ProcessingRegistry.create(key, key.toLowerCase(Locale.ROOT), dimension);
      if (ProcessingExtensions.CALCULUS.contains(key))
        item.inputs.put("field", Binding.earlierField(expression.uuid));
      noise.processing.add(item);
      if (key.equals("MAX_FILTER")) noise.outputBinding = Binding.earlier(item.uuid);
    }
    (dimension == 2 ? p.noise2D : p.noise3D).add(noise);
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
