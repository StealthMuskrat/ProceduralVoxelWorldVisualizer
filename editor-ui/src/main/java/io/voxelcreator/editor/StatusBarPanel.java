package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;

/** Main-viewport status and explicit utility actions; all layout measurements are transient. */
final class StatusBarPanel {
  private final EditorState state;
  private final Runnable settings, notifications;
  private StatusBarLayout.Plan plan;
  private String[] noticeLabels;
  private static final String[] SETTINGS = {"Settings", "..."};
  private String noticeDescription;
  private StatusBarLayout.Rect actual;
  private final Map<String, DialogBounds.Rect> controls = new HashMap<>();

  StatusBarPanel(EditorState state, Runnable settings, Runnable notifications) {
    this.state = Objects.requireNonNull(state);
    this.settings = Objects.requireNonNull(settings);
    this.notifications = Objects.requireNonNull(notifications);
  }

  StatusBarLayout.Plan prepare() {
    var viewport = ImGui.getMainViewport();
    var attention = state.events.counts();
    String count = Integer.toString(attention.unread());
    String action = attention.actionRequired() > 0 ? " !" + attention.actionRequired() : "";
    noticeLabels =
        new String[] {
          "Notifications " + count + action,
          "Notices " + count + action,
          (attention.actionRequired() > 0 ? "! " : "N ") + count
        };
    noticeDescription =
        "Notifications: " + count + " unread / " + attention.actionRequired() + " action required";
    float padding = ImGui.getStyle().getFramePaddingX() * 2;
    var settingsWidths = new ArrayList<Float>();
    for (var label : SETTINGS) settingsWidths.add(ImGui.calcTextSize(label).x + padding);
    var noticeWidths = new ArrayList<Float>();
    for (var label : noticeLabels) noticeWidths.add(ImGui.calcTextSize(label).x + padding);
    var metrics =
        new StatusBarLayout.Metrics(
            ImGui.getFontSize(),
            ImGui.getFrameHeight(),
            ImGui.getStyle().getItemSpacingX(),
            Math.max(80, ImGui.calcTextSize("Ready").x),
            settingsWidths,
            noticeWidths);
    plan =
        StatusBarLayout.prepare(
            new DialogBounds.Rect(
                viewport.getWorkPosX(),
                viewport.getWorkPosY(),
                Math.max(1, viewport.getWorkSizeX()),
                Math.max(1, viewport.getWorkSizeY())),
            metrics);
    return plan;
  }

  void draw() {
    controls.clear();
    actual = null;
    if (plan == null || !plan.bar().visible()) return;
    var viewport = ImGui.getMainViewport();
    ImGui.setNextWindowViewport(viewport.getID());
    ImGui.setNextWindowPos(plan.bar().x(), plan.bar().y());
    ImGui.setNextWindowSize(plan.bar().width(), plan.bar().height());
    ImGui.pushStyleVar(ImGuiStyleVar.WindowMinSize, 0, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0);
    ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
    boolean visible =
        ImGui.begin(
            "Status",
            ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoDocking
                | ImGuiWindowFlags.NoSavedSettings
                | ImGuiWindowFlags.NoScrollbar
                | ImGuiWindowFlags.NoScrollWithMouse
                | ImGuiWindowFlags.NoCollapse
                | ImGuiWindowFlags.NoBringToFrontOnFocus);
    ImGui.setWindowFontScale(1);
    try {
      actual =
          new StatusBarLayout.Rect(
              ImGui.getWindowPosX(),
              ImGui.getWindowPosY(),
              ImGui.getWindowSizeX(),
              ImGui.getWindowSizeY());
      if (!visible) return;
      var text = plan.status();
      if (text.visible()) {
        var draw = ImGui.getWindowDrawList();
        draw.pushClipRect(
            text.x(), text.y(), text.x() + text.width(), text.y() + text.height(), true);
        draw.addText(
            text.x(),
            text.y(),
            ImGui.getColorU32(ImGuiCol.Text),
            StatusBarText.clip(state.status, 120));
        draw.popClipRect();
        if (ImGui.isWindowHovered()
            && ImGui.isMouseHoveringRect(
                text.x(), text.y(), text.x() + text.width(), text.y() + text.height()))
          UiScale.tooltip(StatusBarText.clip(state.status, 2048));
      }
      if (button("settings", SETTINGS[plan.settingsLabel()], plan.settings(), "User Settings"))
        settings.run();
      if (button(
          "notifications",
          noticeLabels[plan.notificationLabel()],
          plan.notifications(),
          noticeDescription)) notifications.run();
    } finally {
      ImGui.end();
      ImGui.popStyleVar(4);
    }
  }

  private boolean button(
      String key, String label, StatusBarLayout.Rect bounds, String description) {
    if (!bounds.visible()) return false;
    ImGui.setCursorScreenPos(bounds.x(), bounds.y());
    boolean clicked = ImGui.button(label + "###status-" + key, bounds.width(), bounds.height());
    controls.put(key, PopupLayout.itemBounds());
    if (ImGui.isItemHovered()) UiScale.tooltip(description);
    return clicked;
  }

  DialogBounds.Rect control(String key) {
    return controls.get(key);
  }

  StatusBarLayout.Rect bounds() {
    return actual;
  }
}
