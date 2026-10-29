package io.voxelcreator.editor;

import java.util.Objects;

/** Pure anchored selector geometry in the hosting viewport's coordinate space. */
final class PopupBounds {
  private PopupBounds() {}

  static DialogBounds.Rect fit(
      DialogBounds.Rect work, DialogBounds.Rect anchor, float width, float height) {
    Objects.requireNonNull(anchor);
    if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0)
      throw new IllegalArgumentException("Invalid selector size");
    var area = DialogBounds.inset(Objects.requireNonNull(work));
    width = Math.min(width, area.width());
    height = Math.min(height, area.height());
    double below = (double) anchor.y() + anchor.height() + 4;
    double above = (double) anchor.y() - height - 4;
    double bottom = (double) area.y() + area.height();
    // Prefer below, then above. If neither fits, retain the full fitted height and overlap the
    // trigger rather than making the searchable list unusably short.
    double y = below + height <= bottom ? below : above >= area.y() ? above : below;
    return new DialogBounds.Rect(
        clamp(anchor.x(), area.x(), (double) area.x() + area.width() - width),
        clamp(y, area.y(), bottom - height),
        width,
        height);
  }

  private static float clamp(double value, double min, double max) {
    return (float)
        Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, Math.max(min, Math.min(max, value))));
  }
}
