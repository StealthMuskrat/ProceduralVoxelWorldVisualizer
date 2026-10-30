package io.voxelcreator.editor;

import imgui.ImGui;

/** User/window/DPI composition and exact font inheritance through native child/popup scopes. */
final class UiScale {
  private UiScale() {}

  static void window(UserConfig user, String name) {
    ImGui.setWindowFontScale(UiScalePreferences.window(user, name) * UiDpi.windowRatio());
  }

  private record Parent(float fontSize, float contentScale) {}

  private static Parent parent() {
    return new Parent(ImGui.getFontSize(), UiDpi.contentScale());
  }

  private static void inherit(Parent parent) {
    ImGui.setWindowFontScale(1);
    float desired =
        UiDpiPolicy.inheritedFont(parent.fontSize(), parent.contentScale(), UiDpi.contentScale());
    float baseline = ImGui.getFontSize();
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
    // Preserve the pinned SetTooltip replacement semantics; BeginTooltip appends instead.
    ImGui.setTooltip(text);
  }
}
