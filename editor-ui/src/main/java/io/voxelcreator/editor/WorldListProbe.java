package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.node.ArrayNode;
import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred native list controls; no normal startup path invokes this probe. */
public final class WorldListProbe {
  private static EditorState state;
  private static PropertyPanel panel;
  private static String property = "samplingTransforms";
  private static int width = 1200, height = 1800;
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
        require(state.change(WorldListProbe::fixture), "List fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        var canonical = ProjectIO.JSON.valueToTree(state.project());
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        state.select("layer", state.project().worldGenerator.layers.getFirst().uuid);
        panel = new PropertyPanel(state);
        var privateBefore = ProjectIO.JSON.valueToTree(state.draft);
        long revision = state.revision, history = state.document.historyBytes();
        var receipts = new ArrayList<Map<String, Object>>();
        for (String name : List.of("samplingTransforms", "parameterDrivers", "typeIntervals")) {
          property = name;
          for (int scenario = 0; scenario < 3; scenario++) {
            width = scenario == 1 ? 360 : 1200;
            height = scenario == 2 ? 320 : 900;
            scale = scenario == 1 ? 1.5f : 1;
            frames(5);
            var controls = panel.probeWorldListControls();
            var ids = new HashSet<Integer>();
            for (var c : controls.values()) {
              require(ids.add(c.id()), "Duplicate native action ID");
              require(c.bounds().width() > 0 && c.bounds().height() > 0, "Empty action geometry");
              require(
                  c.bounds().x() >= 16 && c.bounds().x() + c.bounds().width() <= 16 + width,
                  "Action exceeded horizontal window bounds");
            }
            require(!controls.get(name + "/0/up").enabled(), "First row Up was enabled");
            require(!controls.get(name + "/1/down").enabled(), "Last row Down was enabled");
            require(
                controls.containsKey(name + "/0/duplicate") != name.equals("typeIntervals"),
                "Wrong duplicate affordance");
            String leaf =
                name.equals("samplingTransforms")
                    ? "x"
                    : name.equals("typeIntervals") ? "min" : "outputMin";
            var first = panel.probeWorldControl("settings/" + name + "/0/" + leaf);
            var second = panel.probeWorldControl("settings/" + name + "/1/" + leaf);
            require(
                first != null && second != null && first[1] != second[1], "Indexed controls alias");
            receipts.add(
                Map.of(
                    "property",
                    name,
                    "scenario",
                    scenario,
                    "width",
                    width,
                    "height",
                    height,
                    "scale",
                    scale,
                    "controls",
                    controls,
                    "numbers",
                    panel.probeNumericRows().values()));
          }
        }
        require(
            privateBefore.equals(ProjectIO.JSON.valueToTree(state.draft))
                && canonical.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && !state.document.dirty(),
            "Idle layouts edited the document");
        property = "samplingTransforms";
        width = 1200;
        height = 1800;
        scale = 1;
        frames(5);
        var rows = ((Layer) state.draft).defaults.samplingTransforms;
        var original = rows.getFirst();
        int oldId = control("samplingTransforms/0/duplicate").id();
        require(
            state
                .seedInputs
                .edit(state, "settings/fieldGates/seedOffset", "Gate seed", "-")
                .isEmpty(),
            "Invalid seed unexpectedly parsed");
        press("samplingTransforms/0/duplicate");
        frames(4);
        require(rows.size() == 2, "Button committed before its release");
        release();
        require(
            rows.size() == 3 && rows.getFirst() == original && rows.get(1) != original,
            "Actual duplicate did not retain/copy the source");
        require(
            control("samplingTransforms/0/duplicate").id() != oldId, "Retired action ID reused");
        click("samplingTransforms/1/down");
        require(
            rows.get(1).kind.equals("SCALE") && rows.getLast().kind.equals("TRANSLATE"),
            "Actual move did not preserve ordered rows");
        click("samplingTransforms/2/remove");
        require(
            rows.size() == 2 && rows.getFirst() == original && rows.getLast().kind.equals("SCALE"),
            "Actual removal targeted a successor or also activated Add");
        property = "parameterDrivers";
        frames(4);
        click("parameterDrivers/0/duplicate");
        require(
            ((Layer) state.draft).defaults.parameterDrivers.size() == 3, "Driver duplicate failed");
        require(
            canonical.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && !state.document.dirty(),
            "List action escaped private draft");
        var expectedPrivate = privateBefore.deepCopy();
        var expectedDrivers = (ArrayNode) expectedPrivate.path("defaults").path("parameterDrivers");
        expectedDrivers.insert(1, expectedDrivers.get(0).deepCopy());
        require(
            expectedPrivate.equals(ProjectIO.JSON.valueToTree(state.draft)),
            "Unexpected private edit outside the duplicated driver");
        var expectedProject = canonical.deepCopy();
        var canonicalDrivers =
            (ArrayNode)
                expectedProject
                    .path("worldGenerator")
                    .path("layers")
                    .get(0)
                    .path("defaults")
                    .path("parameterDrivers");
        canonicalDrivers.insert(1, canonicalDrivers.get(0).deepCopy());
        require(!state.applyDraft(), "List edit discarded an unrelated invalid seed");
        var problem = state.seedInputs.problems(state).getFirst();
        require(
            !problem.orphan() && problem.text().equals("-"), "Unchanged gate input was moved/lost");
        state.seedInputs.discard(state, problem.id());
        require(state.applyDraft(), "List Apply rejected");
        save(out, "applied");
        var applied = ProjectIO.JSON.valueToTree(state.project());
        require(expectedProject.equals(applied), "Apply changed the complete project unexpectedly");
        state.document.undo();
        require(
            canonical.equals(ProjectIO.JSON.valueToTree(state.project()))
                && !state.document.dirty(),
            "One Undo did not restore the complete saved document");
        save(out, "undone");
        state.document.redo();
        require(
            applied.equals(ProjectIO.JSON.valueToTree(state.project())),
            "Redo changed the complete document");
        save(out, "redone");
        require(
            preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "List actions edited preferences");
        state.user.save(out.resolve("preferences-after.json"));
        Files.writeString(
            out.resolve("controls.json"), ProjectIO.JSON.writeValueAsString(receipts));
        System.out.println("WORLD_LIST_NATIVE_PASS");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void fixture(ProjectData p) {
    var type = new VoxelType();
    type.id = "stone";
    p.voxelTypes.add(type);
    var noise = new Noise();
    noise.id = "field";
    p.noise2D.add(noise);
    var layer = new Layer();
    layer.id = "layer";
    var translation = new SamplingTransform();
    translation.x = 3;
    var scale = new SamplingTransform();
    scale.kind = "SCALE";
    scale.x = scale.y = scale.z = 2;
    layer.defaults.samplingTransforms.addAll(List.of(translation, scale));
    for (int i = 0; i < 2; i++) {
      var driver = new NumericDriver();
      driver.enabled = true;
      driver.outputMin = driver.outputMax = i + 1;
      layer.defaults.parameterDrivers.add(driver);
      var interval = new TypeInterval();
      interval.min = i - 1;
      interval.max = i;
      interval.voxelTypeUuid = type.uuid;
      layer.defaults.typeIntervals.add(interval);
    }
    layer.defaults.fieldGates.kind = "PROBABILITY";
    p.worldGenerator.layers.add(layer);
  }

  private static PropertyPanel.WorldListControl control(String key) {
    return Objects.requireNonNull(panel.probeWorldListControls().get(key), key);
  }

  private static void press(String key) {
    var bounds = control(key).bounds();
    ImGui.getIO()
        .addMousePosEvent(bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2);
    frame();
    ImGui.getIO().addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frames(4);
  }

  private static void click(String key) {
    press(key);
    release();
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
    boolean open = ImGui.begin("World list production controls", ImGuiWindowFlags.NoSavedSettings);
    try {
      if (open) panel.probeWorldListPanel(property);
    } finally {
      ImGui.end();
    }
    ImGui.render();
  }

  private static void save(Path out, String name) throws Exception {
    new ProjectIO().save(out.resolve(name + ".json"), state.project());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
