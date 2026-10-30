package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.LinkedHashMap;
import java.util.Map;

/** A scrolling settings body and one persistent, bounded action row. */
final class SettingsPageChrome {
  private final int actionCount;
  private int submitted;
  private float actionWidth;
  private DialogBounds.Rect body;
  private final Map<String, DialogBounds.Rect> actions = new LinkedHashMap<>();

  SettingsPageChrome(int actionCount) {
    if (actionCount < 1 || actionCount > 16)
      throw new IllegalArgumentException("Invalid action count");
    this.actionCount = actionCount;
  }

  void begin(String id) {
    actions.clear();
    submitted = 0;
    float height =
        SettingsPageLayout.body(
            ImGui.getContentRegionAvailY(),
            ImGui.getFrameHeight(),
            ImGui.getStyle().getItemSpacingY());
    UiScale.beginChild(id, 0, height, false);
    body =
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY());
  }

  void end() {
    ImGui.endChild();
    actionWidth =
        SettingsPageLayout.actionWidth(
            ImGui.getContentRegionAvailX(), actionCount, ImGui.getStyle().getItemSpacingX());
  }

  boolean action(String key, String label) {
    if (submitted >= actionCount) throw new IllegalStateException("Too many settings actions");
    if (submitted++ > 0) ImGui.sameLine();
    boolean clicked =
        ImGui.button(label, Math.min(actionWidth, Math.max(1, ImGui.getContentRegionAvailX())), 0);
    actions.put(key, PopupLayout.itemBounds());
    PopupLayout.clippedLabel(label);
    return clicked;
  }

  DialogBounds.Rect body() {
    return body;
  }

  Map<String, DialogBounds.Rect> actions() {
    return Map.copyOf(actions);
  }

  static SettingsPageLayout.Pair pair() {
    float minimum =
        ImGui.calcTextSize("-0.000000000000").x + 2 * ImGui.getStyle().getFramePaddingX();
    return SettingsPageLayout.pair(
        ImGui.getContentRegionAvailX(), minimum, ImGui.getStyle().getItemSpacingX());
  }
}
