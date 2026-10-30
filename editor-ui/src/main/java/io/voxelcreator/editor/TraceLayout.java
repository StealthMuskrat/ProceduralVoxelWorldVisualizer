package io.voxelcreator.editor;

/** Measured trace viewport/list budgets, independent of retained values and source ownership. */
final class TraceLayout {
  private TraceLayout() {}

  static float workspace(float remaining, float frameHeight) {
    if (!Float.isFinite(remaining)) throw new IllegalArgumentException("Invalid trace space");
    positive(frameHeight);
    return (float) Math.min(Float.MAX_VALUE, Math.max(5.0 * frameHeight, remaining));
  }

  /** A parent has already reserved its own actions; never enlarge beyond its visible body. */
  static float contained(float remaining) {
    if (!Float.isFinite(remaining)) throw new IllegalArgumentException("Invalid trace space");
    return Math.max(1, remaining);
  }

  static float steps(float bodyHeight, float lineHeight) {
    return list(bodyHeight, lineHeight, 3, .2);
  }

  static float values(float bodyHeight, float lineHeight) {
    return list(bodyHeight, lineHeight, 5, .3);
  }

  private static float list(float height, float lineHeight, int rows, double fraction) {
    positive(height);
    positive(lineHeight);
    return (float) Math.max(1, Math.min(fraction * height, rows * (double) lineHeight));
  }

  private static void positive(float value) {
    if (!Float.isFinite(value) || value <= 0)
      throw new IllegalArgumentException("Invalid trace metric");
  }
}
