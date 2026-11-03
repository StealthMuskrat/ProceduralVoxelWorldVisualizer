package io.voxelcreator.editor;

import imgui.*;
import imgui.type.ImString;
import java.util.*;
import java.util.function.*;

/**
 * Responsive production XYZ presentation; buffers, raw validation and task ownership stay outside.
 */
final class InspectorCoordinateFields {
  private final Map<String, DialogBounds.Rect> bounds = new LinkedHashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private final Map<String, CoordinateInput> inputs = new LinkedHashMap<>();
  private final Map<String, CoordinateInput.Row> rows = new LinkedHashMap<>();
  private CoordinateFieldLayout.Plan plan;
  private String focus;

  void reveal(String axis) {
    if (axis != null && !List.of("X", "Y", "Z").contains(axis))
      throw new IllegalArgumentException("Unknown Inspector axis " + axis);
    focus = axis;
  }

  String pendingFocus() {
    return focus;
  }

  void draw(
      Function<String, ImString> buffers,
      Function<String, String> accepted,
      BiConsumer<String, String> edit,
      Consumer<String> record) {
    Objects.requireNonNull(buffers);
    Objects.requireNonNull(accepted);
    Objects.requireNonNull(edit);
    Objects.requireNonNull(record);
    ImGui.text("XYZ");
    float minimum = CoordinateInput.minimumTextWidth();
    float labelWidth = 0;
    for (var axis : List.of("X", "Y", "Z"))
      labelWidth = Math.max(labelWidth, ImGui.calcTextSize(axis).x);
    labelWidth += ImGui.getStyle().getItemInnerSpacingX();
    plan =
        CoordinateFieldLayout.prepare(
            ImGui.getContentRegionAvailX(),
            minimum,
            labelWidth,
            ImGui.getStyle().getItemSpacingX());
    bounds.clear();
    ids.clear();
    rows.clear();
    int index = 0;
    for (var axis : List.of("X", "Y", "Z")) {
      if (index++ % plan.columns() != 0) ImGui.sameLine();
      ImGui.beginGroup();
      if (plan.stackedLabels()) ImGui.textWrapped(axis);
      String label = (plan.stackedLabels() ? "" : axis) + "###inspector-coordinate-" + axis;
      var input = buffers.apply(axis);
      boolean reveal = axis.equals(focus);
      var row =
          inputs
              .computeIfAbsent(axis, ignored -> new CoordinateInput())
              .draw(
                  axis,
                  label,
                  plan.inputWidth(),
                  input,
                  accepted.apply(axis),
                  text -> edit.accept(axis, text),
                  () -> {
                    if (reveal) ImGui.setKeyboardFocusHere();
                  });
      if (reveal) {
        ImGui.setScrollHereY(.5f);
        if (ImGui.isItemActive() || ImGui.isItemFocused()) focus = null;
      }
      bounds.put(axis, row.input());
      ids.put(axis, row.inputId());
      rows.put(axis, row);
      record.accept(axis);
      ImGui.endGroup();
    }
  }

  DialogBounds.Rect bounds(String axis) {
    return bounds.get(axis);
  }

  Map<String, Integer> ids() {
    return Map.copyOf(ids);
  }

  CoordinateFieldLayout.Plan plan() {
    return plan;
  }

  Map<String, CoordinateInput.Row> rows() {
    return Map.copyOf(rows);
  }
}
