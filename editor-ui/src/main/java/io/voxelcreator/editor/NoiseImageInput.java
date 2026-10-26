package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;

/** Per-surface production ImGui event routing and captured 2D pan ownership. */
final class NoiseImageInput {
  private NoiseImageNavigation baseline, last;
  private float startX, startY;

  void reset() {
    baseline = last = null;
  }

  NoiseImageNavigation navigate(
      NoiseImageNavigation before, boolean hovered, float px, float py, float width, float height) {
    // Modal/context UI or another focused window supersedes this surface's gesture.
    if (ShortcutKeys.popup() || !ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)) {
      reset();
      return before;
    }
    var io = ImGui.getIO();
    // External navigation (e.g. teleport) supersedes a held gesture.
    if (baseline != null && !before.equals(last)) reset();
    if (baseline != null) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
        var restored = baseline;
        reset();
        return restored;
      }
      if (!ImGui.isMouseDown(0)) {
        reset();
        return before;
      }
      if (ImGui.isMouseDragging(0)) {
        try {
          last =
              baseline.pan(
                  (io.getMousePosX() - startX) / (double) width,
                  (io.getMousePosY() - startY) / (double) height);
        } catch (IllegalArgumentException failure) {
          reset();
          throw failure;
        }
        return last;
      }
      return before;
    }
    if (!hovered) return before;
    if (ImGui.isMouseClicked(0)) {
      baseline = last = before;
      startX = io.getMousePosX();
      startY = io.getMousePosY();
      return before;
    }
    // A held button without ownership began outside this surface or was cancelled.
    if (ImGui.isMouseDown(0)) return before;
    if (io.getMouseWheel() != 0)
      return before.zoom(
          (io.getMousePosX() - px) / (double) width,
          (io.getMousePosY() - py) / (double) height,
          io.getMouseWheel());
    return before;
  }
}
