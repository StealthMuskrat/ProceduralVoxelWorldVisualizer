package io.voxelcreator.editor;

import imgui.*;
import imgui.type.*;
import io.voxelcreator.core.NoiseAuthoring;
import io.voxelcreator.editor.NoiseDraftIndex.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Fixed-height ordered summaries with one expanded property item, preserving canonical indices. */
public final class NoiseItemBrowser {
  private static final class View {
    final ImString query = new ImString(256);
    String type = "All", key;
    NoiseDraftIndex source;
    List<Row> rows = List.of();
    UUID selected;
    boolean reveal;
  }

  private final Map<Section, View> views = new EnumMap<>(Section.class);
  private UUID owner;
  private final Map<UUID, float[]> centers = new HashMap<>();
  private int submitted;
  private boolean selectionChanged, userSelectionChanged;
  private float[] filterCenter;

  public int[] draw(UUID noise, NoiseDraftIndex index, Section section, UUID focus) {
    return draw(noise, index, section, focus, (row, enabled) -> {});
  }

  public int[] draw(
      UUID noise,
      NoiseDraftIndex index,
      Section section,
      UUID focus,
      BiConsumer<Row, Boolean> toggle) {
    if (!noise.equals(owner)) {
      owner = noise;
      views.clear();
    }
    var view = views.computeIfAbsent(section, ignored -> new View());
    UUID previousSelection = view.selected;
    var focused = index.get(focus);
    if (focused != null && focused.section() == section) {
      view.selected = focus;
      view.query.set("");
      view.type = "All";
      view.reveal = true;
    }
    ImGui.pushID(section.name());
    ImGui.setNextItemWidth(240);
    ImGui.inputTextWithHint("##item-filter", "Filter item ID, kind or type...", view.query);
    var fa = ImGui.getItemRectMin();
    var fb = ImGui.getItemRectMax();
    filterCenter = new float[] {(fa.x + fb.x) / 2, (fa.y + fb.y) / 2};
    ImGui.sameLine();
    ImGui.setNextItemWidth(130);
    if (UiScale.beginCombo("##type", view.type)) {
      if (ImGui.selectable("All")) view.type = "All";
      for (String type : NoiseAuthoring.TYPES) if (ImGui.selectable(type)) view.type = type;
      ImGui.endCombo();
    }
    String key = view.query.get().strip().toLowerCase(Locale.ROOT) + ":" + view.type;
    if (view.source != index || !key.equals(view.key)) {
      String query = view.query.get().strip().toLowerCase(Locale.ROOT);
      view.rows =
          index.rows(section).stream()
              .filter(
                  row ->
                      (view.type.equals("All") || row.type().equals(view.type))
                          && row.label().toLowerCase(Locale.ROOT).contains(query))
              .toList();
      view.key = key;
      view.source = index;
    }
    if (index.get(view.selected) == null) {
      view.selected = view.rows.isEmpty() ? null : view.rows.getFirst().uuid();
      view.reveal = true;
    }
    ImGui.text(
        view.rows.size() + " matching / " + index.rows(section).size() + " authored item(s)");
    UiScale.beginChild("ordered-items", 0, 180, true);
    centers.clear();
    submitted = 0;
    var clipper = new ImGuiListClipper();
    try {
      clipper.begin(view.rows.size(), ImGui.getFrameHeightWithSpacing());
      while (clipper.step())
        for (int i = clipper.getDisplayStart(); i < clipper.getDisplayEnd(); i++) {
          var row = view.rows.get(i);
          submitted++;
          ImGui.pushID(row.uuid().toString());
          if (section != Section.PARAMETERS) {
            var enabled = new ImBoolean(row.enabled());
            if (ImGui.checkbox("##enabled", enabled)) toggle.accept(row, enabled.get());
            ImGui.sameLine();
          }
          if (ImGui.selectable(
              AppMetadata.uiText(row.label()) + "###" + row.uuid(),
              row.uuid().equals(view.selected),
              0,
              0,
              ImGui.getFrameHeight())) {
            view.selected = row.uuid();
            view.reveal = true;
            userSelectionChanged = true;
          }
          var a = ImGui.getItemRectMin();
          var b = ImGui.getItemRectMax();
          centers.put(row.uuid(), new float[] {(a.x + b.x) / 2, (a.y + b.y) / 2});
          ImGui.popID();
        }
      clipper.end();
    } finally {
      clipper.destroy();
    }
    ImGui.endChild();
    var row = index.get(view.selected);
    if (section != Section.PARAMETERS && row != null && view.reveal)
      ImGui.setNextItemOpen(true, imgui.flag.ImGuiCond.Always);
    view.reveal = false;
    if (!Objects.equals(previousSelection, view.selected)) selectionChanged = true;
    ImGui.popID();
    return row == null || row.section() != section ? new int[0] : new int[] {row.position()};
  }

  void select(Section section, UUID uuid) {
    var view = views.computeIfAbsent(section, ignored -> new View());
    view.selected = uuid;
    view.reveal = true;
    selectionChanged = true;
  }

  void filter(Section section, String query) {
    views.computeIfAbsent(section, ignored -> new View()).query.set(query);
  }

  float[] center(UUID id) {
    return centers.get(id).clone();
  }

  boolean consumeUserSelectionChanged() {
    boolean value = userSelectionChanged;
    userSelectionChanged = false;
    return value;
  }

  boolean consumeSelectionChanged() {
    boolean value = selectionChanged;
    selectionChanged = false;
    return value;
  }

  float[] filterCenter() {
    return filterCenter.clone();
  }

  int submitted() {
    return submitted;
  }
}
