package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;

/**
 * Bounded transient selector; queries native viewports locally and retains value measurements only.
 */
final class PopupLayout {
  private DialogBounds.Rect bounds, query, list;
  private final PopupWorkAreaResolver workAreas;
  private PopupWorkArea.Plan workArea;

  PopupLayout() {
    this(new PopupWorkAreaResolver());
  }

  PopupLayout(PopupWorkAreaResolver workAreas) {
    this.workAreas = java.util.Objects.requireNonNull(workAreas);
  }

  PopupWorkArea.Plan workArea() {
    return workArea;
  }

  static DialogBounds.Rect itemBounds() {
    return new DialogBounds.Rect(
        ImGui.getItemRectMinX(),
        ImGui.getItemRectMinY(),
        Math.max(1, ImGui.getItemRectMaxX() - ImGui.getItemRectMinX()),
        Math.max(1, ImGui.getItemRectMaxY() - ImGui.getItemRectMinY()));
  }

  boolean begin(String title, DialogBounds.Rect anchor, float width, float height) {
    bounds = query = list = null;
    workArea = null;
    // Do not submit NextWindow data when the selector is closed: it could affect the next window.
    if (!ImGui.isPopupOpen(title)) return false;
    int host = ImGui.getWindowViewport().getID();
    workArea = workAreas.resolve(host, anchor, width, height);
    var fit = PopupBounds.fit(workArea.bounds(), anchor, width, height);
    ImGui.setNextWindowViewport(workArea.viewportId());
    ImGui.setNextWindowPos(fit.x(), fit.y(), ImGuiCond.Always);
    ImGui.setNextWindowSize(fit.width(), fit.height(), ImGuiCond.Always);
    ImGui.setNextWindowSizeConstraints(1, 1, fit.width(), fit.height());
    if (!UiScale.beginPopup(title, ImGuiWindowFlags.NoMove)) return false;
    bounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    return true;
  }

  DialogBounds.Rect bounds() {
    return bounds;
  }

  void search(String id, String hint, ImString filter) {
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    ImGui.inputTextWithHint(id, hint, filter);
    query = itemBounds();
  }

  void observeList() {
    list =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
  }

  DialogBounds.Rect queryBounds() {
    return query;
  }

  DialogBounds.Rect listBounds() {
    return list;
  }

  static void clippedLabel(String text) {
    if (ImGui.isItemHovered()
        && ImGui.calcTextSize(text).x > ImGui.getItemRectMaxX() - ImGui.getItemRectMinX())
      UiScale.tooltip(text);
  }
}
