package io.voxelcreator.editor;

import java.util.Objects;

/**
 * Refuses startup/native resize recursion before panels exist or while a frame is already active.
 */
final class EditorFrameGate {
  private boolean ready, drawing, stopped;

  void ready() {
    if (stopped) throw new IllegalStateException("Editor frames are stopped");
    ready = true;
  }

  void stop() {
    stopped = true;
    ready = false;
  }

  void draw(Runnable frame) {
    Objects.requireNonNull(frame);
    if (!ready || drawing) return;
    drawing = true;
    try {
      frame.run();
    } finally {
      drawing = false;
    }
  }
}
