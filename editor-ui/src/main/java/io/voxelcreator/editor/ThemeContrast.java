package io.voxelcreator.editor;

import imgui.flag.ImGuiCol;
import io.voxelcreator.editor.ThemePalette.Rgba;
import java.util.*;

/**
 * sRGB source-over composition followed by relative-luminance contrast; no display certification.
 */
public final class ThemeContrast {
  private ThemeContrast() {}

  public record Check(String label, double ratio, double target) {
    public boolean attention() {
      return ratio < target;
    }
  }

  public static Rgba over(Rgba foreground, Rgba background) {
    double alpha = foreground.a() + background.a() * (1 - foreground.a());
    if (alpha == 0) return new Rgba(0, 0, 0, 0);
    double w = background.a() * (1 - foreground.a());
    return new Rgba(
        (float) ((foreground.r() * foreground.a() + background.r() * w) / alpha),
        (float) ((foreground.g() * foreground.a() + background.g() * w) / alpha),
        (float) ((foreground.b() * foreground.a() + background.b() * w) / alpha),
        (float) alpha);
  }

  public static double luminance(Rgba color) {
    return .2126 * linear(color.r()) + .7152 * linear(color.g()) + .0722 * linear(color.b());
  }

  private static double linear(double c) {
    return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
  }

  /** Inputs are final opaque displayed RGB values. */
  public static double ratio(Rgba a, Rgba b) {
    if (a.a() != 1 || b.a() != 1)
      throw new IllegalArgumentException("Contrast requires composed opaque colors");
    double x = luminance(a), y = luminance(b);
    return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
  }

  public static List<Check> audit(Map<Integer, Rgba> colors, ThemePalette palette) {
    if (colors.size() != ImGuiCol.COUNT)
      throw new IllegalArgumentException("Incomplete resolved palette");
    Rgba canvas = palette.base().equals("Dark") ? new Rgba(0, 0, 0, 1) : new Rgba(1, 1, 1, 1);
    Rgba window = over(colors.get(ImGuiCol.WindowBg), canvas);
    var checks = new ArrayList<Check>();
    for (int key :
        new int[] {
          ImGuiCol.WindowBg,
          ImGuiCol.ChildBg,
          ImGuiCol.PopupBg,
          ImGuiCol.MenuBarBg,
          ImGuiCol.FrameBg,
          ImGuiCol.FrameBgHovered,
          ImGuiCol.FrameBgActive,
          ImGuiCol.Button,
          ImGuiCol.ButtonHovered,
          ImGuiCol.ButtonActive,
          ImGuiCol.Header,
          ImGuiCol.HeaderHovered,
          ImGuiCol.HeaderActive,
          ImGuiCol.Tab,
          ImGuiCol.TabActive,
          ImGuiCol.TabUnfocused,
          ImGuiCol.TabUnfocusedActive,
          ImGuiCol.TextSelectedBg
        }) {
      Rgba bg = key == ImGuiCol.WindowBg ? window : over(colors.get(key), window);
      String name = ThemeRegistry.COLORS.get(key).name();
      checks.add(new Check("Text / " + name, ratio(over(colors.get(ImGuiCol.Text), bg), bg), 4.5));
    }
    checks.add(
        new Check(
            "Secondary text / WindowBg",
            ratio(over(colors.get(ImGuiCol.TextDisabled), window), window),
            4.5));
    for (String role : ThemePalette.ROLES)
      checks.add(
          new Check(
              role + " text / WindowBg", ratio(over(palette.status(role), window), window), 4.5));
    Rgba frame = over(colors.get(ImGuiCol.FrameBg), window);
    checks.add(
        new Check(
            "Check mark / FrameBg", ratio(over(colors.get(ImGuiCol.CheckMark), frame), frame), 3));
    checks.add(
        new Check(
            "Slider grab / FrameBg",
            ratio(over(colors.get(ImGuiCol.SliderGrab), frame), frame),
            3));
    checks.add(
        new Check(
            "Border / WindowBg", ratio(over(colors.get(ImGuiCol.Border), window), window), 3));
    return List.copyOf(checks);
  }
}
