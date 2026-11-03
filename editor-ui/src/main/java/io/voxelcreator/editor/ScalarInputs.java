package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.type.ImDouble;
import imgui.type.ImString;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Compact idle ink over an unchanged full-precision native editor, with native focus and IDs. */
final class ScalarInputs {
  record Receipt(
      int id,
      String kind,
      boolean compact,
      boolean active,
      boolean visible,
      boolean painted,
      String ink,
      String source,
      String nativeFormat,
      DialogBounds.Rect item,
      ScalarInputPresentation.Ink clip,
      float font,
      int color) {}

  private static final ThreadLocal<Consumer<Receipt>> OBSERVER = new ThreadLocal<>();
  private final ScalarInputPresentation presentation = new ScalarInputPresentation();

  boolean number(String label, ImDouble value) {
    return number(label, value, true);
  }

  boolean number(String label, ImDouble value, boolean compact) {
    int id = ImGui.getID(label);
    return draw(
        label,
        "double",
        compact,
        () -> ImGui.inputDouble(label, value, 0, 0, ScalarInputPresentation.EXACT_FORMAT),
        () -> presentation.compact(id, value.get()),
        () -> ScalarDisplay.exact(value.get()));
  }

  boolean coordinate(String label, ImDouble value) {
    int id = ImGui.getID(label);
    return draw(
        label,
        "coordinate",
        true,
        () -> ImGui.inputDouble(label, value, 0, 0, ScalarInputPresentation.EXACT_FORMAT),
        () -> presentation.coordinate(id, value.get()),
        () -> ScalarDisplay.exact(value.get()));
  }

  /**
   * A null validated value leaves rejected/raw non-scalar text completely with its native owner.
   */
  boolean text(String label, ImString buffer, Double validated) {
    int id = ImGui.getID(label);
    return draw(
        label,
        "raw",
        validated != null && Double.isFinite(validated),
        () -> ImGui.inputText(label, buffer),
        () -> presentation.compact(id, validated),
        buffer::get);
  }

  private boolean draw(
      String label,
      String kind,
      boolean allowed,
      BooleanSupplier nativeInput,
      Supplier<String> readout,
      Supplier<String> source) {
    int id = ImGui.getID(label);
    boolean compact =
        ScalarInputPresentation.compact(allowed, id, imgui.internal.ImGui.getActiveID());
    float width = Math.max(1, ImGui.calcItemWidth()), height = ImGui.getFrameHeight();
    int color = ImGui.getColorU32(ImGuiCol.Text);
    if (compact) ImGui.pushStyleColor(ImGuiCol.Text, 0, 0, 0, 0);
    boolean edited;
    try {
      edited = nativeInput.getAsBoolean();
    } finally {
      if (compact) ImGui.popStyleColor();
    }
    var item = PopupLayout.itemBounds();
    boolean active = ImGui.isItemActive(), visible = ImGui.isItemVisible();
    var clip =
        ScalarInputPresentation.ink(
            item,
            width,
            height,
            ImGui.getStyle().getFramePaddingX(),
            ImGui.getStyle().getFramePaddingY());
    String shown = "";
    if (compact && visible) {
      // A raw edit arriving on the activation frame must show its actual spelling immediately.
      shown = kind.equals("raw") && edited ? source.get() : readout.get();
      var draw = ImGui.getWindowDrawList();
      if (clip.visible()) {
        draw.pushClipRect(
            clip.x(), clip.y(), clip.x() + clip.width(), clip.y() + clip.height(), true);
        try {
          draw.addText(clip.x(), clip.y(), color, shown);
        } finally {
          draw.popClipRect();
        }
      }
      String visibleLabel = ScalarInputPresentation.visibleLabel(label);
      if (!visibleLabel.isEmpty()) {
        draw.pushClipRect(
            item.x(), item.y(), item.x() + item.width(), item.y() + item.height(), true);
        try {
          draw.addText(
              item.x() + width + ImGui.getStyle().getItemInnerSpacingX(),
              item.y() + ImGui.getStyle().getFramePaddingY(),
              color,
              visibleLabel);
        } finally {
          draw.popClipRect();
        }
      }
    }
    var observer = OBSERVER.get();
    if (observer != null)
      observer.accept(
          new Receipt(
              id,
              kind,
              compact,
              active,
              visible,
              compact && visible && clip.visible(),
              shown,
              source.get(),
              kind.equals("raw") ? "raw" : ScalarInputPresentation.EXACT_FORMAT,
              item,
              clip,
              ImGui.getFontSize(),
              color));
    return edited;
  }

  void clear() {
    presentation.clear();
  }

  static void observe(Consumer<Receipt> observer) {
    if (observer == null) OBSERVER.remove();
    else OBSERVER.set(observer);
  }
}
