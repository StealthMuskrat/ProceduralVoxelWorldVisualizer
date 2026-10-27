package io.voxelcreator.editor;

/** UI-thread flight intent, separate from camera pose and native cursor ownership. */
final class ViewportFlight {
  enum Mode {
    ORBIT,
    CAPTURED,
    INSPECTOR,
    PAUSED
  }

  private Mode mode = Mode.ORBIT;

  Mode mode() {
    return mode;
  }

  boolean captured() {
    return mode == Mode.CAPTURED;
  }

  void enter() {
    mode = Mode.CAPTURED;
  }

  void inspect() {
    if (captured()) mode = Mode.INSPECTOR;
  }

  void pause() {
    if (mode != Mode.ORBIT) mode = Mode.PAUSED;
  }

  void reconcile(boolean eligible, boolean inspectorOpen) {
    if (captured() && !eligible) mode = Mode.PAUSED;
    else if (mode == Mode.INSPECTOR && !inspectorOpen)
      mode = eligible ? Mode.CAPTURED : Mode.PAUSED;
  }

  void exit() {
    mode = Mode.ORBIT;
  }
}
