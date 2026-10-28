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
  }

  /** Called inside the shared Settings window; true closes that window after Apply/Cancel. */
  public boolean draw() {
    if (session == null || session.closed()) reload();
    controls.clear();
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
      if (ImGui.button("Discard and Reload")) reload();
      control("reload");
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
    if (problems.isEmpty())
      for (var problem : session.applyIssues().stream().limit(8).toList())
        ImGui.textWrapped("! " + problem.jsonPointer() + ": " + problem.message());
    if (!message.isEmpty()) ImGui.textWrapped("! " + message);
    boolean done = false;
    if (ImGui.button("Apply")) {
      try {
        done = session.apply(state);
        if (!done)
          message =
              "Project Settings were rejected. Correct the values or inspect Validation Results.";
      } catch (RuntimeException invalid) {
        message =
            Objects.requireNonNullElse(
                invalid.getMessage(), "Project Settings could not be applied.");
      }
    }
    control("apply");
    ImGui.sameLine();
    if (ImGui.button("Cancel")) done = true;
    control("cancel");
    if (done) cancel();
    return done;
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
}
