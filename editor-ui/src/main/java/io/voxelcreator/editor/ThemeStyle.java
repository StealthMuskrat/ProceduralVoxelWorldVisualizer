package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import io.voxelcreator.editor.ThemePalette.Rgba;
import java.util.*;

/** Native style operations stay on the editor thread, outside theme contrast/value services. */
public final class ThemeStyle {
  private static ThemePalette active = ThemePalette.starting("Dark");

  private ThemeStyle() {}

  static Map<Integer, Rgba> snapshot() {
    var colors = new TreeMap<Integer, Rgba>();
    for (int i = 0; i < ImGuiCol.COUNT; i++) {
      var c = ImGui.getStyle().getColor(i);
      colors.put(i, new Rgba(c.x, c.y, c.z, c.w));
    }
    return Collections.unmodifiableMap(colors);
  }

  private static void install(Map<Integer, Rgba> colors) {
    colors.forEach((key, c) -> ImGui.getStyle().setColor(key, c.r(), c.g(), c.b(), c.a()));
  }

  private static void starting(String base) {
    if (base.equals("Light")) ImGui.styleColorsLight();
    else ImGui.styleColorsDark();
    if (base.equals("Dark")) {
      var style = ImGui.getStyle();
      style.setColor(ImGuiCol.WindowBg, .065f, .078f, .1f, 1);
      style.setColor(ImGuiCol.Header, .15f, .24f, .29f, 1);
      style.setColor(ImGuiCol.Button, .13f, .25f, .29f, 1);
      style.setColor(ImGuiCol.TabActive, .15f, .35f, .39f, 1);
      style.setColor(ImGuiCol.CheckMark, .5f, .85f, .75f, 1);
    }
  }

  public static Map<Integer, Rgba> resolve(ThemePalette palette) {
    var previous = snapshot();
    try {
      starting(palette.base());
      install(palette.colors());
      return snapshot();
    } finally {
      install(previous);
    }
  }

  public static void apply(ThemePalette palette) {
    install(resolve(palette));
    active = palette;
    var style = ImGui.getStyle();
    style.setWindowRounding(5);
    style.setFrameRounding(4);
    style.setGrabRounding(4);
    style.setFramePadding(7, 5);
    style.setItemSpacing(8, 7);
  }

  public static void text(String role, String text) {
    var c = active.status(role);
    ImGui.textColored(c.r(), c.g(), c.b(), c.a(), text);
  }
}
