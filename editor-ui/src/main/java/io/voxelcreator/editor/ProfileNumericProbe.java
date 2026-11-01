package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;

/** Deferred profile slider precision, retained raw validation, presentation order and creation. */
public final class ProfileNumericProbe {
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";
  private static final String VECTOR = "processing.DOT.input.a";
  private static final String INTEGER = "processing.FBM.parameter.octaves";
  private static ApplicationSettingsPanel panel;
  private static int width = 1400, height = 1000;
  private static float scale = 1;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDeltaTime(1f / 60);
      UiFontAtlas.install();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      try (var state = new EditorState(new UserConfig(), false)) {
        require(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "existing";
                  n.algorithm.key = "CONSTANT";
                  n.algorithm.parameters.clear();
                  n.algorithm.parameters.put("value", .125);
                  p.noise2D.add(n);
                }),
            "Fixture rejected");
        state.document.save(out.resolve("before.json"));
        state.user.save(out.resolve("preferences-before.json"));
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        var preferences = ProjectIO.JSON.valueToTree(state.user);
        long revision = state.revision, history = state.document.historyBytes();
        var original = state.metadata;
        original.save(out.resolve("profile-before.json"));
        original.save(out.resolve("app-config.json"));
        panel = new ApplicationSettingsPanel(state);
        show(VALUE, "Presentation");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {360, 640}, {720, 640}};
        float[] scales = {1, 2.5f, 6.25f};
        var ids = panel.controlIds();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          scale = scales[i];
          frames(6);
          require(ids.equals(panel.controlIds()), "Presentation order IDs changed during reflow");
          cases.add(
              Map.of(
                  "scenario",
                  i,
                  "scale",
                  scale,
                  "detail",
                  panel.detailBounds(),
                  "row",
                  panel.probeOrderRow()));
        }
        width = 1400;
        height = 1000;
        scale = 1;
        show("noise", "Presentation");
        show(VALUE, "Presentation");
        var orderRow = panel.probeOrderRow();
        click(point(orderRow.slider(), .8));
        typeAt(panel.controlAt("order-value"), "10001");
        require(!panel.applyEnabled(), "Invalid presentation order enabled Apply");
        typeAt(panel.controlAt("order-value"), "-17");
        require(panel.applyEnabled(), "Corrected presentation order remained invalid");
        show(VALUE, "Creation Defaults");
        click(panel.controlAt("override"));
        click(fraction("slider-0", .371));
        double scalar = panel.defaultValue(VALUE).doubleValue();
        String scalarText = panel.rawText(VALUE).getFirst();
        require(
            scalar != Math.rint(scalar * 100000) / 100000
                && Double.doubleToRawLongBits(scalar)
                    == Double.doubleToRawLongBits(Double.parseDouble(scalarText)),
            "Creation scalar slider rounded before the exact raw ledger");
        typeAt(panel.controlAt("value-0"), "1e-");
        var scalarBefore = panel.defaultValue(VALUE).deepCopy();
        click(fraction("slider-0", .8));
        require(
            panel.rawText(VALUE).equals(List.of("1e-"))
                && panel.defaultValue(VALUE).equals(scalarBefore)
                && !panel.applyEnabled(),
            "Invalid raw scalar was replaced by its slider");
        typeAt(panel.controlAt("value-0"), scalarText);
        show(VECTOR, "Creation Defaults");
        click(panel.controlAt("override"));
        click(fraction("slider-0", .413));
        double vectorSlider = panel.defaultValue(VECTOR).get(0).doubleValue();
        require(
            vectorSlider != Math.rint(vectorSlider * 100000) / 100000,
            "Vector slider rounded its readout");
        typeAt(panel.controlAt("value-1"), "-");
        var vectorBefore = panel.defaultValue(VECTOR).deepCopy();
        var rawBefore = panel.rawText(VECTOR);
        click(fraction("slider-0", .8));
        require(
            rawBefore.equals(panel.rawText(VECTOR))
                && vectorBefore.equals(panel.defaultValue(VECTOR)),
            "Another invalid component did not block the vector slider");
        typeAt(panel.controlAt("value-0"), "3.5");
        typeAt(panel.controlAt("value-1"), "2.25");
        typeAt(panel.controlAt("value-2"), "9");
        show(INTEGER, "Creation Defaults");
        click(panel.controlAt("override"));
        click(fraction("slider-0", .8));
        require(
            panel.defaultValue(INTEGER).isIntegralNumber(),
            "Integer slider stored a fractional node");
        typeAt(panel.controlAt("value-0"), "7");
        require(state.metadata == original, "Private profile edits published before Apply");
        click(panel.controlAt("apply"));
        frames(4);
        var saved = AppMetadata.read(out.resolve("app-config.json"));
        require(
            saved.controls.get(VALUE).order == -17
                && saved.creationDefaults.get(VALUE).doubleValue() == scalar
                && saved.creationDefaults.get(INTEGER).intValue() == 7,
            "Saved numeric profile differs from its controls");
        require(
            EditorCreations.processing(saved, "FBM", "future-fractal", 3)
                    .parameters
                    .get("octaves")
                    .intValue()
                == 7,
            "Future integer creation did not use the saved default");
        show(VALUE, "Creation Defaults");
        var oldIds = panel.controlIds();
        panel.probeRevert();
        frames(4);
        require(
            !oldIds.equals(panel.controlIds()),
            "Replacing the private profile reused old numeric IDs");
        var created = new ProjectData();
        created.noise2D.add(EditorCreations.noise(saved, 2, "future-flat", null, "CONSTANT"));
        var dot = EditorCreations.noise(saved, 3, "future-dot", null, "CONSTANT");
        var item = EditorCreations.processing(saved, "DOT", "dot", 3);
        item.inputs.get("b").value = ProjectIO.JSON.valueToTree(new double[] {1, 2, 3});
        dot.processing.add(item);
        dot.outputBinding = Binding.earlier(item.uuid);
        created.noise3D.add(dot);
        new ProjectIO().save(out.resolve("created.json"), created);
        new ProjectIO().save(out.resolve("after.json"), state.project());
        state.user.save(out.resolve("preferences.json"));
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && revision == state.revision
                && history == state.document.historyBytes()
                && !state.document.dirty()
                && state.seedInputs.text("seedOffset").equals("-")
                && preferences.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Application numeric controls changed project/private/preferences state");
        Files.writeString(
            out.resolve("profile-numeric-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "scalar",
                    scalar,
                    "scalarText",
                    scalarText,
                    "vectorSlider",
                    vectorSlider,
                    "invalidRawGuarded",
                    true,
                    "integerStored",
                    true,
                    "ownerIdsReplaced",
                    true,
                    "physicalDpiVerified",
                    false)));
        System.out.println(
            "PROFILE_NUMERIC_NATIVE_PASS exact sliders / retained invalid components / order /"
                + " future defaults");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void show(String id, String page) {
    panel.showControl(id, page);
    frames(5);
  }

  private static float[] point(DialogBounds.Rect r, double fraction) {
    return new float[] {r.x() + (float) (r.width() * fraction), r.y() + r.height() / 2};
  }

  private static float[] fraction(String key, double fraction) {
    return point(panel.probeControlBounds(key), fraction);
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

  private static void typeAt(float[] p, String text) {
    click(p);
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

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(scale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    if (ImGui.begin("Application Settings")) panel.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
