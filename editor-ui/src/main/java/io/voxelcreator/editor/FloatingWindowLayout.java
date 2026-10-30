package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;

/** Frame-boundary floating-tool correction, with no retained native window/monitor pointers. */
final class FloatingWindowLayout {
  private final float width, height;
  private final FloatingWindowBounds geometry;
  private DialogBounds.Rect measured;

  FloatingWindowLayout(float width, float height) {
    this.width = width;
    this.height = height;
    geometry = new FloatingWindowBounds(width, height);
  }

  void prepare() {
    prepare(true);
  }

  /** Preserve native/caller first-use sizing for core panels that historically have no default. */
  void prepareExisting() {
    prepare(false);
  }

  private void prepare(boolean initialSize) {
    if (initialSize) ImGui.setNextWindowSize(width, height, ImGuiCond.FirstUseEver);
    var main = ImGui.getMainViewport();
    var mainWork =
        UiMonitorWorkAreas.rectangle(
            main.getWorkPosX(), main.getWorkPosY(), main.getWorkSizeX(), main.getWorkSizeY());
    var inventory = UiMonitorWorkAreas.snapshot();
    var host = geometry.viewport() == 0 ? null : ImGui.findViewportByID(geometry.viewport());
    boolean missing = geometry.viewport() != 0 && (host == null || !host.isValidPtr());
    var work =
        FloatingWindowBounds.work(
            inventory.absolute(),
            geometry.viewport() == main.getID() || missing,
            mainWork,
            geometry.bounds(),
            inventory.monitors());
    var plan =
        geometry.prepare(
            ImGui.isMouseDown(0) || ImGui.isMouseDown(1) || ImGui.isMouseDown(2), work);
    if (plan == null) return;
    var usable = DialogBounds.inset(work);
    ImGui.setNextWindowSizeConstraints(
        plan.minimumWidth(), plan.minimumHeight(), usable.width(), usable.height());
    if (missing) ImGui.setNextWindowViewport(main.getID());
    if (plan.position())
      ImGui.setNextWindowPos(plan.bounds().x(), plan.bounds().y(), ImGuiCond.Always);
    if (plan.size())
      ImGui.setNextWindowSize(plan.bounds().width(), plan.bounds().height(), ImGuiCond.Always);
  }

  void observe() {
    measured =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    geometry.observe(
        measured,
        ImGui.getWindowViewport().getID(),
        ImGui.isWindowDocked(),
        ImGui.isWindowCollapsed());
  }

  DialogBounds.Rect bounds() {
    return measured;
  }
}
