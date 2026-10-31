package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred ordinary-save/Appearance callbacks after complete private-form footer decisions. */
public final class PrivatePreferenceSaveProbe {
  private static EditorState state;
  private static PerformancePanel limits;
  private static UpdateTrustPanel trust;
  private static UiScalePanel scales;
  private static InputSettingsPanel input;
  private static AppearanceSettingsPanel appearance;
  private static PerformanceSettingsPage performance;
  private static final UiFields fields = new UiFields();
  private static Path file;
  private static String form = "Appearance";
  private static int width = 1200, height = 900, attempts, saves, errors, opened;
  private static float global = 1, multiplier = 1;
  private static boolean available = true, suppressed;
  private static String command;
  private static DialogBounds.Rect window;
  private static final String ORIGINS =
      "https://updates.example.test\nhttps://releases.example.test";

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
        state.user.appearance.windowScales.put("extension-window", .75f);
        limits =
            new PerformancePanel(
                state.user,
                file,
                ignored -> {
                  throw new AssertionError("Unexpected private limit Apply");
                });
        limits.probeControls();
        trust =
            new UpdateTrustPanel(
                state.user,
                file,
                ignored -> {
                  throw new AssertionError("Unexpected private trust Apply");
                });
        trust.probeControls();
        performance = new PerformanceSettingsPage(limits, trust);
        scales =
            new UiScalePanel(
                state.user,
                file,
                ignored -> {
                  throw new AssertionError("Unexpected private scale Apply");
                });
        scales.probeControls();
        input = new InputSettingsPanel(state.user);
        appearance =
            new AppearanceSettingsPanel(
                state.user,
                scales,
                () -> ThemeStyle.apply(ThemePalette.from(state.user.appearance)),
                () -> opened++,
                PrivatePreferenceSaveProbe::save);
        for (var mode : List.of("Appearance", "Limits", "Trust", "Input")) {
          form = mode;
          prime(false);
        }
        var baseline = ProjectIO.JSON.valueToTree(state.user);
        state.user.save(file);
        var baselineBytes = Files.readAllBytes(file);
        Files.write(out.resolve("preferences-before.json"), baselineBytes);
        var cases = new ArrayList<Map<String, Object>>();
        for (var mode : List.of("Appearance", "Limits", "Trust", "Input")) {
          form = mode;
          capture(cases);
        }
        normal();
        for (var mode : List.of("Appearance", "Limits", "Trust", "Input")) {
          form = mode;
          prime(false);
          showCallbacks();
          frames(6);
          for (var key : mode.equals("Appearance") ? List.of("save", "themes") : List.of("save")) {
            var raw = raw();
            releaseWithEscape(key);
            require(
                attempts == 0
                    && opened == 0
                    && raw.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Escape authorized ordinary preference callback");
            int oldId = callbackIds().get(key);
            press(control(key));
            form = "Hidden";
            frame();
            form = mode;
            showCallbacks();
            frame();
            release();
            frames(4);
            require(
                callbackIds().get(key) != oldId
                    && attempts == 0
                    && opened == 0
                    && raw.equals(raw()),
                "Held callback crossed hidden presentation");
            press(control(key));
            prime(true);
            var successor = raw();
            showCallbacks();
            frame();
            release();
            frames(4);
            require(
                attempts == 0 && opened == 0 && successor.equals(raw()),
                "Held callback crossed private draft reset");
          }
          for (var key : List.of("apply", "revert")) {
            prime(false);
            showCallbacks();
            frames(5);
            var raw = raw();
            press(control(key));
            form = "Hidden";
            frame();
            form = mode;
            showCallbacks();
            frame();
            release();
            frames(4);
            require(
                raw.equals(raw())
                    && attempts == 0
                    && opened == 0
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Private footer crossed category lifetime");
          }
          if (!mode.equals("Appearance")) {
            showCallbacks();
            frames(4);
            int oldId = callbackIds().get("save");
            var footer = chrome().actionIds();
            press(control("save"));
            available = false;
            frame();
            available = true;
            showCallbacks();
            frame();
            release();
            frames(4);
            require(
                oldId != callbackIds().get("save")
                    && footer.equals(chrome().actionIds())
                    && attempts == 0,
                "Callback availability changed private footer or reused Save activation");
          }
        }
        // Ordinary save cancels recording before polling and retains suppression for the frame.
        form = "Input";
        prime(false);
        showCallbacks();
        frames(5);
        require(input.probeRecord("Find"), "Recording fixture refused");
        showCallbacks();
        frames(4);
        press(control("save"));
        var io = ImGui.getIO();
        io.addMouseButtonEvent(0, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, true);
        io.addKeyEvent(ImGuiKey.S, true);
        frame();
        require(
            attempts == 1
                && saves == 1
                && input.recordingAction() == null
                && suppressed
                && command == null,
            "Ordinary save captured or dispatched the same-frame chord");
        io.addKeyEvent(ImGuiKey.S, false);
        io.addKeyEvent(ImGuiKey.ModCtrl, false);
        frames(4);
        // Each form retains failed-save text and native retry IDs, then saves active values only.
        for (var mode : List.of("Appearance", "Limits", "Trust", "Input")) {
          form = mode;
          prime(false);
          showCallbacks();
          frames(5);
          var raw = raw();
          var ids = callbackIds();
          int previousErrors = errors;
          Files.delete(file);
          Files.createDirectory(file);
          Files.writeString(file.resolve("keep"), "retained");
          click("save");
          require(
              errors == previousErrors + 1
                  && raw.equals(raw())
                  && ids.equals(callbackIds())
                  && Files.readString(file.resolve("keep")).equals("retained")
                  && baseline.equals(ProjectIO.JSON.valueToTree(state.user)),
              "Handled ordinary-save failure changed owner, draft or active preferences");
          Files.delete(file.resolve("keep"));
          Files.delete(file);
          click("save");
          require(
              ids.equals(callbackIds())
                  && raw.equals(raw())
                  && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
              "Ordinary retry applied private values");
          Files.write(
              out.resolve(mode.toLowerCase(Locale.ROOT) + "-saved.json"), Files.readAllBytes(file));
        }
        form = "Appearance";
        showCallbacks();
        frames(4);
        click("themes");
        require(opened == 1, "Open Themes routing failed");
        require(
            attempts == 9
                && saves == 5
                && errors == 4
                && project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == draft
                && privateValues.equals(ProjectIO.JSON.valueToTree(draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.engine == engine
                && engine.profiler().current().isEmpty(),
            "Private preference saves changed project/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("private-preference-saves-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-user-scale"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("hiddenCallbacksScoped", true),
                    Map.entry("resetCallbacksScoped", true),
                    Map.entry("hiddenPrivateFootersScoped", true),
                    Map.entry("availabilityScoped", true),
                    Map.entry("recordingSuppressed", true),
                    Map.entry("failedRetriesRetained", true),
                    Map.entry("activeOnlySaved", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("attempts", attempts),
                    Map.entry("saves", saves),
                    Map.entry("errors", errors),
                    Map.entry("openedThemes", opened),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PRIVATE_PREFERENCE_SAVES_NATIVE_PASS deferred callbacks / category lifetime / reset /"
                + " active-only retry / recording suppression");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void prime(boolean successor) {
    switch (form) {
      case "Appearance" -> {
        scales.probeRevert();
        scales.probeEditGlobal(successor ? "1.75" : "1.25");
        scales.probeEditWindow("Resources", "1.5");
      }
      case "Limits" -> {
        limits.probeRevert();
        limits.probeEdit(PerformancePreferences.Field.CACHE_MIB, successor ? "23" : "17");
        limits.probeEdit(PerformancePreferences.Field.PREVIEW_CELLS, Long.toString(Long.MAX_VALUE));
      }
      case "Trust" -> {
        trust.probeRevert();
        trust.probeEdit(
            successor
                ? "https://releases.example.test/next.json"
                : "https://updates.example.test/meta.json",
            ORIGINS);
      }
      case "Input" -> {
        input.probeRevert();
        input.probeEdit("Profiler", successor ? "Alt+F10" : "Alt+F9");
      }
      default -> throw new AssertionError();
    }
  }

  private static Object raw() {
    return switch (form) {
      case "Appearance" -> scales.probeText();
      case "Limits" -> limits.probeText();
      case "Trust" -> trust.probeText();
      default -> input.draftValues();
    };
  }

  private static SettingsPageChrome chrome() {
    return switch (form) {
      case "Appearance" -> scales.layout;
      case "Limits" -> limits.layout;
      case "Trust" -> trust.layout;
      default -> input.layout;
    };
  }

  private static Map<String, Integer> callbackIds() {
    return switch (form) {
      case "Appearance" -> appearance.callbackIds();
      case "Limits" -> Map.of("save", limits.probeSaveId());
      case "Trust" -> Map.of("save", trust.probeSaveId());
      default -> Map.of("save", input.probeSaveId());
    };
  }

  private static float[] control(String key) {
    if (form.equals("Appearance"))
      return key.equals("save") || key.equals("themes")
          ? appearance.controlAt(key)
          : scales.probeControl(key);
    if (key.equals("save")) key = "save-preferences";
    return switch (form) {
      case "Limits" -> limits.probeControl(key);
      case "Trust" -> trust.probeControl(key);
      default -> input.controlAt(key);
    };
  }

  private static void showCallbacks() {
    switch (form) {
      case "Appearance" -> appearance.probeRevealCallbacks();
      case "Limits" -> limits.probeRevealSave();
      case "Trust" -> trust.probeRevealSave();
      case "Input" -> input.probeRevealSave();
      default -> {}
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

  private static void capture(List<Map<String, Object>> cases) {
    int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
    float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, scales_ = {1, 1, 1, 1, 2.5f, 1};
    Map<String, Integer> ids = null, footer = null;
    var raw = raw();
    for (int i = 0; i < sizes.length; i++) {
      width = sizes[i][0];
      height = sizes[i][1];
      global = globals[i];
      multiplier = scales_[i];
      showCallbacks();
      frames(8);
      if (ids == null) {
        ids = callbackIds();
        footer = chrome().actionIds();
      }
      require(
          ids.equals(callbackIds()) && footer.equals(chrome().actionIds()) && raw.equals(raw()),
          "Reflow changed private callback ownership");
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
              Map.entry("callbacks", callbackIds())));
    }
  }

  private static void normal() {
    width = 1200;
    height = 900;
    global = multiplier = 1;
    showCallbacks();
    frames(6);
  }

  private static void press(float[] at) {
    require(at != null, "Missing private callback target");
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
    input.beginFrame();
    if (!form.equals("Appearance")) appearance.suspendActions();
    if (!form.equals("Input")) input.cancelCapture();
    if (!form.equals("Limits") && !form.equals("Trust")) performance.suspendActions();
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
          fields,
          form.equals("Limits") || form.equals("Trust") ? "Performance" : form,
          ignored -> {});
      switch (form) {
        case "Appearance" -> appearance.draw();
        case "Input" -> input.draw(available ? PrivatePreferenceSaveProbe::save : null);
        case "Limits", "Trust" -> {
          if (form.equals("Limits")) performance.showLimits();
          else performance.showUpdates();
          performance.draw(available ? PrivatePreferenceSaveProbe::save : null);
        }
        default -> throw new AssertionError();
      }
      ImGui.end();
    }
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
