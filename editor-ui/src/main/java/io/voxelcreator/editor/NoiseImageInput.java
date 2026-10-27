package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiFocusedFlags;
import imgui.flag.ImGuiKey;

/** Per-surface production ImGui event routing and captured 2D pan ownership. */
final class NoiseImageInput {
  private NoiseImageNavigation baseline, last;
  private float startX, startY, capturedWidth, capturedHeight;
  private boolean dragged, picked;
  private double started;

  boolean picked() {
    return picked;
  }

  void reset() {
    baseline = last = null;
    picked = false;
  }

  NoiseImageNavigation navigate(
      NoiseImageNavigation before, boolean hovered, float px, float py, float width, float height) {
    picked = false;
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
      boolean released = ImGui.isMouseReleased(0);
      if (!released && !ImGui.isMouseDown(0)) {
        reset();
        return before;
      }
      double dx = io.getMousePosX() - startX, dy = io.getMousePosY() - startY;
      if (dx * dx + dy * dy > 9) dragged = true;
      if (dragged) {
        try {
          last = baseline.pan(dx / capturedWidth, dy / capturedHeight);
        } catch (IllegalArgumentException failure) {
          reset();
          throw failure;
        }
      }
      var next = last;
      double duration = ImGui.getTime() - started;
      boolean click = released && !dragged && hovered && duration >= 0 && duration <= .35;
      if (released) reset();
      picked = click;
      return next;
    }
    if (!hovered) return before;
    if (ImGui.isMouseClicked(0) && !ImGui.isMouseDown(1) && !ImGui.isMouseDown(2)) {
      baseline = last = before;
      startX = io.getMousePosX();
      startY = io.getMousePosY();
      capturedWidth = width;
      capturedHeight = height;
      dragged = false;
      started = ImGui.getTime();
      return before;
    }
    // A held button without ownership began outside this surface or was cancelled.
    if (ImGui.isMouseDown(0) || ImGui.isMouseDown(1) || ImGui.isMouseDown(2)) return before;
    if (io.getMouseWheel() != 0)
      return before.zoom(
          (io.getMousePosX() - px) / (double) width,
          (io.getMousePosY() - py) / (double) height,
          io.getMouseWheel());
    return before;
  }
}
