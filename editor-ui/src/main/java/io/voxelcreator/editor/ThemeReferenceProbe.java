package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiCol;
import imgui.type.ImBoolean;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;

/** Isolated real ImGui palette/preview reference; never reads normal user preferences. */
public final class ThemeReferenceProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Pass the isolated output directory");
    Path output = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(output);
    System.setProperty("voxel.configDir", output.toString());
    ImGui.createContext();
    try {
      var io = ImGui.getIO();
      io.setIniFilename(null);
      io.setDisplaySize(1200, 850);
      io.setDeltaTime(1f / 60);
      io.getFonts().addFontDefault();
      io.getFonts().build();
      var profiles = new ArrayList<Map<String, Object>>();
      for (String base : List.of("Dark", "Light")) {
        var palette = ThemePalette.starting(base);
        ThemeStyle.apply(palette);
        var before = ThemeStyle.snapshot();
        ThemeStyle.resolve(ThemePalette.starting(base.equals("Dark") ? "Light" : "Dark"));
        if (!before.equals(ThemeStyle.snapshot()))
          throw new AssertionError("Resolving a palette changed native style");
        profiles.add(report(base, palette));
      }
      var alpha =
          ThemePalette.starting("Dark")
              .color(ImGuiCol.WindowBg, new ThemePalette.Rgba(.7f, .3f, .1f, .45f))
              .color(ImGuiCol.Text, new ThemePalette.Rgba(.9f, .8f, .5f, .6f))
              .color(ImGuiCol.FrameBg, new ThemePalette.Rgba(.2f, .5f, .8f, .3f))
              .status("Warning", new ThemePalette.Rgba(1, 1, 1, .5f));
      profiles.add(report("Alpha composition", alpha));
      var user = new UserConfig();
      ThemeStyle.apply(ThemePalette.from(user.appearance));
      var panel = new ThemePanel(user);
      panel.smokeDraft();
      var open = new ImBoolean(true);
      for (int i = 0; i < 3; i++) {
        ImGui.newFrame();
        panel.draw(open);
        ImGui.render();
      }
      user.save(output.resolve("preview-workspace-save.json"));
      if (!ThemePalette.from(
              UserConfig.read(output.resolve("preview-workspace-save.json")).appearance)
          .equals(ThemePalette.starting("Dark")))
        throw new AssertionError("Workspace save persisted preview");
      panel.assertSmokeDrawn();
      open.set(false);
      panel.draw(open);
      var restored = ImGui.getStyle().getColor(ImGuiCol.WindowBg);
      if (restored.x != .065f || restored.y != .078f || restored.z != .1f)
        throw new AssertionError("Closing preview did not restore native colors");
      try (var state = new EditorState(user, false)) {
        var noise = new Noise();
        noise.id = "theme-invariance";
        noise.algorithm.key = "CONSTANT";
        noise.algorithm.parameters.clear();
        noise.algorithm.parameters.put("value", .25);
        state.change(p -> p.noise2D.add(noise));
        state.select("noise2D", noise.uuid);
        var project = ProjectIO.JSON.valueToTree(state.project());
        Object draft = state.draft;
        long revision = state.revision, history = state.document.historyBytes();
        boolean dirty = state.document.dirty();
        var session = new ThemeEditSession(user);
        session.edit(
            ThemePalette.starting("Light")
                .color(ImGuiCol.Header, new ThemePalette.Rgba(.7f, .8f, .9f, 1))
                .status("Error", new ThemePalette.Rgba(.6f, 0, .1f, 1)));
        user.appearance.globalScale = 1.25f;
        user.workspace.width = 1234;
        session.apply(user, output.resolve("user-config.json"));
        ThemeStyle.apply(session.draft());
        var reload = UserConfig.read(output.resolve("user-config.json"));
        if (!ThemePalette.from(reload.appearance).equals(session.draft())
            || reload.appearance.globalScale != 1.25f
            || reload.workspace.width != 1234)
          throw new AssertionError("Theme restart or preference merge failed");
        var sample = new NoiseCompiler(state.project(), 0).compile(noise.uuid);
        if (sample.sample(2, 0, -3) != .25
            || !project.equals(ProjectIO.JSON.valueToTree(state.project()))
            || draft != state.draft
            || revision != state.revision
            || history != state.document.historyBytes()
            || dirty != state.document.dirty())
          throw new AssertionError("Theme changed project state or evaluation");
      }
      Files.writeString(
          output.resolve("theme-contrast.json"),
          ProjectIO.JSON.writeValueAsString(Map.of("formatVersion", 1, "profiles", profiles)));
      System.out.println(
          "THEME_REFERENCE_NATIVE_PASS Dark / Light / alpha composition / readable inspector /"
              + " private preview / workspace save exclusion / native restore / atomic save /"
              + " restart / project and numeric invariance");
    } finally {
      ImGui.destroyContext();
    }
  }

  private static Map<String, Object> report(String name, ThemePalette palette) {
    var resolved = ThemeStyle.resolve(palette);
    var colors = new TreeMap<Integer, float[]>();
    resolved.forEach((key, value) -> colors.put(key, value.array()));
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
        ThemeContrast.audit(resolved, palette));
  }
}
