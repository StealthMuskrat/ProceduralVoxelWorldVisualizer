package io.voxelcreator.editor;

import imgui.ImGui;

/** One stable per-tab scrolling body; measured content adapts without resetting native scroll. */
final class ViewportControlsPane {
  private float contentHeight, scroll, maximumScroll;
  private ViewportPanelLayout.Split plan;
  private DialogBounds.Rect bounds;
  private boolean resetScroll;

  void begin(String id) {
    plan =
        ViewportPanelLayout.split(
            ImGui.getContentRegionAvailY(),
            contentHeight,
            ImGui.getTextLineHeight(),
            ImGui.getStyle().getWindowPaddingY(),
            ImGui.getStyle().getItemSpacingY());
    UiScale.beginChild(id, 0, plan.controls(), true);
    if (resetScroll) {
      ImGui.setScrollY(0);
      resetScroll = false;
    }
    bounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
  }

  void end() {
    contentHeight =
        Math.max(
            1,
            ImGui.getCursorPosY()
                + ImGui.getStyle().getWindowPaddingY()
                - ImGui.getStyle().getItemSpacingY());
    scroll = ImGui.getScrollY();
    maximumScroll = ImGui.getScrollMaxY();
    ImGui.endChild();
  }

  ViewportPanelLayout.Split plan() {
    return plan;
  }

  DialogBounds.Rect bounds() {
    return bounds;
  }

  float contentHeight() {
    return contentHeight;
  }

  float scroll() {
    return scroll;
  }

  float maximumScroll() {
    return maximumScroll;
  }

  void reset() {
    contentHeight = scroll = maximumScroll = 0;
    bounds = null;
    plan = null;
    resetScroll = true;
  }
}
