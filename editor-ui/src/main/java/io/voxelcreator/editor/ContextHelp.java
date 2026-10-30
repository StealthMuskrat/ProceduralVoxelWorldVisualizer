package io.voxelcreator.editor;

import imgui.ImGui;
import java.util.Objects;

/** Bounded explicit right-click help on supported items, without changing their primary action. */
final class ContextHelp {
  private final HelpRequests requests;
  private final PopupLayout layout = new PopupLayout();
  private final SettingsPageChrome chrome = new SettingsPageChrome(1);
  private DialogBounds.Rect popupBounds, actionBounds;
  private float[] actionCenter;

  ContextHelp(HelpRequests requests) {
    this.requests = Objects.requireNonNull(requests);
  }

  void item(String topic) {
    item(topic, topic);
  }

  void item(String topic, String identity) {
    var anchor = PopupLayout.itemBounds();
    String id = "context-help-" + identity;
    if (ImGui.isItemHovered() && ImGui.isMouseReleased(1)) ImGui.openPopup(id);
    if (layout.begin(id, anchor, 360, 180)) {
      popupBounds = layout.bounds();
      chrome.begin("context-help-body");
      ImGui.textWrapped("Read guidance without changing this control.");
      chrome.end();
      if (chrome.action("open", "Open Help")) {
        requests.request(topic);
        ImGui.closeCurrentPopup();
      }
      actionBounds = PopupLayout.itemBounds();
      actionCenter =
          new float[] {
            actionBounds.x() + actionBounds.width() / 2,
            actionBounds.y() + actionBounds.height() / 2
          };
      ImGui.endPopup();
    }
  }

  DialogBounds.Rect popupBounds() {
    return popupBounds;
  }

  DialogBounds.Rect actionBounds() {
    return actionBounds;
  }

  float[] actionCenter() {
    return actionCenter;
  }
}
