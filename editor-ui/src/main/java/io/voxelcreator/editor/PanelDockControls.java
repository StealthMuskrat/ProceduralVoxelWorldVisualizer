package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import java.util.*;

/** Real per-panel Undock/Redock requests are applied before the following Begin. */
final class PanelDockControls {
  private final String name;
  private final PanelDockState state = new PanelDockState();
  private Runnable defaults;
  private boolean probing;
  private int current;
  private final Map<String, float[]> controls = new HashMap<>();

  PanelDockControls(String name) {
    this.name = name;
  }

  void defaults(Runnable action) {
    defaults = Objects.requireNonNull(action);
  }

  void reset() {
    state.reset();
  }

  private boolean enabled() {
    return (ImGui.getIO().getConfigFlags() & ImGuiConfigFlags.DockingEnable) != 0;
  }

  private boolean exists(int id) {
    var node = imgui.internal.ImGui.dockBuilderGetNode(id);
    return node != null && node.isValidPtr();
  }

  void prepare() {
    var target = state.take(id -> enabled() && exists(id));
    if (enabled() && target.isPresent())
      ImGui.setNextWindowDockID(target.getAsInt(), ImGuiCond.Always);
  }

  void draw() {
    if (probing) controls.clear();
    current = ImGui.getWindowDockID();
    if (!enabled()) {
      ImGui.textDisabled("Docking is disabled");
      return;
    }
    int dock = current;
    state.observe(dock);
    if (dock != 0) {
      if (DialogLayout.button("Undock " + name)) state.undock();
      control("undock");
    } else if (state.canRedock(this::exists)) {
      if (DialogLayout.button("Redock " + name)) state.redock(this::exists);
      control("redock");
    } else if (defaults != null) {
      if (DialogLayout.button("Redock (default layout)")) defaults.run();
      control("defaults");
      if (ImGui.isItemHovered())
        ImGui.setTooltip(
            "Restore the default docking layout for Resources, Properties and Visualizers");
    } else ImGui.textDisabled("Drag the title bar to dock this panel");
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

  int probeDock() {
    return current;
  }
}
