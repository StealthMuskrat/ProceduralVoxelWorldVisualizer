package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiHoveredFlags;
import imgui.flag.ImGuiSliderFlags;
import imgui.type.ImString;
import java.util.function.Consumer;

/** One coordinate slider/text pair; caller owns raw text, focus and explicit actions. */
final class CoordinateInput {
  record Row(
      DialogBounds.Rect slider,
      DialogBounds.Rect input,
      int sliderId,
      int inputId,
      boolean inline,
      boolean enabled,
      CoordinateSlider.Range range) {}

  private final CoordinateSlider slider = new CoordinateSlider();

  static float minimumTextWidth() {
    float digit = 0;
    for (int i = 0; i <= 9; i++) digit = Math.max(digit, ImGui.calcTextSize(Integer.toString(i)).x);
    return digit * 10 + ImGui.calcTextSize("-").x + ImGui.getStyle().getFramePaddingX() * 2;
  }

  Row draw(
      String axis,
      String label,
      float width,
      ImString buffer,
      String accepted,
      Consumer<String> edit,
      Runnable beforeInput) {
    return draw(axis, label, width, buffer, accepted, true, edit, beforeInput);
  }

  Row draw(
      String axis,
      String label,
      float width,
      ImString buffer,
      String accepted,
      boolean allowed,
      Consumer<String> edit,
      Runnable beforeInput) {
    var pair =
        SettingsPageLayout.pair(width, minimumTextWidth(), ImGui.getStyle().getItemSpacingX());
    String sliderLabel = "##coordinate-slider-" + axis;
    int sliderId = ImGui.getID(sliderLabel);
    var view = slider.prepare(sliderId, ImGui.getFrameCount(), accepted, buffer.get());
    boolean enabled = allowed && view.enabled();
    double[] value = {view.value()};
    ImGui.setNextItemWidth(pair.sliderWidth());
    boolean edited;
    ImGui.beginDisabled(!enabled);
    try {
      edited =
          ImGui.sliderScalar(
              sliderLabel,
              value,
              view.range().minimum(),
              view.range().maximum(),
              enabled ? "%.0f" : view.enabled() ? "Unavailable" : "Invalid",
              ImGuiSliderFlags.NoInput | ImGuiSliderFlags.NoRoundToFormat);
    } finally {
      ImGui.endDisabled();
    }
    var proposal = slider.finish(ImGui.isItemActive(), enabled && edited, value[0]);
    if (proposal.isPresent()) {
      buffer.set(proposal.get());
      edit.accept(proposal.get());
    }
    var bounds = PopupLayout.itemBounds();
    if (ImGui.isItemHovered(ImGuiHoveredFlags.AllowWhenDisabled))
      UiScale.tooltip(
          enabled
              ? axis
                  + " suggested range ["
                  + view.range().minimum()
                  + ", "
                  + view.range().maximum()
                  + "]. "
                  + HelpDefinitions.COORDINATE_TOOLTIP
              : HelpDefinitions.COORDINATE_INVALID_TOOLTIP);
    if (pair.inline()) ImGui.sameLine();
    ImGui.setNextItemWidth(pair.inputWidth());
    beforeInput.run();
    if (ImGui.inputText(label, buffer)) edit.accept(buffer.get());
    return new Row(
        bounds,
        PopupLayout.itemBounds(),
        sliderId,
        ImGui.getID(label),
        pair.inline(),
        enabled,
        view.range());
  }
}
