package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred production scale-editor input/retry plus isolated native font-metric composition. */
public final class UiScaleProbe {
  private static UiScalePanel panel;
  private static EditorState state;
  private static boolean visible = true, collapsed;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1500, 1200);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var user = new UserConfig();
      Path file = out.resolve("user-config.json");
      user.save(file);
      byte[] initial = Files.readAllBytes(file);
      try (var editor = new EditorState(user, false)) {
        state = editor;
        require(
            state.change(
                p -> {
                  var noise = new Noise();
                  noise.id = "field";
                  noise.algorithm.key = "CONSTANT";
                  noise.algorithm.parameters.clear();
                  noise.algorithm.parameters.put("value", .125);
                  p.noise2D.add(noise);
                }),
            "Fixture rejected");
        state.select("noise2D", state.project().noise2D.getFirst().uuid);
        var privateDraft = (Noise) state.draft;
        privateDraft.id = "private";
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        var project = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        panel =
            new UiScalePanel(
                user,
                file,
                message ->
                    state.error(
                        "UI_SCALE_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel.probeControls();
        frames(3);
        type("Global", "+1.25");
        select("Help");
        click("override");
        type("Help", "-");
        select("Logs");
        click("override");
        type("Logs", "2.000");
        require(
            !panel.probeApplyEnabled() && panel.probeText().windows().get("Help").equals("-"),
            "Hidden invalid multiplier enabled Apply or lost raw input");
        click("apply");
        require(
            Arrays.equals(initial, Files.readAllBytes(file)) && user.appearance.globalScale == 1,
            "Invalid scale wrote preferences");
        user.save(out.resolve("periodic-preferences.json"));
        select("Help");
        type("Help", "1.500");
        var raw = panel.probeText();
        long validations = panel.validations();
        frames(5);
        require(
            panel.validations() == validations && panel.probeApplyEnabled(),
            "Unchanged feedback rebuilt");
        visible = false;
        frames(3);
        visible = true;
        collapsed = true;
        frames(3);
        require(
            panel.probeText().equals(raw) && panel.validations() == validations,
            "Hidden/collapsed host lost private scale fields");
        collapsed = false;
        frames(2);
        user.appearance.windowScales.put("Resources", 1.1f);
        frames(2);
        require(
            !panel.probeApplyEnabled() && panel.probeText().equals(raw),
            "Changed active scales overwrote the private draft");
        click("revert");
        require(
            panel.probeText().enabled().equals(Set.of("Resources"))
                && panel.probeText().global().equals("1.0"),
            "Revert did not load active scales");
        type("Global", "+1.25");
        select("Help");
        click("override");
        type("Help", "1.500");
        select("Logs");
        click("override");
        type("Logs", "2.000");
        raw = panel.probeText();
        var active = UiScalePreferences.from(user);
        Files.delete(file);
        Files.createDirectory(file);
        Files.write(file.resolve("keep"), initial);
        click("apply");
        require(
            active.equals(UiScalePreferences.from(user))
                && panel.probeText().equals(raw)
                && Arrays.equals(initial, Files.readAllBytes(file.resolve("keep")))
                && state.events.snapshot().stream()
                    .anyMatch(e -> e.code().equals("UI_SCALE_SAVE_FAILED")),
            "Failed save changed active scales, private fields or prior destination");
        user.appearance.theme = "Light";
        user.workspace.width = 1234;
        user.input.shortcuts.put("Profiler", "Alt+F9");
        user.performance.cacheMiB = 17;
        user.appearance.windowScales.put("Future Window", 1.75f);
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("apply");
        var firstSaved = UserConfig.read(file);
        require(
            firstSaved.appearance.globalScale == 1.25f
                && firstSaved.appearance.windowScales.equals(
                    Map.of("Resources", 1.1f, "Help", 1.5f, "Logs", 2f, "Future Window", 1.75f))
                && panel.probeText().global().equals("1.25")
                && panel.probeText().windows().get("Help").equals("1.5"),
            "Retry lost canonical scales or unknown map entries");
        select("Help");
        click("override");
        click("apply");
        var saved = UserConfig.read(file);
        require(
            !saved.appearance.windowScales.containsKey("Help")
                && !panel.probeApplyEnabled()
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light")
                && saved.input.shortcuts.get("Profiler").equals("Alt+F9")
                && saved.performance.cacheMiB == 17,
            "Inherit or latest unrelated merge failed");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset"))
                && state.engine.profiler().current().isEmpty(),
            "Scale editing changed project/private input/profiling");
        float base = baseline();
        var metrics = metrics(saved);
        for (var name : UiScalePreferences.WINDOWS) {
          double expected =
              base
                  * (double) saved.appearance.globalScale
                  * saved.appearance.windowScales.getOrDefault(name, 1f);
          require(
              Math.abs(metrics.get(name) - expected) < .0001,
              "Native font metric mismatch for " + name);
        }
        try (var paths = Files.walk(out)) {
          require(
              paths.noneMatch(p -> p.getFileName().toString().startsWith(".user-config-")),
              "Temporary preference file leaked");
        }
        Files.writeString(
            out.resolve("ui-scale-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("hiddenInvalidBlocked", true),
                    Map.entry("cachedFeedback", true),
                    Map.entry("hiddenDraftRetained", true),
                    Map.entry("staleRevert", true),
                    Map.entry("failedSaveRetry", true),
                    Map.entry("canonicalAfterSave", true),
                    Map.entry("inheritRemovedEntry", true),
                    Map.entry("unknownEntryRetained", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("baseFontSize", base),
                    Map.entry("fontSizes", metrics),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("allProductionWindowsExercised", false))));
        System.out.println(
            "UI_SCALE_NATIVE_PASS real scale editor / hidden invalid / failed save retry / inherit"
                + " / native helper font metrics; full production-window/DPI acceptance separate");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static float baseline() {
    ImGui.getIO().setFontGlobalScale(1);
    ImGui.newFrame();
    ImGui.begin("Scale baseline fixture");
    UiScale.window(null, "Resources");
    float result = ImGui.getTextLineHeight();
    ImGui.end();
    ImGui.render();
    return result;
  }

  private static Map<String, Float> metrics(UserConfig user) {
    var metrics = new LinkedHashMap<String, Float>();
    for (int frame = 0; frame < 3; frame++) {
      ImGui.getIO().setFontGlobalScale(user.appearance.globalScale);
      ImGui.newFrame();
      for (var name : UiScalePreferences.WINDOWS) {
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(800, 600, ImGuiCond.Always);
        ImGui.begin("Scale metric fixture " + name, ImGuiWindowFlags.NoSavedSettings);
        UiScale.window(user, name);
        metrics.put(name, ImGui.getTextLineHeight());
        ImGui.text("Metric fixture");
        ImGui.end();
      }
      ImGui.render();
    }
    return Map.copyOf(metrics);
  }

  private static void select(String name) {
    type("filter", name);
    click("target-" + name);
  }

  private static void click(String key) {
    var center = panel.probeControl(key);
    require(center != null, "Missing scale control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frames(2);
  }

  private static void type(String key, String text) {
    click(key);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    click("blur");
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.getIO().setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(1400, 1100, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      boolean shown = ImGui.begin("Scale settings fixture");
      UiScale.window(state.user, "User Settings");
      if (shown) panel.draw();
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
