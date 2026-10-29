package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred real private trust inputs/Apply/Revert; reserved hosts are fixtures, never requested.
 */
public final class UpdateTrustProbe {
  private static UpdateTrustPanel panel;
  private static EditorState state;
  private static boolean visible = true, collapsed;
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final String RAW_ENDPOINT = " " + ENDPOINT + " ";
  private static final String RAW_ORIGINS =
      "HTTPS://UPDATES.EXAMPLE.TEST:443/\n"
          + "https://releases.example.test\n"
          + "https://updates.example.test";

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 950);
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
            new UpdateTrustPanel(
                user,
                file,
                message ->
                    state.error(
                        "UPDATE_TRUST_SAVE_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel.probeControls();
        frames(3);
        type("endpoint", "http://updates.example.test/meta.json");
        type("origins", RAW_ORIGINS);
        require(!panel.probeApplyEnabled(), "Invalid endpoint enabled Apply");
        click("apply");
        require(
            Arrays.equals(initial, Files.readAllBytes(file)), "Invalid Apply wrote preferences");
        stage();
        var raw = panel.probeText();
        long validations = panel.validations();
        frames(5);
        require(
            panel.validations() == validations && panel.probeApplyEnabled(),
            "Unchanged trust feedback rebuilt");
        user.save(out.resolve("periodic-preferences.json"));
        require(
            UserConfig.read(out.resolve("periodic-preferences.json"))
                .performance
                .updateEndpoint
                .isEmpty(),
            "Periodic save included pending endpoint");
        visible = false;
        frames(3);
        visible = true;
        collapsed = true;
        frames(3);
        require(
            panel.probeText().equals(raw) && panel.validations() == validations,
            "Hidden/collapsed host lost pending input");
        collapsed = false;
        frames(2);
        user.performance.trustedOrigins = new ArrayList<>(List.of("https://old.example.test"));
        frames(2);
        require(
            !panel.probeApplyEnabled() && panel.probeText().equals(raw),
            "Stale base changed private fields");
        click("revert");
        require(
            panel.probeText().endpoint().isEmpty()
                && panel.probeText().origins().equals("https://old.example.test"),
            "Revert did not reload active trust");
        stage();
        raw = panel.probeText();
        var active = user.performance.trustedOrigins;
        Files.delete(file);
        Files.createDirectory(file);
        Files.write(file.resolve("keep"), initial);
        click("apply");
        require(
            user.performance.updateEndpoint.isEmpty()
                && user.performance.trustedOrigins == active
                && panel.probeText().equals(raw)
                && Arrays.equals(initial, Files.readAllBytes(file.resolve("keep")))
                && state.events.snapshot().stream()
                    .anyMatch(e -> e.code().equals("UPDATE_TRUST_SAVE_FAILED")),
            "Failed write changed active trust/private text or lost feedback");
        user.workspace.width = 1234;
        user.appearance.theme = "Light";
        user.input.shortcuts.put("Profiler", "Alt+F9");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("apply");
        var saved = UserConfig.read(file);
        require(
            saved.performance.updateEndpoint.equals(ENDPOINT)
                && saved.performance.trustedOrigins.equals(
                    List.of("https://updates.example.test", "https://releases.example.test"))
                && saved.workspace.width == 1234
                && saved.appearance.theme.equals("Light")
                && saved.input.shortcuts.get("Profiler").equals("Alt+F9"),
            "Retry lost canonical trust or latest unrelated preferences");
        require(
            panel.probeText().endpoint().equals(ENDPOINT)
                && panel
                    .probeText()
                    .origins()
                    .equals("https://updates.example.test\nhttps://releases.example.test"),
            "Canonical values were not displayed after success");
        var metadata = AppMetadata.defaults();
        metadata.controls.get("noise").label = "Preference writer fixture";
        metadata.save(out.resolve("app-config.json"));
        require(
            AppMetadata.read(out.resolve("app-config.json"))
                .controls
                .get("noise")
                .label
                .equals("Preference writer fixture"),
            "Application profile did not round-trip through shared writer");
        String release =
            "{\"formatVersion\":1,\"version\":\"1.2.3\",\"releaseUrl\":\"https://releases.example.test/1.2.3\"}";
        require(
            UpdateChecker.parse(release, new HashSet<>(saved.performance.trustedOrigins))
                .version()
                .equals("1.2.3"),
            "Controlled metadata fixture rejected");
        Files.writeString(out.resolve("release-metadata.json"), release);
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Preference workflow changed project/private input");
        try (var paths = Files.walk(out)) {
          require(
              paths.noneMatch(
                  p -> p.getFileName().toString().matches("\\.(user|app)-config-.*\\.tmp")),
              "Temporary configuration leaked");
        }
        Files.writeString(
            out.resolve("update-trust-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("invalidBlocked", true),
                    Map.entry("cachedFeedback", true),
                    Map.entry("hiddenDraftRetained", true),
                    Map.entry("staleBaseRevert", true),
                    Map.entry("failedWriteRetry", true),
                    Map.entry("canonicalAfterSave", true),
                    Map.entry("profileWriterRoundTrip", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("networkRequests", 0))));
        System.out.println(
            "UPDATE_TRUST_NATIVE_PASS private fields / hidden host / stale Revert / failed-write"
                + " retry / canonical save / no network");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void stage() {
    type("endpoint", RAW_ENDPOINT);
    type("origins", RAW_ORIGINS);
  }

  private static void click(String key) {
    var center = panel.probeControl(key);
    require(center != null, "Missing update control " + key);
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
      ImGui.setNextWindowSize(1100, 850, ImGuiCond.Always);
      ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
      if (ImGui.begin("Update trust fixture")) panel.draw();
      ImGui.end();
    }
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
