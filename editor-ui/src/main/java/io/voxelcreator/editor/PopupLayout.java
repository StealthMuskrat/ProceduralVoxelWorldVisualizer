package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;

/**
 * Bounded transient selector; queries native viewports locally and retains value measurements only.
 */
final class PopupLayout {
  private DialogBounds.Rect bounds, query, list;

  static DialogBounds.Rect itemBounds() {
    return new DialogBounds.Rect(
        ImGui.getItemRectMinX(),
        ImGui.getItemRectMinY(),
        Math.max(1, ImGui.getItemRectMaxX() - ImGui.getItemRectMinX()),
        Math.max(1, ImGui.getItemRectMaxY() - ImGui.getItemRectMinY()));
  }

  boolean begin(String title, DialogBounds.Rect anchor, float width, float height) {
    bounds = query = list = null;
    // Do not submit NextWindow data when the selector is closed: it could affect the next window.
    if (!ImGui.isPopupOpen(title)) return false;
    var viewport = ImGui.getWindowViewport();
    if (viewport == null || !viewport.isValidPtr()) viewport = ImGui.getMainViewport();
    var work = work(viewport);
    if (work == null) {
      viewport = ImGui.getMainViewport();
      work = work(viewport);
      if (work == null) work = new DialogBounds.Rect(0, 0, width + 16, height + 16);
    }
    var fit = PopupBounds.fit(work, anchor, width, height);
    ImGui.setNextWindowViewport(viewport.getID());
    ImGui.setNextWindowPos(fit.x(), fit.y(), ImGuiCond.Always);
    ImGui.setNextWindowSize(fit.width(), fit.height(), ImGuiCond.Always);
    ImGui.setNextWindowSizeConstraints(1, 1, fit.width(), fit.height());
    if (!ImGui.beginPopup(title, ImGuiWindowFlags.NoMove)) return false;
    bounds =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
    return true;
  }

  private static DialogBounds.Rect work(ImGuiViewport viewport) {
    float x = viewport.getWorkPosX(),
        y = viewport.getWorkPosY(),
        width = viewport.getWorkSizeX(),
        height = viewport.getWorkSizeY();
    if (!Float.isFinite(x)
        || !Float.isFinite(y)
        || !Float.isFinite(width)
        || !Float.isFinite(height)
        || width <= 0
        || height <= 0) return null;
    return new DialogBounds.Rect(x, y, width, height);
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
      ImGui.setTooltip(text);
  }
}
