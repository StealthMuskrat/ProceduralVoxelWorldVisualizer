package io.voxelcreator.editor;

import java.util.Objects;

/** One native cursor lease; dead platform windows are never read or restored. */
final class FlightCursor implements AutoCloseable {
  record Window(int viewport, long handle) {
    Window {
      if (handle <= 0) throw new IllegalArgumentException("A live platform handle is required");
    }
  }

  interface Backend {
    boolean live(Window window);

    boolean focused(Window window);

    int mode(Window window);

    void mode(Window window, int value);

    boolean cursorUpdatesSuspended();

    void cursorUpdatesSuspended(boolean value);
  }

  private final Backend backend;
  private final int capturedMode;
  private Window window;
  private int previousMode;
  private boolean ownsSuspension, closed;

  FlightCursor(Backend backend, int capturedMode) {
    this.backend = Objects.requireNonNull(backend);
    this.capturedMode = capturedMode;
  }

  Window window() {
    return window;
  }

  boolean sync(Window requested) {
    if (closed) throw new IllegalStateException("Flight cursor is closed");
    if (requested != null && (!backend.live(requested) || !backend.focused(requested)))
      requested = null;
    if (!Objects.equals(window, requested)) {
      release();
      if (requested != null) {
        previousMode = backend.mode(requested);
        window = requested;
      }
    }
    if (window == null) return false;
    try {
      // imgui-java's GLFW backend otherwise resets detached-window cursors each frame.
      if (!backend.cursorUpdatesSuspended()) {
        backend.cursorUpdatesSuspended(true);
        ownsSuspension = true;
      }
      if (backend.mode(window) != capturedMode) backend.mode(window, capturedMode);
      return true;
    } catch (RuntimeException failure) {
      release();
      throw failure;
    }
  }

  private void release() {
    var previous = window;
    window = null;
    try {
      if (previous != null && backend.live(previous) && backend.mode(previous) == capturedMode)
        backend.mode(previous, previousMode);
    } finally {
      if (ownsSuspension) {
        ownsSuspension = false;
        backend.cursorUpdatesSuspended(false);
      }
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    release();
  }
}
