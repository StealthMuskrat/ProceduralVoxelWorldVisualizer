package io.voxelcreator.editor;

/** Finite view-only coordinates; never writes curve metadata or authoring history. */
public final class CurveView {
  public record Bounds(double minX, double maxX, double minY, double maxY) {
    public Bounds {
      if (!Double.isFinite(minX)
          || !Double.isFinite(maxX)
          || !Double.isFinite(minY)
          || !Double.isFinite(maxY)
          || !Double.isFinite(maxX - minX)
          || !Double.isFinite(maxY - minY)
          || !(minX < maxX)
          || !(minY < maxY))
        throw new IllegalArgumentException("View requires finite positive spans");
    }

    public double width() {
      return maxX - minX;
    }

    public double height() {
      return maxY - minY;
    }

    public double x(double fraction) {
      return minX + fraction * width();
    }

    public double y(double fraction) {
      return minY + fraction * height();
    }
  }

  private Bounds base, current, panBase;
  private double zoom = 1;

  public void synchronize(double minX, double maxX, double minY, double maxY) {
    var next = new Bounds(minX, maxX, minY, maxY);
    if (!next.equals(base)) {
      base = next;
      fit();
    }
  }

  public Bounds bounds() {
    if (current == null) throw new IllegalStateException("Curve view is not initialized");
    return current;
  }

  public double zoom() {
    return zoom;
  }

  public void fit() {
    current = base;
    zoom = 1;
    panBase = null;
  }

  public boolean wheel(double fractionX, double fractionY, double wheel) {
    if (panBase != null || !Double.isFinite(wheel) || !fraction(fractionX) || !fraction(fractionY))
      return false;
    double nextZoom =
        Math.max(.1, Math.min(100, zoom * Math.exp(Math.max(-50, Math.min(50, wheel * .12)))));
    if (nextZoom == zoom) return false;
    var before = bounds();
    double width = before.width() * (zoom / nextZoom), height = before.height() * (zoom / nextZoom);
    double loX = before.x(fractionX) - fractionX * width,
        loY = before.y(fractionY) - fractionY * height;
    try {
      var next = new Bounds(loX, loX + width, loY, loY + height);
      current = next;
      zoom = nextZoom;
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public void beginPan() {
    if (panBase == null) panBase = bounds();
  }

  public void endPan() {
    panBase = null;
  }

  public void cancelPan() {
    if (panBase != null) current = panBase;
    panBase = null;
  }

  public boolean pan(double fractionX, double fractionY) {
    if (panBase == null || !Double.isFinite(fractionX) || !Double.isFinite(fractionY)) return false;
    var before = bounds();
    double dx = fractionX * before.width(), dy = fractionY * before.height();
    try {
      current =
          new Bounds(
              before.minX() + dx, before.maxX() + dx, before.minY() + dy, before.maxY() + dy);
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public boolean centerAt(double x, double y) {
    var before = bounds();
    try {
      var next =
          new Bounds(
              x - before.width() / 2,
              x + before.width() / 2,
              y - before.height() / 2,
              y + before.height() / 2);
      current = next;
      panBase = null;
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  private static boolean fraction(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }
}
