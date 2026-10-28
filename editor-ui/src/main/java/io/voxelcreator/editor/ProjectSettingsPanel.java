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
  private final Map<String, ImString> buffers = new LinkedHashMap<>();
  private final Map<String, float[]> controls = new HashMap<>();
  private final PropertyFocus focus = new PropertyFocus();
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
    if (session != null) session.cancel();
    session = new ProjectSettingsSession(state);
    buffers.clear();
    for (var field : ProjectSettingsSession.FIELDS)
      buffers.put(
          field, new ImString(session.text(field), ProjectSettingsSession.MAX_CHARACTERS + 1));
    focus.cancel();
    message = "";
  }

  public void cancel() {
    if (session != null) session.cancel();
    session = null;
    buffers.clear();
    focus.cancel();
    message = "";
    focusWindow = false;
    diagnosticRows = 0;
    controls.clear();
  }

  boolean pendingChanges() {
    return session != null && session.modified();
  }

  /** Called inside the shared Settings window; true closes that window after Apply/Cancel. */
  public boolean draw() {
    controls.clear();
    diagnosticRows = 0;
    if (session == null || session.closed()) return true;
    if (focusWindow) {
      ImGui.setWindowFocus("Project Settings");
      focusWindow = false;
    }
    boolean current = session.current(state);
    focus.synchronize(state.project().project.uuid, null, session);
    if (!current) {
      focus.cancel();
      ImGui.textWrapped(
          "The project changed while these settings were open. This private text is preserved;"
              + " discard and reload before applying.");
      boolean reload = ImGui.button("Discard and Reload");
      control("reload");
      if (reload) {
        reload();
        return false;
      }
    }
    ImGui.textWrapped(
        "Apply validates these values against the complete current project. Changes remain private"
            + " until Apply; saving is explicit.");
    for (var field : ProjectSettingsSession.FIELDS) {
      boolean targeted = focus.matches(null, field);
      boolean submitted = targeted && focus.submit();
      if (submitted) ImGui.setKeyboardFocusHere();
      String label =
          switch (field) {
            case "seed" -> "Project seed";
            case "minY" -> "Minimum Y (inclusive)";
            default -> "Maximum Y (inclusive)";
          };
      if (ImGui.inputText(label, buffers.get(field))) {
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
      ImGui.beginChild("project-settings-diagnostics", 0, 110, true);
      for (var problem : session.applyIssues().stream().limit(8).toList()) {
        diagnosticRows++;
        ImGui.textWrapped("! " + bounded(problem.jsonPointer() + ": " + problem.message(), 1024));
      }
      ImGui.endChild();
      ImGui.textDisabled(session.issueCount() + " candidate diagnostic(s); showing up to eight.");
    }
    if (!message.isEmpty()) ImGui.textWrapped("! " + bounded(message, 512));
    boolean done = false;
    ImGui.beginDisabled(!current);
    if (ImGui.button("Apply")) {
      try {
        var displayed = new LinkedHashMap<String, String>();
        for (var field : ProjectSettingsSession.FIELDS)
          displayed.put(field, buffers.get(field).get());
        done = session.apply(state, displayed);
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
    }
    control("apply");
    ImGui.endDisabled();
    ImGui.sameLine();
    if (ImGui.button("Cancel")) done = true;
    control("cancel");
    if (done) cancel();
    return done;
  }

  private static String bounded(String text, int limit) {
    text = Objects.requireNonNullElse(text, "");
    return text.length() > limit ? text.substring(0, limit - 1) + "…" : text;
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

  String applyFailure() {
    return session == null ? "" : session.applyFailure();
  }
}
