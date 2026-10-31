package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.type.ImBoolean;
import java.util.*;
import java.util.function.*;

/** Complete command drawing precedes one owned callback outside native ID/popup scopes. */
final class ViewportCommands {
  enum Command {
    GRAYSCALE("Grayscale"),
    HEATMAP("Heatmap"),
    CONTOUR("Contour"),
    THRESHOLD("Threshold"),
    HISTOGRAM("Histogram"),
    GRID("Grid overlay"),
    TELEPORT("Teleport"),
    ADD_BOOKMARK("Add Bookmark"),
    REGENERATE("Regenerate"),
    AUTO("Auto");
    final String label;

    Command(String label) {
      this.label = label;
    }
  }

  private final EditorState state;
  private final SettingsActionScope toolbar = new SettingsActionScope();
  private final String nativeId = UUID.randomUUID().toString();
  final PopupLayout layout = new PopupLayout();
  private DialogBounds.Rect anchor;
  private ViewportActionContext toolbarReview, menuReview;
  private int toolbarFrame = -1, menuFrame = -1, menuHost;
  private long menuOpening;
  private boolean menuActive;
  private final Map<String, Integer> toolbarIds = new LinkedHashMap<>(),
      menuIds = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> toolbarBounds = new LinkedHashMap<>(),
      menuBounds = new LinkedHashMap<>();
  private DialogBounds.Rect popupBounds;

  ViewportCommands(EditorState state) {
    this.state = Objects.requireNonNull(state);
  }

  void toolbar(
      Supplier<ViewportActionContext> context, BiConsumer<Command, ViewportActionContext> apply) {
    var review = context.get();
    int frame = ImGui.getFrameCount();
    if (frame < toolbarFrame || frame - toolbarFrame > 1) toolbar.release();
    toolbarFrame = frame;
    if (!review.same(toolbarReview)) toolbarReview = review;
    long version = toolbar.synchronize(review.document(), toolbarReview);
    toolbarIds.clear();
    toolbarBounds.clear();
    ImGui.pushID("viewport-toolbar:" + nativeId + ":" + version);
    toolbarIds.put(Command.REGENERATE.name(), ImGui.getID(Command.REGENERATE.label));
    boolean regenerate = DialogLayout.button(Command.REGENERATE.label);
    toolbarBounds.put(Command.REGENERATE.name(), PopupLayout.itemBounds());
    ImGui.sameLine();
    float autoWidth =
        ImGui.getFrameHeight()
            + ImGui.getStyle().getItemInnerSpacingX()
            + ImGui.calcTextSize(Command.AUTO.label).x;
    if (ImGui.getContentRegionAvailX() < autoWidth) ImGui.newLine();
    var auto = new ImBoolean(review.auto());
    toolbarIds.put(Command.AUTO.name(), ImGui.getID(Command.AUTO.label));
    boolean automatic = ImGui.checkbox(Command.AUTO.label, auto);
    toolbarBounds.put(Command.AUTO.name(), PopupLayout.itemBounds());
    ImGui.popID();
    if (!ImGui.isKeyPressed(ImGuiKey.Escape, false)
        && review.same(context.get())
        && toolbar.current(review.document(), toolbarReview)) {
      var chosen = regenerate ? Command.REGENERATE : automatic ? Command.AUTO : null;
      if (chosen != null && invoke(chosen, review, apply)) toolbar.release();
    }
  }

  void openContext(ViewportActionContext context) {
    if (ImGui.isKeyPressed(ImGuiKey.Escape, false)) return;
    menuReview = Objects.requireNonNull(context);
    menuOpening++;
    menuFrame = -1;
    menuHost = ImGui.getID("viewport-context");
    anchor =
        new DialogBounds.Rect(ImGui.getIO().getMousePosX(), ImGui.getIO().getMousePosY(), 1, 1);
    menuActive = true;
    ImGui.openPopup("viewport-context");
  }

