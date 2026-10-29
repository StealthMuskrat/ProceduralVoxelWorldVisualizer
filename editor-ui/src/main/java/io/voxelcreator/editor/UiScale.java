package io.voxelcreator.editor;

import imgui.ImGui;

/** Native top-level window multiplier. Called between Begin/End, including collapsed Begin. */
final class UiScale {
  private UiScale() {}

  static void window(UserConfig user, String name) {
    ImGui.setWindowFontScale(UiScalePreferences.window(user, name));
  }
}
