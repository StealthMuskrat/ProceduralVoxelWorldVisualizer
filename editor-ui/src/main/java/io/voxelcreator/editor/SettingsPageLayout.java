package io.voxelcreator.editor;

/** Measured settings-page budgets; raw edit buffers and persistence stay with their owners. */
final class SettingsPageLayout {
  record Pair(boolean inline, float sliderWidth, float inputWidth) {}

  record Panes(
      boolean columns,
      float resultsWidth,
      float resultsHeight,
      float detailWidth,
      float detailHeight) {}

  private SettingsPageLayout() {}

  static float body(float height, float frameHeight, float gap) {
    return body(height, frameHeight, gap, 1);
  }

  static float body(float height, float frameHeight, float gap, int rows) {
    finite(height);
    positive(frameHeight);
    nonnegative(gap);
    if (rows < 1 || rows > 4) throw new IllegalArgumentException("Invalid action row count");
    return (float) Math.max(1, (double) height - rows * ((double) frameHeight + gap));
  }

  static float actionWidth(float width, int count, float gap) {
    finite(width);
    nonnegative(gap);
    if (count < 1 || count > 16) throw new IllegalArgumentException("Invalid action count");
    return (float) Math.max(1, ((double) width - (count - 1.0) * gap) / count);
  }

  static float choices(float height, float lineHeight) {
    finite(height);
    positive(lineHeight);
    return (float)
        Math.min(
            Float.MAX_VALUE,
            Math.max(2.0 * lineHeight, Math.min(6.0 * lineHeight, .28 * Math.max(0, height))));
  }

  static float multiline(float bodyHeight, float lineHeight) {
    positive(bodyHeight);
    positive(lineHeight);
    return (float) Math.max(1, Math.min(.4 * bodyHeight, 5.0 * lineHeight));
  }

  static Pair pair(float width, float minimumFieldWidth, float gap) {
    finite(width);
    positive(minimumFieldWidth);
    nonnegative(gap);
    double available = Math.max(1, width);
    if (available < 2.0 * minimumFieldWidth + gap)
      return new Pair(false, (float) available, (float) available);
    double usable = available - gap;
    double slider = Math.max(minimumFieldWidth, Math.min(usable * .52, usable - minimumFieldWidth));
    return new Pair(true, (float) slider, (float) (usable - slider));
  }

  static Panes panes(
      float width,
      float height,
      float minimumPaneWidth,
      float lineHeight,
      float horizontalGap,
      float verticalGap) {
    finite(width);
    finite(height);
    positive(minimumPaneWidth);
    positive(lineHeight);
    nonnegative(horizontalGap);
    nonnegative(verticalGap);
    // If the heading exhausts the visible body, keep editable pane viewports scrollable inside it.
    double w = Math.max(1, width),
        h = Math.min(Float.MAX_VALUE, Math.max(4.0 * lineHeight, height));
    if (w >= 2.0 * minimumPaneWidth + horizontalGap) {
      double usable = w - horizontalGap;
      double left = Math.max(minimumPaneWidth, Math.min(.36 * usable, usable - minimumPaneWidth));
      return new Panes(true, (float) left, (float) h, (float) (usable - left), (float) h);
    }
    double list = Math.max(1, Math.min(4.0 * lineHeight, .32 * Math.max(1, h - verticalGap)));
    return new Panes(
        false, (float) w, (float) list, (float) w, (float) Math.max(1, h - list - verticalGap));
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite settings metric");
  }

  private static void positive(float value) {
    finite(value);
    if (value <= 0) throw new IllegalArgumentException("Nonpositive settings metric");
  }

  private static void nonnegative(float value) {
    finite(value);
    if (value < 0) throw new IllegalArgumentException("Negative settings metric");
  }
}
