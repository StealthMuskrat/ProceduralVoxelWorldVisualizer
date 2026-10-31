package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred immediate-preference callback decisions with synthetic fonts and isolated file writes.
 */
public final class ImmediateSettingsDecisionProbe {
  private static EditorState state;
  private static WorkspacePanel workspace, basic;
  private static LivePreferencesPanel live;
  private static PerformancePanel limits;
  private static UiScalePanel scales;
  private static String form = "Workspace";
  private static int width = 1200,
      height = 900,
      restores,
      centers,
      changes,
      limitOpens,
      retries,
      attempts,
      saves,
      errors;
  private static float global = 1, multiplier = 1;
  private static boolean saveAvailable = true;
  private static Path file;
  private static DialogBounds.Rect window;
  private static final UiFields fields = new UiFields();

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    file = out.resolve("preferences.json");
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
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var draft = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft);
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var engine = state.engine;
        state.user.visualization.shadows = true;
        state.user.workspace.visibleWindows = new ArrayList<>(List.of("Help"));
        state.user.appearance.windowScales.put("extension-window", .75f);
        var tools = new LinkedHashMap<String, ImBoolean>();
        WorkspaceVisibility.restore(state.user.workspace)
            .forEach((name, shown) -> tools.put(name, new ImBoolean(shown)));
        Runnable changed =
            () -> {
              changes++;
              var shown = new LinkedHashMap<String, Boolean>();
              tools.forEach((name, value) -> shown.put(name, value.get()));
              WorkspaceVisibility.capture(state.user.workspace, shown);
            };
        workspace =
            new WorkspacePanel(state.user, tools, () -> restores++, changed, () -> centers++);
        workspace.probeControls();
        basic = new WorkspacePanel(state.user, tools, () -> restores++, changed);
        basic.probeControls();
        live =
            new LivePreferencesPanel(
                state.user,
                ImmediateSettingsDecisionProbe::save,
                () -> limitOpens++,
                () -> retries++,
                () -> List.of("2D/3D/World: controlled display status; no GPU context"));
        live.probeControls();
        limits = new PerformancePanel(state.user, file, ignored -> {});
        limits.probeEdit(PerformancePreferences.Field.CACHE_MIB, "17");
        limits.probeEdit(PerformancePreferences.Field.RENDER_DISTANCE, "-");
        limits.probeEdit(PerformancePreferences.Field.PREVIEW_CELLS, Long.toString(Long.MAX_VALUE));
        scales = new UiScalePanel(state.user, file, ignored -> {});
        scales.probeEditGlobal("1.25");
        scales.probeEditWindow("Resources", "1.5");
        var pendingLimits = limits.probeText();
        var pendingScales = scales.probeText();
        var cases = new ArrayList<Map<String, Object>>();
        for (var mode : List.of("Workspace", "Workspace Basic", "Editor", "Visualization")) {
          form = mode;
          capture(cases);
        }
        normal();
        state.user.save(file);
        Files.write(out.resolve("preferences-before.json"), Files.readAllBytes(file));
        var baseline = ProjectIO.JSON.valueToTree(state.user);
        var baselineBytes = Files.readAllBytes(file);
        for (var mode : List.of("Workspace", "Workspace Basic", "Editor", "Visualization")) {
          form = mode;
          frames(4);
          for (var key : keys()) {
            releaseWithEscape(key);
            require(
                total() == 0
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Escape authorized a settings callback");
            long old = scope().version();
            int oldId = chrome().actionIds().get(key);
            press(control(key));
            form = "Hidden";
            frame();
            form = mode;
            frame();
            release();
            frames(3);
            require(
                scope().version() > old
                    && oldId != chrome().actionIds().get(key)
                    && total() == 0
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Held settings action survived hiding");
          }
        }
        // Distinct Workspace instances may share a root/body without sharing armed callbacks.
        form = "Workspace";
        frames(3);
        press(control("defaults"));
        form = "Workspace Basic";
        frame();
        release();
        frames(3);
        require(total() == 0, "Workspace action crossed panel instances");
        press(control("save-preferences"));
        saveAvailable = false;
        frame();
        saveAvailable = true;
        frame();
        release();
        frames(3);
        require(attempts == 0, "Save callback availability reused an armed predecessor");
        form = "Visualization";
        frames(3);
        press(control("retry"));
        state.user.visualization.shadows = false;
        frame();
        release();
        frames(3);
        click("retry");
        require(retries == 0, "Disabled retry invoked renderer callback");
        state.user.visualization.shadows = true;
        frames(3);
        // Immediate widgets keep their existing live semantics and footer IDs.
        form = "Workspace";
        frames(3);
        long owner = scope().version();
        var ids = chrome().actionIds();
        click("Logs");
        require(
            changes == 1 && owner == scope().version() && ids.equals(chrome().actionIds()),
            "Visibility edit replaced presentation");
        form = "Editor";
        frames(3);
        owner = scope().version();
        ids = chrome().actionIds();
        click("auto");
        var rate = live.inputBounds().get("rate");
        press(new float[] {rate.x() + .99f * rate.width(), rate.y() + rate.height() / 2});
        release();
        frames(3);
        require(
            !state.user.editor.autoRegenerate
                && state.user.editor.opportunitiesPerSecond == 20
                && owner == scope().version()
                && ids.equals(chrome().actionIds()),
            "Immediate Editor edit changed callback owner");
        form = "Visualization";
        frames(3);
        owner = scope().version();
        ids = chrome().actionIds();
        click("grid");
        require(
            !state.user.visualization.grid
                && owner == scope().version()
                && ids.equals(chrome().actionIds()),
            "Display edit changed callback owner");
        form = "Workspace";
        frames(3);
        click("defaults");
        click("center");
        form = "Workspace Basic";
        frames(3);
        click("defaults");
        require(restores == 2 && centers == 1, "Explicit workspace routing failed");
        form = "Editor";
        frames(3);
        owner = scope().version();
        ids = chrome().actionIds();
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "retained");
        click("save");
        require(
            errors == 1
                && saves == 0
                && attempts == 1
                && owner == scope().version()
                && ids.equals(chrome().actionIds())
                && Files.readString(file.resolve("keep")).equals("retained"),
            "Handled save failure lost retry context");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("save");
        require(
            saves == 1 && owner == scope().version() && ids.equals(chrome().actionIds()),
            "Save retry changed presentation");
        Files.write(out.resolve("editor-saved.json"), Files.readAllBytes(file));
        form = "Workspace";
        frames(3);
        click("save-preferences");
        Files.write(out.resolve("workspace-saved.json"), Files.readAllBytes(file));
        form = "Workspace Basic";
        frames(3);
        click("save-preferences");
        form = "Visualization";
        frames(3);
        click("limits");
        click("retry");
        click("save");
        Files.write(out.resolve("visualization-saved.json"), Files.readAllBytes(file));
        var saved = UserConfig.read(file);
        require(
            attempts == 5
                && saves == 4
                && errors == 1
                && limitOpens == 1
                && retries == 1
                && changes == 1
                && saved.workspace.visibleWindows.equals(List.of("Logs", "Help"))
                && saved.performance.cacheMiB != 17
                && saved.performance.renderDistance == 1
                && saved.performance.previewCellBudget != Long.MAX_VALUE
                && saved.appearance.globalScale == 1
                && !saved.appearance.windowScales.containsKey("Resources")
                && pendingLimits.equals(limits.probeText())
                && pendingScales.equals(scales.probeText()),
            "Ordinary save applied private text or missed explicit callback");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Immediate settings changed project/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("immediate-settings-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-user-scale"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("instanceScopesDistinct", true),
                    Map.entry("callbackAvailabilityScoped", true),
                    Map.entry("disabledRetryBlocked", true),
                    Map.entry("immediateEditsPreserved", true),
                    Map.entry("failedRetryRetained", true),
                    Map.entry("privateFormsPreserved", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("saveAttempts", attempts),
                    Map.entry("saves", saves),
                    Map.entry("saveErrors", errors),
                    Map.entry("restores", restores),
                    Map.entry("centers", centers),
                    Map.entry("changes", changes),
                    Map.entry("limitOpens", limitOpens),
                    Map.entry("retries", retries),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "IMMEDIATE_SETTINGS_DECISIONS_NATIVE_PASS callback arbitration / hidden owners / live"
                + " edits / active-only save retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void save() {
    attempts++;
    try {
      state.user.save(file);
      saves++;
    } catch (Exception failure) {
      errors++;
      state.error(
          "PREFERENCES_SAVE_FAILED",
          EditorEvents.Subsystem.APPLICATION,
          Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName()));
    }
  }

  private static int total() {
    return restores + centers + changes + limitOpens + retries + attempts;
  }

  private static List<String> keys() {
    return switch (form) {
      case "Workspace" -> List.of("defaults", "center", "save-preferences");
      case "Workspace Basic" -> List.of("defaults", "save-preferences");
      case "Editor" -> List.of("save");
      default -> List.of("save", "limits", "retry");
    };
  }

  private static SettingsPageChrome chrome() {
    return switch (form) {
      case "Workspace" -> workspace.layout;
      case "Workspace Basic" -> basic.layout;
      case "Editor" -> live.editorLayout;
      default -> live.visualizationLayout;
    };
  }

  private static SettingsActionScope scope() {
    return switch (form) {
      case "Workspace" -> workspace.actionScope;
      case "Workspace Basic" -> basic.actionScope;
      case "Editor" -> live.editorActions;
      default -> live.visualizationActions;
    };
  }

  private static float[] control(String key) {
    var at =
        switch (form) {
          case "Workspace" -> workspace.probeControl(key);
          case "Workspace Basic" -> basic.probeControl(key);
          default -> live.probeControl(key);
        };
    require(at != null, "Missing callback control " + key);
    return at;
  }

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales = {1, 1, 1, 1, 2.5f, 1};
    Long version = null;
    Map<String, Integer> ids = null;
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      multiplier = scales[i];
      frames(7);
      if (version == null) {
        version = scope().version();
        ids = chrome().actionIds();
      }
      require(
          version == scope().version() && ids.equals(chrome().actionIds()),
          "Reflow replaced callback context");
      cases.add(
          Map.ofEntries(
              Map.entry("form", form),
              Map.entry("scenario", i),
              Map.entry("globalScale", global),
              Map.entry("windowScale", multiplier),
              Map.entry("window", window),
              Map.entry("body", chrome().body()),
              Map.entry("actions", chrome().actions()),
              Map.entry("ids", chrome().actionIds()),
              Map.entry("request", scope().version())));
    }
  }

  private static void normal() {
    width = 1200;
    height = 900;
    global = multiplier = 1;
    frames(5);
  }

  private static void press(float[] at) {
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
  }

  private static void release() {
    ImGui.getIO().addMouseButtonEvent(0, false);
    frame();
  }

  private static void click(String key) {
    press(control(key));
    release();
    frames(4);
  }

  private static void releaseWithEscape(String key) {
    press(control(key));
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
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
    if (!form.equals("Workspace")) workspace.suspendActions();
    if (!form.equals("Workspace Basic")) basic.suspendActions();
    if (!form.equals("Editor")) live.suspendEditorActions();
    if (!form.equals("Visualization")) live.suspendVisualizationActions();
    if (!form.equals("Hidden")) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowFocus();
      ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
      ImGui.setWindowFontScale(multiplier);
      window =
          new DialogBounds.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      AppearanceSettingsPanel.category(
          fields, form.startsWith("Workspace") ? "Workspace" : form, ignored -> {});
      switch (form) {
        case "Workspace" ->
            workspace.draw(saveAvailable ? ImmediateSettingsDecisionProbe::save : null);
        case "Workspace Basic" ->
            basic.draw(saveAvailable ? ImmediateSettingsDecisionProbe::save : null);
        case "Editor" -> live.drawEditor();
        case "Visualization" -> live.drawVisualization();
        default -> throw new AssertionError();
      }
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
