package io.voxelcreator.editor;

/** Constant-height row budgets keep shortcut clipping correct through inline/stacked reflow. */
final class ShortcutRowLayout {
  record Plan(
      boolean inline,
      int actionColumns,
      int actionRows,
      float inputWidth,
      float buttonWidth,
      float rowHeight) {}

  private ShortcutRowLayout() {}

  static Plan prepare(
      float width,
      float minimumInput,
      float minimumButton,
      float lineHeight,
      float frameHeight,
      float gap) {
    finite(width);
    positive(minimumInput);
    positive(minimumButton);
    positive(lineHeight);
    positive(frameHeight);
    finite(gap);
    if (gap < 0) throw new IllegalArgumentException("Negative shortcut spacing");
    double w = Math.max(1, width);
    double line = Math.ceil(lineHeight), frame = Math.ceil(frameHeight);
    if (w >= minimumInput + 3.0 * minimumButton + 3.0 * gap)
      return new Plan(
          true,
          3,
          1,
          (float) (w - 3.0 * minimumButton - 3.0 * gap),
          minimumButton,
          height(2.0 * line + frame));
    int columns = 3;
    while (columns > 1 && columns * (double) minimumButton + (columns - 1.0) * gap > w) columns--;
    int rows = (3 + columns - 1) / columns;
    return new Plan(
        false,
        columns,
        rows,
        (float) w,
        (float) Math.max(1, (w - (columns - 1.0) * gap) / columns),
        height(2.0 * line + (1.0 + rows) * frame));
  }

  static float viewport(float remaining, float bodyHeight, float frameHeight) {
    finite(remaining);
    positive(bodyHeight);
    positive(frameHeight);
    return (float)
        Math.max(
            1,
            Math.min(
                .65 * bodyHeight, Math.max(frameHeight, (double) remaining - 3.0 * frameHeight)));
  }

  private static float height(double value) {
    return (float) Math.min(Float.MAX_VALUE, value);
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite shortcut metric");
  }

  private static void positive(float value) {
    finite(value);
    if (value <= 0) throw new IllegalArgumentException("Nonpositive shortcut metric");
  }
}
