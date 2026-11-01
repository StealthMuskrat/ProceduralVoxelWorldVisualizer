package io.voxelcreator.editor;

import imgui.*;
import imgui.callback.ImGuiInputTextCallback;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiSliderFlags;
import imgui.type.*;
import java.util.*;
import java.util.function.*;

/** Uniform property rows. Text remains provisional until the parent Apply transaction. */
public final class UiFields {
  record NumericRow(
      DialogBounds.Rect slider,
      DialogBounds.Rect input,
      int sliderId,
      int inputId,
      boolean inline,
      double minimum,
      double maximum) {}

  private final Map<String, ImString> texts = new HashMap<>();
  private final Runnable changed;

  public UiFields() {
    this(() -> {});
  }

  public UiFields(Runnable changed) {
    this.changed = Objects.requireNonNull(changed);
  }

  public void clear() {
    texts.clear();
  }

  public void text(String label, String value, Consumer<String> set) {
    text(label, value, set, null);
  }

  void text(String label, String value, Consumer<String> set, ImGuiInputTextCallback callback) {
    ImString b =
        texts.computeIfAbsent(
            ImGui.getID(label) + label,
            k ->
                new ImString(
                    value == null ? "" : value,
                    callback == null ? 16384 : 4 * ExpressionEditSession.MAX_CHARACTERS + 1));
    boolean edited =
        callback == null
            ? ImGui.inputText(label, b)
            : ImGui.inputText(label, b, ImGuiInputTextFlags.CallbackAlways, callback);
    if (edited) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void number(String label, double value, DoubleConsumer set) {
    ImDouble b = new ImDouble(value);
    if (ImGui.inputDouble(label, b, 0, 0, "%.17g")) {
      set.accept(b.get());
      changed.run();
    }
  }

  /** The caller's focus/navigation wrapper owns only the exact input, after the slider. */
  NumericRow number(
      String label,
      double value,
      double minimum,
      double maximum,
      boolean integer,
      DoubleConsumer set,
      Consumer<Runnable> input) {
    if (!Double.isFinite(minimum)
        || !Double.isFinite(maximum)
        || minimum > maximum
        || minimum < -Double.MAX_VALUE / 4
        || maximum > Double.MAX_VALUE / 4)
      throw new IllegalArgumentException("Invalid numeric slider range");
    ImGui.textWrapped(label);
    var pair =
        SettingsPageLayout.pair(
            ImGui.getContentRegionAvailX(),
            7 * ImGui.getFontSize(),
            ImGui.getStyle().getItemSpacingX());
    String sliderKey = "##slider-" + label, inputKey = "##" + label;
    int sliderId = ImGui.getID(sliderKey), inputId = ImGui.getID(inputKey);
    ImGui.setNextItemWidth(pair.sliderWidth());
    double[] slider = {value};
    if (ImGui.sliderScalar(
        sliderKey,
        slider,
        minimum,
        maximum,
        integer ? "%.0f" : "%.5f",
        ImGuiSliderFlags.NoInput | ImGuiSliderFlags.NoRoundToFormat)) {
      double next = integer ? StrictMath.rint(slider[0]) : slider[0];
      if (Double.isFinite(next)
          && Double.doubleToRawLongBits(next) != Double.doubleToRawLongBits(value)) {
        value = next;
        set.accept(value);
        changed.run();
      }
    }
    var sliderBounds = PopupLayout.itemBounds();
    if (pair.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(pair.inputWidth());
    double exact = value;
    input.accept(() -> number(inputKey, exact, set));
    return new NumericRow(
        sliderBounds, PopupLayout.itemBounds(), sliderId, inputId, pair.inline(), minimum, maximum);
  }

  public void integer(String label, int value, IntConsumer set) {
    ImInt b = new ImInt(value);
    if (ImGui.inputInt(label, b)) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void bool(String label, boolean value, Consumer<Boolean> set) {
    ImBoolean b = new ImBoolean(value);
    if (ImGui.checkbox(label, b)) {
      set.accept(b.get());
      changed.run();
    }
  }

  public void choice(String label, String value, List<String> choices, Consumer<String> set) {
    if (UiScale.beginCombo(label, value)) {
      for (String c : choices)
        if (ImGui.selectable(c, c.equals(value))) {
          set.accept(c);
          changed.run();
        }
      ImGui.endCombo();
    }
  }

  public void tooltip(String message) {
    if (ImGui.isItemHovered()) UiScale.tooltip(message);
  }
}
