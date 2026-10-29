package io.voxelcreator.editor;

/** Pure screen-coordinate fitting for one modal lifetime; never retains a native object. */
final class DialogBounds {
  record Rect(float x, float y, float width, float height) {
    Rect {
      if (!Float.isFinite(x)
          || !Float.isFinite(y)
          || !Float.isFinite(width)
          || !Float.isFinite(height)
          || width <= 0
          || height <= 0) throw new IllegalArgumentException("Invalid dialog rectangle");
    }
  }

  record Plan(
      Rect bounds, float minimumWidth, float minimumHeight, boolean position, boolean size) {}

  private final float preferredWidth, preferredHeight;
  private Rect last, area;
  private int viewport;

  DialogBounds(float width, float height) {
    if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0)
      throw new IllegalArgumentException("Invalid preferred dialog size");
    preferredWidth = width;
    preferredHeight = height;
  }

  void reset() {
    last = area = null;
    viewport = 0;
  }

  Rect observed() {
    return last;
  }

  Plan prepare(int targetViewport, Rect work) {
    if (targetViewport == 0) throw new IllegalArgumentException("Missing target viewport");
    Rect usable = inset(work);
    float width =
        Math.max(
            Math.min(160, usable.width()),
            Math.min(last == null ? preferredWidth : last.width(), usable.width()));
    float height =
        Math.max(
            Math.min(120, usable.height()),
            Math.min(last == null ? preferredHeight : last.height(), usable.height()));
    float x =
        last == null
            ? coordinate((double) usable.x() + (usable.width() - width) / 2.0)
            : coordinate(clamp(last.x(), usable.x(), (double) usable.x() + usable.width() - width));
    float y =
        last == null
            ? coordinate((double) usable.y() + (usable.height() - height) / 2.0)
            : coordinate(
                clamp(last.y(), usable.y(), (double) usable.y() + usable.height() - height));
    var fitted = new Rect(x, y, width, height);
    boolean changed = !work.equals(area) || viewport != targetViewport;
    var plan =
        new Plan(
            fitted,
            Math.min(160, usable.width()),
            Math.min(120, usable.height()),
            last == null
                || changed
                || Float.compare(last.x(), x) != 0
                || Float.compare(last.y(), y) != 0,
            last == null
                || Float.compare(last.width(), width) != 0
                || Float.compare(last.height(), height) != 0);
    area = work;
    viewport = targetViewport;
    return plan;
  }

  void observe(Rect bounds) {
    last = bounds;
  }

  static Rect inset(Rect work) {
    double marginX = Math.min(8, Math.max(0, (work.width() - 1.0) / 2));
    double marginY = Math.min(8, Math.max(0, (work.height() - 1.0) / 2));
    return new Rect(
        coordinate(work.x() + marginX),
        coordinate(work.y() + marginY),
        (float) (work.width() - 2 * marginX),
        (float) (work.height() - 2 * marginY));
  }

  static boolean contains(Rect area, Rect bounds) {
    return bounds.x() >= area.x()
        && bounds.y() >= area.y()
        && (double) bounds.x() + bounds.width() <= (double) area.x() + area.width() + .01
        && (double) bounds.y() + bounds.height() <= (double) area.y() + area.height() + .01;
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static float coordinate(double value) {
    return (float) clamp(value, -Float.MAX_VALUE, Float.MAX_VALUE);
  }
}
