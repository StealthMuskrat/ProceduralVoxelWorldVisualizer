package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;

/** Per-surface ImGui routing for captured left/middle slice pans and anchored wheel zoom. */
final class SliceInput {
  record Result(SliceNavigation navigation, boolean picked) {}

  private SliceNavigation baseline, last;
  private int button = -1;
  private float startX, startY, width, height;
  private boolean dragged;
  private double started;

  void reset() {
    baseline = last = null;
    button = -1;
  }

  Result navigate(
      SliceNavigation before,
      boolean eligible,
      boolean hovered,
      float px,
      float py,
      float w,
      float h) {
    var io = ImGui.getIO();
    if (!eligible) {
      reset();
      return new Result(before, false);
    }
    if (baseline != null && !before.equals(last)) reset();
    if (baseline != null) {
      if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
        var restored = baseline;
        reset();
        return new Result(restored, false);
      }
      boolean released = ImGui.isMouseReleased(button);
      if (!released && !ImGui.isMouseDown(button)) {
        reset();
        return new Result(before, false);
      }
      double dx = io.getMousePosX() - startX, dy = io.getMousePosY() - startY;
      if (dx * dx + dy * dy > 9) dragged = true;
      if (dragged) {
        try {
          last = baseline.pan(dx / width, dy / height);
        } catch (IllegalArgumentException invalid) {
          reset();
          throw invalid;
        }
      }
      var next = last;
      double duration = ImGui.getTime() - started;
      boolean picked =
          released && button == 0 && !dragged && hovered && duration >= 0 && duration <= .35;
      if (released) reset();
      return new Result(next, picked);
    }
    if (!hovered) return new Result(before, false);
    for (int candidate = 0; candidate <= 2; candidate += 2)
      if (ImGui.isMouseClicked(candidate)) {
        baseline = last = before;
        button = candidate;
        startX = io.getMousePosX();
        startY = io.getMousePosY();
        width = w;
        height = h;
        started = ImGui.getTime();
        dragged = false;
        return new Result(before, false);
      }
    if (ImGui.isMouseDown(0) || ImGui.isMouseDown(1) || ImGui.isMouseDown(2))
      return new Result(before, false);
    return new Result(
        io.getMouseWheel() == 0
            ? before
            : before.zoom(
                (io.getMousePosX() - px) / (double) w,
                (io.getMousePosY() - py) / (double) h,
                io.getMouseWheel()),
        false);
  }
}
