package io.voxelcreator.editor;

/** Measured content/whole-pixel clipping budgets shared by resource transaction dialogs. */
final class ResourceReviewLayout {
  private ResourceReviewLayout() {}

  static float content(float remaining) {
    finite(remaining);
    return Math.max(1, remaining);
  }

  static float importStride(float line, float frame, float gap) {
    positive(line);
    positive(frame);
    nonnegative(gap);
    return pixel(3.0 * line + frame + 4.0 * gap);
  }

  static float membershipStride(float frame, float gap) {
    positive(frame);
    nonnegative(gap);
    return pixel((double) frame + gap);
  }

  private static float pixel(double value) {
    return (float) Math.min(Float.MAX_VALUE, Math.ceil(value));
  }

  private static void finite(float value) {
    if (!Float.isFinite(value))
      throw new IllegalArgumentException("Invalid resource review metric");
  }

  private static void positive(float value) {
    finite(value);
    if (value <= 0) throw new IllegalArgumentException("Invalid resource review metric");
  }

  private static void nonnegative(float value) {
    finite(value);
    if (value < 0) throw new IllegalArgumentException("Invalid resource review metric");
  }
}
