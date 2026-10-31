package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import java.util.Objects;
import java.util.UUID;

/** Bounded explicit right-click help on supported items, without changing their primary action. */
final class ContextHelp {
  private final HelpRequests requests;
  private final PopupLayout layout = new PopupLayout();
  final SettingsPageChrome chrome = new SettingsPageChrome(1);
  private DialogBounds.Rect popupBounds, actionBounds;
  private float[] actionCenter;
  private final String actionOwner = UUID.randomUUID().toString();
  private String openedIdentity, openedTopic;
  private long version;
  private int popupFrame = -1;
  private int openedHost;

  ContextHelp(HelpRequests requests) {
    this.requests = Objects.requireNonNull(requests);
  }

  void item(String topic) {
    item(topic, topic);
  }

  void item(String topic, String identity) {
    var anchor = PopupLayout.itemBounds();
    String id = "context-help-" + identity;
    int host = ImGui.getID(id);
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false);
    if (!escape && ImGui.isItemHovered() && ImGui.isMouseReleased(1)) {
      openedIdentity = id;
      openedTopic = topic;
      openedHost = host;
      version++;
      popupFrame = ImGui.getFrameCount();
      ImGui.openPopup(id);
    }
    if (layout.begin(id, anchor, 360, 180)) {
      int frame = ImGui.getFrameCount();
      if (!id.equals(openedIdentity)
          || !topic.equals(openedTopic)
          || host != openedHost
          || frame < popupFrame
          || frame - popupFrame > 1) {
        ImGui.closeCurrentPopup();
        retire();
        ImGui.endPopup();
        return;
      }
      popupFrame = frame;
      popupBounds = layout.bounds();
      chrome.begin("context-help-body");
      ImGui.textWrapped("Read guidance without changing this control.");
      chrome.end();
      long request = version;
      ImGui.pushID("context-help-action:" + actionOwner + ":" + request);
      boolean clicked = chrome.identifiedAction("open", "Open Help");
      actionBounds = PopupLayout.itemBounds();
      actionCenter =
          new float[] {
            actionBounds.x() + actionBounds.width() / 2,
            actionBounds.y() + actionBounds.height() / 2
          };
      ImGui.popID();
      if (escape) {
        ImGui.closeCurrentPopup();
        retire();
      } else if (clicked
          && request == version
          && id.equals(openedIdentity)
          && host == openedHost
          && topic.equals(openedTopic)) {
        requests.request(topic);
        ImGui.closeCurrentPopup();
        retire();
      }
      ImGui.endPopup();
    }
  }

  private void retire() {
    openedIdentity = openedTopic = null;
    popupFrame = -1;
    openedHost = 0;
  }

  int actionId() {
    return popupFrame == ImGui.getFrameCount() && openedIdentity != null
        ? chrome.actionIds().getOrDefault("open", 0)
        : 0;
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
