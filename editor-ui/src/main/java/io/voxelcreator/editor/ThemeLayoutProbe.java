package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImBoolean;
import io.voxelcreator.model.ProjectData.Noise;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import java.util.*;

/** Deferred production Themes geometry/channel/ownership workflow without GLFW or a renderer. */
public final class ThemeLayoutProbe {
  private static EditorState state;
  private static ThemePanel panel;
  private static final ImBoolean open = new ImBoolean(true);
  private static int width = 1400, height = 1000, failures;
  private static final String ROW = "status:Warning";

  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    System.setProperty("voxel.configDir", out.toString());
    ImGui.createContext();
    try {
      ImGui.getIO().setIniFilename(null);
      ImGui.getIO().setDeltaTime(1f / 60);
      UiFontAtlas.install();
      try (var editor = new EditorState(new UserConfig(), false)) {
        state = editor;
        ThemeStyle.apply(ThemePalette.from(state.user.appearance));
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
        var file = out.resolve("user-config.json");
        state.user.save(file);
        panel = new ThemePanel(state.user, ignored -> failures++);
        panel.probeControls();
        var low =
            ThemePalette.starting("Dark")
                .color(ImGuiCol.Text, new ThemePalette.Rgba(.2f, .2f, .2f, 1))
                .color(ImGuiCol.WindowBg, new ThemePalette.Rgba(.2f, .2f, .2f, 1))
                .status("Warning", new ThemePalette.Rgba(.1f, .2f, .3f, 1));
        panel.probeEdit(low);
        frames(5);
        require(
            panel.submittedRows() > 0
                && panel.submittedRows() < ThemeRegistry.COLORS.size() + ThemePalette.ROLES.size(),
            "Palette rows not clipped");
        var unfiltered =
            Map.of(
                "plan",
                panel.channelLayout(),
                "rows",
                panel.colorRowsBounds(),
                "submitted",
                panel.submittedRows(),
                "total",
                ThemeRegistry.COLORS.size() + ThemePalette.ROLES.size(),
                "steps",
                panel.rowSteps(),
                "strides",
                panel.rowStrides());
        typeFilter("Warning");
        require(panel.submittedRows() == 1, "Palette filter did not isolate Warning");
        click("channel:" + ROW + ":R");
        var authored = panel.probeDraft();
        var warning = authored.status("Warning");
        require(
            warning.r() > .35
                && warning.r() < .65
                && warning.g() == .2f
                && warning.b() == .3f
                && warning.a() == 1
                && authored.colors().equals(low.colors()),
            "RGBA field changed another component or palette entry");
        state.user.save(out.resolve("ordinary-preferences.json"));
        require(
            ThemePalette.from(UserConfig.read(out.resolve("ordinary-preferences.json")).appearance)
                .equals(ThemePalette.starting("Dark")),
            "Ordinary save published preview");
        var ids = panel.channelIds();
        require(ids.size() == 4, "Missing RGBA IDs");
        var cases = new ArrayList<Map<String, Object>>();
        int[][] sizes = {{1400, 1000}, {700, 640}, {320, 640}, {260, 640}, {720, 640}, {1000, 300}};
        float[] global = {1, 1, 1.5f, 2.5f, 2.5f, 1};
        float[] multiplier = {1, 1, 1, 1, 2.5f, 1};
        long evaluations = panel.evaluations(), queries = panel.queries();
        for (int i = 0; i < sizes.length; i++) {
          width = sizes[i][0];
          height = sizes[i][1];
          state.user.appearance.globalScale = global[i];
          state.user.appearance.windowScales.put("Themes", multiplier[i]);
          frames(5);
          if (!panel.panes().columns()) {
            if (panel.showingContrast()) click("view");
            else {
              click("view");
              frames(3);
              click("view");
            }
          } else click("view");
          frames(5);
          var window = panel.windowLayout.bounds();
          require(DialogBounds.contains(window, panel.layout.body()), "Theme body outside window");
          require(
              panel
                  .layout
                  .actions()
                  .keySet()
                  .equals(Set.of("save", "revert", "reset", "resume", "view")),
              "Missing persistent theme action");
          for (var action : panel.layout.actions().values())
            require(DialogBounds.contains(window, action), "Theme footer outside window");
          require(
              panel.probeDraft().equals(authored)
                  && panel.channelIds().equals(ids)
                  && panel.probeGuarded()
                  && !panel.probePaused()
                  && panel.probeApplyEnabled()
                  && panel.evaluations() == evaluations
                  && panel.queries() == queries
                  && ThemeStyle.snapshot().equals(panel.probeColors()),
              "Reflow/view change altered private palette, identity, cache or safe/native style");
          var channels = new LinkedHashMap<String, DialogBounds.Rect>();
          for (String axis : List.of("R", "G", "B", "A")) {
            var bounds = panel.controlBounds().get("channel:" + ROW + ":" + axis);
            require(
                bounds != null
                    && bounds.x() >= panel.colorRowsBounds().x() - .1
                    && bounds.x() + bounds.width()
                        <= panel.colorRowsBounds().x() + panel.colorRowsBounds().width() + .1,
                "RGBA channel needs horizontal scrolling");
            channels.put(axis, bounds);
          }
          for (var entry : panel.rowSteps().entrySet())
            require(
                entry.getValue() <= panel.channelLayout().rowHeight() + .1
                    && Math.abs(
                            panel.rowStrides().get(entry.getKey())
                                - panel.channelLayout().rowHeight())
                        < .1,
                "Theme native row exceeds its reserved stride");
          var receipt = new LinkedHashMap<String, Object>();
          receipt.put("scenario", i);
          receipt.put("window", window);
          receipt.put("body", panel.layout.body());
          receipt.put("actions", panel.layout.actions());
          receipt.put("panes", panel.panes());
          receipt.put("palette", panel.paletteBounds());
          receipt.put("rows", panel.colorRowsBounds());
          receipt.put("channels", channels);
          receipt.put("plan", panel.channelLayout());
          receipt.put("ids", ids);
          receipt.put("steps", panel.rowSteps());
          receipt.put("strides", panel.rowStrides());
          receipt.put("globalScale", global[i]);
          receipt.put("windowScale", multiplier[i]);
          if (!panel.panes().columns()) {
            click("view");
            frames(4);
            require(
                panel.showingContrast()
                    && panel.paletteBounds() == null
                    && panel.contrastBounds() != null
                    && panel.probeDraft().equals(authored),
                "Narrow contrast switch lost private palette");
            receipt.put("contrast", panel.contrastBounds());
            click("view");
            frames(4);
          } else receipt.put("contrast", panel.contrastBounds());
          require(
              panel.probeChecks().stream()
                  .anyMatch(
                      c -> c.label().equals("Text / WindowBg") && c.ratio() == 1 && c.attention()),
              "Low contrast feedback changed during layout");
          cases.add(receipt);
        }
        width = 1400;
        height = 1000;
        state.user.appearance.globalScale = 1;
        state.user.appearance.windowScales.remove("Themes");
        frames(5);
        ThemeStyle.apply(ThemePalette.starting("Light"));
        frames(3);
        require(
            panel.probePaused()
                && !panel.probeApplyEnabled()
                && panel.probeDraft().equals(authored),
            "External style did not pause private preview");
        click("resume");
        frames(3);
        require(
            !panel.probePaused()
                && panel.probeDraft().equals(authored)
                && ThemeStyle.snapshot().equals(panel.probeColors()),
            "Persistent Resume lost ownership");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("keep"), "original");
        click("save");
        width = 360;
        height = 640;
        frames(5);
        require(
            failures == 1
                && panel.probeDraft().equals(authored)
                && ThemePalette.from(state.user.appearance).equals(ThemePalette.starting("Dark"))
                && Files.readString(file.resolve("keep")).equals("original"),
            "Failed save/reflow changed active/private palette");
        state.user.workspace.width = 1234;
        state.user.appearance.globalScale = 1.25f;
        frames(3);
        Files.delete(file.resolve("keep"));
        Files.delete(file);
        click("save");
        frames(3);
        var saved = UserConfig.read(file);
        require(
            ThemePalette.from(saved.appearance).equals(authored)
                && saved.workspace.width == 1234
                && saved.appearance.globalScale == 1.25f,
            "Footer retry lost palette/unrelated preferences");
        open.set(false);
        frame();
        require(
            ThemeStyle.snapshot().equals(ThemeStyle.resolve(authored)),
            "Close did not restore saved native style");
        require(
            project.equals(ProjectIO.JSON.valueToTree(state.project()))
                && state.revision == revision
                && state.document.historyBytes() == history
                && state.document.dirty() == dirty
                && state.draft == draft
                && draft.id.equals("private")
                && state.seedInputs.text("seedOffset").equals("-"),
            "Theme changed project/private Properties");
        Files.writeString(
            out.resolve("theme-layout-native.json"),
            ProjectIO.JSON.writeValueAsString(
                Map.ofEntries(
                    Map.entry("formatVersion", 1),
                    Map.entry("fontSource", "user-scale-fixture"),
                    Map.entry("cases", cases),
                    Map.entry("unfiltered", unfiltered),
                    Map.entry("warning", warning.array()),
                    Map.entry("safeInspector", true),
                    Map.entry("ordinarySaveExcludedDraft", true),
                    Map.entry("externalPauseResume", true),
                    Map.entry("writeRetry", true),
                    Map.entry("projectPreserved", true),
                    Map.entry("closeRestoredSavedStyle", true),
                    Map.entry("physicalDpiVerified", false),
                    Map.entry("physicalDetachedVerified", false),
                    Map.entry("gpuFontTextureVerified", false))));
        System.out.println(
            "THEME_LAYOUT_NATIVE_PASS responsive RGBA/panes, readable private preview, persistent"
                + " actions, pause/resume, clipped stride and save retry");
      }
    } finally {
      ImGui.destroyContext();
    }
  }

  private static void typeFilter(String text) {
    click("filter");
    var io = ImGui.getIO();
    io.addKeyEvent(ImGuiKey.ModCtrl, true);
    io.addKeyEvent(ImGuiKey.A, true);
    frame();
    io.addKeyEvent(ImGuiKey.ModCtrl, false);
    io.addKeyEvent(ImGuiKey.A, false);
    frame();
    io.addInputCharactersUTF8(text);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, true);
    frame();
    io.addKeyEvent(ImGuiKey.Enter, false);
    frames(3);
  }

  private static void click(String key) {
    var at = panel.probeControl(key);
    require(at != null, "Missing Theme control " + key);
    var io = ImGui.getIO();
    io.addMousePosEvent(at[0], at[1]);
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
    io.setDisplaySize(width + 32, height + 32);
    io.setFontGlobalScale(state.user.appearance.globalScale);
    ImGui.newFrame();
    ImGui.setNextWindowPos(16, 16, ImGuiCond.Always);
    ImGui.setNextWindowSize(width, height, ImGuiCond.Always);
    panel.draw(open);
    ImGui.render();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
