package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.Objects;

/** Shared modal bounds/owner policy. All native wrappers are queried and used within one call. */
final class DialogLayout {
  private final EditorState state;
  private final String owner;
  private final DialogBounds geometry;
  private int viewportId;
  private final PopupWorkAreaResolver workAreas;
  private PopupWorkArea.Plan workArea;
  private final float preferredWidth, preferredHeight;

  DialogLayout(EditorState state, String owner, float width, float height) {
    this(state, owner, width, height, new PopupWorkAreaResolver());
  }

  DialogLayout(
      EditorState state, String owner, float width, float height, PopupWorkAreaResolver workAreas) {
    this.workAreas = Objects.requireNonNull(workAreas);
    this.state = Objects.requireNonNull(state);
    this.owner = owner;
    geometry = new DialogBounds(width, height);
    preferredWidth = width;
    preferredHeight = height;
  }

  static void observe(EditorState state, String owner) {
    state.dialogHosts.observe(
        owner,
        ImGui.getWindowViewport().getID(),
        ImGui.getFrameCount(),
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY()));
  }

  void open(String title) {
    geometry.reset();
    ImGui.openPopup(title);
  }

  boolean begin(String title) {
    return begin(title, 0);
  }

  boolean begin(String title, int flags) {
    // An inactive dialog must preserve another caller's queued NextWindow data.
    if (!ImGui.isPopupOpen(title)) {
      geometry.reset();
      workArea = null;
      return false;
    }
    int requested = state.dialogHosts.viewport(owner, ImGui.getFrameCount());
    var anchor = geometry.observed();
    if (anchor == null) anchor = state.dialogHosts.bounds(owner, ImGui.getFrameCount());
    workArea = workAreas.resolve(requested, anchor, preferredWidth, preferredHeight);
    viewportId = workArea.viewportId();
    var work = workArea.bounds();
    var plan = geometry.prepare(viewportId, work);
    var usable = DialogBounds.inset(work);
    ImGui.setNextWindowViewport(viewportId);
    ImGui.setNextWindowSizeConstraints(
        plan.minimumWidth(), plan.minimumHeight(), usable.width(), usable.height());
    if (plan.position())
      ImGui.setNextWindowPos(plan.bounds().x(), plan.bounds().y(), ImGuiCond.Always);
    if (plan.size())
      ImGui.setNextWindowSize(plan.bounds().width(), plan.bounds().height(), ImGuiCond.Always);
    if (!UiScale.beginPopupModal(title, flags & ~ImGuiWindowFlags.AlwaysAutoResize)) {
      geometry.reset();
      return false;
    }
    geometry.observe(
        new DialogBounds.Rect(
            ImGui.getWindowPosX(),
            ImGui.getWindowPosY(),
            ImGui.getWindowSizeX(),
            ImGui.getWindowSizeY()));
    return true;
  }

  PopupWorkArea.Plan workArea() {
    return workArea;
  }

  DialogBounds.Rect bounds() {
    return geometry.observed();
  }

  int viewportId() {
    return viewportId;
  }

  static void sameLine(String nextLabel) {
    ImGui.sameLine();
    if (ImGui.getContentRegionAvailX() < ImGui.calcTextSize(nextLabel).x + 24) ImGui.newLine();
  }

  static boolean button(String label) {
    float desired = ImGui.calcTextSize(label).x + 24;
    float available = Math.max(1, ImGui.getContentRegionAvailX());
    boolean clicked = ImGui.button(label, Math.min(desired, available), 0);
    if (desired > available && ImGui.isItemHovered()) UiScale.tooltip(label);
    return clicked;
  }

  static void inputWidth(String label) {
    ImGui.setNextItemWidth(
        Math.max(1, ImGui.getContentRegionAvailX() - ImGui.calcTextSize(label).x - 24));
  }
}
