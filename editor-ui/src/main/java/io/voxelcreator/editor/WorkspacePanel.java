package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.type.ImBoolean;
import java.util.*;

/** Production Workspace settings: actual tool flags and an explicit default-layout request. */
final class WorkspacePanel {
  private final UserConfig user;
  private final Map<String, ImBoolean> tools;
  private final Runnable defaults, changed, center;
  private boolean probing;
  private final Map<String, float[]> controls = new HashMap<>();

  WorkspacePanel(
      UserConfig user, Map<String, ImBoolean> tools, Runnable defaults, Runnable changed) {
    this(user, tools, defaults, changed, null);
  }

  WorkspacePanel(
      UserConfig user,
      Map<String, ImBoolean> tools,
      Runnable defaults,
      Runnable changed,
      Runnable center) {
    this.user = Objects.requireNonNull(user);
    if (!tools.keySet().equals(new HashSet<>(WorkspaceVisibility.WINDOWS))
        || tools.values().stream().anyMatch(Objects::isNull))
      throw new IllegalArgumentException("Complete workspace tool flags are required");
    this.tools = Map.copyOf(tools);
    this.defaults = Objects.requireNonNull(defaults);
    this.changed = Objects.requireNonNull(changed);
    this.center = center;
  }

  void draw() {
    var multi = new ImBoolean(user.workspace.multiViewport);
    if (ImGui.checkbox("Multi-viewport windows (restart)", multi))
      user.workspace.multiViewport = multi.get();
    ImGui.textWrapped(
        "Tool window visibility is retained in User Config. Reopening a tool does not start"
            + " profiling or a network check.");
    for (String name : WorkspaceVisibility.WINDOWS) {
      if (ImGui.checkbox(name + " window", tools.get(name))) changed.run();
      control(name);
    }
    if (ImGui.button("Restore Default Docking")) defaults.run();
    control("defaults");
    if (center != null) {
      if (ImGui.button("Center Main Window")) center.run();
      control("center");
      if (ImGui.isItemHovered())
        UiScale.tooltip(
            "Restore the normal window and center it on the primary monitor. Docking and private"
                + " editors are retained.");
    }
  }

  void probeControls() {
    probing = true;
  }

  private void control(String key) {
    if (probing)
      controls.put(
          key,
          new float[] {
            (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
            (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
          });
  }

  float[] probeControl(String key) {
    return controls.get(key);
  }
}
