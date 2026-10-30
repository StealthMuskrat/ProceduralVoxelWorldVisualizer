package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiConfigFlags;
import java.util.*;

/** Per-call backend inventory copied to immutable values; never retains native monitor wrappers. */
final class UiMonitorWorkAreas {
  record Snapshot(boolean absolute, List<DialogBounds.Rect> monitors) {
    Snapshot {
      monitors = List.copyOf(monitors);
    }
  }

  private UiMonitorWorkAreas() {}

  static Snapshot snapshot() {
    boolean absolute = ImGui.getIO().hasConfigFlags(ImGuiConfigFlags.ViewportsEnable);
    var areas = new ArrayList<DialogBounds.Rect>();
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
        if (area != null) areas.add(area);
      }
    }
    return new Snapshot(absolute, areas);
  }

  static DialogBounds.Rect rectangle(float x, float y, float width, float height) {
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
