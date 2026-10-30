package io.voxelcreator.editor;

/** Logical tooltip budgets use current inherited font metrics and actual client/monitor bounds. */
final class TooltipLayout {
  record Budget(float width, float height) {}

  static Budget budget(DialogBounds.Rect work, float font, float paddingX, float paddingY) {
    if (!Float.isFinite(font)
        || font <= 0
        || !Float.isFinite(paddingX)
        || paddingX < 0
        || !Float.isFinite(paddingY)
        || paddingY < 0) throw new IllegalArgumentException("Invalid tooltip metrics");
    var area = DialogBounds.inset(work);
    return new Budget(
        (float) Math.max(1, Math.min(36.0 * font, (double) area.width() - 2.0 * paddingX - 4)),
        (float) Math.max(1, Math.min(12.0 * font, (double) area.height() - 2.0 * paddingY - 4)));
  }

  private TooltipLayout() {}
}
