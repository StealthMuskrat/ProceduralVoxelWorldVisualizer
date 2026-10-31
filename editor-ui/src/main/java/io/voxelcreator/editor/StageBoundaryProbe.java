package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred production stage selector without GLFW, rendering or asynchronous generation. */
public final class StageBoundaryProbe {
  private static EditorState state;
  private static StageBoundaryPicker panel;
  private static UUID current, fill, clear, disabled;
  private static String mode = "Before";
  private static int width = 1200, height = 900, expectedHost;
  private static float global = 1, multiplier = 1;
  private static boolean visible = true, fail;
  private static final List<Selection> selections = new ArrayList<>();

  private record Selection(String mode, UUID uuid) {}

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
        require(state.change(StageBoundaryProbe::fixture), "Fixture rejected");
        var file = out.resolve("stage-project.json");
        state.document.save(file);
        require(state.document.open(file).isEmpty(), "Fixture reopen failed");
        var layer = state.project().worldGenerator.layers.getFirst();
        fill = layer.operations.getFirst().uuid;
        clear = layer.operations.get(1).uuid;
        disabled = state.project().worldGenerator.layers.getLast().operations.getLast().uuid;
        current = fill;
        panel = new StageBoundaryPicker(state);
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        UUID privateSelection = state.selected;
        var draft = state.draft;
        ((Noise) draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var project = ProjectIO.JSON.valueToTree(state.project());
        var engine = state.engine;
        long revision = state.revision;
        require(
            panel.initial(null, clear).equals(clear)
                && panel.initial(null, state.selected).equals(layer.uuid)
                && panel.initial(clear, fill).equals(clear),
            "Default boundary substituted selection");
        var cases = new ArrayList<Map<String, Object>>();
        for (var preview : List.of("Before", "After", "Only Changes")) {
          mode = preview;
          frames(4);
          open();
          type("");
          frames(4);
          int all = panel.picker.submitted();
          require(all > 0 && all < panel.catalog().rows().size(), "Stage list is not clipped");
          type("target-clear");
          frames(4);
          capture(cases, all);
          escape();
        }
        dimensions(1200, 900, 1, 1);
        frames(4);
        state.user.save(out.resolve("preferences-before.json"));
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        mode = "Before";
        frames(4);
        open();
        type("target-clear");
        frames(3);
        press(option(clear));
        ImGui.getIO().addMouseButtonEvent(0, false);
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
        frame();
        ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
        frames(4);
        require(selections.isEmpty(), "Escape selected a boundary");
        // Preview mode is part of the opening, even when the same UUID is still selected.
        open();
        type("target-clear");
        frames(3);
        press(option(clear));
        mode = "After";
        frame();
        release();
        frames(3);
        require(selections.isEmpty() && current.equals(fill), "Held row crossed preview mode");
        open();
        type("target-clear");
        frames(3);
        var ids = panel.picker.rowIds();
        long evaluations = panel.picker.evaluations();
        press(option(clear));
        panel.picker.probeQuery(" TARGET-CLEAR ");
        frame();
        release();
        frames(3);
        require(
            selections.isEmpty()
                && !ids.equals(panel.picker.rowIds())
                && evaluations == panel.picker.evaluations(),
            "Raw query failed to retire held row");
        escape();
        open();
        type("target-clear");
        frames(3);
        press(option(clear));
        visible = false;
        frame();
        visible = true;
        frame();
        release();
        frames(3);
        require(selections.isEmpty(), "Hidden stage selector retained a gesture");
        open();
        type("target-clear");
        frames(3);
        press(option(clear));
        escape();
        open();
        move(option(clear));
        release();
        frames(3);
        require(selections.isEmpty(), "Reopened stage selector retained predecessor release");
        escape();
        open();
        type("target-clear");
        frames(3);
        press(option(clear));
        require(state.document.open(file).isEmpty(), "Same-UUID replacement rejected");
        frame();
        release();
        frames(3);
        require(selections.isEmpty(), "Boundary selection crossed document lifetime");
        // The selected missing UUID is never silently replaced by the first stage or Final.
        current = clear;
        frames(4);
        require(
            state.change(
                p ->
                    p.worldGenerator
                        .layers
                        .getFirst()
                        .operations
                        .removeIf(op -> op.uuid.equals(clear))),
            "Fixture boundary delete failed");
        frames(4);
        require(
            current.equals(clear)
                && panel.initial(current, fill).equals(clear)
                && !panel.available(mode, current)
                && panel.available("Final", current),
            "Deleted boundary silently became another preview");
        require(state.document.canUndo(), "Boundary delete has no Undo");
        state.document.undo();
        state.revision++;
        frames(4);
        require(panel.available(mode, current), "Undo did not restore exact boundary UUID");
        // Empty and disabled pipeline stages remain distinct from an unavailable reference.
        state.document.newProject();
        frames(4);
        require(
            panel.catalog().rows().isEmpty()
                && panel.initial(null, fill) == null
                && !panel.available(mode, current)
                && panel.available("Final", null),
            "Empty pipeline invented a boundary");
        require(state.document.open(file).isEmpty(), "Protected fixture restore failed");
        current = fill;
        frames(4);
        for (var preview : List.of("Before", "After", "Only Changes")) {
          mode = preview;
          current = fill;
          frames(4);
          open();
          type("target-clear");
          frames(3);
          click(option(clear));
          require(current.equals(clear), "Explicit stage route failed");
        }
        mode = "Before";
        current = fill;
        frames(4);
        open();
        type("target-clear");
        frames(3);
        fail = true;
        click(option(clear));
        fail = false;
        frames(4);
        require(
            current.equals(fill)
                && !panel.picker.feedback().isEmpty()
                && panel.picker.query().equals("target-clear"),
            "Failed route lost owned retry");
        click(option(clear));
        require(current.equals(clear), "Explicit stage retry failed");
        mode = "After";
        frames(4);
        open();
        type(disabled.toString());
        frames(3);
        click(option(disabled));
        require(
            current.equals(disabled)
                && panel.available(mode, current)
                && !panel.catalog().get(current).enabled(),
            "Disabled stage is not selectable");
        require(selections.size() == 5, "Unexpected boundary delivery count");
        int builds = panel.builds();
        frames(12);
        require(panel.builds() == builds, "Unchanged stage catalog rebuilt every frame");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && Objects.equals(state.selected, privateSelection)
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.revision == revision + 2
                && !state.document.dirty()
                && state.document.historyBytes() == 0
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user))
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Stage selection changed protected project/private/preferences/runtime state");
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        Files.writeString(
            out.resolve("stage-events.json"),
            ProjectIO.JSON.writeValueAsString(state.events.view()));
        Files.writeString(
            out.resolve("stage-boundaries-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("catalog", panel.catalog().rows()),
                    Map.entry("fill", fill),
                    Map.entry("clear", clear),
                    Map.entry("disabled", disabled),
                    Map.entry("selections", selections),
                    Map.entry("escapePreserved", true),
                    Map.entry("modeQueryLifetimeScoped", true),
                    Map.entry("hiddenReopenScoped", true),
                    Map.entry("documentLifetimeScoped", true),
                    Map.entry("missingUndoRestored", true),
                    Map.entry("emptyFinalDistinct", true),
                    Map.entry("disabledSelectable", true),
                    Map.entry("callbacksBalanced", true),
                    Map.entry("failureRetryScoped", true),
                    Map.entry("catalogCached", true),
                    Map.entry("privatePreserved", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("gpuPreviewVerified", false))));
        System.out.println(
            "STAGE_BOUNDARIES_NATIVE_PASS searchable clipped boundaries / owned routes / missing"
                + " Undo recovery");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var a = new VoxelType();
    a.id = "a";
    var b = new VoxelType();
    b.id = "b";
    p.voxelTypes.addAll(List.of(a, b));
    var noise = new Noise();
    noise.id = "constant";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", 1.);
    p.noise2D.add(noise);
    for (int i = 0; i < 48; i++) {
      var layer = new Layer();
      layer.id = i == 0 ? "pipeline" : "disabled-layer-" + i;
      layer.enabled = i == 0;
      layer.defaults.action = "SET";
      layer.defaults.voxelTypeUuid = a.uuid;
      for (int j = 0; j < 4; j++) {
        var op = new Operation();
        op.id =
            i == 0
                ? List.of("fill", "target-clear", "replace", "inactive").get(j)
                : "op-" + i + "-" + j;
        op.noiseGeneratorUuid = noise.uuid;
        op.interpretation = "SELECTION";
        if (i == 0 && j == 1) op.overrides.put("action", ProjectIO.JSON.valueToTree("CLEAR"));
        if (i == 0 && j == 2) op.overrides.put("voxelTypeUuid", ProjectIO.JSON.valueToTree(b.uuid));
        if (i == 0 && j == 3) op.enabled = false;
        layer.operations.add(op);
      }
      p.worldGenerator.layers.add(layer);
    }
  }

  private static float[] option(UUID id) {
    return panel.picker.option(id.toString());
  }

  private static void open() {
    frames(3);
    panel.picker.probeOpen();
    frames(4);
  }

  private static void type(String value) {
    var r = panel.picker.layout.queryBounds();
    click(new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2});
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, true);
    frame();
    io.addKeyEvent(ImGuiKey.Backspace, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
  }

  private static void capture(List<Map<String, Object>> cases, int all) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    var ids = panel.picker.rowIds();
    for (int i = 0; i < sizes.length; i++) {
      dimensions(sizes[i][0], sizes[i][1], globals[i], scales[i]);
      frames(7);
      require(
          ids.equals(panel.picker.rowIds()) && panel.picker.submitted() == 1,
          "Stage reflow changed row identity");
      cases.add(
          Map.ofEntries(
              Map.entry("mode", mode),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("popup", panel.picker.layout.bounds()),
              Map.entry("query", panel.picker.layout.queryBounds()),
              Map.entry("list", panel.picker.layout.listBounds()),
              Map.entry("ids", panel.picker.rowIds()),
              Map.entry("allSubmitted", all),
              Map.entry("rows", panel.picker.submitted()),
              Map.entry("stride", panel.picker.rowStride())));
    }
  }

  private static void dimensions(int w, int h, float g, float m) {
    width = w;
    height = h;
    global = g;
    multiplier = m;
  }

  private static void move(float[] point) {
    ImGui.getIO().addMousePosEvent(point[0], point[1]);
    frame();
  }

  private static void press(float[] point) {
    move(point);
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(float[] point) {
    press(point);
    release();
    frames(4);
  }

  private static void escape() {
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, true);
    frame();
    ImGui.getIO().addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Stage boundaries");
    ImGui.setWindowFontScale(multiplier);
    expectedHost = ImGui.getID("stage-parent-sentinel");
    if (visible)
      panel.draw(
          mode,
          current,
          id -> {
            require(
                ImGui.getID("stage-parent-sentinel") == expectedHost,
                "Stage callback ran within popup native scopes");
            if (fail) throw new IllegalStateException("stage route boundary");
            selections.add(new Selection(mode, id));
            current = id;
          });
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
