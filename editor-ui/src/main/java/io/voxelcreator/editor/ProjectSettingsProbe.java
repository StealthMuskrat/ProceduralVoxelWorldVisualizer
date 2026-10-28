package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production Project Settings native buffer/focus/transaction workflow. */
public final class ProjectSettingsProbe {
  private static EditorState state;
  private static ProjectSettingsPanel panel;
  private static boolean visible;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 900);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(ProjectSettingsProbe::fixture), "Fixture rejected");
        var noise = state.project().noise2D.getFirst();
        state.select("noise2D", noise.uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private-unapplied";
        var initial = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        panel = new ProjectSettingsPanel(state);
        open("seed");
        type("-");
        click(panel.controlAt("apply"));
        frames(3);
        require(visible && panel.text("seed").equals("-"), "Invalid text was lost or closed");
        require(
            initial.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Invalid text applied a previous value");
        var issue = state.issues.getFirst();
        require(
            panel.open(EditorEvents.Target.diagnostic(state.project().project.uuid, issue)),
            "Project diagnostic route");
        until("seed");
        require(panel.text("seed").equals("-"), "Diagnostic replaced private text");
        type("123");
        require(state.change(p -> p.project.seed = 1), "Concurrent settings fixture edit");
        frames(3);
        click(panel.controlAt("apply"));
        frames(3);
        require(
            visible && state.project().project.seed == 1 && panel.text("seed").equals("123"),
            "Stale settings overwrote current project");
        click(panel.controlAt("reload"));
        frames(3);
        require(panel.text("seed").equals("1"), "Explicit reload did not use current project");
        open("seed");
        type("44");
        click(panel.controlAt("cancel"));
        frames(2);
        require(!visible && state.project().project.seed == 1, "Cancel published settings");
        state.document.undo();
        state.revision++;
        require(initial.equals(ProjectIO.JSON.valueToTree(state.project())), "Fixture edit Undo");

        long revision = state.revision, history = state.document.historyBytes();
        open("seed");
        type("17");
        open("minY");
        type("-2");
        open("maxY");
        type("3");
        require(
            panel.text("seed").equals("17") && panel.text("minY").equals("-2"),
            "Field navigation reset pending settings");
        require(
            initial.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history,
            "Private settings changed project/history");
        require(
            state.draft == privateDraft && privateDraft.id.equals("private-unapplied"),
            "Settings consumed resource draft");
        require(sample(state.project()) == 639, "Original ambient expression");
        click(panel.controlAt("apply"));
        frames(3);
        require(
            !visible
                && state.project().project.seed == 17
                && state.project().project.minY == -2
                && state.project().project.maxY == 3,
            "Settings Apply failed");
        require(sample(state.project()) == 24, "Edited ambient expression");
        require(
            state.draft == privateDraft && state.project().noise2D.getFirst().id.equals("context"),
            "Settings applied the resource draft");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(initial.equals(ProjectIO.JSON.valueToTree(state.project())), "One settings Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "One settings Redo");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)), "Settings changed preferences");
        state.document.save(out.resolve("project-settings-project.json"));
        Files.writeString(
            out.resolve("project-settings-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "focused",
                    List.of("seed", "minY", "maxY"),
                    "before",
                    639,
                    "after",
                    24,
                    "invalidTextRetained",
                    true,
                    "staleApplyRejected",
                    true,
                    "explicitReload",
                    true,
                    "cancel",
                    true,
                    "resourceDraftPreserved",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "PROJECT_SETTINGS_NATIVE_PASS exact raw text / diagnostic focus / stale guard / reload"
                + " / Cancel / one Apply-Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData project) {
    var noise = new Noise();
    noise.id = "context";
    var expression = ProcessingRegistry.create("EXPRESSION", "context");
    expression.parameters.put(
        "expression", ProjectIO.JSON.valueToTree("x+projectSeed+worldMinY+worldMaxY+worldHeight"));
    noise.processing.add(expression);
    noise.outputBinding = Binding.earlier(expression.uuid);
    project.noise2D.add(noise);
    var voxel = new VoxelType();
    voxel.id = "stone";
    project.voxelTypes.add(voxel);
    var layer = new Layer();
    layer.id = "bounds";
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = voxel.uuid;
    var operation = new Operation();
    operation.id = "all";
    operation.interpretation = "ALL";
    operation.noiseGeneratorUuid = noise.uuid;
    layer.operations.add(operation);
    project.worldGenerator.layers.add(layer);
  }

  private static double sample(ProjectData project) {
    return new NoiseCompiler(project, project.project.seed)
        .compile(project.noise2D.getFirst().uuid)
        .sample(0, 0, 0);
  }

  private static void open(String field) {
    require(
        panel.open(
            new EditorEvents.Target(
                state.project().project.uuid, null, null, field, "/project/" + field, true)),
        "Project Settings route");
    visible = true;
    until(field);
  }

  private static void until(String field) {
    for (int i = 0; i < 16; i++) {
      frame();
      if (field.equals(panel.focusedField())) break;
    }
    require(
        field.equals(panel.focusedField()), "Project field did not acknowledge focus: " + field);
    frames(3);
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(float[] at) {
    require(at != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(850, 650, ImGuiCond.Always);
      ImGui.begin("Project Settings");
      if (panel.draw()) visible = false;
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
