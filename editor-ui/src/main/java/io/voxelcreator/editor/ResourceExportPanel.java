package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Clipped export workspace over one owned source; changed sources require explicit reload. */
public final class ResourceExportPanel {
  private final EditorState state;
  private final Set<UUID> selected;
  private ExportEditSession edit;
  private final ImString filter = new ImString(256);
  private ResourceTreeIndex previous;
  private List<ResourceTreeIndex.Node> all = List.of(), rows = List.of();
  private String query = "", message = "";
  private int submitted;
  private final Map<String, float[]> controls = new HashMap<>();

  public ResourceExportPanel(EditorState state, Set<UUID> selected) {
    this.state = state;
    this.selected = selected;
  }

  void open() {
    if (edit == null) edit = new ExportEditSession(state, selected);
  }

  public ResourceExportSession prepare() {
    open();
    edit.select(selected);
    return edit.preparedModel();
  }

  boolean canExport() {
    return edit != null && selected.equals(edit.selected()) && edit.canExport();
  }

  void write(Path destination, ResourcePackageFiles files) throws IOException {
    open();
    edit.select(selected);
    edit.write(destination, files);
  }

  public void reset() {
    if (edit != null) edit.cancel();
    edit = null;
    filter.set("");
    previous = null;
    all = rows = List.of();
    message = "";
    controls.clear();
  }

  public void draw() {
    controls.clear();
    submitted = 0;
    try {
      open();
    } catch (RuntimeException failure) {
      message = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
      ImGui.textWrapped("! Export unavailable: " + bounded(message));
      return;
    }
    if (edit.closed()) return;
    boolean current = edit.current();
    ImGui.textWrapped(
        "Export uses committed resources. Unapplied Properties edits remain private.");
    if (!current) {
      ImGui.textWrapped(
          "The source changed. Reload Preview to retain your choices and review the current"
              + " resources.");
      ImGui.beginDisabled(!edit.canReload());
      boolean reload = ImGui.button("Reload Preview");
      remember("reload");
      ImGui.endDisabled();
      if (reload) {
        try {
          edit.reload();
          previous = null;
          message = "";
        } catch (RuntimeException failure) {
          message = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
        }
        return;
      }
      if (!edit.canReload())
        ImGui.textWrapped("The project was replaced. Cancel and reopen Export.");
    }
    if (!message.isEmpty()) ImGui.textWrapped("! " + bounded(message));
    var index = edit.catalog();
    ImGui.inputTextWithHint("##export-filter", "Filter resource IDs or kinds...", filter);
    remember("filter");
    String text = filter.get().strip().toLowerCase(Locale.ROOT);
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
    ImGui.beginDisabled(!current);
    if (edit.unavailableCount() != 0) {
      ImGui.textWrapped(
          "Some selected resources are unavailable. Remove them explicitly before exporting.");
      if (ImGui.button("Remove Unavailable Selections"))
        selected.removeIf(id -> index.get(id) == null);
      remember("remove-unavailable");
    }
    boolean choicesVisible = UiScale.beginChild("export-choices", 0, 125, true);
    if (choicesVisible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(rows.size(), ImGui.getFrameHeightWithSpacing());
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var node = rows.get(i);
            submitted++;
            var value = new ImBoolean(selected.contains(node.uuid()));
            if (ImGui.checkbox(
                AppMetadata.uiText(bounded(node.kind() + " / " + node.id())) + "###" + node.uuid(),
                value)) {
              if (value.get()) selected.add(node.uuid());
              else selected.remove(node.uuid());
            }
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    ImGui.endChild();
    ImGui.endDisabled();
    if (current) {
      try {
        edit.select(selected);
      } catch (RuntimeException failure) {
        message = Objects.requireNonNullElse(failure.getMessage(), failure.toString());
        ImGui.textWrapped("! " + bounded(message));
        return;
      }
    }
    var preview = edit.preview();
    if (preview == null) {
      ImGui.textWrapped(
          "Export preview unavailable; choose current resources or remove unavailable selections.");
      return;
    }
    var included = preview.included();
    ImGui.text(
        "Package contains "
            + included.size()
            + " resources (with dependencies and folder context)");
    boolean previewVisible = UiScale.beginChild("export-included", 0, 100, true);
    if (previewVisible) {
      var clipper = new ImGuiListClipper();
      try {
        clipper.begin(included.size(), ImGui.getTextLineHeightWithSpacing());
        while (clipper.step())
          for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
            var node = included.get(i);
            ImGui.text(
                bounded(
                    (preview.selected().contains(node.uuid()) ? "Selected: " : "Required: ")
                        + node.kind()
                        + " / "
                        + node.id()));
          }
        clipper.end();
      } finally {
        clipper.destroy();
      }
    }
    ImGui.endChild();
    if (current && previewVisible) edit.markShown(preview);
  }

  private static String bounded(String text) {
    return text.length() > 512 ? text.substring(0, 511) + "…" : text;
  }

  private void remember(String key) {
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

  int submitted() {
    return submitted;
  }

  long preparations() {
    return edit == null ? 0 : edit.preparations();
  }

  void filter(String text) {
    filter.set(text);
  }
}
