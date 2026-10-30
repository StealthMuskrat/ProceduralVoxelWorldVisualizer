package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import imgui.type.ImString;
import io.voxelcreator.model.*;
import java.util.*;

/** Private Project Settings buffer shared by the menu and diagnostic entry points. */
public final class ProjectSettingsPanel {
  private final EditorState state;
  private ProjectSettingsSession session;
  final SettingsActionScope actionScope = new SettingsActionScope();
  private boolean revealFeedback;
  private final Map<String, ImString> buffers = new LinkedHashMap<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private final PropertyFocus focus = new PropertyFocus();
  final SettingsPageChrome layout = new SettingsPageChrome(4);
  private final Map<String, Integer> fieldIds = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> inputBounds = new LinkedHashMap<>();
  private boolean focusWindow;
  private String message = "";
  private int diagnosticRows;

  public ProjectSettingsPanel(EditorState state) {
    this.state = state;
  }

  public boolean open(EditorEvents.Target target) {
    if (target != null
        && (!target.settings()
            || target.resource() != null
            || target.control() != null
            || !state.canNavigate(target))) return false;
    if (session == null || session.closed()) reload();
    focusWindow = true;
    String field = ProjectSettingsSession.field(target);
    if (field != null && session.current(state))
      focus.request(
          new EditorEvents.Target(state.project().project.uuid, null, null, field, "", true),
          session);
    else focus.cancel();
    return true;
  }

  private void reload() {
    actionScope.release();
    if (session != null) session.cancel();
    session = new ProjectSettingsSession(state);
    buffers.clear();
    for (var field : ProjectSettingsSession.FIELDS)
      buffers.put(
          field, new ImString(session.text(field), ProjectSettingsSession.MAX_CHARACTERS + 1));
    focus.cancel();
    message = "";
    revealFeedback = false;
  }

  public void cancel() {
    actionScope.release();
    revealFeedback = false;
    if (session != null) session.cancel();
    session = null;
    buffers.clear();
    focus.cancel();
    message = "";
    focusWindow = false;
    diagnosticRows = 0;
    controls.clear();
    fieldIds.clear();
    inputBounds.clear();
  }

  boolean pendingChanges() {
    return session != null && session.modified();
  }

