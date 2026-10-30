package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred production Application Settings reflow/input workflow; no GLFW or renderer. */
public final class SettingsLayoutProbe {
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";
  private static EditorState state;
  private static ApplicationSettingsPanel panel;
  private static int width = 1400, height = 1000;
  private static DialogBounds.Rect window;

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
        state.metadata = AppMetadata.defaults();
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
        var active = state.metadata;
        var file = out.resolve("app-config.json");
        active.save(file);
        byte[] original = Files.readAllBytes(file);
        panel = new ApplicationSettingsPanel(state);
        panel.showControl(VALUE, "Creation Defaults");
        frames(5);
        click(panel.controlAt("override"));
        type("1e-");
        require(
            !panel.applyEnabled() && panel.rawText(VALUE).equals(List.of("1e-")),
            "Invalid exact input was not retained");
        var ids = panel.controlIds();
        require(ids.size() == 2, "No scalar slider/text identities");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {360, 640}, {360, 640}, {720, 640}, {300, 240}, {1000, 300}};
        float[] global = {1, 1, 2.5f, 2.5f, 1, 1};
        float[] multiplier = {1, 1, 1, 2.5f, 1, 1};
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Application Settings", multiplier[i]);
          frames(5);
          var actions = panel.layout.actions();
          require(
              actions.keySet().equals(Set.of("apply", "revert", "builtins", "invalid-value")),
              "Missing persistent settings action");
          require(DialogBounds.contains(window, panel.layout.body()), "Body outside client");
          for (var action : actions.values())
            require(DialogBounds.contains(window, action), "Settings action outside client");
          require(
              panel.controlIds().equals(ids)
                  && panel.rawText(VALUE).equals(List.of("1e-"))
                  && !panel.applyEnabled(),
              "Reflow changed identity/raw input or enabled invalid Apply");
          long builds = panel.rowBuilds();
          frames(3);
          require(panel.rowBuilds() == builds, "Reflow rebuilt an unchanged row projection");
          click(panel.controlAt("apply"));
          require(
              Arrays.equals(original, Files.readAllBytes(file)) && state.metadata == active,
              "Invalid footer Apply published profile");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("window", window);
          receipt.put("body", panel.layout.body());
          receipt.put("actions", actions);
          receipt.put("panes", panel.panes());
          receipt.put("results", panel.resultsBounds());
          receipt.put("detail", panel.detailBounds());
          receipt.put("globalScale", global[i]);
          receipt.put("windowScale", multiplier[i]);
          receipt.put("ids", ids);
          receipt.put("raw", panel.rawText(VALUE));
          cases.add(receipt);
        }
        width = 1400;
        height = 1000;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.remove("Application Settings");
        frames(5);
        // The footer can navigate back to the bad control after it was hidden by a section change.
        panel.showControl("noise", "Presentation");
        frames(3);
        click(panel.controlAt("invalid-value"));
        frames(4);
        require(
            panel.rawText(VALUE).equals(List.of("1e-")) && panel.controlIds().equals(ids),
            "Show Invalid Value lost its original buffer or field identity");
        type("1e-9");
        require(panel.applyEnabled(), "Corrected value did not enable Apply");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click(panel.controlAt("apply"));
        width = 360;
        frames(5);
        require(
            state.metadata == active
                && panel.rawText(VALUE).equals(List.of("1e-9"))
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed save/reflow lost private input or published profile");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click(panel.controlAt("apply"));
        frames(3);
        require(
            state.metadata != active
                && AppMetadata.read(file).creationDefaults.get(VALUE).asDouble() == 1e-9,
            "Visible footer retry did not publish the corrected value");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Settings changed project/private Properties");
        Files.writeString(
            out.resolve("settings-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.of(
                    "formatVersion",
                    1,
                    "cases",
                    cases,
                    "invalidApplyBlocked",
                    true,
                    "hiddenInvalidNavigation",
                    true,
                    "writeRetry",
                    true,
                    "projectPreserved",
                    true,
                    "physicalDpiVerified",
                    false,
                    "physicalDetachedVerified",
                    false,
                    "gpuFontTextureVerified",
                    false)));
        System.out.println(
            "SETTINGS_LAYOUT_NATIVE_PASS measured panes/footer, invalid raw reflow,"
                + " stable scalar IDs, hidden input navigation and visible retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void type(String text) {
    click(panel.controlAt("value-0"));
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
  }

  private static void click(float[] point) {
    require(point != null, "Missing settings input target");
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
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("Application Settings", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "Application Settings");
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    panel.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
