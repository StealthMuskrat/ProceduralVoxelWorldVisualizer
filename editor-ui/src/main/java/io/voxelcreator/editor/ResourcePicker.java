package io.voxelcreator.editor;

import imgui.*;
import imgui.type.ImString;
import java.util.*;
import java.util.function.Consumer;

/**
 * Simple searchable, clipped UUID selector. Drawing/filtering never invokes the change callback.
 */
public final class ResourcePicker {
  private final EditorState state;
  private final ResourceTreeIndex.Cache cache = new ResourceTreeIndex.Cache();

  private static final class Widget {
    final ImString filter = new ImString(256);
    final ResourceChoices choices = new ResourceChoices();
  }

  private final Map<Integer, Widget> widgets = new LinkedHashMap<>(16, .75f, true);
  private boolean probeOpen;
  private final Map<UUID, float[]> optionBounds = new HashMap<>();
  private int submitted;
  private float[] buttonBounds;
  private io.voxelcreator.model.ProjectData observedProject;
  private long observedRevision;

  public ResourcePicker(EditorState state) {
    this.state = state;
  }

  public void synchronize() {
    if (observedProject != state.project() || observedRevision != state.revision) {
      cache.clear();
      widgets.values().forEach(widget -> widget.choices.clear());
      observedProject = state.project();
      observedRevision = state.revision;
    }
  }

  public void draw(
      String label, String kind, UUID current, String noneLabel, Consumer<UUID> change) {
    synchronize();
    var index = cache.get(state.project(), state.revision);
    int id = ImGui.getID(label);
    var node = index.get(current);
    String preview =
        current == null
            ? Objects.requireNonNullElse(noneLabel, "Select...")
            : ResourceChoices.accepts(node, kind)
                ? ResourceChoices.label(node)
                : "Missing reference";
    ImGui.pushID(id);
    if (ImGui.button(
            AppMetadata.uiText(preview) + "  v###resource-button",
            Math.max(100, Math.min(320, ImGui.getContentRegionAvailX() * .65f)),
            0)
        || probeOpen) {
      ImGui.openPopup("resource-choice");
      probeOpen = false;
    }
    buttonBounds =
        new float[] {
          (ImGui.getItemRectMinX() + ImGui.getItemRectMaxX()) / 2,
          (ImGui.getItemRectMinY() + ImGui.getItemRectMaxY()) / 2
        };
    if (ImGui.isItemHovered() && current != null) ImGui.setTooltip(preview + " / " + current);
    ImGui.sameLine();
    ImGui.text(label);
    submitted = 0;
    optionBounds.clear();
    if (ImGui.beginPopup("resource-choice")) {
      if (!widgets.containsKey(id) && widgets.size() >= 128)
        widgets.remove(widgets.keySet().iterator().next());
      var widget = widgets.computeIfAbsent(id, key -> new Widget());
      if (ImGui.isWindowAppearing()) ImGui.setKeyboardFocusHere();
      ImGui.setNextItemWidth(340);
      ImGui.inputTextWithHint("##query", "Filter IDs...", widget.filter);
      if (noneLabel != null && ImGui.selectable(noneLabel + "###none", current == null)) {
        change.accept(ResourceChoices.choose(index, kind, null, true));
        ImGui.closeCurrentPopup();
      }
      var rows = widget.choices.rows(index, kind, widget.filter.get());
      ImGui.text(rows.size() + " matching resource(s)");
      ImGui.beginChild("resource-options", 380, 220, true);
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(rows.size(), ImGui.getTextLineHeightWithSpacing());
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var option = rows.get(i);
            submitted++;
            if (ImGui.selectable(
                AppMetadata.uiText(ResourceChoices.label(option)) + "###" + option.uuid(),
                option.uuid().equals(current))) {
              change.accept(ResourceChoices.choose(index, kind, option.uuid(), noneLabel != null));
              ImGui.closeCurrentPopup();
            }
            var min = ImGui.getItemRectMin();
            var max = ImGui.getItemRectMax();
            optionBounds.put(option.uuid(), new float[] {(min.x + max.x) / 2, (min.y + max.y) / 2});
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
      ImGui.endChild();
      ImGui.endPopup();
    }
    ImGui.popID();
  }

  float[] buttonCenter() {
    return buttonBounds.clone();
  }

  void probeOpen() {
    probeOpen = true;
  }

  float[] optionCenter(UUID id) {
    return optionBounds.get(id).clone();
  }

  int submitted() {
    return submitted;
  }

  int widgetCount() {
    return widgets.size();
  }
}
