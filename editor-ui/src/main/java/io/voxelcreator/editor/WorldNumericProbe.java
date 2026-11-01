package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production World numeric layouts, exact edits and sparse override lifecycle. */
public final class WorldNumericProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static int width = 1200, height = 900;
  private static float scale = 1;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        require(state.change(WorldNumericProbe::fixture), "World numeric fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        var before = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        var layer = state.project().worldGenerator.layers.getFirst();
        UUID layerId = layer.uuid, operationId = layer.operations.getFirst().uuid;
        state.select("layer", layerId);
        panel = new PropertyPanel(state);
        panel.probeWorldNumericRows(true);
        var cases = new ArrayList<Map<String, Object>>();
        capture("Layer", cases);
        var draft = (Layer) state.draft;
        require(
            draft.defaults.minY == Integer.MIN_VALUE && draft.defaults.maxY == Integer.MAX_VALUE,
            "Idle slider display clamped inherited signed integer endpoints");
        reveal("settings/minY");
        type("-7");
        require(draft.defaults.minY == -7, "Integer text edit failed");
        type("-2147483648");
        require(draft.defaults.minY == Integer.MIN_VALUE, "Exact minimum integer text lost");
        reveal("settings/threshold");
        var row = row("settings/threshold");
        click(point(row.slider(), .371));
        double layerSlider = draft.defaults.threshold;
        require(
            layerSlider >= -1
                && layerSlider <= 1
                && layerSlider != Math.rint(layerSlider * 100000) / 100000,
            "Layer slider rounded its five-place readout into storage");
        reveal("settings/threshold");
        type("2");
        require(draft.defaults.threshold == 2, "Outside-suggestion threshold text was clamped");
        require(
            before.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Private layer edit published");
        require(state.applyDraft(), "Layer Apply rejected");
        save(out, "layer");
        var layerApplied = ProjectIO.JSON.valueToTree(state.project());
        state.select("operation", operationId);
        capture("Inherited", cases);
        require(
            ((Operation) state.draft).overrides.isEmpty(), "Inherited layout materialized values");
        reveal("settings/threshold");
        require(
            "null:override/threshold".equals(panel.probeFocusedProperty()),
            "Inherited navigation bypassed explicit Override");
        click(panel.probeWorldControl("override/threshold"));
        frames(4);
        require(
            ((Operation) state.draft).overrides.get("threshold").doubleValue() == 2,
            "Override did not copy the current layer value");
        capture("Override", cases);
        reveal("settings/threshold");
        click(point(row("settings/threshold").slider(), .75));
        double overrideSlider = ((Operation) state.draft).overrides.get("threshold").doubleValue();
        require(
            overrideSlider >= -1 && overrideSlider <= 1 && overrideSlider != 2,
            "Placement slider failed to edit its own override");
        reveal("settings/threshold");
        type("0");
        require(
            ((Operation) state.draft).overrides.get("threshold").doubleValue() == 0,
            "Explicit zero was lost");
        require(
            layerApplied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Private placement edit mutated the layer or project");
        require(state.applyDraft(), "Override Apply rejected");
        save(out, "override");
        var overridden = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        save(out, "override-undone");
        require(
            layerApplied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Override was not one Undo");
        state.document.redo();
        state.revision++;
        save(out, "override-redone");
        require(
            overridden.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Override Redo changed data");
        state.refreshDraft();
        reveal("settings/threshold");
        click(panel.probeWorldControl("reset/threshold"));
        frames(4);
        require(
            ((Operation) state.draft).overrides.isEmpty(), "Reset stored a resolved layer value");
        require(
            overridden.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Reset published before Apply");
        require(state.applyDraft(), "Reset Apply rejected");
        save(out, "reset");
        var reset = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        state.revision++;
        save(out, "reset-undone");
        require(
            overridden.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Reset was not one Undo");
        state.document.redo();
        state.revision++;
        save(out, "reset-redone");
        require(
            reset.equals(ProjectIO.JSON.valueToTree(state.project())), "Reset Redo changed data");
        state.select("layer", layerId);
        reveal("settings/threshold");
        type("-0.5");
        require(state.applyDraft(), "Live layer edit rejected");
        save(out, "live");
        require(
            state
                .project()
                .worldGenerator
                .layers
                .getFirst()
                .operations
                .getFirst()
                .overrides
                .isEmpty(),
            "Live default edit wrote placement copies");
        state.user.save(out.resolve("preferences.json"));
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "World numeric edits changed preferences");
        Files.writeString(
            out.resolve("world-numeric-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "fontSource",
                    "synthetic-global-font-fixture",
                    "cases",
                    cases,
                    "layerSlider",
                    layerSlider,
                    "overrideSlider",
                    overrideSlider,
                    "integerTextRestored",
                    true,
                    "sparseInheritance",
                    true,
                    "resetUndoRedo",
                    true,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "WORLD_NUMERIC_NATIVE_PASS paired controls / exact integers / private overrides / reset"
                + " and live inheritance");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    p.project.minY = -8;
    p.project.maxY = 8;
    var n = new Noise();
    n.id = "one";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", 1.0);
    p.noise2D.add(n);
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var layer = new Layer();
    layer.id = "base";
    layer.defaults.threshold = .5;
    layer.defaults.action = "SET";
    layer.defaults.voxelTypeUuid = type.uuid;
    for (String kind : List.of("TRANSLATE", "SCALE", "ROTATE")) {
      var t = new SamplingTransform();
      t.kind = kind;
      if (kind.equals("SCALE")) t.x = t.y = t.z = 1;
      layer.defaults.samplingTransforms.add(t);
    }
    var interval = new TypeInterval();
    interval.voxelTypeUuid = type.uuid;
    layer.defaults.typeIntervals.add(interval);
    var driver = new NumericDriver();
    driver.destination = "transformX";
    driver.transformIndex = 0;
    driver.noiseGeneratorUuid = n.uuid;
    driver.outputMin = -4;
    driver.outputMax = 4;
    layer.defaults.parameterDrivers.add(driver);
    layer.defaults.fieldGates.kind = "COMPARE";
    layer.defaults.fieldGates.threshold = -1;
    var op = new Operation();
    op.id = "selection";
    op.noiseGeneratorUuid = n.uuid;
    op.interpretation = "SELECTION";
    layer.operations.add(op);
    p.worldGenerator.layers.add(layer);
  }

  private static void capture(String phase, List<Map<String, Object>> cases) {
    var project = ProjectIO.JSON.valueToTree(state.project());
    var draft = state.draft;
    var privateBefore = ProjectIO.JSON.valueToTree(draft);
    long revision = state.revision, history = state.document.historyBytes();
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] scales = {1, .5f, 1, 2.5f, 6.25f, 1};
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      scale = scales[i];
      frames(8);
      var rows = panel.probeNumericRows().values();
      require(
          phase.equals("Inherited") ? rows.isEmpty() : !rows.isEmpty(),
          "Wrong inheritance editability");
      cases.add(
          Map.of(
              "phase",
              phase,
              "scenario",
              i,
              "scale",
              scale,
              "body",
              panel.chrome.body(),
              "rows",
              rows));
    }
    require(
        state.draft == draft
            && privateBefore.equals(ProjectIO.JSON.valueToTree(draft))
            && project.equals(ProjectIO.JSON.valueToTree(state.project()))
            && revision == state.revision
            && history == state.document.historyBytes(),
        "Numeric reflow changed project/private/history");
    width = 1200;
    height = 900;
    scale = 1;
    frames(8);
  }

  private static void reveal(String parameter) {
    require(
        panel.goToProblem(
            new EditorEvents.Target(
                state.project().project.uuid, state.selected, null, parameter, "", false)),
        "World numeric route rejected");
    for (int i = 0; i < 20; i++) {
      frame();
      if (panel.probeFocusedProperty() != null) break;
    }
    require(panel.probeFocusedProperty() != null, "World numeric input was not focused");
    frames(3);
  }

  private static UiFields.NumericRow row(String key) {
    var p = panel.probeWorldControl(key);
    return panel.probeNumericRows().values().stream()
        .filter(
            r ->
                p[0] >= r.input().x()
                    && p[0] <= r.input().x() + r.input().width()
                    && p[1] >= r.input().y()
                    && p[1] <= r.input().y() + r.input().height())
        .findFirst()
        .orElseThrow();
  }

  private static void type(String text) {
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
  }

  private static float[] point(DialogBounds.Rect r, double fraction) {
    return new float[] {r.x() + (float) (r.width() * fraction), r.y() + r.height() / 2};
  }

  private static void click(float[] p) {
    var io = ImGui.getIO();
    io.addMousePosEvent(p[0], p[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    panel.draw();
    ImGui.render();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
