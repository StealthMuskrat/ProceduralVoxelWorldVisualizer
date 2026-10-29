package io.voxelcreator.editor;

import imgui.*;
import imgui.flag.*;
import java.util.Objects;

/** Shared modal bounds/owner policy. All native wrappers are queried and used within one call. */
final class DialogLayout {
  private final EditorState state;
  private final String owner;
  private final DialogBounds geometry;
  private int viewportId, lastWorkViewport;
  private final float preferredWidth, preferredHeight;
  private DialogBounds.Rect lastWork;

  DialogLayout(EditorState state, String owner, float width, float height) {
    this.state = Objects.requireNonNull(state);
    this.owner = owner;
    geometry = new DialogBounds(width, height);
    preferredWidth = width;
    preferredHeight = height;
  }

  static void observe(EditorState state, String owner) {
    state.dialogHosts.observe(owner, ImGui.getWindowViewport().getID(), ImGui.getFrameCount());
  }

  void open(String title) {
    geometry.reset();
    ImGui.openPopup(title);
  }

  boolean begin(String title) {
    return begin(title, 0);
  }

  boolean begin(String title, int flags) {
    int requested = state.dialogHosts.viewport(owner, ImGui.getFrameCount());
    var viewport = requested == 0 ? null : ImGui.findViewportByID(requested);
    if (viewport == null || !viewport.isValidPtr()) viewport = ImGui.getMainViewport();
    viewportId = viewport.getID();
    float x = viewport.getWorkPosX(),
        y = viewport.getWorkPosY(),
        width = viewport.getWorkSizeX(),
        height = viewport.getWorkSizeY();
    DialogBounds.Rect work;
    if (valid(x, y, width, height)) work = new DialogBounds.Rect(x, y, width, height);
    else if (lastWork != null && lastWorkViewport == viewportId) work = lastWork;
    else {
      var main = ImGui.getMainViewport();
      x = main.getWorkPosX();
      y = main.getWorkPosY();
      width = main.getWorkSizeX();
      height = main.getWorkSizeY();
      viewportId = main.getID();
      if (!valid(x, y, width, height)) {
        x = main.getPosX();
        y = main.getPosY();
        width = main.getSizeX();
        height = main.getSizeY();
      }
      if (!valid(x, y, width, height)) {
        x = y = 0;
        width = Math.max(160, preferredWidth) + 16;
        height = Math.max(120, preferredHeight) + 16;
      }
      work = new DialogBounds.Rect(x, y, width, height);
    }
    lastWork = work;
    lastWorkViewport = viewportId;
    var plan = geometry.prepare(viewportId, work);
    var usable = DialogBounds.inset(work);
    ImGui.setNextWindowViewport(viewportId);
    ImGui.setNextWindowSizeConstraints(
        plan.minimumWidth(), plan.minimumHeight(), usable.width(), usable.height());
    if (plan.position())
      ImGui.setNextWindowPos(plan.bounds().x(), plan.bounds().y(), ImGuiCond.Always);
    if (plan.size())
      ImGui.setNextWindowSize(plan.bounds().width(), plan.bounds().height(), ImGuiCond.Always);
    if (!ImGui.beginPopupModal(title, flags & ~ImGuiWindowFlags.AlwaysAutoResize)) {
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

  private static boolean valid(float x, float y, float width, float height) {
    return Float.isFinite(x)
        && Float.isFinite(y)
        && Float.isFinite(width)
        && Float.isFinite(height)
        && width > 0
        && height > 0;
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
    if (desired > available && ImGui.isItemHovered()) ImGui.setTooltip(label);
    return clicked;
  }

  static void inputWidth(String label) {
    ImGui.setNextItemWidth(
        Math.max(1, ImGui.getContentRegionAvailX() - ImGui.calcTextSize(label).x - 24));
  }
}
