package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.*;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred real numeric inputs and Apply/Revert; no renderer, worker-load or hardware claim. */
public final class PerformancePreferencesProbe {
  private static PerformancePanel panel;
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
      io.setDisplaySize(1400, 1200);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      ThemeStyle.apply(ThemePalette.starting("Dark"));
      var user = new UserConfig();
      user.performance.workers = 2;
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
        var engine = state.engine;
        panel =
            new PerformancePanel(
                user,
                file,
                message ->
                    state.error(
                        "PERFORMANCE_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel.probeControls();
        frames(3);
        type("maxPreviewDimension", "16");
        require(!panel.probeApplyEnabled(), "Mismatched default extent enabled Apply");
        click("apply");
        require(
            user.performance.maxPreviewDimension == 96
                && user.visualization.previewSize == 32
                && Arrays.equals(initial, Files.readAllBytes(file)),
            "Invalid coupled Apply changed preferences");
        type("previewSize", "16");
        type("previewCellBudget", "-");
        require(
            !panel.probeApplyEnabled() && panel.probeText().get(Field.PREVIEW_CELLS).equals("-"),
            "Intermediate raw budget was lost or enabled Apply");
        user.save(out.resolve("periodic-preferences.json"));
        stage();
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
            "Hidden/collapsed host lost draft");
        collapsed = false;
        frames(2);
        user.performance.uploadKiB = 1024;
        frames(2);
        require(
            !panel.probeApplyEnabled() && panel.probeText().equals(raw),
            "Changed active limits replaced private input");
        click("revert");
        require(
            panel.probeText().get(Field.MAX_DIMENSION).equals("96")
                && panel.probeText().get(Field.UPLOAD_KIB).equals("1024"),
            "Revert did not reload active limits");
        stage();
        raw = panel.probeText();
        var active = PerformancePreferences.from(user);
        Files.delete(file);
        Files.createDirectory(file);
        Files.write(file.resolve("keep"), initial);
        click("apply");
        require(
            active.equals(PerformancePreferences.from(user))
                && panel.probeText().equals(raw)
                && Arrays.equals(initial, Files.readAllBytes(file.resolve("keep")))
                && state.events.snapshot().stream()
                    .anyMatch(e -> e.code().equals("PERFORMANCE_SAVE_FAILED")),
            "Failed save changed live limits, raw input or prior file");
        user.workspace.width = 1234;
        user.appearance.theme = "Light";
        user.input.shortcuts.put("Profiler", "Alt+F9");
        user.performance.updateEndpoint = "https://updates.example.test/meta.json";
        user.performance.trustedOrigins.add("https://updates.example.test");
        user.visualization.grid = false;
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("apply");
        var saved = UserConfig.read(file);
        require(
            PerformancePreferences.from(saved).equals(PerformancePreferences.from(user))
                && saved.performance.previewCellBudget == 9007199254740993L
                && saved.visualization.previewSize == 16
                && saved.performance.maxPreviewDimension == 16
                && saved.performance.cacheMiB == 0
                && saved.performance.workers == 4
                && saved.performance.engineMiB == 256
                && saved.performance.uploadKiB == 64
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light")
                && saved.input.shortcuts.get("Profiler").equals("Alt+F9")
                && saved.performance.updateEndpoint.equals(user.performance.updateEndpoint)
                && saved.performance.trustedOrigins.equals(user.performance.trustedOrigins)
                && !saved.visualization.grid,
            "Retry lost exact limits or latest unrelated preferences");
        require(
            panel.probeText().get(Field.MAX_DIMENSION).equals("16")
                && panel.probeText().get(Field.CACHE_MIB).equals("0")
                && !panel.probeApplyEnabled()
                && panel.restartRequired(),
            "Canonical save or restart notice missing");
        byte[] accepted = Files.readAllBytes(file);
        click("apply");
        require(
            Arrays.equals(accepted, Files.readAllBytes(file)),
            "Unchanged Apply rewrote preferences");
        type("cacheMiB", "77");
        click("revert");
        require(
            panel.probeText().get(Field.CACHE_MIB).equals("0") && !panel.probeApplyEnabled(),
            "Explicit Revert did not discard private edit");
        var restarted =
            new PerformancePanel(
                saved,
                file,
                message -> {
                  throw new AssertionError(message);
                });
        require(!restarted.restartRequired(), "New host retained prior launch values");
        require(
            state.engine == engine
                && state.engine.profiler().current().isEmpty()
                && project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Preference workflow changed engine, project or private input");
        try (var paths = Files.walk(out)) {
          require(
              paths.noneMatch(p -> p.getFileName().toString().matches("\\.user-config-.*\\.tmp")),
              "Temporary configuration leaked");
        }
        Files.writeString(
            out.resolve("performance-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("invalidCouplingBlocked", true),
                    Map.entry("rawBudgetRetained", true),
                    Map.entry("periodicSaveExcludedDraft", true),
                    Map.entry("cachedFeedback", true),
                    Map.entry("hiddenDraftRetained", true),
                    Map.entry("staleBaseRevert", true),
                    Map.entry("failedWriteRetry", true),
                    Map.entry("exactLongRoundTrip", true),
                    Map.entry("canonicalAfterSave", true),
                    Map.entry("restartNotice", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("engineRetained", true))));
        System.out.println(
            "PERFORMANCE_PREFERENCES_NATIVE_PASS private limits / coupled extent / exact long /"
                + " failed-save retry / restart notice");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void stage() {
    type("maxPreviewDimension", "+0016");
    type("previewSize", "0016");
    type("previewCellBudget", "9007199254740993");
    type("cacheMiB", "+0000");
    type("workers", "4");
    type("engineMiB", "256");
    type("sceneCpuMiB", "16");
    type("sceneGpuMiB", "8");
    type("uploadKiB", "64");
    type("renderDistance", "0");
    type("verticalDistance", "0");
    type("lodStart", "1");
    type("lodQuality", "2");
    type("streamChunks", "27");
  }

  private static void click(String key) {
    var center = panel.probeControl(key);
    require(center != null, "Missing performance control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frame();
  }

  private static void type(String key, String value) {
    click(key);
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(value);
    frames(3);
    click("blur");
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    if (visible) {
      ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
      ImGui.setNextWindowSize(1300, 1100, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      if (ImGui.begin("Performance preferences fixture")) panel.draw();
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
