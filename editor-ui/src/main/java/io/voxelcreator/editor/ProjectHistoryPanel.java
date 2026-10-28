package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** One shared project-history decision for menu and configured shortcut entry points. */
final class ProjectHistoryPanel {
  final ProjectHistorySession session;
  private boolean prompt;
  private final Map<String, float[]> controls = new HashMap<>();

  ProjectHistoryPanel(EditorState state, BooleanSupplier blocked) {
    session = new ProjectHistorySession(state, blocked);
  }

  void request(ProjectHistorySession.Direction direction) {
    if (session.request(direction) == ProjectHistorySession.Result.NEEDS_DECISION) prompt = true;
  }

  void draw() {
    controls.clear();
    if (prompt) {
      ImGui.openPopup("Unapplied Properties and Project History");
      prompt = false;
    }
    if (!ImGui.beginPopupModal(
        "Unapplied Properties and Project History", ImGuiWindowFlags.AlwaysAutoResize)) return;
    if (!session.pending()) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
      return;
    }
    String action = session.direction() == ProjectHistorySession.Direction.UNDO ? "Undo" : "Redo";
    ImGui.text("Requested project action: " + action);
    ImGui.textWrapped(
        "Undo and Redo affect committed project history. Discard unapplied Properties input to"
            + " continue, or cancel to keep editing.");
    if (button("Discard Draft and " + action, "DISCARD")) {
      session.decide(ProjectHistorySession.Decision.DISCARD);
      ImGui.closeCurrentPopup();
    }
    ImGui.sameLine();
    if (button("Cancel", "CANCEL") || ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      session.decide(ProjectHistorySession.Decision.CANCEL);
      ImGui.closeCurrentPopup();
    }
    ImGui.endPopup();
  }

  private boolean button(String label, String key) {
    boolean clicked = ImGui.button(label);
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
    return clicked;
  }

  float[] control(String key) {
    return controls.get(key);
  }
}
