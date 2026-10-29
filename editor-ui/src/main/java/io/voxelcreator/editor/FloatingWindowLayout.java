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
    ImGui.setNextWindowSize(width, height, ImGuiCond.FirstUseEver);
    var main = ImGui.getMainViewport();
    var mainWork =
        rectangle(main.getWorkPosX(), main.getWorkPosY(), main.getWorkSizeX(), main.getWorkSizeY());
    boolean absolute = ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable);
    var monitors = new ArrayList<DialogBounds.Rect>();
    if (absolute) {
      var platform = ImGui.getPlatformIO();
      for (int i = 0; i < platform.getMonitorsSize(); i++) {
        var monitor = platform.getMonitors(i);
        var area =
            rectangle(
                monitor.getWorkPosX(),
                monitor.getWorkPosY(),
                monitor.getWorkSizeX(),
                monitor.getWorkSizeY());
        if (area != null) monitors.add(area);
      }
    }
    var host = geometry.viewport() == 0 ? null : ImGui.findViewportByID(geometry.viewport());
    boolean missing = geometry.viewport() != 0 && (host == null || !host.isValidPtr());
    var work =
        FloatingWindowBounds.work(
            absolute,
            geometry.viewport() == main.getID() || missing,
            mainWork,
            geometry.bounds(),
            monitors);
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

  private static DialogBounds.Rect rectangle(float x, float y, float width, float height) {
    return Float.isFinite(x)
            && Float.isFinite(y)
            && Float.isFinite(width)
            && Float.isFinite(height)
            && width > 0
            && height > 0
        ? new DialogBounds.Rect(x, y, width, height)
        : null;
  }
}
