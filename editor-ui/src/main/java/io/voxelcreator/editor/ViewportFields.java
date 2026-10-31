package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.type.*;
import java.util.*;
import java.util.function.*;

/** Local view values: readable wrapped labels and bounded numeric fields, with finite doubles. */
final class ViewportFields {
  private final Map<String, DialogBounds.Rect> bounds = new LinkedHashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private final Set<Integer> nonfinite = new HashSet<>();

  void number(String label, double value, DoubleConsumer set) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    var b = new ImDouble(value);
    int id = ImGui.getID("##" + label);
    ids.put(label, id);
    if (ImGui.inputDouble("##" + label, b, 0, 0, "%.17g")) {
      if (Double.isFinite(b.get())) {
        nonfinite.remove(id);
        set.accept(b.get());
      } else nonfinite.add(id);
    }
    bounds.put(label, PopupLayout.itemBounds());
    if (!ImGui.isItemActive()) nonfinite.remove(id);
    if (nonfinite.contains(id)) ImGui.textWrapped("Enter a finite number.");
  }

  void integer(String label, int value, IntConsumer set) {
    ImGui.textWrapped(label);
    ImGui.setNextItemWidth(Math.max(1, ImGui.getContentRegionAvailX()));
    var b = new ImInt(value);
    ids.put(label, ImGui.getID("##" + label));
    if (ImGui.inputInt("##" + label, b)) set.accept(b.get());
    bounds.put(label, PopupLayout.itemBounds());
  }

  void bool(String label, boolean value, Consumer<Boolean> set) {
    float needed =
        ImGui.getFrameHeight()
            + ImGui.getStyle().getItemInnerSpacingX()
            + ImGui.calcTextSize(label).x;
    boolean inline = needed <= ImGui.getContentRegionAvailX();
    if (!inline) ImGui.textWrapped(label);
    var b = new ImBoolean(value);
    String widget = (inline ? label : "") + "###" + label;
    ids.put(label, ImGui.getID(widget));
    if (ImGui.checkbox(widget, b)) set.accept(b.get());
    bounds.put(label, PopupLayout.itemBounds());
  }

  DialogBounds.Rect bounds(String label) {
    return bounds.get(label);
  }

  Map<String, Integer> ids() {
    return Map.copyOf(ids);
  }
}
