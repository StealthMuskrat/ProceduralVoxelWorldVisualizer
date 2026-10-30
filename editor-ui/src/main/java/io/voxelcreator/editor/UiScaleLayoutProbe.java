package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred production Appearance reflow/input workflow with explicit synthetic font magnification.
 */
public final class UiScaleLayoutProbe {
  private static EditorState state;
  private static UiScalePanel scales;
  private static AppearanceSettingsPanel appearance;
  private static final UiFields fields = new UiFields();
  private static int width = 1400, height = 1000, saves, opens, errors;
  private static float fontGlobal = 1, fontWindow = 1;
  private static DialogBounds.Rect window, category;

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
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var draft = (Noise) state.draft;
        draft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        state.user.appearance.windowScales.put("Future Window", 1.75f);
        var file = out.resolve("user-config.json");
        state.user.save(file);
        scales = new UiScalePanel(state.user, file, message -> errors++);
        scales.probeControls();
        appearance =
            new AppearanceSettingsPanel(
                state.user,
                scales,
                () -> ThemeStyle.apply(ThemePalette.from(state.user.appearance)),
                () -> opens++,
                () -> {
                  try {
                    state.user.save(file);
                    state.user.save(out.resolve("ordinary-preferences.json"));
                    saves++;
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                });
        frames(5);
        type("Global", "+1.25");
        select("Help");
        click(scales.probeControl("override"));
        type("Help", "-");
        select("Logs");
        click(scales.probeControl("override"));
        type("Logs", "2.000");
        var raw = scales.probeText();
        var ids = scales.probeIds();
        require(
            !scales.probeApplyEnabled() && ids.size() == 4, "Hidden error or input IDs missing");
        click(appearance.controlAt("save"));
        var ordinary = UserConfig.read(file);
        require(
            saves == 1
                && ordinary.appearance.globalScale == 1
                && ordinary.appearance.windowScales.equals(Map.of("Future Window", 1.75f))
                && scales.probeText().equals(raw),
            "Ordinary save applied private scale input");
        click(appearance.controlAt("themes"));
        require(
            opens == 1 && scales.probeText().equals(raw),
            "Theme navigation replaced pending scales");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1};
        float[] windows = {1, 1, 1, 1, 2.5f, 1};
        long validations = scales.validations();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          fontGlobal = globals[i];
          fontWindow = windows[i];
          frames(5);
          var actions = scales.layout.actions();
          require(
              actions.keySet().equals(Set.of("apply", "revert", "invalid")),
              "Missing scale footer action");
          require(
              DialogBounds.contains(window, category)
                  && DialogBounds.contains(window, scales.layout.body()),
              "Category/body outside client");
          for (var action : actions.values())
            require(DialogBounds.contains(window, action), "Scale action outside client");
          require(
              scales.probeText().equals(raw)
                  && scales.probeIds().equals(ids)
                  && !scales.probeApplyEnabled()
                  && scales.validations() == validations,
              "Reflow changed raw input/IDs/feedback or enabled invalid Apply");
          click(scales.probeControl("apply"));
          require(
              state.user.appearance.globalScale == 1 && scales.probeText().equals(raw),
              "Invalid footer Apply published");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("window", window);
          receipt.put("category", category);
          receipt.put("body", scales.layout.body());
          receipt.put("actions", actions);
          receipt.put("targets", scales.targetBounds());
          receipt.put("targetRowHeight", scales.targetRowHeight());
          receipt.put("fontGlobalScale", fontGlobal);
          receipt.put("fontWindowScale", fontWindow);
          receipt.put("rows", scales.probeRows());
          receipt.put("ids", scales.probeIds());
          receipt.put("raw", scales.probeText());
          cases.add(receipt);
        }
        // Reveal operates from the fixed footer even when the offending field is hidden by
        // filtering.
        click(scales.probeControl("invalid"));
        frames(5);
        require(
            scales.probeSelected().equals("Help")
                && scales.probeText().equals(raw)
                && scales.validations() == validations,
            "Problem navigation changed private input");
        width = 1400;
        height = 1000;
        fontGlobal = fontWindow = 1;
        frames(6);
        type("Help", "1.500");
        require(scales.probeApplyEnabled(), "Corrected scale did not enable Apply");
        var corrected = scales.probeText();
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click(scales.probeControl("apply"));
        width = 720;
        height = 640;
        fontGlobal = fontWindow = 2.5f;
        frames(5);
        require(
            errors == 1
                && state.user.appearance.globalScale == 1
                && scales.probeText().equals(corrected)
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed write/reflow changed private input or active scales");
        state.user.workspace.width = 1234;
        state.user.appearance.windowScales.put("Future Window", 2f);
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click(scales.probeControl("apply"));
        frames(3);
        var saved = UserConfig.read(file);
        require(
            saved.appearance.globalScale == 1.25f
                && saved.appearance.windowScales.equals(
                    Map.of("Help", 1.5f, "Logs", 2f, "Future Window", 2f))
                && saved.workspace.width == 1234
                && !scales.probeApplyEnabled(),
            "Footer retry lost exact values, inheritance or unrelated preferences");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Appearance changed project/private Properties");
        Files.writeString(
            out.resolve("ui-scale-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic"),
                    Map.entry("cases", cases),
                    Map.entry("ordinarySaveExcludedDraft", true),
                    Map.entry("problemNavigationPreserved", true),
                    Map.entry("cachedFeedback", true),
                    Map.entry("writeRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("saveCallbacks", saves),
                    Map.entry("themeCallbacks", opens),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "UI_SCALE_LAYOUT_NATIVE_PASS Appearance body/footer, stable raw fields,"
                + " hidden error reveal, ordinary-save exclusion and failed-write retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void select(String name) {
    type("filter", name);
    click(scales.probeControl("target-" + name));
  }

  private static void type(String key, String text) {
    click(scales.probeControl(key));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    click(scales.probeControl("blur"));
  }

  private static void click(float[] point) {
    require(point != null, "Missing Appearance input target");
    var io = ImGui.getIO();
    io.addMousePosEvent(point[0], point[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(3);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(fontGlobal);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
    // Magnification is a fixture input, independent of the private/active scale ownership checks.
    ImGui.setWindowFontScale(fontWindow);
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    AppearanceSettingsPanel.category(
        fields,
        "Appearance",
        value -> {
          if (!value.equals("Appearance"))
            throw new AssertionError("Unexpected fixture category change");
        });
    category = PopupLayout.itemBounds();
    appearance.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
