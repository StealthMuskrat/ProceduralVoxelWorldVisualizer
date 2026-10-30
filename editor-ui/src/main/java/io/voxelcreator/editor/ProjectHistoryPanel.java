package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One shared project-history decision for menu and configured shortcut entry points. */
final class ProjectHistoryPanel {
  final ProjectHistorySession session;
  final DialogLayout layout;
  private boolean prompt, revealBody;
  private long shownRequest = -1;
  final SettingsPageChrome chrome = new SettingsPageChrome(2);
  private final Map<String, float[]> controls = new HashMap<>();

  ProjectHistoryPanel(EditorState state, BooleanSupplier blocked) {
    session = new ProjectHistorySession(state, blocked);
    layout = new DialogLayout(state, "Properties", 620, 280);
  }

  void request(ProjectHistorySession.Direction direction) {
    if (session.request(direction) == ProjectHistorySession.Result.NEEDS_DECISION) prompt = true;
  }

  void draw() {
    controls.clear();
    if (!session.pending()) {
      prompt = false;
      if (layout.begin("Unapplied Properties and Project History")) {
        ImGui.closeCurrentPopup();
        ImGui.endPopup();
      }
      return;
    }
    if (prompt || shownRequest != session.requestVersion()) {
      layout.open("Unapplied Properties and Project History");
      prompt = false;
      shownRequest = session.requestVersion();
      revealBody = true;
    }
    if (!layout.begin(
        "Unapplied Properties and Project History", ImGuiWindowFlags.AlwaysAutoResize)) return;
    String action = session.direction() == ProjectHistorySession.Direction.UNDO ? "Undo" : "Redo";
    chrome.begin("project-history-body");
    if (revealBody) {
      ImGui.setScrollY(0);
      revealBody = false;
    }
    ImGui.textWrapped("Requested project action: " + action);
    ImGui.textWrapped(
        "Undo and Redo affect committed project history. Discard unapplied Properties input to"
            + " continue, or cancel to keep editing.");
    chrome.end();
    ImGui.pushID("project-history:" + session.requestVersion());
    boolean discard = button("Discard Draft and " + action, "DISCARD");
    boolean cancel = button("Cancel", "CANCEL");
    ImGui.popID();
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.decide(ProjectHistorySession.Decision.CANCEL);
      ImGui.closeCurrentPopup();
    } else if (discard) {
      session.decide(ProjectHistorySession.Decision.DISCARD);
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private boolean button(String label, String key) {
    boolean clicked = chrome.identifiedAction(key, label);
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
    return clicked;
  }

  float[] control(String key) {
    return controls.get(key);
  }
}