  /** Called inside the shared Settings window; true closes that window after Apply/Cancel. */
  public boolean draw() {
    controls.clear();
    fieldIds.clear();
    inputBounds.clear();
    diagnosticRows = 0;
    if (session == null || session.closed()) return true;
    if (focusWindow) {
      ImGui.setWindowFocus("Project Settings");
      focusWindow = false;
    }
    long request = actionScope.synchronize(state.project(), session, state.revision);
    boolean current = session.current(state);
    focus.synchronize(state.project().project.uuid, null, session);
    layout.begin("project-settings-body");
    if (!current) {
      focus.cancel();
      ImGui.textWrapped(
          "The project changed while these settings were open. This private text is preserved;"
              + " discard and reload before applying.");
    }
    ImGui.textWrapped(
        "Apply validates these values against the complete current project. Changes remain private"
            + " until Apply; saving is explicit.");
    for (var field : ProjectSettingsSession.FIELDS) {
      boolean targeted = focus.matches(null, field);
      boolean submitted = targeted && focus.submit();
      String label =
          switch (field) {
            case "seed" -> "Project seed";
            case "minY" -> "Minimum Y (inclusive)";
            default -> "Maximum Y (inclusive)";
          };
      ImGui.textWrapped(label);
      ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
      String id = "###project-setting-" + field;
      fieldIds.put(field, ImGui.getID(id));
      if (submitted) ImGui.setKeyboardFocusHere();
      if (ImGui.inputText(id, buffers.get(field))) {
        String value = buffers.get(field).get();
        if (value.length() > ProjectSettingsSession.MAX_CHARACTERS) {
          buffers.get(field).set(session.text(field));
          message = "Project setting text limit " + ProjectSettingsSession.MAX_CHARACTERS;
        } else {
          session.edit(field, value);
          message = "";
        }
      }
      control(field);
      inputBounds.put(field, PopupLayout.itemBounds());
      if (targeted) {
        ImGui.setScrollHereY(.25f);
        if (ImGui.isItemActive() || ImGui.isItemFocused()) focus.acknowledge(field);
        else if (!submitted && ImGui.isMouseClicked(0)
            || ImGui.isKeyPressed(ImGuiKey.Escape, false)) focus.cancel();
      }
    }
    var problems = session.validate();
    for (var problem : problems) ImGui.textWrapped("! " + problem.message());
    if (problems.isEmpty() && session.issueCount() > 0) {
      UiScale.beginChild(
          "project-settings-diagnostics",
          0,
          SettingsPageLayout.multiline(
              layout.body().height(), ImGui.getTextLineHeightWithSpacing()),
          true);
      for (var problem : session.applyIssues().stream().limit(8).toList()) {
        diagnosticRows++;
        ImGui.textWrapped("! " + bounded(problem.jsonPointer() + ": " + problem.message(), 1024));
      }
      ImGui.endChild();
      ImGui.textDisabled(session.issueCount() + " candidate diagnostic(s); showing up to eight.");
    }
    if (!message.isEmpty()) ImGui.textWrapped("! " + bounded(message, 512));
    if (revealFeedback) {
      ImGui.setScrollHereY(.6f);
      revealFeedback = false;
    }
    var owned = new LinkedHashMap<String, String>();
    for (var field : ProjectSettingsSession.FIELDS) owned.put(field, session.text(field));
    String firstProblem =
        ProjectSettingsProblemNavigation.first(owned, displayed(), problems, session.applyIssues());
    layout.end();
    ImGui.pushID("project-settings-request:" + request);
    ImGui.beginDisabled(!current);
    boolean apply = layout.identifiedAction("apply", "Apply");
    control("apply");
    ImGui.endDisabled();
    boolean cancel = layout.identifiedAction("cancel", "Cancel");
    control("cancel");
    ImGui.beginDisabled(current);
    boolean reload = layout.identifiedAction("reload", "Discard and Reload");
    control("reload");
    ImGui.endDisabled();
    ImGui.beginDisabled(firstProblem == null || !current);
    boolean problem = layout.identifiedAction("problem", "Show Problem");
    control("problem");
    ImGui.endDisabled();
    ImGui.popID();
    boolean done = false;
    if (!actionScope.current(state.project(), session, state.revision)) return false;
    boolean escape =
        ImGui.isWindowFocused(ImGuiFocusedFlags.RootAndChildWindows)
            && ImGui.isKeyPressed(ImGuiKey.Escape, false);
    if (cancel || escape) done = true;
    else if (reload && !current) reload();
    else if (problem && firstProblem != null && current)
      focus.request(
          new EditorEvents.Target(state.project().project.uuid, null, null, firstProblem, "", true),
          session);
    else if (apply && current) {
      try {
        done = session.apply(state, displayed());
        if (!done)
          message =
              session.applyFailure().isEmpty()
                  ? "Project Settings were rejected. Correct the values or inspect Validation"
                      + " Results."
                  : "Project Settings could not be applied. Your text is preserved; retry or"
                      + " inspect Logs.";
      } catch (RuntimeException invalid) {
        message =
            Objects.requireNonNullElse(
                invalid.getMessage(), "Project Settings could not be applied.");
      }
      if (!done) revealFeedback = true;
    }
    if (done) cancel();
    return done;
  }

  private Map<String, String> displayed() {
    var result = new LinkedHashMap<String, String>();
    buffers.forEach((key, value) -> result.put(key, value.get()));
    return result;
  }

  Map<String, Integer> fieldIds() {
    return Map.copyOf(fieldIds);
  }

  Map<String, DialogBounds.Rect> inputBounds() {
    return Map.copyOf(inputBounds);
  }

  List<Diagnostic> feedback() {
    return session.validate();
  }

  private static String bounded(String text, int limit) {
    return StatusBarText.clip(Objects.requireNonNullElse(text, ""), limit);
  }

  private void control(String key) {
    var lo = ImGui.getItemRectMin();
    var hi = ImGui.getItemRectMax();
    controls.put(key, new float[] {lo.x + Math.min(80, (hi.x - lo.x) * .25f), (lo.y + hi.y) / 2});
  }

  float[] controlAt(String key) {
    return controls.get(key);
  }

  String focusedField() {
    return focus.acknowledged();
  }

  String text(String field) {
    return session.text(field);
  }

  int diagnosticRows() {
    return diagnosticRows;
  }

  int candidateIssueCount() {
    return session == null ? 0 : session.issueCount();
  }

  void probeText(String field, String text) {
    session.edit(field, text);
    buffers.get(field).set(text);
  }

  String applyFailure() {
    return session == null ? "" : session.applyFailure();
  }
}
