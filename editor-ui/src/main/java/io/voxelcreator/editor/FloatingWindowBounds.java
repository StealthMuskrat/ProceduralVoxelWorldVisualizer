package io.voxelcreator.editor;

import java.util.*;

/**
 * Value-only fitting for floating tools; docking, collapse and pointer interaction retain
 * ownership.
 */
final class FloatingWindowBounds {
  private final DialogBounds geometry;
  private int viewport;
  private boolean docked, collapsed;

  FloatingWindowBounds(float width, float height) {
    geometry = new DialogBounds(width, height);
  }

  void observe(DialogBounds.Rect bounds, int viewport, boolean docked, boolean collapsed) {
    if (viewport == 0) throw new IllegalArgumentException("Missing viewport");
    Objects.requireNonNull(bounds);
    this.viewport = viewport;
    this.docked = docked;
    this.collapsed = collapsed;
    if (!docked && !collapsed) geometry.observe(bounds);
  }

  DialogBounds.Plan prepare(boolean pointerDown, DialogBounds.Rect work) {
    if (pointerDown || docked || collapsed || geometry.observed() == null || work == null)
      return null;
    return geometry.prepare(viewport, work);
  }

  DialogBounds.Rect bounds() {
    return geometry.observed();
  }

  int viewport() {
    return viewport;
  }

  /** Monitor coordinates are usable only with absolute multi-viewport coordinates enabled. */
  static DialogBounds.Rect work(
      boolean absolute,
      boolean mainHost,
      DialogBounds.Rect main,
      DialogBounds.Rect window,
      List<DialogBounds.Rect> monitors) {
    Objects.requireNonNull(monitors);
    if (!absolute || window == null) return main;
    if (monitors.isEmpty()) return mainHost ? main : null;
    DialogBounds.Rect chosen = null;
    double bestOverlap = -1, bestDistance = Double.POSITIVE_INFINITY;
    for (var area : monitors) {
      double overlap =
          overlap(window.x(), window.width(), area.x(), area.width())
              * overlap(window.y(), window.height(), area.y(), area.height());
      double distance =
          gap(window.x(), window.width(), area.x(), area.width())
              + gap(window.y(), window.height(), area.y(), area.height());
      if (overlap > bestOverlap || overlap == bestOverlap && distance < bestDistance) {
        chosen = area;
        bestOverlap = overlap;
        bestDistance = distance;
      }
    }
    return chosen;
  }

  private static double overlap(double start, double size, double other, double otherSize) {
    return Math.max(0, Math.min(start + size, other + otherSize) - Math.max(start, other));
  }

  private static double gap(double start, double size, double other, double otherSize) {
    return Math.max(0, Math.max(other - start - size, start - other - otherSize));
  }
}
