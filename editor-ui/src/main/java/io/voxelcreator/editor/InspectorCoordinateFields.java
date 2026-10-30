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
  private CoordinateFieldLayout.Plan plan;

  void draw(
      Function<String, ImString> buffers,
      BiConsumer<String, String> edit,
      Consumer<String> record) {
    Objects.requireNonNull(buffers);
    Objects.requireNonNull(edit);
    Objects.requireNonNull(record);
    ImGui.text("XYZ");
    float digit = 0;
    for (int i = 0; i <= 9; i++) digit = Math.max(digit, ImGui.calcTextSize(Integer.toString(i)).x);
    float minimum =
        digit * 10 + ImGui.calcTextSize("-").x + ImGui.getStyle().getFramePaddingX() * 2;
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
    int index = 0;
    for (var axis : List.of("X", "Y", "Z")) {
      if (index++ % plan.columns() != 0) ImGui.sameLine();
      if (plan.stackedLabels()) ImGui.textWrapped(axis);
      String label = (plan.stackedLabels() ? "" : axis) + "###inspector-coordinate-" + axis;
      ImGui.setNextItemWidth(plan.inputWidth());
      var input = buffers.apply(axis);
      if (ImGui.inputText(label, input)) edit.accept(axis, input.get());
      bounds.put(axis, PopupLayout.itemBounds());
      ids.put(axis, ImGui.getID(label));
      record.accept(axis);
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
}
