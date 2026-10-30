package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.editor.PerformancePreferences.Field;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred three-form preference layout/input/retry workflow; no GLFW, renderer or update client.
 */
public final class PreferencesLayoutProbe {
  private static EditorState state;
  private static PerformancePanel limits;
  private static UpdateTrustPanel trust;
  private static WorkspacePanel workspace;
  private static PerformanceSettingsPage page;
  private static final UiFields fields = new UiFields();
  private static final Map<String, ImBoolean> tools = new LinkedHashMap<>();
  private static int width = 1400,
      height = 1200,
      saves,
      defaults,
      centers,
      changed,
      limitErrors,
      trustErrors;
  private static String mode = "limits";
  private static DialogBounds.Rect window, category;
  private static Path out, file;
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final String ORIGINS =
      "HTTPS://UPDATES.EXAMPLE.TEST:443/\n"
          + "https://releases.example.test\n"
          + "https://updates.example.test";

  public static void main(String[] args) throws Exception {
    out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    file = out.resolve("user-config.json");
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
        var engine = state.engine;
        WorkspaceVisibility.WINDOWS.forEach(name -> tools.put(name, new ImBoolean(true)));
        captureVisibility();
        state.user.save(file);
        limits = new PerformancePanel(state.user, file, ignored -> limitErrors++);
        limits.probeControls();
        trust = new UpdateTrustPanel(state.user, file, ignored -> trustErrors++);
        trust.probeControls();
        page = new PerformanceSettingsPage(limits, trust);
        workspace =
            new WorkspacePanel(
                state.user,
                tools,
                () -> defaults++,
                () -> {
                  changed++;
                  captureVisibility();
                },
                () -> centers++);
        workspace.probeControls();
        show("limits");
        type("previewCellBudget", Long.toString(Long.MAX_VALUE));
        type("renderDistance", "-");
        var rawLimits = limits.probeText();
        show("trust");
        type("endpoint", "http://updates.example.test/meta.json");
        type("origins", ORIGINS);
        var rawTrust = trust.probeText();
        show("workspace");
        click("Logs");
        click("save-preferences");
        require(
            saves == 1 && changed == 1 && !tools.get("Logs").get(),
            "Workspace callbacks/visibility failed");
        var ordinary = UserConfig.read(file);
        require(
            ordinary.performance.previewCellBudget != Long.MAX_VALUE
                && ordinary.performance.updateEndpoint.isEmpty()
                && limits.probeText().equals(rawLimits)
                && trust.probeText().equals(rawTrust),
            "Ordinary save applied a hidden form");
        var visibility = visibility();
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1200}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, .5f, 1, 2.5f, 2.5f, 1};
        float[] multiplier = {1, 1, 1, 1, 2.5f, 1};
        Map<Field, Integer> limitIds = null;
        Map<String, Integer> trustIds = null;
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("User Settings", multiplier[i]);
          for (String form : List.of("limits", "trust", "workspace")) {
            show(form);
            if (form.equals("limits")) {
              click("problem");
              frames(5);
            }
            if (form.equals("trust")) {
              click("fields");
              frames(5);
            }
            var chrome = chrome();
            require(
                DialogBounds.contains(window, category)
                    && DialogBounds.contains(window, chrome.body()),
                "Header/body outside client");
            for (var action : chrome.actions().values())
              require(DialogBounds.contains(window, action), "Preference action outside client");
            require(
                limits.probeText().equals(rawLimits)
                    && trust.probeText().equals(rawTrust)
                    && !limits.probeApplyEnabled()
                    && !trust.probeApplyEnabled()
                    && visibility().equals(visibility),
                "Reflow/section navigation changed pending/active values");
            long lv = limits.validations(), tv = trust.validations();
            frames(3);
            require(
                lv == limits.validations() && tv == trust.validations(),
                "Unchanged private validation rebuilt");
            var receipt = new LinkedHashMap<String, Object>();
            if (!form.equals("workspace"))
              require(
                  page.section()
                      .equals(form.equals("limits") ? "Performance limits" : "Update trust"),
                  "Selected performance section did not draw its retained form");
            receipt.put("scenario", i);
            receipt.put("form", form);
            receipt.put("window", window);
            receipt.put("category", category);
            receipt.put("body", chrome.body());
            receipt.put("actions", chrome.actions());
            receipt.put("globalScale", global[i]);
            receipt.put("windowScale", multiplier[i]);
            receipt.put("limits", limits.probeText());
            receipt.put("trust", trust.probeText());
            receipt.put("visibility", visibility());
            if (form.equals("limits")) {
              if (limitIds == null) limitIds = limits.fieldIds();
              require(limits.fieldIds().equals(limitIds), "Performance input IDs changed");
              receipt.put("ids", limits.fieldIds());
              receipt.put("selector", page.selector());
              receipt.put("section", page.section());
            } else if (form.equals("trust")) {
              if (trustIds == null) trustIds = trust.fieldIds();
              require(trust.fieldIds().equals(trustIds), "Trust input IDs changed");
              receipt.put("ids", trust.fieldIds());
              receipt.put("selector", page.selector());
              receipt.put("section", page.section());
              receipt.put(
                  "inputs",
                  Map.of(
                      "endpoint",
                      trust.bounds().get("endpoint"),
                      "origins",
                      trust.bounds().get("origins")));
            }
            cases.add(receipt);
          }
        }
        width = 1400;
        height = 1200;
        state.user.appearance.globalScale = 1.25f;
        state.user.appearance.windowScales.remove("User Settings");
        show("limits");
        click("problem");
        frames(4);
        type("renderDistance", "3");
        require(limits.probeApplyEnabled(), "Corrected limit did not enable Apply");
        blockFile();
        click("apply");
        show("trust");
        require(
            limitErrors == 1
                && trust.probeText().equals(rawTrust)
                && limits.probeText().get(Field.PREVIEW_CELLS).equals(Long.toString(Long.MAX_VALUE))
                && Files.readString(file.resolve("keep")).equals("original"),
            "Limit write failure/section switch lost draft");
        unblockFile();
        show("limits");
        click("apply");
        require(
            state.user.performance.previewCellBudget == Long.MAX_VALUE
                && state.user.performance.renderDistance == 3
                && state.user.performance.updateEndpoint.isEmpty(),
            "Limit Apply changed trust or lost exact long");
        show("trust");
        type("endpoint", ENDPOINT);
        require(trust.probeApplyEnabled(), "Corrected endpoint did not enable Apply");
        blockFile();
        click("apply");
        width = 360;
        height = 640;
        frames(5);
        require(
            trustErrors == 1
                && trust.probeText().endpoint().equals(ENDPOINT)
                && trust.probeText().origins().equals(ORIGINS)
                && state.user.performance.updateEndpoint.isEmpty(),
            "Trust write failure/reflow lost private input");
        state.user.workspace.width = 1234;
        state.user.appearance.theme = "Light";
        frames(3);
        unblockFile();
        click("apply");
        var saved = UserConfig.read(file);
        require(
            saved.performance.previewCellBudget == Long.MAX_VALUE
                && saved.performance.renderDistance == 3
                && saved.performance.updateEndpoint.equals(ENDPOINT)
                && saved.performance.trustedOrigins.equals(
                    List.of("https://updates.example.test", "https://releases.example.test"))
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light"),
            "Independent Apply/retry lost settings");
        show("workspace");
        click("defaults");
        click("center");
        require(
            defaults == 1 && centers == 1 && visibility().equals(visibility),
            "Workspace action routing changed tool flags");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-")
                && state.engine == engine
                && state.engine.profiler().current().isEmpty(),
            "Preference UI changed project/private input/runtime owner");
        Files.writeString(
            out.resolve("preferences-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("ordinarySaveExcludedDrafts", true),
                    Map.entry("independentWriteRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("runtimeOwnerPreserved", true),
                    Map.entry("defaultCallbacks", defaults),
                    Map.entry("centerCallbacks", centers),
                    Map.entry("physicalCenterVerified", false),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PREFERENCES_LAYOUT_NATIVE_PASS persistent form actions, private section retention,"
                + " exact long/trust retry and workspace callbacks; no physical center/DPI claim");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void show(String value) {
    mode = value;
    if (value.equals("limits")) page.showLimits();
    else if (value.equals("trust")) page.showUpdates();
    frames(5);
  }

  private static SettingsPageChrome chrome() {
    return mode.equals("limits")
        ? limits.layout
        : mode.equals("trust") ? trust.layout : workspace.layout;
  }

  private static float[] at(String key) {
    return mode.equals("limits")
        ? limits.probeControl(key)
        : mode.equals("trust") ? trust.probeControl(key) : workspace.probeControl(key);
  }

  private static void blockFile() throws Exception {
    Files.delete(file);
    Files.createDirectory(file);
    Files.writeString(file.resolve("keep"), "original");
  }

  private static void unblockFile() throws Exception {
    Files.delete(file.resolve("keep"));
    Files.delete(file);
  }

  private static Map<String, Boolean> visibility() {
    var map = new LinkedHashMap<String, Boolean>();
    tools.forEach((name, value) -> map.put(name, value.get()));
    return Map.copyOf(map);
  }

  private static void captureVisibility() {
    WorkspaceVisibility.capture(state.user.workspace, visibility());
  }

  private static void type(String key, String text) {
    click(key);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    click("blur");
  }

  private static void click(String key) {
    var point = at(key);
    require(point != null, "Missing preference control " + mode + "/" + key);
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
    ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
    UiScale.window(state.user, "User Settings");
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    AppearanceSettingsPanel.category(
        fields, mode.equals("workspace") ? "Workspace" : "Performance", ignored -> {});
    category = PopupLayout.itemBounds();
    Runnable save =
        () -> {
          try {
            state.user.save(file);
            state.user.save(out.resolve("ordinary-preferences.json"));
            saves++;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        };
    if (mode.equals("workspace")) workspace.draw(save);
    else page.draw(save);
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
