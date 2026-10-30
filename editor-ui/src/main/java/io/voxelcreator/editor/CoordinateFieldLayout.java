package io.voxelcreator.editor;

/** Pure three-axis grid budget, independent of raw coordinate parsing or native state. */
final class CoordinateFieldLayout {
  record Plan(int columns, float inputWidth, boolean stackedLabels) {}

  private CoordinateFieldLayout() {}

  static Plan prepare(float availableWidth, float minimumInputWidth, float labelWidth, float gap) {
    if (!Float.isFinite(availableWidth)
        || !Float.isFinite(minimumInputWidth)
        || !Float.isFinite(labelWidth)
        || !Float.isFinite(gap)
        || minimumInputWidth <= 0
        || labelWidth < 0
        || gap < 0) throw new IllegalArgumentException("Invalid coordinate grid metrics");
    double available = Math.max(1, availableWidth);
    int columns = 3;
    while (columns > 1
        && columns * ((double) minimumInputWidth + labelWidth) + (columns - 1.0) * gap > available)
      columns--;
    boolean stacked = available < (double) minimumInputWidth + labelWidth;
    double width = (available - (columns - 1.0) * gap) / columns - (stacked ? 0 : labelWidth);
    return new Plan(columns, (float) Math.max(1, Math.min(Float.MAX_VALUE, width)), stacked);
  }
}
