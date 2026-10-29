package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Deferred real theme toolbar/default/filter controls with explicit external-change fixtures. */
public final class ThemeWorkspaceProbe {
  private static ThemePanel panel;
  private static EditorState state;
  private static final ImBoolean open = new ImBoolean(true);
  private static boolean collapsed;

  public static void main(String[] args) throws Exception {
    Path out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1300, 1100);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      var user = new UserConfig();
      Path file = out.resolve("user-config.json");
      user.save(file);
      byte[] initial = Files.readAllBytes(file);
      ThemeStyle.apply(ThemePalette.from(user.appearance));
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
            new ThemePanel(
                user,
                message ->
                    state.error("THEME_EDIT_FAILED", EditorEvents.Subsystem.APPLICATION, message));
        panel.probeControls();
        var low =
            ThemePalette.starting("Dark")
                .color(ImGuiCol.Text, new ThemePalette.Rgba(.2f, .2f, .2f, 1))
                .color(ImGuiCol.WindowBg, new ThemePalette.Rgba(.2f, .2f, .2f, 1));
        panel.probeEdit(low);
        frames(4);
        long evaluations = panel.evaluations(), queries = panel.queries();
        int submitted = panel.submittedRows();
        frames(5);
        require(
            panel.evaluations() == evaluations
                && panel.queries() == queries
                && submitted > 0
                && submitted < 20
                && panel.probeGuarded(),
            "Feedback/projection/guard/clipping failed");
        require(
            panel.probeChecks().stream()
                .anyMatch(
                    c -> c.label().equals("Text / WindowBg") && c.ratio() == 1 && c.attention()),
            "Low contrast was not reported");
        var profiles = new ArrayList<Map<String, Object>>();
        profiles.add(report("Private low contrast"));
        user.save(out.resolve("periodic-preferences.json"));
        require(
            ThemePalette.from(UserConfig.read(out.resolve("periodic-preferences.json")).appearance)
                .equals(ThemePalette.starting("Dark")),
            "Periodic save included preview");
        ThemeStyle.apply(ThemePalette.starting("Light"));
        var external = ThemeStyle.snapshot();
        frames(3);
        require(
            panel.probePaused()
                && !panel.probeApplyEnabled()
                && panel.probeDraft().equals(low)
                && external.equals(ThemeStyle.snapshot()),
            "External native style was overwritten");
        open.set(false);
        frame();
        require(external.equals(ThemeStyle.snapshot()), "Closing replaced a newer native style");
        open.set(true);
        frames(3);
        panel.probeEdit(low);
        frames(2);
        ThemeStyle.apply(ThemePalette.starting("Light"));
        frames(2);
        click("resume");
        require(
            !panel.probePaused()
                && panel.probeDraft().equals(low)
                && ThemeStyle.snapshot().equals(panel.probeColors()),
            "Explicit resume lost authored/native colors");
        user.appearance.theme = "Light";
        ThemeStyle.apply(ThemePalette.from(user.appearance));
        frames(3);
        require(
            panel.probePaused() && !panel.probeApplyEnabled() && panel.probeDraft().equals(low),
            "Stale active base lost private preview");
        click("save");
        require(Arrays.equals(initial, Files.readAllBytes(file)), "Stale/paused Save published");
        click("revert");
        require(
            !panel.probePaused()
                && panel.probeDraft().equals(ThemePalette.starting("Light"))
                && ThemeStyle.snapshot().equals(panel.probeColors()),
            "Revert was overwritten by inspector style restoration");
        var custom =
            ThemePalette.starting("Light")
                .color(ImGuiCol.Header, new ThemePalette.Rgba(.7f, .8f, .9f, .5f))
                .status("Warning", new ThemePalette.Rgba(.4f, .2f, 0, 1));
        collapsed = true;
        evaluations = panel.evaluations();
        panel.probeEdit(custom);
        frames(3);
        require(
            panel.evaluations() == evaluations && panel.probeDraft().equals(custom),
            "Collapsed content rebuilt private feedback");
        collapsed = false;
        frames(3);
        Files.delete(file);
        Files.createDirectory(file);
        Files.write(file.resolve("keep"), initial);
        click("save");
        require(
            ThemePalette.from(user.appearance).equals(ThemePalette.starting("Light"))
                && panel.probeDraft().equals(custom)
                && Arrays.equals(initial, Files.readAllBytes(file.resolve("keep")))
                && state.events.snapshot().stream()
                    .anyMatch(e -> e.code().equals("THEME_EDIT_FAILED")),
            "Failed Save lost active/private state or feedback");
        user.workspace.width = 1234;
        user.appearance.globalScale = 1.25f;
        user.input.shortcuts.put("Profiler", "Alt+F9");
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("save");
        var restored = UserConfig.read(file);
        require(
            ThemePalette.from(restored.appearance).equals(custom)
                && restored.workspace.width == 1234
                && restored.appearance.globalScale == 1.25f
                && restored.input.shortcuts.get("Profiler").equals("Alt+F9"),
            "Retry lost theme or unrelated preferences");
        byte[] saved = Files.readAllBytes(file);
        click("reset");
        require(
            panel.probeDraft().equals(ThemePalette.starting("Dark"))
                && ThemePalette.from(user.appearance).equals(custom)
                && Arrays.equals(saved, Files.readAllBytes(file)),
            "Reset persisted without Save");
        click("revert");
        typeFilter("Warning");
        require(panel.submittedRows() == 1, "Palette filter did not narrow rows");
        click("default:status:Warning");
        require(
            !panel.probeDraft().statuses().containsKey("Warning")
                && panel.probeDraft().colors().equals(custom.colors())
                && ThemePalette.from(user.appearance).equals(custom),
            "Starting color changed other/private/active values");
        click("revert");
        require(
            panel.probeDraft().equals(custom)
                && panel.probeGuarded()
                && ThemeStyle.snapshot().equals(panel.probeColors()),
            "Revert did not restore saved authored palette");
        profiles.add(report("Saved preview"));
        open.set(false);
        frame();
        require(
            ThemeStyle.snapshot().equals(ThemeStyle.resolve(custom)),
            "Closing owned preview did not restore saved theme");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && revision == state.revision
                && history == state.document.historyBytes()
                && dirty == state.document.dirty()
                && state.draft == privateDraft
                && privateDraft.id.equals("private")
                && "-".equals(state.seedInputs.text("seedOffset")),
            "Theme workflow changed project/private input");
        Files.writeString(
            out.resolve("theme-workspace-contrast.json"),
            ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "profiles", profiles)));
        Files.writeString(
            out.resolve("theme-workspace-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("externalStylePreserved", true),
                    Map.entry("explicitResume", true),
                    Map.entry("staleBaseRevert", true),
                    Map.entry("failedWriteRetry", true),
                    Map.entry("resetAndStartingColorPrivate", true),
                    Map.entry("cachedFeedbackAndRows", true),
                    Map.entry("readableInspector", true),
                    Map.entry("collapsedContent", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("submittedRows", submitted))));
        System.out.println(
            "THEME_WORKSPACE_NATIVE_PASS ownership / resume / stale Revert / write retry / cached"
                + " feedback / private input");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Map<String, Object> report(String name) {
    var palette = panel.probeDraft();
    var colors = new TreeMap<Integer, float[]>();
    panel.probeColors().forEach((key, value) -> colors.put(key, value.array()));
    var statuses = new TreeMap<String, float[]>();
    for (String role : ThemePalette.ROLES) statuses.put(role, palette.status(role).array());
    return Map.of(
        "name",
        name,
        "base",
        palette.base(),
        "colors",
        colors,
        "statuses",
        statuses,
        "checks",
        panel.probeChecks());
  }

  private static void click(String name) {
    var center = panel.probeControl(name);
    require(center != null, "Missing theme control " + name);
    var io = ImGui.getIO();
    io.addMousePosEvent(center[0], center[1]);
    frame();
    io.addMouseButtonEvent(0, true);
    frame();
    io.addMouseButtonEvent(0, false);
    frame();
    frame();
  }

  private static void typeFilter(String text) {
    click("filter");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.A, false);
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    frame();
    io.addInputCharactersUTF8(text);
    frames(3);
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(2);
  }

  private static void frames(int count) {
    for (int i = 0; i < count; i++) frame();
  }

  private static void frame() {
    state.drain();
    ImGui.newFrame();
    ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
    ImGui.setNextWindowSize(1200, 1000, ImGuiCond.Always);
    ImGui.setNextWindowCollapsed(collapsed, ImGuiCond.Always);
    panel.draw(open);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
