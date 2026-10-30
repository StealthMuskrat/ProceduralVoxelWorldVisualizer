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
  private long shownRequest = -1;
  private final SettingsPageChrome decisions = new SettingsPageChrome(3);
  private final SettingsPageChrome paths = new SettingsPageChrome(2);
  private SettingsPageChrome chrome = paths;
  private boolean focusPath, revealError;
  private String shownError = "";
  private int pathId;
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
    pathId = 0;
    var phase = workflow.phase();
    long request = workflow.requestVersion();
    boolean changed = shown != phase || shownRequest != request;
    if (changed && shown != ProjectFileWorkflow.Phase.IDLE && layout.begin(title(shown))) {
      ImGui.closeCurrentPopup();
      ImGui.endPopup();
    }
    if (changed && phase != ProjectFileWorkflow.Phase.IDLE) {
      if (phase == ProjectFileWorkflow.Phase.OPEN || phase == ProjectFileWorkflow.Phase.SAVE) {
        path.set(
            state.document.path() == null
                ? "project.pvproj.json"
                : state.document.path().toString());
        focusPath = true;
      }
      shownError = "";
      revealError = false;
      layout.open(title(phase));
    }
    shown = phase;
    shownRequest = request;
    if (phase == ProjectFileWorkflow.Phase.IDLE || !layout.begin(title(phase))) return;
    boolean pathPhase =
        phase == ProjectFileWorkflow.Phase.SAVE || phase == ProjectFileWorkflow.Phase.OPEN;
    chrome = pathPhase ? paths : decisions;
    String error = workflow.error();
    if (pathPhase && !error.isEmpty() && !error.equals(shownError)) focusPath = true;
    if (!pathPhase && !error.isEmpty() && !error.equals(shownError)) revealError = true;
    shownError = error;
    // Own native press/release identity across external cancellation and same-phase reopening.
    String scope = "project-file:" + request + ":" + phase.name();
    chrome.begin("project-file-body");
    if (changed) ImGui.setScrollY(0);
    ImGui.pushID(scope);
    if (phase == ProjectFileWorkflow.Phase.DRAFT) {
      ImGui.textWrapped("Continue with: " + workflow.action());
      ImGui.textWrapped(
          "Properties has unapplied input. Apply it before continuing, discard it only if the"
              + " transition succeeds, or cancel.");
      ImGui.textWrapped(
          "Save writes the committed project. Applying here creates one ordinary undoable edit"
              + " before any save decision.");
    } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
      ImGui.textWrapped("Continue with: " + workflow.action());
      ImGui.textWrapped("Save changes before continuing?");
    } else {
      ImGui.textWrapped(
          (phase == ProjectFileWorkflow.Phase.SAVE ? "Save" : "Open") + " project file");
      ImGui.textWrapped("Path");
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      if (focusPath) ImGui.setKeyboardFocusHere();
      ImGui.inputText("##project-path", path);
      pathId = ImGui.getID("##project-path");
      control("Path");
      if (focusPath) {
        ImGui.setScrollHereY(.25f);
        if (ImGui.isItemActive() || ImGui.isItemFocused()) focusPath = false;
      }
    }
    if (!error.isEmpty()) {
      ImGui.textWrapped(StatusBarText.clip(error, 4096));
      if (revealError) {
        ImGui.setScrollHereY(.75f);
        revealError = false;
      }
    }
    ImGui.popID();
    chrome.end();
    ImGui.pushID(scope);
    boolean primary, secondary = false, cancel;
    if (phase == ProjectFileWorkflow.Phase.DRAFT) {
      primary = button("primary", "Apply Draft");
      secondary = button("secondary", "Discard Draft");
      cancel = button("cancel", "Cancel");
    } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
      primary = button("primary", "Save");
      secondary = button("secondary", "Don't Save");
      cancel = button("cancel", "Cancel");
    } else {
      primary = button("primary", phase == ProjectFileWorkflow.Phase.SAVE ? "Save" : "Open");
      cancel = button("cancel", "Cancel");
    }
    ImGui.popID();
    if (cancel || ImGui.isKeyPressed(ImGuiKey.Escape, false)) workflow.cancel();
    else if (phase == ProjectFileWorkflow.Phase.DRAFT) {
      if (secondary) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.DISCARD);
      else if (primary) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.APPLY);
    } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
      if (secondary) workflow.decide(ProjectFileWorkflow.Decision.DISCARD);
      else if (primary) workflow.decide(ProjectFileWorkflow.Decision.SAVE);
    } else if (primary) workflow.submit(path.get());
    if (primary && workflow.phase() == phase && !workflow.error().isEmpty()) {
      if (pathPhase) focusPath = true;
      else revealError = true;
    }
    if (workflow.phase() != phase || workflow.requestVersion() != request) {
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

  private boolean button(String key, String label) {
    boolean clicked = chrome.identifiedAction(key, label);
    control(label);
    return clicked;
  }

  private void control(String label) {
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(label, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
  }

  SettingsPageChrome chrome() {
    return chrome;
  }

  int pathId() {
    return pathId;
  }

  boolean focusPending() {
    return focusPath;
  }

  String probePath() {
    return path.get();
  }

  float[] probeControl(String label) {
    return controls.get(label);
  }
}
