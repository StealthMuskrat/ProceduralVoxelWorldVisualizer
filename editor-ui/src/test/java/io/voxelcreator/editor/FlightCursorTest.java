package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred cursor lease contracts, including detached windows and destroyed/reused handles. */
class FlightCursorTest {
  private static final int NORMAL = 1, HIDDEN = 2, CAPTURED = 3;
  private static final FlightCursor.Window MAIN = new FlightCursor.Window(10, 100);
  private static final FlightCursor.Window DETACHED = new FlightCursor.Window(20, 200);

  private static final class Backend implements FlightCursor.Backend {
    final Map<FlightCursor.Window, Integer> modes = new HashMap<>();
    final List<String> writes = new ArrayList<>();
    FlightCursor.Window focused;
    boolean suspended, failCapture;
    int reads;

    Backend() {
      modes.put(MAIN, NORMAL);
      modes.put(DETACHED, HIDDEN);
      focused = MAIN;
    }

    public boolean live(FlightCursor.Window window) {
      return modes.containsKey(window);
    }

    public boolean focused(FlightCursor.Window window) {
      return window.equals(focused);
    }

    public int mode(FlightCursor.Window window) {
      assertTrue(live(window), "Read from a destroyed platform window");
      reads++;
      return modes.get(window);
    }

    public void mode(FlightCursor.Window window, int value) {
      assertTrue(live(window), "Write to a destroyed platform window");
      modes.put(window, value);
      writes.add(window.viewport() + ":" + value);
      if (value == CAPTURED && failCapture) {
        failCapture = false;
        throw new IllegalStateException("Injected capture failure");
      }
    }

    public boolean cursorUpdatesSuspended() {
      return suspended;
    }

    public void cursorUpdatesSuspended(boolean value) {
      suspended = value;
    }
  }

  @Test
  void detachedCaptureUsesItsFocusedWindowAndPreservesTheMainCursor() {
    var backend = new Backend();
    backend.focused = DETACHED;
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      assertTrue(cursor.sync(DETACHED));
      assertEquals(DETACHED, cursor.window());
      assertEquals(NORMAL, backend.modes.get(MAIN).intValue());
      assertEquals(CAPTURED, backend.modes.get(DETACHED).intValue());
      assertTrue(backend.suspended);
      int writes = backend.writes.size();
      assertTrue(cursor.sync(DETACHED));
      assertEquals(writes, backend.writes.size());
    }
    assertEquals(HIDDEN, backend.modes.get(DETACHED).intValue());
    assertFalse(backend.suspended);
  }

  @Test
  void transferRestoresTheOldWindowBeforeCapturingTheNewOne() {
    var backend = new Backend();
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      cursor.sync(MAIN);
      backend.focused = DETACHED;
      cursor.sync(DETACHED);
      assertEquals(List.of("10:3", "10:1", "20:3"), backend.writes);
      assertEquals(NORMAL, backend.modes.get(MAIN).intValue());
      assertFalse(cursor.sync(null));
      assertEquals(HIDDEN, backend.modes.get(DETACHED).intValue());
      assertFalse(backend.suspended);
    }
  }

  @Test
  void focusLossReleasesAndReturningFocusDoesNotCaptureWithoutAnExplicitRequest() {
    var backend = new Backend();
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      cursor.sync(MAIN);
      backend.focused = DETACHED;
      assertFalse(cursor.sync(MAIN));
      assertNull(cursor.window());
      assertEquals(NORMAL, backend.modes.get(MAIN).intValue());
      backend.focused = MAIN;
      assertFalse(cursor.sync(null));
      assertNull(cursor.window());
      assertTrue(cursor.sync(MAIN));
    }
  }

  @Test
  void destroyedWindowsAreNeverReadAndReusedHandlesRequireTheirNewViewportIdentity() {
    var backend = new Backend();
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      cursor.sync(MAIN);
      backend.modes.remove(MAIN);
      var replacement = new FlightCursor.Window(30, MAIN.handle());
      backend.modes.put(replacement, NORMAL);
      backend.focused = replacement;
      int reads = backend.reads;
      assertFalse(cursor.sync(MAIN));
      assertEquals(reads, backend.reads);
      assertEquals(NORMAL, backend.modes.get(replacement).intValue());
      assertFalse(backend.suspended);
      assertTrue(cursor.sync(replacement));
      assertEquals(replacement, cursor.window());
    }
  }

  @Test
  void preexistingBackendCursorPolicySurvivesCaptureAndRelease() {
    var backend = new Backend();
    backend.suspended = true;
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      cursor.sync(MAIN);
      cursor.sync(null);
      assertTrue(backend.suspended);
    }
    assertTrue(backend.suspended);
  }

  @Test
  void anExternalCursorModeIsNotOverwrittenDuringRelease() {
    var backend = new Backend();
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      cursor.sync(MAIN);
      backend.modes.put(MAIN, HIDDEN);
      cursor.sync(null);
      assertEquals(HIDDEN, backend.modes.get(MAIN).intValue());
      assertFalse(backend.suspended);
    }
  }

  @Test
  void failedCaptureRestoresThePriorModeAndOwnedBackendFlagBeforeRetry() {
    var backend = new Backend();
    try (var cursor = new FlightCursor(backend, CAPTURED)) {
      backend.failCapture = true;
      assertThrows(IllegalStateException.class, () -> cursor.sync(MAIN));
      assertNull(cursor.window());
      assertEquals(NORMAL, backend.modes.get(MAIN).intValue());
      assertFalse(backend.suspended);
      assertTrue(cursor.sync(MAIN));
    }
    assertEquals(NORMAL, backend.modes.get(MAIN).intValue());
  }

  @Test
  void closeIsIdempotentEvenAfterTheCapturedWindowIsDestroyed() {
    var backend = new Backend();
    var cursor = new FlightCursor(backend, CAPTURED);
    cursor.sync(MAIN);
    backend.modes.remove(MAIN);
    int reads = backend.reads;
    cursor.close();
    cursor.close();
    assertEquals(reads, backend.reads);
    assertFalse(backend.suspended);
    assertThrows(IllegalStateException.class, () -> cursor.sync(DETACHED));
  }
}
