package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import java.util.*;

/** Shared production presentation for project paths and the unsaved-project decision. */
final class ProjectFileDialog {
  private final EditorState state;
  final ProjectFileWorkflow workflow;
  private final ImString path = new ImString(4096);
  private ProjectFileWorkflow.Phase shown = ProjectFileWorkflow.Phase.IDLE;
  private final Map<String, float[]> controls = new HashMap<>();

  ProjectFileDialog(EditorState state, Runnable replaced, Runnable exit) {
    this.state = state;
    workflow = new ProjectFileWorkflow(state, replaced, exit);
  }

  void draw() {
    controls.clear();
    var phase = workflow.phase();
    if (phase != shown && phase != ProjectFileWorkflow.Phase.IDLE) {
      if (phase != ProjectFileWorkflow.Phase.GUARD)
        path.set(
            state.document.path() == null
                ? "project.pvproj.json"
                : state.document.path().toString());
      ImGui.openPopup(title(phase));
    }
    shown = phase;
    if (phase == ProjectFileWorkflow.Phase.IDLE) return;
    if (!ImGui.beginPopupModal(title(phase), ImGuiWindowFlags.AlwaysAutoResize)) return;
    if (phase == ProjectFileWorkflow.Phase.GUARD) {
      ImGui.text("Save changes before continuing?");
      if (button("Save")) workflow.decide(ProjectFileWorkflow.Decision.SAVE);
      ImGui.sameLine();
      if (button("Don't Save")) workflow.decide(ProjectFileWorkflow.Decision.DISCARD);
      ImGui.sameLine();
      if (button("Cancel")) workflow.cancel();
    } else {
      String action = phase == ProjectFileWorkflow.Phase.SAVE ? "Save" : "Open";
      ImGui.text(action + " project file");
      ImGui.setNextItemWidth(600);
      ImGui.inputText("Path", path);
      control("Path");
      if (!workflow.error().isEmpty()) ImGui.textWrapped(workflow.error());
      if (button(action)) workflow.submit(path.get());
      ImGui.sameLine();
      if (button("Cancel")) workflow.cancel();
    }
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) workflow.cancel();
    if (workflow.phase() != phase) {
      ImGui.closeCurrentPopup();
      shown = ProjectFileWorkflow.Phase.IDLE;
    }
    ImGui.endPopup();
  }

  private static String title(ProjectFileWorkflow.Phase phase) {
    return phase == ProjectFileWorkflow.Phase.GUARD ? "Unsaved Project" : "Project File Location";
  }

  private boolean button(String label) {
    boolean clicked = ImGui.button(label);
    control(label);
    return clicked;
  }

  private void control(String label) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(label, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  float[] probeControl(String label) {
    return controls.get(label);
  }
}
