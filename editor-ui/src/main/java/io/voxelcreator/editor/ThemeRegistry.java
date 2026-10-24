package io.voxelcreator.editor;

import imgui.flag.ImGuiCol;
import java.lang.reflect.Modifier;
import java.util.*;

/** Versioned presentation-only palette keys for the pinned ImGui binding. */
public final class ThemeRegistry {
  private ThemeRegistry() {}

  public record Color(int key, String name, String group) {}

  public static final List<Color> COLORS;

  static {
    var rows = new ArrayList<Color>();
    for (var field : ImGuiCol.class.getFields()) {
      if (field.getType() != int.class
          || !Modifier.isStatic(field.getModifiers())
          || field.getName().equals("COUNT")) continue;
      try {
        int key = field.getInt(null);
        rows.add(new Color(key, field.getName(), group(field.getName())));
      } catch (IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }
    rows.sort(Comparator.comparingInt(Color::key));
    if (rows.size() != ImGuiCol.COUNT
        || rows.stream().map(Color::key).distinct().count() != ImGuiCol.COUNT)
      throw new ExceptionInInitializerError("Palette keys do not match pinned binding");
    COLORS = List.copyOf(rows);
  }

  private static String group(String name) {
    if (name.startsWith("Text")) return "Text and selection";
    if (name.startsWith("Header")) return "Section headers";
    if (name.startsWith("Button")
        || name.startsWith("Frame")
        || name.startsWith("Check")
        || name.startsWith("Slider")) return "Buttons and inputs";
    if (name.contains("Border") || name.startsWith("Separator") || name.startsWith("Resize"))
      return "Borders and resizing";
    if (name.startsWith("Tab")
        || name.startsWith("Dock")
        || name.startsWith("Title")
        || name.startsWith("Menu")) return "Tabs, menus and docking";
    if (name.endsWith("Bg")) return "Windows and panels";
    return "Navigation and plots";
  }

  public static Map<String, Object> registry() {
    return Map.of(
        "registryVersion",
        1,
        "startingAppearances",
        List.of("Dark", "Light"),
        "colors",
        COLORS,
        "statusRoles",
        ThemePalette.ROLES,
        "componentRange",
        List.of(0, 1),
        "textContrastTarget",
        4.5,
        "controlContrastTarget",
        3.0);
  }
}
