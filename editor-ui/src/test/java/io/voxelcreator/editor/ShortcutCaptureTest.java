package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ShortcutCaptureTest {
  private static final Optional<Shortcuts.Chord> PRESS = Shortcuts.parse("Ctrl+Shift+F8");

  @Test
  void captureWaitsForReleaseAndConsumesOnlyOneFreshChord() {
    var c = new ShortcutCapture();
    c.start("Find");
    assertTrue(c.poll(true, false, PRESS).isEmpty());
    assertTrue(c.active());
    assertTrue(c.poll(false, false, Optional.empty()).isEmpty());
    assertEquals("Ctrl+Shift+F8", c.poll(true, false, PRESS).orElseThrow());
    assertFalse(c.active());
    assertTrue(c.poll(true, false, PRESS).isEmpty());
  }

  @Test
  void escapeCancelsBothWaitingAndArmedStatesWithoutProducingBinding() {
    for (boolean release : new boolean[] {false, true}) {
      var c = new ShortcutCapture();
      c.start("Save");
      if (release) c.poll(false, false, Optional.empty());
      assertTrue(c.poll(false, true, PRESS).isEmpty());
      assertFalse(c.active());
    }
  }

  @Test
  void restartingOrLeavingCaptureDiscardsHeldKeyState() {
    var c = new ShortcutCapture();
    c.start("Find");
    c.poll(false, false, Optional.empty());
    c.start("Save");
    assertEquals("Save", c.action());
    assertTrue(c.poll(true, false, PRESS).isEmpty());
    c.cancel();
    assertFalse(c.active());
    assertThrows(IllegalArgumentException.class, () -> c.start("Unknown"));
  }
}
