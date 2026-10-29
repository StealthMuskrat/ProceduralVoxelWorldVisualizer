package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.ImGuiDir;
import imgui.type.ImInt;

/** The production default docking builder, shared with the deferred native workspace scenario. */
final class WorkspaceDocking {
  private WorkspaceDocking() {}

  static void defaults(int dock) {
    var viewport = ImGui.getMainViewport();
    imgui.internal.ImGui.dockBuilderRemoveNode(dock);
    imgui.internal.ImGui.dockBuilderAddNode(dock);
    imgui.internal.ImGui.dockBuilderSetNodeSize(
        dock, viewport.getWorkSizeX(), Math.max(1, viewport.getWorkSizeY() - 26));
    ImInt left = new ImInt(), right = new ImInt(), center = new ImInt();
    imgui.internal.ImGui.dockBuilderSplitNode(dock, ImGuiDir.Left, .19f, left, center);
    imgui.internal.ImGui.dockBuilderSplitNode(center.get(), ImGuiDir.Right, .31f, right, center);
    imgui.internal.ImGui.dockBuilderDockWindow("Resources", left.get());
    imgui.internal.ImGui.dockBuilderDockWindow("Properties", right.get());
    imgui.internal.ImGui.dockBuilderDockWindow("Visualizers", center.get());
    imgui.internal.ImGui.dockBuilderFinish(dock);
  }
}
