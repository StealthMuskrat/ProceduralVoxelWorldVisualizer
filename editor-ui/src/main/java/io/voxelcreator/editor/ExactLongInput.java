package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.*;
import imgui.type.ImString;
import java.util.function.Consumer;

/** Relative long slider beside the original raw text widget; never publishes on redraw. */
final class ExactLongInput {
  record Row(
      DialogBounds.Rect slider,
      DialogBounds.Rect input,
      int sliderId,
      int inputId,
      boolean inline,
      boolean enabled,
      ExactLongSlider.Range range) {}

  private final ExactLongSlider slider = new ExactLongSlider();

  Row draw(
      String inputLabel,
      float width,
      ImString buffer,
      String accepted,
      Long model,
      boolean allowed,
      Consumer<String> edit,
      Runnable beforeInput) {
    int hidden = inputLabel.indexOf("##");
    String visible = hidden < 0 ? inputLabel : inputLabel.substring(0, hidden);
    float labelWidth =
        visible.isEmpty()
            ? 0
            : ImGui.calcTextSize(visible).x + ImGui.getStyle().getItemInnerSpacingX();
    float digit = 0;
    for (int i = 0; i < 10; i++) digit = Math.max(digit, ImGui.calcTextSize(Integer.toString(i)).x);
    float minimum =
        digit * 19
            + Math.max(ImGui.calcTextSize("-").x, ImGui.calcTextSize("+").x)
            + ImGui.getStyle().getFramePaddingX() * 2;
    var pair =
        SettingsPageLayout.pair(
            Math.max(1, width - labelWidth), minimum, ImGui.getStyle().getItemSpacingX());
    // Derive a distinct slider scope from the complete existing text ID, including ### labels.
    int inputId = ImGui.getID(inputLabel);
    String sliderLabel = "##long-slider-" + Integer.toUnsignedString(inputId);
    int sliderId = ImGui.getID(sliderLabel);
    var view = slider.prepare(sliderId, ImGui.getFrameCount(), accepted, buffer.get());
    boolean enabled =
        allowed && view.enabled() && (model == null || model.longValue() == view.value());
    boolean valid = view.enabled() && (model == null || model.longValue() == view.value());
    double[] offset = {view.offset()};
    ImGui.setNextItemWidth(pair.sliderWidth());
    boolean edited;
    ImGui.beginDisabled(!enabled);
    try {
      edited =
          ImGui.sliderScalar(
              sliderLabel,
              offset,
              view.range().minimumOffset(),
              view.range().maximumOffset(),
              enabled ? "" : valid ? "Unavailable" : "Invalid",
              ImGuiSliderFlags.NoInput | ImGuiSliderFlags.NoRoundToFormat);
    } finally {
      ImGui.endDisabled();
    }
    var proposal = slider.finish(ImGui.isItemActive(), enabled && edited, offset[0]);
    if (proposal.isPresent()) {
      buffer.set(proposal.get());
      edit.accept(proposal.get());
    }
    var bounds = PopupLayout.itemBounds();
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      UiScale.tooltip(
          enabled
              ? HelpDefinitions.LONG_SLIDER_TOOLTIP
                  + "\nSuggested range ["
                  + Math.addExact(view.range().anchor(), view.range().minimumOffset())
                  + ", "
                  + Math.addExact(view.range().anchor(), view.range().maximumOffset())
                  + "]."
              : "Correct or restore the complete seed text before using its slider.");
    if (pair.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(pair.inputWidth());
    beforeInput.run();
    if (ImGui.inputText(inputLabel, buffer)) edit.accept(buffer.get());
    return new Row(
        bounds, PopupLayout.itemBounds(), sliderId, inputId, pair.inline(), enabled, view.range());
  }
}
