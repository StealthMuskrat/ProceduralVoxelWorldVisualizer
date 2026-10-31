package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.Field.*;

import imgui.ImGui;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production preference footers; synthetic magnification does not edit active scales. */
public final class PreferenceDecisionProbe {
  private static EditorState state;
  private static PerformancePanel limits;
  private static UpdateTrustPanel trust;
  private static UiScalePanel scales;
  private static String form = "Limits";
  private static int width = 1200, height = 900, errors;
  private static float global = 1, multiplier = 1;
  private static DialogBounds.Rect window;
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final String ORIGINS =
      "HTTPS://UPDATES.EXAMPLE.TEST:443/\n"
          + "https://releases.example.test\n"
          + "https://updates.example.test";

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    var file = out.resolve("preferences.json");
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
            "Preference fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        ((Noise) state.draft).id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        var owner = state.draft;
        var privateValues = ProjectIO.JSON.valueToTree(owner);
        long revision = state.revision, history = state.document.historyBytes();
        var engine = state.engine;
        state.user.appearance.theme = "Light";
        state.user.appearance.windowScales.put("extension-window", .75f);
        state.user.workspace.width = 1234;
        state.user.save(file);
        Files.write(out.resolve("preferences-before.json"), Files.readAllBytes(file));
        var baseline = ProjectIO.JSON.valueToTree(state.user);
        var baselineBytes = Files.readAllBytes(file);
        limits = new PerformancePanel(state.user, file, ignored -> errors++);
        limits.probeControls();
        trust = new UpdateTrustPanel(state.user, file, ignored -> errors++);
        trust.probeControls();
        scales = new UiScalePanel(state.user, file, ignored -> errors++);
        scales.probeControls();
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1200, 900}, {360, 640}, {360, 640}, {360, 640}, {720, 640}, {1000, 320}};
        float[] globals = {1, .5f, 1, 2.5f, 2.5f, 1}, windows = {1, 1, 1, 1, 2.5f, 1};
        for (String mode : List.of("Limits", "Trust", "Scales")) {
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
            frames(8);
            if (version == null) {
              version = scope().version();
              ids = chrome().actionIds();
            }
            require(
                version == scope().version()
                    && ids.equals(chrome().actionIds())
                    && raw.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user)),
                "Magnification changed preference ownership or active values");
            cases.add(receipt(i));
          }
        }
        width = 1200;
        height = 900;
        global = multiplier = 1;
        for (String mode : List.of("Limits", "Trust", "Scales")) {
          form = mode;
          for (String action : List.of("apply", "revert")) {
            prime(false);
            frames(5);
            var raw = raw();
            releaseWithEscape(control(action));
            require(
                raw.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Escape authorized a preference mutation");
            frames(3);
            long old = scope().version();
            var oldId = chrome().actionIds().get(action);
            press(control(action));
            prime(true);
            var successor = raw();
            frame();
            release();
            frames(4);
            require(
                scope().version() > old
                    && !oldId.equals(chrome().actionIds().get(action))
                    && successor.equals(raw())
                    && baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                    && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
                "Held preference action affected a successor");
          }
          prime(false);
          frames(5);
          var raw = raw();
          long old = scope().version();
          press(control("revert"));
          switch (form) {
            case "Limits" -> state.user.performance.cacheMiB++;
            case "Trust" -> {
              state.user.performance.updateEndpoint = "https://external.example.test/meta.json";
              state.user.performance.trustedOrigins =
                  new ArrayList<>(List.of("https://external.example.test"));
            }
            case "Scales" -> state.user.appearance.globalScale = 1.5f;
            default -> throw new AssertionError();
          }
          frame();
          release();
          frames(4);
          require(
              scope().version() > old && raw.equals(raw()),
              "Held Revert adopted an in-place active source change");
          switch (form) {
            case "Limits" -> state.user.performance.cacheMiB--;
            case "Trust" -> {
              state.user.performance.updateEndpoint = "";
              state.user.performance.trustedOrigins = new ArrayList<>();
            }
            case "Scales" -> state.user.appearance.globalScale = 1;
            default -> throw new AssertionError();
          }
          prime(false);
          frames(4);
          click(control("revert"));
          require(
              baseline.equals(ProjectIO.JSON.valueToTree(state.user))
                  && Arrays.equals(baselineBytes, Files.readAllBytes(file)),
              "Explicit Revert wrote active preferences");
        }
        // Independent private drafts survive each other's successful scoped publication.
        form = "Limits";
        prime(false);
        limits.probeEdit(CACHE_MIB, "0");
        form = "Trust";
        prime(false);
        var pendingTrust = trust.probeText();
        form = "Scales";
        prime(false);
        var pendingScales = scales.probeText();
        for (String mode : List.of("Limits", "Trust", "Scales")) {
          form = mode;
          frames(5);
          require(enabled(), "Valid pending preferences were not applicable");
          var before = ProjectIO.JSON.valueToTree(state.user);
          var raw = raw();
          long retry = scope().version();
          Files.delete(file);
          Files.createDirectory(file);
          Files.writeString(file.resolve("keep"), "retained");
          click(control("apply"));
          require(
              scope().version() == retry
                  && before.equals(ProjectIO.JSON.valueToTree(state.user))
                  && raw.equals(raw())
                  && Files.readString(file.resolve("keep")).equals("retained"),
              "Failed preference write consumed its request or changed active values");
          Files.delete(file.resolve("keep"));
          Files.delete(file);
          click(control("apply"));
          frames(4);
          require(
              scope().version() > retry,
              "Successful preference publication retained the old action owner");
          if (form.equals("Limits"))
            require(
                pendingTrust.equals(trust.probeText()) && pendingScales.equals(scales.probeText()),
                "Limit Apply consumed another private draft");
          if (form.equals("Trust"))
            require(
                pendingScales.equals(scales.probeText()), "Trust Apply consumed private scales");
          Files.write(
              out.resolve(mode.toLowerCase(Locale.ROOT) + "-saved.json"), Files.readAllBytes(file));
        }
        var saved = UserConfig.read(file);
        require(
            errors == 3
                && saved.performance.cacheMiB == 0
                && saved.performance.previewCellBudget == Long.MAX_VALUE
                && saved.performance.updateEndpoint.equals(ENDPOINT)
                && saved.performance.trustedOrigins.equals(
                    List.of("https://updates.example.test", "https://releases.example.test"))
                && saved.appearance.globalScale == 1.25f
                && saved.appearance.windowScales.get("Resources") == 1.5f
                && saved.appearance.windowScales.get("extension-window") == .75f,
            "Scoped preference publications lost exact or unrelated values");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.draft == owner
                && privateValues.equals(ProjectIO.JSON.valueToTree(state.draft))
                && state.seedInputs.text("seedOffset").equals("-")
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.engine == engine
                && state.engine.profiler().current().isEmpty(),
            "Preferences changed project/private/runtime ownership");
        new ProjectIO().save(out.resolve("unchanged-project.json"), state.project());
        Files.writeString(
            out.resolve("preference-decisions-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "synthetic-user-scale"),
                    Map.entry("cases", cases),
                    Map.entry("escapePreserved", true),
                    Map.entry("heldSuccessorsBlocked", true),
                    Map.entry("activeChangesScoped", true),
                    Map.entry("revertPrivateOnly", true),
                    Map.entry("failedRetriesRetained", true),
                    Map.entry("independentDraftsPreserved", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("errors", errors),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "PREFERENCE_DECISIONS_NATIVE_PASS scoped performance/trust/scale actions / Escape /"
                + " retry / independent preference owners");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void prime(boolean successor) {
    switch (form) {
      case "Limits" -> {
        limits.probeRevert();
        limits.probeEdit(CACHE_MIB, successor ? "23" : "17");
        limits.probeEdit(PREVIEW_CELLS, Long.toString(Long.MAX_VALUE));
      }
      case "Trust" -> {
        trust.probeRevert();
        trust.probeEdit(successor ? "https://releases.example.test/next.json" : ENDPOINT, ORIGINS);
      }
      case "Scales" -> {
        scales.probeRevert();
        scales.probeEditGlobal(successor ? "1.75" : "1.25");
        scales.probeEditWindow("Resources", "1.5");
      }
      default -> throw new AssertionError();
    }
  }

  private static Object raw() {
    return switch (form) {
      case "Limits" -> limits.probeText();
      case "Trust" -> trust.probeText();
      case "Scales" -> scales.probeText();
      default -> throw new AssertionError();
    };
  }

  private static PreferenceActionScope scope() {
    return form.equals("Limits")
        ? limits.actionScope
        : form.equals("Trust") ? trust.actionScope : scales.actionScope;
  }

  private static SettingsPageChrome chrome() {
    return form.equals("Limits")
        ? limits.layout
        : form.equals("Trust") ? trust.layout : scales.layout;
  }

  private static boolean enabled() {
    return form.equals("Limits")
        ? limits.probeApplyEnabled()
        : form.equals("Trust") ? trust.probeApplyEnabled() : scales.probeApplyEnabled();
  }

  private static float[] control(String key) {
    return form.equals("Limits")
        ? limits.probeControl(key)
        : form.equals("Trust") ? trust.probeControl(key) : scales.probeControl(key);
  }

  private static Map<String, Object> receipt(int scenario) {
    return Map.ofEntries(
        Map.entry("form", form),
        Map.entry("scenario", scenario),
        Map.entry("globalScale", global),
        Map.entry("windowScale", multiplier),
        Map.entry("window", window),
        Map.entry("body", chrome().body()),
        Map.entry("actions", chrome().actions()),
        Map.entry("ids", chrome().actionIds()),
        Map.entry("request", scope().version()));
  }

  private static void press(float[] at) {
    require(at != null, "Missing preference action");
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

  private static void frames(int n) {
    for (int i = 0; i < n; i++) frame();
  }

  private static void frame() {
    var io = ImGui.getIO();
    io.setDisplaySize(width, height);
    io.setFontGlobalScale(global);
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    ImGui.begin("User Settings", ImGuiWindowFlags.NoSavedSettings);
    ImGui.setWindowFontScale(multiplier);
    window =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    if (form.equals("Limits")) limits.draw();
    else if (form.equals("Trust")) trust.draw();
    else scales.draw();
    ImGui.end();
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
