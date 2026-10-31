package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred Theme/Input decision ownership; no GLFW, renderer or physical keyboard is created. */
public final class InteractivePreferenceDecisionProbe {
  private static EditorState state;
  private static ThemePanel theme;
  private static InputSettingsPanel input;
  private static final ImBoolean open = new ImBoolean(true);
  private static String form = "Theme";
  private static int width = 1200, height = 900, themeErrors;
  private static float global = 1, multiplier = 1;
  private static DialogBounds.Rect window;
  private static boolean suppressed;
  private static String command;

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    var file = out.resolve("user-config.json");
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
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
        var owner = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(owner);
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        state.user.appearance.colors.put(ImGuiCol.Header, new float[] {.25f, .5f, .75f, 1});
        state.user.appearance.windowScales.put("Themes", 1f);
        state.user.appearance.windowScales.put("extension-window", .75f);
        state.user.workspace.width = 1234;
        state.user.save(file);
        Files.write(out.resolve("preferences-before.json"), Files.readAllBytes(file));
        var baseline = ProjectIO.JSON.valueToTree(state.user);
        var baselineBytes = Files.readAllBytes(file);
        var baseTheme = ThemePalette.from(state.user.appearance);
        var baseBindings = new LinkedHashMap<>(state.user.input.shortcuts);
        ThemeStyle.apply(baseTheme);
        theme = new ThemePanel(state.user, ignored -> themeErrors++);
        theme.probeControls();
        input = new InputSettingsPanel(state.user);
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, windows = {1, 1, 1, 1, 2.5f, 1};
        for (String mode : List.of("Theme", "Input")) {
          form = mode;
          prime(false);
          var raw = raw();
          Long version = null;
          Map<String, Integer> ids = null;
          for (int i = 0; i < sizes.length; i++) {
            width = sizes[i][0];
            height = sizes[i][1];
            global = globals[i];
            multiplier = windows[i];
            state.user.appearance.windowScales.put("Themes", multiplier);
            frames(7);
            if (version == null) {
              version = scope().version();
              ids = chrome().actionIds();
            }
            require(
                version == scope().version()
                    && ids.equals(chrome().actionIds())
                    && raw.equals(raw())
                    && baseTheme.equals(ThemePalette.from(state.user.appearance))
                    && baseBindings.equals(state.user.input.shortcuts),
                "Reflow changed decision ownership");
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
        width = 1200;
        height = 900;
        global = multiplier = 1;
        state.user.appearance.windowScales.put("Themes", 1f);
        for (String mode : List.of("Theme", "Input")) {
          form = mode;
          for (String action :
              mode.equals("Theme")
                  ? List.of("save", "revert", "reset")
                  : List.of("apply", "revert", "reset")) {
            prime(false);
            frames(5);
            var raw = raw();
            releaseWithEscape(control(action));
            require(
                raw.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Escape changed a private or active preference");
            long old = scope().version();
            int oldId = chrome().actionIds().get(action);
            press(control(action));
            prime(true);
            var successor = raw();
            frame();
            release();
            frames(4);
            require(
                scope().version() > old
                    && oldId != chrome().actionIds().get(action)
                    && successor.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Held action affected a replacement form");
          }
          prime(false);
          frames(5);
          var raw = raw();
          long old = scope().version();
          press(control("revert"));
          if (form.equals("Theme")) state.user.appearance.colors.get(ImGuiCol.Header)[0] = .125f;
          else state.user.input.shortcuts.put("Profiler", "Alt+F10");
          frame();
          release();
          frames(4);
          require(
              scope().version() > old && raw.equals(raw()),
              "Held Revert adopted changed active values");
          if (form.equals("Theme")) state.user.appearance.colors.get(ImGuiCol.Header)[0] = .25f;
          else state.user.input.shortcuts.putAll(baseBindings);
          frames(3);
          click(control("revert"));
          require(
              baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
              "Revert published preferences");
          require(
              form.equals("Theme")
                  ? theme.probeDraft().equals(baseTheme)
                  : input.draftValues().equals(baseBindings),
              "Explicit Revert did not reload active values");
        }

        form = "Theme";
        prime(false);
        frames(4);
        var privatePalette = theme.probeDraft();
        long activeVersion = scope().version();
        ThemeStyle.apply(ThemePalette.starting("Dark"));
        var external = ThemeStyle.snapshot();
        frames(4);
        require(
            theme.probePaused() && scope().version() > activeVersion,
            "Preview pause retained predecessor actions");
        long pausedVersion = scope().version();
        releaseWithEscape(control("resume"));
        require(
            theme.probePaused()
                && privatePalette.equals(theme.probeDraft())
                && external.equals(ThemeStyle.snapshot())
                && scope().version() == pausedVersion,
            "Escape resumed a private preview");
        press(control("resume"));
        ThemeStyle.apply(ThemePalette.starting("Light"));
        var newerExternal = ThemeStyle.snapshot();
        frame();
        release();
        frames(4);
        require(
            theme.probePaused()
                && scope().version() > pausedVersion
                && privatePalette.equals(theme.probeDraft())
                && newerExternal.equals(ThemeStyle.snapshot()),
            "Held Resume replaced a newer paused style owner");
        long newerPausedVersion = scope().version();
        click(control("resume"));
        require(
            !theme.probePaused()
                && privatePalette.equals(theme.probeDraft())
                && scope().version() > newerPausedVersion,
            "Explicit Resume lost palette or action ownership");
        // Even an unchanged private draft receives a new footer context after collapse.
        long expanded = scope().version();
        press(control("reset"));
        collapsed = true;
        frame();
        collapsed = false;
        frame();
        release();
        frames(4);
        require(
            scope().version() > expanded && privatePalette.equals(theme.probeDraft()),
            "Collapsed Theme retained an armed predecessor");

        form = "Input";
        prime(false);
        frames(5);
        var rawBindings = input.draftValues();
        long visible = scope().version();
        press(control("apply"));
        input.cancelCapture();
        form = "Hidden";
        frame();
        form = "Input";
        frame();
        release();
        frames(4);
        require(
            scope().version() > visible
                && rawBindings.equals(input.draftValues())
                && baseline.equals(ProjectIO.JSON.valueToTree(state.user)),
            "Hidden Input published a held action");
        require(input.probeRecord("Find"), "Recording fixture rejected");
        frames(4);
        require(
            "Find".equals(input.recordingAction()) && !input.applyEnabled(),
            "Recording did not own input");
        press(control("cancel-recording"));
        var io = ImGui.getIO();
        io.addMouseButtonEvent(0, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.S, true);
        frame();
        require(
            input.recordingAction() == null
                && suppressed
                && command == null
                && rawBindings.equals(input.draftValues()),
            "Recording cancel captured/dispatched Save");
        io.addKeyEvent(ImGuiKey.S, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frames(4);
        require(!suppressed, "Recording suppression did not end");

        // Pending Theme and Input survive independent saves and occupied-destination retries.
        form = "Theme";
        prime(false);
        frames(4);
        var pendingTheme = theme.probeDraft();
        form = "Input";
        prime(false);
        frames(4);
        var pendingInput = input.draftValues();
        for (String mode : List.of("Input", "Theme")) {
          form = mode;
          frames(5);
          var before = ProjectIO.JSON.valueToTree(state.user);
          var raw = raw();
          long retry = scope().version();
          var ids = chrome().actionIds();
          Files.delete(file);
          Files.createDirectory(file);
          Files.writeString(file.resolve("keep"), "retained");
          click(control(form.equals("Theme") ? "save" : "apply"));
          require(
              scope().version() == retry
                  && ids.equals(chrome().actionIds())
                  && raw.equals(raw())
                  && before.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Files.readString(file.resolve("keep")).equals("retained"),
              "Failed save lost its retry owner");
          if (mode.equals("Input"))
            require(input.probeMessage().startsWith("!"), "Missing binding save feedback");
          Files.delete(file.resolve("keep"));
          Files.delete(file);
          click(control(form.equals("Theme") ? "save" : "apply"));
          require(scope().version() > retry, "Successful save retained predecessor actions");
          if (mode.equals("Input"))
            require(pendingTheme.equals(theme.probeDraft()), "Binding save consumed Theme draft");
          else
            require(pendingInput.equals(input.draftValues()), "Theme save consumed binding draft");
          Files.write(
              out.resolve(mode.toLowerCase(Locale.ROOT) + "-saved.json"), Files.readAllBytes(file));
        }
        var saved = UserConfig.read(file);
        require(
            themeErrors == 1
                && ThemePalette.from(saved.appearance).equals(pendingTheme)
                && saved.input.shortcuts.get("Profiler").equals("Alt+F9")
                && saved.appearance.windowScales.get("extension-window") == .75f,
            "Saved preference merge lost values");
        open.set(false);
        frame();
        require(
            ThemeStyle.snapshot().equals(ThemeStyle.resolve(pendingTheme)),
            "Close lost saved style");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == owner
                && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.engine == engine
                && state.engine.profiler().current().isEmpty(),
            "Interactive preferences changed project ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("interactive-preference-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-global-window-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("activeChangesScoped", true),
                    Map.entry("previewPauseResume", true),
                    Map.entry("pausedOwnerChangesScoped", true),
                    Map.entry("hiddenContextsReleased", true),
                    Map.entry("recordingCancellationSuppressed", true),
                    Map.entry("failedRetriesRetained", true),
                    Map.entry("independentDraftsPreserved", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("themeErrors", themeErrors),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "INTERACTIVE_PREFERENCE_DECISIONS_NATIVE_PASS scoped Theme/Input / Escape / preview /"
                + " recording / independent retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static boolean collapsed;

  private static ThemePalette palette(boolean successor) {
    return ThemePalette.starting("Light")
        .status("Warning", new ThemePalette.Rgba(successor ? .5f : .25f, .5f, .75f, 1));
  }

  private static void prime(boolean successor) {
    if (form.equals("Theme")) {
      theme.close();
      open.set(true);
      theme.probeEdit(palette(successor));
    } else {
      input.probeRevert();
      input.probeEdit("Profiler", successor ? "Alt+F10" : "Alt+F9");
    }
  }

  private static Object raw() {
    return form.equals("Theme") ? theme.probeDraft() : input.draftValues();
  }

  private static PreferenceActionScope scope() {
    return form.equals("Theme") ? theme.actionScope : input.actionScope;
  }

  private static SettingsPageChrome chrome() {
    return form.equals("Theme") ? theme.layout : input.layout;
  }

  private static float[] control(String key) {
    return form.equals("Theme") ? theme.probeControl(key) : input.controlAt(key);
  }

  private static void press(float[] at) {
    require(at != null, "Missing decision target");
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

  private static void click(float[] at) {
    press(at);
    release();
    frames(4);
  }

  private static void releaseWithEscape(float[] at) {
    press(at);
    var io = ImGui.getIO();
    io.addMouseButtonEvent(0, false);
    io.addKeyEvent(ImGuiKey.Escape, true);
    frame();
    io.addKeyEvent(ImGuiKey.Escape, false);
    frames(4);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    input.beginFrame();
    if (form.equals("Theme")) {
      ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
      ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      ImGui.setNextWindowFocus();
      theme.draw(open);
      window = theme.windowLayout.bounds();
    } else if (form.equals("Input")) {
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
      input.draw();
      ImGui.end();
    } else input.cancelCapture();
    suppressed = input.recording();
    command =
        ShortcutKeys.match(state.user.input.shortcuts, Shortcuts.Focus.GENERAL, suppressed)
            .map(Shortcuts.Action::id)
            .orElse(null);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
