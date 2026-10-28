package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native definition for world Properties links and explicit inherited overrides. */
public final class WorldSettingFocusProbe {
  private static PropertyPanel panel;
  private static EditorState state;
  private static boolean focus;
  private static final List<String> acknowledged = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 1000);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      acknowledged.clear();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(WorldSettingFocusProbe::fixture), "Fixture rejected");
        var layer = state.project().worldGenerator.layers.getFirst();
        UUID layerId = layer.uuid, operationId = layer.operations.getFirst().uuid;
        state.select("layer", layerId);
        panel = new PropertyPanel(state);
        frames(4);
        var draft = (Layer) state.draft;
        var before = ProjectIO.JSON.valueToTree(state.project());
        var user = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        draft.defaults.threshold = Double.NaN;
        require(!state.applyDraft(), "Invalid world draft accepted");
        var issue =
            state.issues.stream()
                .filter(
                    d -> layerId.equals(d.sourceUuid()) && d.jsonPointer().endsWith("/threshold"))
                .findFirst()
                .orElseThrow();
        require(
            panel.goToProblem(EditorEvents.Target.diagnostic(state.project().project.uuid, issue)),
            "World diagnostic unavailable");
        until("settings/threshold");
        type("2");
        require(draft.defaults.threshold == 2, "Layer threshold keyboard repair");
        reveal("settings/probabilityDriver/outputMax", "settings/probabilityDriver/outputMax");
        type("0.4");
        require(draft.defaults.probabilityDriver.outputMax == .4, "Singleton driver keyboard edit");
        reveal("settings/fieldGates/threshold", "settings/fieldGates/kind");
        reveal("settings/voxelStatePredicate/reference", "settings/voxelStatePredicate/kind");
        reveal("settings/samplingTransforms/999/x", "settings/samplingTransforms");
        reveal("enabled", "enabled");
        require(
            draft.enabled
                && draft.defaults.fieldGates.kind.equals("ALWAYS")
                && draft.defaults.voxelStatePredicate.kind.equals("ANY")
                && draft.defaults.samplingTransforms.isEmpty(),
            "Navigation changed controls");
        require(
            state.draft == draft && before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Navigation published or replaced the draft");
        require(
            revision == state.revision && history == state.document.historyBytes(),
            "Navigation changed history");
        require(state.applyDraft(), "Layer Apply failed");
        var layerApplied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(before.equals(ProjectIO.JSON.valueToTree(state.project())), "Layer one Undo");
        state.document.redo();
        state.revision++;
        require(layerApplied.equals(ProjectIO.JSON.valueToTree(state.project())), "Layer one Redo");

        state.select("operation", operationId);
        var operation = (Operation) state.draft;
        frames(3);
        reveal("settings/threshold", "override/threshold");
        require(operation.overrides.isEmpty(), "Inherited focus enabled an override");
        require(
            layerApplied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Inherited focus published");
        click(panel.probeWorldControl("override/threshold"));
        frames(2);
        require(
            operation.overrides.get("threshold").asDouble() == 2,
            "Explicit override did not copy layer value");
        reveal("settings/threshold", "settings/threshold");
        type("0");
        require(
            operation.overrides.get("threshold").asDouble() == 0, "Explicit zero keyboard edit");
        reveal("interpretation", "interpretation");
        reveal("noiseGeneratorUuid", "noiseGeneratorUuid");
        require(operation.interpretation.equals("SELECTION"), "Navigation changed interpretation");
        require(state.applyDraft(), "Placement Apply failed");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        require(
            layerApplied.equals(ProjectIO.JSON.valueToTree(state.project())), "Placement one Undo");
        state.document.redo();
        state.revision++;
        require(applied.equals(ProjectIO.JSON.valueToTree(state.project())), "Placement one Redo");
        require(
            user.equals(ProjectIO.JSON.valueToTree(state.user)), "Navigation changed preferences");
        state.document.save(out.resolve("world-setting-focus-project.json"));
        Files.writeString(
            out.resolve("world-setting-focus-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "focused",
                    acknowledged,
                    "layerThreshold",
                    2,
                    "overrideThreshold",
                    0,
                    "navigationOnly",
                    true,
                    "privateDraft",
                    true,
                    "explicitOverride",
                    true,
                    "oneUndoRedo",
                    true)));
        System.out.println(
            "WORLD_SETTING_FOCUS_NATIVE_PASS diagnostics / numeric repair / inherited focus /"
                + " explicit zero / Undo-Redo");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    p.project.minY = -8;
    p.project.maxY = 8;
    var noise = new Noise();
    noise.id = "one";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 1.0);
    p.noise2D.add(noise);
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "base";
    layer.defaults.threshold = .5;
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    var operation = new Operation();
    operation.id = "selection";
    operation.noiseGeneratorUuid = noise.uuid;
    operation.interpretation = "SELECTION";
    layer.operations.add(operation);
    p.worldGenerator.layers.add(layer);
  }

  private static void reveal(String parameter, String expected) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, null, parameter, "", false)),
        "World source route unavailable: " + parameter);
    until(expected);
  }

  private static void until(String expected) {
    focus = true;
    for (int i = 0; i < 20; i++) {
      frame();
      if (("null:" + expected).equals(panel.probeFocusedProperty())) break;
    }
    require(
        ("null:" + expected).equals(panel.probeFocusedProperty()),
        "World focus not acknowledged: " + expected);
    acknowledged.add(expected);
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

  private static void click(float[] point) {
    require(point != null, "Control missing");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1100, 900, ImGuiCond.Always);
    if (focus) {
      ImGui.setNextWindowFocus();
      focus = false;
    }
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
