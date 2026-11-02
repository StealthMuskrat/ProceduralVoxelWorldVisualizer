package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;

/** One clipped status line with an inherited-font tooltip; raw observations stay unchanged. */
final class ViewportSampleStatus {
  static void draw(String id, String text, float width, float height) {
    draw(id, text, "", width, height);
  }

  static void draw(String id, String text, String details, float width, float height) {
    if (height <= 0) return;
    String shown = StatusBarText.clip(text, 1024);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
    UiScale.beginChild(
        id,
        Math.max(1, width),
        height,
        false,
        ImGuiWindowFlags.NoScrollbar
            | ImGuiWindowFlags.NoScrollWithMouse
            | ImGuiWindowFlags.NoNavFocus);
    ImGui.popStyleVar();
    ImGui.textDisabled(shown);
    if (ImGui.isItemHovered())
      UiScale.tooltip(details.isEmpty() ? shown : shown + "\n" + StatusBarText.clip(details, 2048));
    ImGui.endChild();
  }

  private ViewportSampleStatus() {}
}
