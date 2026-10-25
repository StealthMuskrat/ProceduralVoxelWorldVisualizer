package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import java.util.*;

/**
 * Clipped explicit choices and read-only dependency preview; filtering never changes selections.
 */
public final class ResourceExportPanel {
  private final EditorState state;
  private final Set<UUID> selected;
  private final ResourceExportSession session = new ResourceExportSession();
  private final ResourceTreeIndex.Cache cache = new ResourceTreeIndex.Cache();
  private final ImString filter = new ImString(256);
  private ResourceTreeIndex previous;
  private List<ResourceTreeIndex.Node> all = List.of(), rows = List.of();
  private String query = "";
  private int submitted;

  public ResourceExportPanel(EditorState state, Set<UUID> selected) {
    this.state = state;
    this.selected = selected;
  }

  public ResourceExportSession prepare() {
    session.prepare(state.project(), state.revision, selected);
    return session;
  }

  public void reset() {
    session.clear();
    cache.clear();
    filter.set("");
    previous = null;
    all = rows = List.of();
  }

  public void draw() {
    var index = cache.get(state.project(), state.revision);
    String text = filter.get().strip().toLowerCase(Locale.ROOT);
    ImGui.inputTextWithHint("##export-filter", "Filter resource IDs or kinds...", filter);
    text = filter.get().strip().toLowerCase(Locale.ROOT);
    if (previous != index || !query.equals(text)) {
      if (previous != index) all = index.all();
      String match = text;
      rows =
          all.stream()
              .filter(n -> (n.kind() + " " + n.id()).toLowerCase(Locale.ROOT).contains(match))
              .toList();
      previous = index;
      query = text;
    }
    ImGui.text(selected.size() + " explicitly selected / " + rows.size() + " matching resources");
    ImGui.beginChild("export-choices", 0, 125, true);
    submitted = 0;
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(rows.size(), ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var node = rows.get(i);
          submitted++;
          var value = new ImBoolean(selected.contains(node.uuid()));
          if (ImGui.checkbox(
              AppMetadata.uiText(node.kind() + " / " + node.id()) + "###" + node.uuid(), value)) {
            if (value.get()) selected.add(node.uuid());
            else selected.remove(node.uuid());
          }
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    ResourceExportSession plan;
    try {
      plan = prepare();
    } catch (RuntimeException failure) {
      ImGui.textWrapped("! Export preview unavailable: " + failure.getMessage());
      if (ImGui.button("Remove Unavailable Selections"))
        selected.removeIf(id -> index.get(id) == null);
      return;
    }
    var included = plan.included();
    ImGui.text(
        "Package contains "
            + included.size()
            + " resources (with dependencies and folder context)");
    ImGui.beginChild("export-included", 0, 100, true);
    var preview = new ImGuiListClipper();
    try {
      preview.begin(included.size(), ImGui.getTextLineHeightWithSpacing());
      while (preview.step())
        for (int i = preview.getDisplayStart(); i < preview.getDisplayEnd(); i++) {
          var node = included.get(i);
          ImGui.text(
              (selected.contains(node.uuid()) ? "Selected: " : "Required: ")
                  + node.kind()
                  + " / "
                  + node.id());
        }
      preview.end();
    } finally {
      preview.destroy();
    }
    ImGui.endChild();
  }

  int submitted() {
    return submitted;
  }

  long preparations() {
    return session.preparations();
  }

  void filter(String text) {
    filter.set(text);
  }
}
