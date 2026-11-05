package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.*;

/** One shared project-history decision for menu and configured shortcut entry points. */
final class ProjectHistoryPanel implements AutoCloseable {
  final ProjectHistorySession session;
  final DialogLayout layout;
  private boolean prompt, revealBody;
  private long shownRequest = -1;
  private final NavigationDecisionScope decisions = new NavigationDecisionScope();
  private int drawnFrame = Integer.MIN_VALUE;
  final SettingsPageChrome chrome = new SettingsPageChrome(2);
  private final Map<String, float[]> controls = new HashMap<>();
  private final ContextHelp help;

  ProjectHistoryPanel(EditorState state, BooleanSupplier blocked) {
    this(state, blocked, () -> state.reconcileSelection(state.multiSelection, state.selected));
  }

  ProjectHistoryPanel(EditorState state, BooleanSupplier blocked, Runnable refresh) {
    this(state, blocked, refresh, null, null);
  }

  static ProjectHistoryPanel background(EditorState state, BooleanSupplier blocked) {
    return new ProjectHistoryPanel(
        state,
        blocked,
        () -> state.reconcileSelection(state.multiSelection, state.selected),
        state.background,
        state.uiQueue::offer);
  }

  ProjectHistoryPanel(
      EditorState state,
      BooleanSupplier blocked,
      Runnable refresh,
      Executor executor,
      Consumer<Runnable> ui) {
    session = new ProjectHistorySession(state, blocked, refresh, executor, ui);
    help = new ContextHelp(state.helpRequests);
    layout = new DialogLayout(state, "Properties", 620, 280);
  }

  void request(ProjectHistorySession.Direction direction) {
    var result = session.request(direction);
    if (result == ProjectHistorySession.Result.NEEDS_DECISION
        || result == ProjectHistorySession.Result.PREPARING) prompt = true;
  }

  void draw() {
    int frame = ImGui.getFrameCount();
    if (drawnFrame == frame) return;
    drawnFrame = frame;
    controls.clear();
    boolean preparing = session.preparing();
    if (!session.pending()) {
      prompt = false;
      decisions.close();
      closePopup();
      return;
    }
    if (prompt || shownRequest != session.requestVersion()) {
      decisions.open();
      layout.open("Unapplied Properties and Project History");
      prompt = false;
      shownRequest = session.requestVersion();
      revealBody = true;
    }
    if (!layout.begin(
        "Unapplied Properties and Project History", ImGuiWindowFlags.AlwaysAutoResize)) return;
    var ticket = decisions.capture(frame);
    long request = shownRequest;
    long work = session.workVersion();
    String action = session.direction() == ProjectHistorySession.Direction.UNDO ? "Undo" : "Redo";
    boolean discard = false, cancel = false;
    try {
      chrome.begin("project-history-body");
      try {
        if (revealBody) {
          ImGui.setScrollY(0);
          revealBody = false;
        }
        ImGui.textWrapped("Requested project action: " + action);
        if (preparing)
          ImGui.textWrapped(
              "Preparing and validating the history step in the background. Committed values and"
                  + " private Properties input stay active until publication. Cancel or Escape"
                  + " retires this request.");
        else
          ImGui.textWrapped(
              "Undo and Redo affect committed project history. Discard unapplied Properties input"
                  + " to continue, or cancel to keep editing.");
      } finally {
        chrome.end();
      }
      ImGui.pushID("project-history:" + request);
      try {
        discard =
            button(
                preparing ? "Preparing " + action + "..." : "Discard Draft and " + action,
                "DISCARD",
                !preparing);
        cancel = button("Cancel", "CANCEL", true);
      } finally {
        ImGui.popID();
      }
      cancel |= ImGui.isKeyPressed(ImGuiKey.Escape, false);
    } finally {
      ImGui.endPopup();
    }
    if ((cancel || discard)
        && decisions.consume(ticket, frame)
        && session.pending()
        && session.requestVersion() == request) {
      session.decide(
          cancel ? ProjectHistorySession.Decision.CANCEL : ProjectHistorySession.Decision.DISCARD);
      if (!session.pending() && decisions.owns(ticket)) {
        decisions.close();
        controls.clear();
        closePopup();
      }
    }
    if (decisions.owns(ticket)
        && session.requestVersion() == request
        && session.workVersion() == work) {
      session.acceptPrepared();
      if (!session.pending() && decisions.owns(ticket) && session.requestVersion() == request) {
        decisions.close();
        controls.clear();
        closePopup();
      }
    }
  }

  private void closePopup() {
    if (layout.begin("Unapplied Properties and Project History")) {
      try {
        ImGui.closeCurrentPopup();
      } finally {
        ImGui.endPopup();
      }
    }
  }

  private boolean button(String label, String key, boolean enabled) {
    ImGui.beginDisabled(!enabled);
    boolean clicked = chrome.identifiedAction(key, label);
    help.item("help.project-history", "project-history:" + key, true);
    ImGui.endDisabled();
    var a = ImGui.getItemRectMin();
    var b = ImGui.getItemRectMax();
    controls.put(key, new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
    return enabled && clicked;
  }

  @Override
  public void close() {
    session.close();
  }

  float[] control(String key) {
    return controls.get(key);
  }
}
