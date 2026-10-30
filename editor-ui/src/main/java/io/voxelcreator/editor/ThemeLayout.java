package io.voxelcreator.editor;

/** Palette/contrast and four-channel budgets, independent of theme values and preview ownership. */
final class ThemeLayout {
  record Panes(boolean columns, float paletteWidth, float contrastWidth, float height) {}

  record Channels(
      int columns, int rows, boolean stackedLabels, float fieldWidth, float rowHeight) {}

  private ThemeLayout() {}

  static Panes panes(
      float width,
      float remaining,
      float bodyHeight,
      float minimumPane,
      float frameHeight,
      float gap) {
    finite(width);
    finite(remaining);
    positive(bodyHeight);
    positive(minimumPane);
    positive(frameHeight);
    nonnegative(gap);
    double w = Math.max(1, width);
    float h =
        (float) Math.max(1, Math.min(.7 * bodyHeight, Math.max(8.0 * frameHeight, remaining)));
    if (w < 2.0 * minimumPane + gap) return new Panes(false, (float) w, (float) w, h);
    double usable = w - gap;
    double left = Math.max(minimumPane, Math.min(.52 * usable, usable - minimumPane));
    return new Panes(true, (float) left, (float) (usable - left), h);
  }

  static float viewport(float remaining, float paneHeight, float frameHeight) {
    finite(remaining);
    positive(paneHeight);
    positive(frameHeight);
    return (float) Math.max(1, Math.min(.65 * paneHeight, Math.max(frameHeight, remaining)));
  }

  static Channels channels(
      float width,
      float minimumField,
      float labelWidth,
      float gap,
      float lineHeight,
      float frameHeight) {
    finite(width);
    positive(minimumField);
    nonnegative(labelWidth);
    nonnegative(gap);
    positive(lineHeight);
    positive(frameHeight);
    double w = Math.max(1, width), label = labelWidth + (double) gap;
    int columns = 4;
    while (columns > 1 && columns * (minimumField + label) + (columns - 1.0) * gap > w)
      columns /= 2;
    boolean stacked = w < minimumField + label;
    double field = (w - (columns - 1.0) * gap) / columns - (stacked ? 0 : label);
    int rows = 4 / columns;
    double stride =
        (2.0 + rows) * Math.ceil(frameHeight) + (stacked ? rows * Math.ceil(lineHeight) : 0);
    return new Channels(
        columns,
        rows,
        stacked,
        (float) Math.max(1, field),
        (float) Math.min(Float.MAX_VALUE, stride));
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite theme metric");
  }

  private static void positive(float value) {
    finite(value);
    if (value <= 0) throw new IllegalArgumentException("Nonpositive theme metric");
  }

  private static void nonnegative(float value) {
    finite(value);
    if (value < 0) throw new IllegalArgumentException("Negative theme metric");
  }
}
