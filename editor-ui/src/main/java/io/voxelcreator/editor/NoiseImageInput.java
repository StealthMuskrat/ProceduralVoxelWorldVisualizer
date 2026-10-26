package io.voxelcreator.editor;

import imgui.ImGui;

/** Shared production ImGui event routing for the 2D image surface. */
final class NoiseImageInput {
  private NoiseImageInput() {}

  static NoiseImageNavigation navigate(
      NoiseImageNavigation before, boolean hovered, float px, float py, float width, float height) {
    if (!hovered) return before;
    var io = ImGui.getIO();
    var after = before;
    if (ImGui.isMouseDragging(0))
      after =
          after.pan(io.getMouseDeltaX() / (double) width, io.getMouseDeltaY() / (double) height);
    if (io.getMouseWheel() != 0)
      after =
          after.zoom(
              (io.getMousePosX() - px) / (double) width,
              (io.getMousePosY() - py) / (double) height,
              io.getMouseWheel());
    return after;
  }
}
