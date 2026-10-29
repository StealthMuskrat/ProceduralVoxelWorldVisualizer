package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.Locale;

/** Pinned DockSpaceOverViewport identity/flags with an explicitly reserved status rectangle. */
final class DockWorkspace {
  private DockWorkspace() {}

  static int draw(StatusBarLayout.Rect bounds) {
    var viewport = ImGui.getMainViewport();
    ImGui.setNextWindowViewport(viewport.getID());
    ImGui.setNextWindowPos(bounds.x(), bounds.y());
    ImGui.setNextWindowSize(Math.max(1, bounds.width()), Math.max(1, bounds.height()));
    int flags =
        ImGuiWindowFlags.NoTitleBar
            | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoResize
            | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoDocking
            | ImGuiWindowFlags.NoBringToFrontOnFocus
            | ImGuiWindowFlags.NoNavFocus;
    ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 0, 0);
    ImGui.begin(String.format(Locale.ROOT, "DockSpaceViewport_%08X", viewport.getID()), flags);
    ImGui.popStyleVar(4);
    int dock = ImGui.getID("DockSpace");
    ImGui.dockSpace(dock);
    ImGui.end();
    return dock;
  }
}
