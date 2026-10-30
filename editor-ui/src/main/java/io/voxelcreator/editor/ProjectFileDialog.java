package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Shared production presentation for project paths and the unsaved-project decision. */
final class ProjectFileDialog {
  private final EditorState state;
  final DialogLayout layout;
  final ProjectFileWorkflow workflow;
  private final ImString path = new ImString(4096);
  private ProjectFileWorkflow.Phase shown = ProjectFileWorkflow.Phase.IDLE;
  private final Map<String, float[]> controls = new HashMap<>();

  ProjectFileDialog(EditorState state, Runnable replaced, Runnable exit) {
    this(state, replaced, exit, () -> false);
  }

  ProjectFileDialog(EditorState state, Runnable replaced, Runnable exit, BooleanSupplier blocked) {
    this(state, replaced, exit, blocked, new PopupWorkAreaResolver());
  }

  ProjectFileDialog(
      EditorState state,
      Runnable replaced,
      Runnable exit,
      BooleanSupplier blocked,
      PopupWorkAreaResolver workAreas) {
    this.state = state;
    layout = new DialogLayout(state, null, 640, 300, workAreas);
    workflow = new ProjectFileWorkflow(state, replaced, exit, blocked);
  }

  void draw() {
    controls.clear();
    var phase = workflow.phase();
    if (shown != phase
        && shown != ProjectFileWorkflow.Phase.IDLE
        && layout.begin(title(shown), ImGuiWindowFlags.AlwaysAutoResize)) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
    if (phase != shown && phase != ProjectFileWorkflow.Phase.IDLE) {
      if (phase == ProjectFileWorkflow.Phase.OPEN || phase == ProjectFileWorkflow.Phase.SAVE)
        path.set(
            state.document.path() == null
                ? "project.pvproj.json"
                : state.document.path().toString());
      layout.open(title(phase));
    }
    shown = phase;
    if (phase == ProjectFileWorkflow.Phase.IDLE) return;
    if (!layout.begin(title(phase), ImGuiWindowFlags.AlwaysAutoResize)) return;
    if (!workflow.error().isEmpty()) ImGui.textWrapped(workflow.error());
    if (phase == ProjectFileWorkflow.Phase.DRAFT) {
      ImGui.textWrapped("Continue with: " + workflow.action());
      ImGui.textWrapped(
          "Properties has unapplied input. Apply it before continuing, discard it only if the"
              + " transition succeeds, or cancel.");
      ImGui.textWrapped(
          "Save writes the committed project. Applying here creates one ordinary undoable edit"
              + " before any save decision.");
      if (button("Apply Draft")) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.APPLY);
      DialogLayout.sameLine("Discard Draft");
      if (button("Discard Draft")) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.DISCARD);
      DialogLayout.sameLine("Cancel");
      if (button("Cancel")) workflow.cancel();
    } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
      ImGui.textWrapped("Continue with: " + workflow.action());
      ImGui.textWrapped("Save changes before continuing?");
      if (button("Save")) workflow.decide(ProjectFileWorkflow.Decision.SAVE);
      DialogLayout.sameLine("Don't Save");
      if (button("Don't Save")) workflow.decide(ProjectFileWorkflow.Decision.DISCARD);
      DialogLayout.sameLine("Cancel");
      if (button("Cancel")) workflow.cancel();
    } else {
      String action = phase == ProjectFileWorkflow.Phase.SAVE ? "Save" : "Open";
      ImGui.textWrapped(action + " project file");
      DialogLayout.inputWidth("Path");
      ImGui.inputText("Path", path);
      control("Path");
      if (button(action)) workflow.submit(path.get());
      DialogLayout.sameLine("Cancel");
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
    return switch (phase) {
      case DRAFT -> "Unapplied Properties";
      case GUARD -> "Unsaved Project";
      default -> "Project File Location";
    };
  }

  private boolean button(String label) {
    boolean clicked = DialogLayout.button(label);
    control(label);
    return clicked;
  }

  private void control(String label) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(label, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  String probePath() {
    return path.get();
  }

  float[] probeControl(String label) {
    return controls.get(label);
  }
}
