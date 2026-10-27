package io.voxelcreator.editor;

import io.voxelcreator.render.Camera;

/** One surface-owned orbit gesture, evaluated from its original pose and pointer position. */
final class OrbitGesture {
  static final double DRAG_PIXELS = 3, CLICK_SECONDS = .35;

  enum Click {
    NONE,
    PICK,
    CONTEXT
  }

  private record Pose(double x, double y, double z, double yaw, double pitch, double distance) {
    static Pose of(Camera c) {
      return new Pose(c.focus.x, c.focus.y, c.focus.z, c.yaw, c.pitch, c.distance);
    }

    void apply(Camera c) {
      c.focus.set(x, y, z);
      c.yaw = yaw;
      c.pitch = pitch;
      c.distance = distance;
    }
  }

  private int button = -1;
  private Pose baseline, last;
  private double startX, startY, started;
  private boolean dragged, vertical;

  int button() {
    return button;
  }

  boolean active() {
    return button >= 0;
  }

  void reset() {
    button = -1;
    baseline = last = null;
    dragged = false;
  }

  void begin(Camera camera, int pressed, boolean ctrl, double x, double y, double time) {
    if (active() || camera.flying || pressed < 0 || pressed > 2 || pressed == 0 && ctrl) return;
    button = pressed;
    baseline = last = Pose.of(camera);
    startX = x;
    startY = y;
    started = time;
    vertical = ctrl;
    dragged = false;
  }

  /** External navigation wins; a held pointer must never overwrite a newer camera pose. */
  boolean current(Camera camera) {
    if (active() && (camera.flying || !last.equals(Pose.of(camera)))) reset();
    return active();
  }

  void move(Camera camera, double x, double y) {
    if (!current(camera)) return;
    double dx = x - startX, dy = y - startY;
    if (dx * dx + dy * dy > DRAG_PIXELS * DRAG_PIXELS) dragged = true;
    if (!dragged) return;
    baseline.apply(camera);
    if (button == 0) camera.orbit(dx, dy);
    else if (button == 1) camera.pan(dx, dy);
    else if (vertical) camera.focus.y -= dy * camera.distance * .002;
    else {
      camera.focus.x -= dx * camera.distance * .002;
      camera.focus.z -= dy * camera.distance * .002;
    }
    last = Pose.of(camera);
  }

  Click release(Camera camera, double x, double y, double time, boolean hovered) {
    if (!current(camera)) return Click.NONE;
    move(camera, x, y);
    double duration = time - started;
    Click click =
        !dragged && hovered && duration >= 0 && duration <= CLICK_SECONDS
            ? button == 0 ? Click.PICK : button == 1 ? Click.CONTEXT : Click.NONE
            : Click.NONE;
    reset();
    return click;
  }

  void escape(Camera camera) {
    if (current(camera)) baseline.apply(camera);
    reset();
  }
}
