package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import java.util.*;

/** Binding to pinned imgui-java named physical keys. No character/text-input interception. */
public final class ShortcutKeys {
  private ShortcutKeys() {}

  private static final Map<String, Integer> CODES;

  static {
    var codes = new LinkedHashMap<String, Integer>();
    for (char c = 'A'; c <= 'Z'; c++) codes.put(Character.toString(c), ImGuiKey.A + c - 'A');
    for (int i = 0; i < 10; i++) codes.put(Integer.toString(i), ImGuiKey._0 + i);
    for (int i = 1; i <= 12; i++) codes.put("F" + i, ImGuiKey.F1 + i - 1);
    for (String name : Shortcuts.keys())
      if (!codes.containsKey(name)) {
        String field =
            switch (name) {
              case "Left", "Right", "Up", "Down" -> name + "Arrow";
              default -> name;
            };
        try {
          codes.put(name, ImGuiKey.class.getField(field).getInt(null));
        } catch (ReflectiveOperationException e) {
          throw new ExceptionInInitializerError(e);
        }
      }
    CODES = Collections.unmodifiableMap(codes);
  }

  public static int code(String key) {
    return Objects.requireNonNull(CODES.get(key), "Unknown physical key");
  }

  public static int modifiers() {
    var io = ImGui.getIO();
    return (io.getKeyCtrl() ? 1 : 0)
        | (io.getKeyShift() ? 2 : 0)
        | (io.getKeyAlt() ? 4 : 0)
        | (io.getKeySuper() ? 8 : 0);
  }

  public static boolean popup() {
    return ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopup);
  }

  private static Map<String, String> previous;
  private static Shortcuts.Bindings compiled;

  public static Optional<Shortcuts.Action> match(
      Map<String, String> bindings, Shortcuts.Focus focus, boolean recording) {
    if (previous == null || !previous.equals(bindings)) {
      compiled = Shortcuts.compile(bindings);
      previous = new LinkedHashMap<>(bindings);
    }
    return compiled.match(
        new Shortcuts.Context(
            focus, ImGui.getIO().getWantTextInput(), popup(), ImGui.isAnyItemActive(), recording),
        modifiers(),
        key -> ImGui.isKeyPressed(code(key), false));
  }

  public static boolean held() {
    return modifiers() != 0
        || ImGui.isKeyDown(ImGuiKey.Escape)
        || CODES.values().stream().anyMatch(ImGui::isKeyDown);
  }

  public static Optional<Shortcuts.Chord> pressed() {
    for (var key : CODES.entrySet())
      if (ImGui.isKeyPressed(key.getValue(), false))
        return Optional.of(new Shortcuts.Chord(modifiers(), key.getKey()));
    return Optional.empty();
  }
}
