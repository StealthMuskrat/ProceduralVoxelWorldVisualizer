package io.voxelcreator.editor;

/** Sticky short-right-click classification for non-orbit image surfaces. */
final class ImageContextGesture {
  private boolean active, dragged;
  private double startX, startY, started;

  boolean active() {
    return active;
  }

  void reset() {
    active = false;
  }

  void begin(double x, double y, double time) {
    if (active) return;
    active = true;
    dragged = false;
    startX = x;
    startY = y;
    started = time;
  }

  void move(double x, double y) {
    if (!active) return;
    double dx = x - startX, dy = y - startY;
    if (dx * dx + dy * dy > 9) dragged = true;
  }

  boolean release(double x, double y, double time, boolean hovered) {
    if (!active) return false;
    move(x, y);
    double duration = time - started;
    boolean click = !dragged && hovered && duration >= 0 && duration <= .35;
    reset();
    return click;
  }
}
