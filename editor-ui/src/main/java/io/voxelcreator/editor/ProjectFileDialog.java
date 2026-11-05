package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Shared production presentation for project paths and the unsaved-project decision. */
final class ProjectFileDialog {
  private final EditorState state;
  final DialogLayout layout;
  final ProjectFileWorkflow workflow;
  private final ImString path = new ImString(4096);
  private ProjectFileWorkflow.Phase shown = ProjectFileWorkflow.Phase.IDLE;
  private long shownRequest = -1, pathRequest = -1;
  private final SettingsPageChrome decisions = new SettingsPageChrome(3);
  private final SettingsPageChrome paths = new SettingsPageChrome(2);
  private SettingsPageChrome chrome = paths;
  private boolean focusPath, revealError;
  private String shownError = "";
  private int pathId;
  private final NavigationDecisionScope delivery = new NavigationDecisionScope();
  private int drawnFrame = Integer.MIN_VALUE;
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
    this(state, replaced, exit, blocked, workAreas, null, null);
  }

  /**
   * Desktop uses real background preparation; immediate constructors retain isolated fixture use.
   */
  static ProjectFileDialog background(
      EditorState state, Runnable replaced, Runnable exit, BooleanSupplier blocked) {
    return background(state, replaced, exit, blocked, state.background, state.uiQueue::offer);
  }

  static ProjectFileDialog background(
      EditorState state,
      Runnable replaced,
      Runnable exit,
      BooleanSupplier blocked,
      Executor executor,
      Consumer<Runnable> ui) {
    return new ProjectFileDialog(
        state,
        replaced,
        exit,
        blocked,
        new PopupWorkAreaResolver(),
        Objects.requireNonNull(executor),
        Objects.requireNonNull(ui));
  }

  private ProjectFileDialog(
      EditorState state,
      Runnable replaced,
      Runnable exit,
      BooleanSupplier blocked,
      PopupWorkAreaResolver workAreas,
      Executor executor,
      Consumer<Runnable> ui) {
    this.state = state;
    layout = new DialogLayout(state, null, 640, 300, workAreas);
    workflow =
        executor != null
            ? new ProjectFileWorkflow(state, replaced, exit, blocked, executor, ui)
            : new ProjectFileWorkflow(state, replaced, exit, blocked);
  }

  /** Explicit supplied path; no native calls or automatic session reopening. */
  boolean openLocation(String location) {
    if (!workflow.requestOpenLocation()) return false;
    path.set(location == null ? "" : location);
    pathRequest = workflow.requestVersion();
    focusPath = true;
    if (workflow.phase() == ProjectFileWorkflow.Phase.OPEN) workflow.submit(location);
    return true;
  }

  void draw() {
    int frame = ImGui.getFrameCount();
    if (drawnFrame == frame) return;
    drawnFrame = frame;
    controls.clear();
    pathId = 0;
    var phase = workflow.phase();
    long request = workflow.requestVersion();
    long openWork = workflow.openVersion();
    boolean changed = shown != phase || shownRequest != request;
    if (changed && shown != ProjectFileWorkflow.Phase.IDLE) closePopup(title(shown));
    if (changed && phase != ProjectFileWorkflow.Phase.IDLE) {
      delivery.open();
      if (phase == ProjectFileWorkflow.Phase.OPEN || phase == ProjectFileWorkflow.Phase.SAVE) {
        if (pathRequest != request) {
          pathRequest = request;
          path.set(
              state.document.path() == null
                  ? "project.pvproj.json"
                  : state.document.path().toString());
        }
        focusPath = true;
      }
      shownError = "";
      revealError = false;
      layout.open(title(phase));
    }
    shown = phase;
    shownRequest = request;
    if (phase == ProjectFileWorkflow.Phase.IDLE) {
      delivery.close();
      return;
    }
    if (!layout.begin(title(phase))) return;
    var ticket = delivery.capture(frame);
    boolean pathPhase =
        phase == ProjectFileWorkflow.Phase.SAVE || phase == ProjectFileWorkflow.Phase.OPEN;
    chrome = pathPhase ? paths : decisions;
    String error = workflow.error();
    if (pathPhase && !error.isEmpty() && !error.equals(shownError)) focusPath = true;
    if (!pathPhase && !error.isEmpty() && !error.equals(shownError)) revealError = true;
    shownError = error;
    // Own native press/release identity across external cancellation and same-phase reopening.
    String scope = "project-file:" + request + ":" + phase.name()
        + (phase == ProjectFileWorkflow.Phase.LOADING ? ":" + openWork : "");
    boolean primary = false, secondary = false, cancel = false;
    String location = null;
    try {
      chrome.begin("project-file-body");
      try {
        if (changed) ImGui.setScrollY(0);
        ImGui.pushID(scope);
        try {
          if (phase == ProjectFileWorkflow.Phase.DRAFT) {
            ImGui.textWrapped("Continue with: " + workflow.action());
            ImGui.textWrapped(
                "Properties has unapplied input. Apply it before continuing, discard it only if the"
                    + " transition succeeds, or cancel.");
            ImGui.textWrapped(
                "Save writes the committed project. Applying here creates one ordinary undoable"
                    + " edit before any save decision.");
          } else if (phase == ProjectFileWorkflow.Phase.APPLYING) {
            ImGui.textWrapped("Preparing Properties Apply before: " + workflow.action());
            ImGui.textWrapped(
                "The project and private draft remain unchanged while validation and Undo history"
                    + " prepare in the background. Cancel Apply or Escape aborts this transition.");
          } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
            ImGui.textWrapped("Continue with: " + workflow.action());
            ImGui.textWrapped("Save changes before continuing?");
          } else if (phase == ProjectFileWorkflow.Phase.SAVING) {
            ImGui.textWrapped("Preparing the committed project Save in the background.");
            ImGui.textWrapped(
                "Cancel Save preserves the current destination and aborts any pending New, Open or"
                    + " Exit.");
            ImGui.textWrapped(
                "The validated temporary file replaces the destination only after this request is"
                    + " checked again.");
          } else if (phase == ProjectFileWorkflow.Phase.LOADING) {
            ImGui.textWrapped("Reading and validating the selected project in the background.");
            ImGui.textWrapped(
                "The current project and unapplied draft remain available until Open succeeds.");
            ImGui.textWrapped(
                "Cancel Open discards this request; it does not change the active project.");
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
        } finally {
          ImGui.popID();
        }
      } finally {
        chrome.end();
      }
      ImGui.pushID(scope);
      try {
        if (phase == ProjectFileWorkflow.Phase.DRAFT) {
          primary = button("primary", "Apply Draft");
          secondary = button("secondary", "Discard Draft");
          cancel = button("cancel", "Cancel");
        } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
          primary = button("primary", "Save");
          secondary = button("secondary", "Don't Save");
          cancel = button("cancel", "Cancel");
        } else if (phase == ProjectFileWorkflow.Phase.APPLYING) {
          cancel = button("cancel", "Cancel Apply");
        } else if (phase == ProjectFileWorkflow.Phase.SAVING) {
          cancel = button("cancel", "Cancel Save");
        } else if (phase == ProjectFileWorkflow.Phase.LOADING) {
          cancel = button("cancel", "Cancel Open");
        } else {
          primary = button("primary", phase == ProjectFileWorkflow.Phase.SAVE ? "Save" : "Open");
          cancel = button("cancel", "Cancel");
        }
      } finally {
        ImGui.popID();
      }
      cancel |= ImGui.isKeyPressed(ImGuiKey.Escape, false);
      location = path.get();
    } finally {
      ImGui.endPopup();
    }
    if (cancel || primary || secondary) {
      if (!delivery.consume(ticket, frame)
          || workflow.phase() != phase
          || workflow.requestVersion() != request) return;
      if (cancel) workflow.cancel();
      else if (phase == ProjectFileWorkflow.Phase.DRAFT) {
        if (secondary) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.DISCARD);
        else if (primary) workflow.decideDraft(ProjectFileWorkflow.DraftDecision.APPLY);
      } else if (phase == ProjectFileWorkflow.Phase.GUARD) {
        if (secondary) workflow.decide(ProjectFileWorkflow.Decision.DISCARD);
        else if (primary) workflow.decide(ProjectFileWorkflow.Decision.SAVE);
      } else if (primary) workflow.submit(location);
    }
    if (!cancel && phase == ProjectFileWorkflow.Phase.LOADING
        && delivery.owns(ticket) && workflow.phase() == phase
        && workflow.requestVersion() == request && workflow.openVersion() == openWork)
      workflow.acceptOpen(
          path.get(),
          () -> delivery.owns(ticket) && workflow.phase() == phase
              && workflow.requestVersion() == request && workflow.openVersion() == openWork);
    if (!cancel && phase == ProjectFileWorkflow.Phase.APPLYING
        && workflow.phase() == phase && workflow.requestVersion() == request) workflow.acceptDraft();
    if (primary
        && workflow.phase() == phase
        && workflow.requestVersion() == request
        && !workflow.error().isEmpty()) {
      if (pathPhase) focusPath = true;
      else revealError = true;
    }
    if (workflow.phase() != phase || workflow.requestVersion() != request) {
      closePopup(title(phase));
      delivery.close();
      controls.clear();
      pathId = 0;
      shown = ProjectFileWorkflow.Phase.IDLE;
    }
  }

  private void closePopup(String title) {
    if (layout.begin(title)) {
      try {
        ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
    }
  }

  private static String title(ProjectFileWorkflow.Phase phase) {
    return switch (phase) {
      case DRAFT -> "Unapplied Properties";
      case APPLYING -> "Applying Properties";
      case GUARD -> "Unsaved Project";
      case LOADING -> "Opening Project";
      case SAVING -> "Saving Project";
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