  boolean context(
      Supplier<ViewportActionContext> context, BiConsumer<Command, ViewportActionContext> apply) {
    menuIds.clear();
    menuBounds.clear();
    int host = ImGui.getID("viewport-context");
    var placement =
        anchor == null
            ? new DialogBounds.Rect(
                ImGui.getIO().getMousePosX(), ImGui.getIO().getMousePosY(), 1, 1)
            : anchor;
    if (!layout.begin("viewport-context", placement, 420, 360)) {
      retireMenu();
      return false;
    }
    int frame = ImGui.getFrameCount();
    var review = menuReview;
    boolean escape = ImGui.isKeyPressed(ImGuiKey.Escape, false);
    if (!menuActive
        || review == null
        || !review.same(context.get())
        || menuHost != host
        || frame < menuFrame
        || menuFrame >= 0 && frame - menuFrame > 1
        || escape) {
      ImGui.closeCurrentPopup();
      retireMenu();
      ImGui.endPopup();
      return true;
    }
    menuFrame = frame;
    popupBounds = layout.bounds();
    long opening = menuOpening;
    Command chosen = null;
    UiScale.beginChild(
        "viewport-command-options", 0, Math.max(1, ImGui.getContentRegionAvailY()), true);
    layout.observeList();
    ImGui.pushID("viewport-menu:" + nativeId + ":" + opening);
    for (var command : menuCommands(review.tab())) {
      boolean checked =
          command == Command.GRID ? review.grid() : command.label.equals(review.representation());
      menuIds.put(command.name(), ImGui.getID(command.label));
      boolean clicked = ImGui.menuItem(command.label, "", checked);
      menuBounds.put(command.name(), PopupLayout.itemBounds());
      PopupLayout.clippedLabel(command.label);
      if (clicked && chosen == null) chosen = command;
    }
    ImGui.popID();
    ImGui.endChild();
    if (chosen != null
        && opening == menuOpening
        && review.same(context.get())
        && !ImGui.isKeyPressed(ImGuiKey.Escape, false)) {
      ImGui.closeCurrentPopup();
      retireMenu();
    } else chosen = null;
    ImGui.endPopup();
    if (chosen != null) invoke(chosen, review, apply);
    return true;
  }

  static List<Command> menuCommands(int tab) {
    return tab == 0
        ? List.of(
            Command.GRAYSCALE,
            Command.HEATMAP,
            Command.CONTOUR,
            Command.THRESHOLD,
            Command.HISTOGRAM,
            Command.GRID,
            Command.TELEPORT,
            Command.ADD_BOOKMARK,
            Command.REGENERATE)
        : List.of(Command.TELEPORT, Command.ADD_BOOKMARK, Command.REGENERATE);
  }

  private boolean invoke(
      Command command,
      ViewportActionContext review,
      BiConsumer<Command, ViewportActionContext> apply) {
    try {
      apply.accept(command, review);
      return true;
    } catch (RuntimeException failure) {
      var detail = failure.getMessage();
      if (detail == null || detail.isBlank()) detail = failure.getClass().getName();
      state.error(
          "VIEWPORT_COMMAND_FAILED",
          EditorEvents.Subsystem.APPLICATION,
          StatusBarText.clip(detail, 1024));
      return false;
    }
  }

  private void retireMenu() {
    menuActive = false;
    menuReview = null;
    menuFrame = -1;
    menuHost = 0;
  }

  Map<String, Integer> toolbarIds() {
    return Map.copyOf(toolbarIds);
  }

  Map<String, Integer> menuIds() {
    return Map.copyOf(menuIds);
  }

  Map<String, DialogBounds.Rect> toolbarBounds() {
    return Map.copyOf(toolbarBounds);
  }

  Map<String, DialogBounds.Rect> menuBounds() {
    return Map.copyOf(menuBounds);
  }

  DialogBounds.Rect popupBounds() {
    return popupBounds;
  }

  float[] toolbarCenter(Command command) {
    return center(toolbarBounds.get(command.name()));
  }

  float[] menuCenter(Command command) {
    return center(menuBounds.get(command.name()));
  }

  private static float[] center(DialogBounds.Rect r) {
    return r == null ? null : new float[] {r.x() + r.width() / 2, r.y() + r.height() / 2};
  }
}
