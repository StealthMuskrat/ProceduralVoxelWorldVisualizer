package io.voxelcreator.editor;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import java.util.*;
import java.util.function.Consumer;

/** Native replacement semantics, followed by an inherited-font append into that tooltip. */
final class UiTooltip {
  record Receipt(
      int sourceCharacters,
      String preview,
      boolean truncated,
      float parentFont,
      float font,
      DialogBounds.Rect work,
      DialogBounds.Rect bounds,
      TooltipLayout.Budget budget,
      int viewport) {}

  private static final ThreadLocal<Consumer<Receipt>> OBSERVER = new ThreadLocal<>();

  static void show(String source) {
    Objects.requireNonNull(source);
    var parent = UiScale.parent();
    var mouse = ImGui.getMousePos();
    var anchor = new DialogBounds.Rect(mouse.x, mouse.y, 16, 20);
    var plan =
        new PopupWorkAreaResolver().resolve(ImGui.getWindowViewport().getID(), anchor, 480, 260);
    float desiredFont =
        UiDpiPolicy.inheritedFont(
            parent.fontSize(), parent.contentScale(), UiDpi.contentScale(plan.viewportId()));
    float ratio = desiredFont / parent.fontSize();
    float paddingX = ImGui.getStyle().getWindowPaddingX(),
        paddingY = ImGui.getStyle().getWindowPaddingY();
    var budget = TooltipLayout.budget(plan.bounds(), desiredFont, paddingX, paddingY);
    var preview =
        TooltipText.fit(
            source,
            budget.width(),
            budget.height(),
            text -> {
              var size = ImGui.calcTextSize(text, false, budget.width() / ratio);
              return new TooltipText.Size(size.x * ratio, size.y * ratio);
            });
    var fit =
        PopupBounds.fit(
            plan.bounds(),
            anchor,
            Math.max(1, preview.size().width() + 2 * paddingX + 4),
            Math.max(1, preview.size().height() + 2 * paddingY + 4));
    prepare(plan.viewportId(), fit);
    // SetTooltip selects/replaces the native slot, including its special drag/drop override policy.
    ImGui.setTooltip("");
    // Normal BeginTooltip appends to that slot; drag/drop may replace the empty slot once more.
    prepare(plan.viewportId(), fit);
    ImGui.beginTooltip();
    try {
      UiScale.inherit(parent);
      ImGui.setCursorPos(paddingX, paddingY);
      ImGui.pushTextWrapPos(paddingX + budget.width());
      try {
        ImGui.textUnformatted(preview.text());
      } finally {
        ImGui.popTextWrapPos();
      }
      var observer = OBSERVER.get();
      if (observer != null)
        observer.accept(
            new Receipt(
                source.length(),
                preview.text(),
                preview.truncated(),
                parent.fontSize(),
                ImGui.getTextLineHeight(),
                plan.bounds(),
                new DialogBounds.Rect(
                    ImGui.getWindowPosX(),
                    ImGui.getWindowPosY(),
                    ImGui.getWindowSizeX(),
                    ImGui.getWindowSizeY()),
                budget,
                ImGui.getWindowViewport().getID()));
    } finally {
      // Native color/drag helpers reuse these slots too; do not leave our local scale behind.
      try {
        ImGui.setWindowFontScale(1);
      } finally {
        ImGui.endTooltip();
      }
    }
  }

  private static void prepare(int viewport, DialogBounds.Rect fit) {
    ImGui.setNextWindowViewport(viewport);
    ImGui.setNextWindowSizeConstraints(1, 1, fit.width(), fit.height());
    ImGui.setNextWindowPos(fit.x(), fit.y(), ImGuiCond.Always);
    ImGui.setNextWindowSize(fit.width(), fit.height(), ImGuiCond.Always);
  }

  static void observe(Consumer<Receipt> observer) {
    if (observer == null) OBSERVER.remove();
    else OBSERVER.set(observer);
  }

  private UiTooltip() {}
}
