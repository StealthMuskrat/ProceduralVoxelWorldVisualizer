package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.LinkedHashMap;
import java.util.Map;

/** A scrolling settings body and measured persistent action rows. */
final class SettingsPageChrome {
  private final int actionCount;
  private final int[] rows;
  private int submitted;
  private float footerWidth;
  private DialogBounds.Rect body;
  private final Map<String, DialogBounds.Rect> actions = new LinkedHashMap<>();

  SettingsPageChrome(int... rows) {
    if (rows.length < 1 || rows.length > 4)
      throw new IllegalArgumentException("Invalid action row count");
    int total = 0;
    for (int count : rows) {
      if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid action count");
      total += count;
    }
    this.rows = rows.clone();
    actionCount = total;
  }

  void begin(String id) {
    actions.clear();
    submitted = 0;
    float height =
        SettingsPageLayout.body(
            ImGui.getContentRegionAvailY(),
            ImGui.getFrameHeight(),
            ImGui.getStyle().getItemSpacingY(),
            rows.length);
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
    footerWidth = ImGui.getContentRegionAvailX();
  }

  boolean action(String key, String label) {
    return action(key, label, label);
  }

  boolean identifiedAction(String key, String label) {
    return action(key, label, label + "###" + key);
  }

  private boolean action(String key, String label, String widget) {
    if (submitted >= actionCount) throw new IllegalStateException("Too many settings actions");
    int row = 0, first = 0;
    while (submitted >= first + rows[row]) first += rows[row++];
    if (submitted++ > first) ImGui.sameLine();
    float actionWidth =
        SettingsPageLayout.actionWidth(footerWidth, rows[row], ImGui.getStyle().getItemSpacingX());
    boolean clicked =
        ImGui.button(widget, Math.min(actionWidth, Math.max(1, ImGui.getContentRegionAvailX())), 0);
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
