package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiSliderFlags;
import imgui.type.*;
import java.util.*;
import java.util.function.*;

/** Local view values: measured slider/text pairs and finite double editing. */
final class ViewportFields {
  private final Map<String, DialogBounds.Rect> bounds = new LinkedHashMap<>();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private final Map<String, DialogBounds.Rect> sliders = new LinkedHashMap<>();
  private final Map<String, Integer> sliderIds = new LinkedHashMap<>();
  private final Map<String, Boolean> inline = new LinkedHashMap<>();
  private final Set<Integer> nonfinite = new HashSet<>();

  void number(String label, double value, double minimum, double maximum, DoubleConsumer set) {
    if (!Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || minimum > maximum
        || !Double.isFinite(maximum - minimum))
      throw new IllegalArgumentException("Invalid viewport slider range");
    ImGui.textWrapped(label);
    var pair = pair(label);
    ImGui.setNextItemWidth(pair.sliderWidth());
    String sliderKey = "##slider-" + label;
    sliderIds.put(label, ImGui.getID(sliderKey));
    boolean finite = Double.isFinite(value);
    double[] slider = {finite ? value : minimum};
    // Slider formatting is presentation only. Its native text shortcut is disabled in favor of
    // the exact double input; values outside the suggested range are never clamped by drawing.
    boolean edited;
    ImGui.beginDisabled(!finite);
    try {
      edited =
          ImGui.sliderScalar(
              sliderKey,
              slider,
              minimum,
              maximum,
              finite ? ScalarDisplay.sliderFormat(value) : "Invalid",
              ImGuiSliderFlags.NoRoundToFormat | ImGuiSliderFlags.NoInput);
    } finally {
      ImGui.endDisabled();
    }
    if (finite && edited && Double.isFinite(slider[0])) {
      value = slider[0];
      nonfinite.remove(ImGui.getID("##" + label));
      set.accept(value);
    }
    sliders.put(label, PopupLayout.itemBounds());
    sliderHelp(value, minimum, maximum);
    if (pair.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(pair.inputWidth());
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

  void integer(String label, int value, int minimum, int maximum, IntConsumer set) {
    if (minimum > maximum) throw new IllegalArgumentException("Invalid viewport slider range");
    ImGui.textWrapped(label);
    var pair = pair(label);
    ImGui.setNextItemWidth(pair.sliderWidth());
    String sliderKey = "##slider-" + label;
    sliderIds.put(label, ImGui.getID(sliderKey));
    // Double exactly represents every int and avoids the native signed-int slider's half-range
    // restriction when project Y bounds span the full signed coordinate domain.
    double[] slider = {value};
    if (ImGui.sliderScalar(
        sliderKey,
        slider,
        minimum,
        maximum,
        "%.0f",
        ImGuiSliderFlags.NoRoundToFormat | ImGuiSliderFlags.NoInput)) {
      int next = (int) StrictMath.rint(slider[0]);
      if (next != value) {
        value = next;
        set.accept(value);
      }
    }
    sliders.put(label, PopupLayout.itemBounds());
    sliderHelp(value, minimum, maximum);
    if (pair.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(pair.inputWidth());
    var b = new ImInt(value);
    ids.put(label, ImGui.getID("##" + label));
    if (ImGui.inputInt("##" + label, b)) set.accept(b.get());
    bounds.put(label, PopupLayout.itemBounds());
  }

  private SettingsPageLayout.Pair pair(String label) {
    var pair =
        SettingsPageLayout.pair(
            ImGui.getContentRegionAvailX(),
            7 * ImGui.getFontSize(),
            ImGui.getStyle().getItemSpacingX());
    inline.put(label, pair.inline());
    return pair;
  }

  private void sliderHelp(double value, double minimum, double maximum) {
    if (ImGui.isItemHovered())
      UiScale.tooltip(
          "Value: "
              + ScalarDisplay.exact(value)
              + "\nSuggested slider range ["
              + minimum
              + ", "
              + maximum
              + "]. Use the text field for an exact value. Existing view limits still apply.");
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

  Map<String, DialogBounds.Rect> sliders() {
    return Map.copyOf(sliders);
  }

  Map<String, Integer> sliderIds() {
    return Map.copyOf(sliderIds);
  }

  Map<String, Boolean> inline() {
    return Map.copyOf(inline);
  }

  void reset() {
    bounds.clear();
    ids.clear();
    sliders.clear();
    sliderIds.clear();
    inline.clear();
    nonfinite.clear();
  }
}
