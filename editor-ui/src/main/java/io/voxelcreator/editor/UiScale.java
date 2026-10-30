package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.ImVec4;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiDragDropFlags;

/** User/window/DPI composition and exact font inheritance through native child/popup scopes. */
final class UiScale {
  private static final ThreadLocal<Boolean> DRAG_PREVIEW = new ThreadLocal<>();

  private UiScale() {}

  static void window(UserConfig user, String name) {
    ImGui.setWindowFontScale(UiScalePreferences.window(user, name) * UiDpi.windowRatio());
  }

  record Parent(float fontSize, float contentScale) {}

  static Parent parent() {
    return new Parent(ImGui.getTextLineHeight(), UiDpi.contentScale());
  }

  static void inherit(Parent parent) {
    ImGui.setWindowFontScale(1);
    float desired =
        UiDpiPolicy.inheritedFont(parent.fontSize(), parent.contentScale(), UiDpi.contentScale());
    float baseline = ImGui.getTextLineHeight();
    ImGui.setWindowFontScale(desired / baseline);
  }

  static boolean beginChild(String id, float width, float height, boolean border) {
    return beginChild(id, width, height, border, 0);
  }

  static boolean beginChild(String id, float width, float height, boolean border, int flags) {
    var parent = parent();
    boolean visible = ImGui.beginChild(id, width, height, border, flags);
    inherit(parent);
    return visible;
  }

  static boolean beginCombo(String label, String preview) {
    var parent = parent();
    boolean visible = ImGui.beginCombo(label, preview);
    if (visible) inherit(parent);
    return visible;
  }

  static boolean beginPopup(String title) {
    return beginPopup(title, 0);
  }

  static boolean beginPopup(String title, int flags) {
    var parent = parent();
    boolean visible = ImGui.beginPopup(title, flags);
    if (visible) inherit(parent);
    return visible;
  }

  static boolean beginPopupModal(String title) {
    return beginPopupModal(title, 0);
  }

  static boolean beginPopupModal(String title, int flags) {
    var parent = parent();
    boolean visible = ImGui.beginPopupModal(title, flags);
    if (visible) inherit(parent);
    return visible;
  }

  static boolean beginPopupContextItem(String id) {
    var parent = parent();
    boolean visible = ImGui.beginPopupContextItem(id);
    if (visible) inherit(parent);
    return visible;
  }

  static boolean beginMenu(String label) {
    var parent = parent();
    boolean visible = ImGui.beginMenu(label);
    if (visible) inherit(parent);
    return visible;
  }

  static void tooltip(String text) {
    UiTooltip.show(text);
  }

  static boolean beginDragDropSource() {
    return beginDragDropSource(0);
  }

  static boolean beginDragDropSource(int flags) {
    var parent = parent();
    boolean active = ImGui.beginDragDropSource(flags);
    if (active) {
      boolean preview = (flags & ImGuiDragDropFlags.SourceNoPreviewTooltip) == 0;
      DRAG_PREVIEW.set(preview);
      if (preview) inherit(parent);
    }
    return active;
  }

  static void endDragDropSource() {
    var preview = DRAG_PREVIEW.get();
    if (preview == null) throw new IllegalStateException("No owned drag source");
    try {
      if (preview) ImGui.setWindowFontScale(1);
    } finally {
      DRAG_PREVIEW.remove();
      ImGui.endDragDropSource();
    }
  }

  static boolean colorEdit3(String label, float[] color) {
    boolean changed = ImGui.colorEdit3(label, color, ImGuiColorEditFlags.NoTooltip);
    colorTooltip(color[0], color[1], color[2], 1);
    return changed;
  }

  static boolean colorEdit4(String label, float[] color, int flags) {
    boolean changed = ImGui.colorEdit4(label, color, flags | ImGuiColorEditFlags.NoTooltip);
    if ((flags & ImGuiColorEditFlags.NoTooltip) == 0)
      colorTooltip(color[0], color[1], color[2], color[3]);
    return changed;
  }

  static boolean colorButton(String label, ImVec4 color) {
    boolean clicked = ImGui.colorButton(label, color, ImGuiColorEditFlags.NoTooltip);
    colorTooltip(color.x, color.y, color.z, color.w);
    return clicked;
  }

  private static void colorTooltip(float r, float g, float b, float a) {
    if (ImGui.isItemHovered())
      tooltip(String.format(java.util.Locale.ROOT, "R %.3f  G %.3f  B %.3f  A %.3f", r, g, b, a));
  }
}
