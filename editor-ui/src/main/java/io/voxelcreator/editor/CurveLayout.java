package io.voxelcreator.editor;

/**
 * Pure vertical budget: keep two action rows outside scrolling content and bound plot dimensions.
 */
final class CurveLayout {
  record Plot(float width, float height) {}

  private CurveLayout() {}

  static float body(float availableHeight, float rowHeight) {
    finite(availableHeight);
    finite(rowHeight);
    if (rowHeight <= 0) throw new IllegalArgumentException("Invalid action row height");
    return Math.max(1, availableHeight - 2 * rowHeight);
  }

  static Plot plot(float availableWidth, float remainingHeight, float bodyHeight) {
    finite(availableWidth);
    finite(remainingHeight);
    finite(bodyHeight);
    if (bodyHeight <= 0) throw new IllegalArgumentException("Invalid body height");
    return new Plot(
        Math.max(1, availableWidth),
        Math.max(1, Math.min(Math.max(170, remainingHeight - 240), bodyHeight * .65f)));
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite layout size");
  }
}
