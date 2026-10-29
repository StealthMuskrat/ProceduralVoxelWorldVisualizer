package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred owned recording and dispatch-suppression definitions independent of native input. */
class ShortcutRecordingTest {
  private static final Optional<Shortcuts.Chord> PRESS = Shortcuts.parse("Ctrl+S");

  private void arm(ShortcutRecording recording, ShortcutEditSession session) {
    assertTrue(recording.poll(session, true, false, false, Optional.empty()).isEmpty());
  }

  @Test
  void acceptedChordUpdatesOnlyItsOwnerAndSuppressesTheCompletionFrame() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.beginFrame();
    assertTrue(recording.start(session, "Find"));
    arm(recording, session);
    var captured = recording.poll(session, true, true, false, PRESS).orElseThrow();
    assertEquals(new ShortcutRecording.Captured("Find", "Ctrl+S"), captured);
    assertEquals("Ctrl+S", session.values().get("Find"));
    assertEquals("Ctrl+F", user.input.shortcuts.get("Find"));
    assertFalse(recording.active());
    assertTrue(recording.suppressed());
    var bindings = Shortcuts.compile(user.input.shortcuts);
    assertTrue(
        bindings
            .match(
                new Shortcuts.Context(
                    Shortcuts.Focus.GENERAL, false, false, false, recording.suppressed()),
                Shortcuts.CTRL,
                "S"::equals)
            .isEmpty());
    recording.beginFrame();
    assertFalse(recording.suppressed());
    assertEquals(
        "Save",
        bindings
            .match(
                new Shortcuts.Context(Shortcuts.Focus.GENERAL, false, false, false, false),
                Shortcuts.CTRL,
                "S"::equals)
            .orElseThrow()
            .id());
  }

  @Test
  void launchKeysMustReleaseBeforeOneFreshPressCanBeRecorded() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.start(session, "Find");
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
    assertTrue(recording.active());
    arm(recording, session);
    assertTrue(recording.poll(session, true, true, false, PRESS).isPresent());
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
  }

  @Test
  void anotherRecordRequestCannotReplaceOrRearmThePendingTarget() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.start(session, "Find");
    arm(recording, session);
    assertFalse(recording.start(session, "Save"));
    assertFalse(recording.start(session, "Find"));
    assertEquals("Find", recording.action());
    assertEquals("Find", recording.poll(session, true, true, false, PRESS).orElseThrow().action());
    assertEquals("Ctrl+S", user.input.shortcuts.get("Save"));
  }

  @Test
  void unavailableInputOrEscapeCancelsWaitingAndArmedStatesWithoutEditing() {
    for (boolean armed : new boolean[] {false, true})
      for (boolean escape : new boolean[] {false, true}) {
        var user = new UserConfig();
        var session = new ShortcutEditSession(user.input.shortcuts);
        var before = session.values();
        var recording = new ShortcutRecording(user);
        recording.start(session, "Find");
        if (armed) arm(recording, session);
        assertTrue(recording.poll(session, escape, true, escape, PRESS).isEmpty());
        assertFalse(recording.active());
        assertTrue(recording.suppressed());
        assertSame(before, session.values());
        recording.beginFrame();
        assertFalse(recording.suppressed());
      }
  }

  @Test
  void anyDraftEditResetRevertOrReplacementOwnerInvalidatesTheOpeningRecording() {
    for (int mode = 0; mode < 4; mode++) {
      var user = new UserConfig();
      var session = new ShortcutEditSession(user.input.shortcuts);
      var recording = new ShortcutRecording(user);
      recording.start(session, "Find");
      arm(recording, session);
      var current = session;
      switch (mode) {
        case 0 -> {
          session.edit("Help", "F6");
          session.edit("Help", "F1");
        }
        case 1 -> session.resetAll();
        case 2 -> session.revert(user.input.shortcuts);
        case 3 -> current = new ShortcutEditSession(user.input.shortcuts);
      }
      var before = current.values();
      assertTrue(recording.poll(current, true, true, false, PRESS).isEmpty());
      assertFalse(recording.active());
      assertSame(before, current.values());
    }
  }

  @Test
  void activeBindingChangesCancelAndAStaleDraftCannotStartAnotherRecording() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.start(session, "Find");
    arm(recording, session);
    user.input.shortcuts.put("Help", "F6");
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
    assertEquals("Ctrl+F", session.values().get("Find"));
    recording.beginFrame();
    assertFalse(recording.start(session, "Find"));
    assertFalse(recording.suppressed());
    user.input.shortcuts.put("Help", "F1");
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
    assertFalse(recording.active());
    assertTrue(recording.start(session, "Find"));
    user.input = null;
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
    assertFalse(recording.active());
  }

  @Test
  void unrelatedPreferencesDoNotInvalidateCaptureAndHiddenFrameCancelExpires() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.start(session, "Find");
    arm(recording, session);
    user.appearance.theme = "Light";
    user.workspace.width = 1234;
    assertTrue(recording.poll(session, true, true, false, PRESS).isPresent());
    recording.beginFrame();
    recording.start(session, "Help");
    recording.beginFrame(); // The next host frame may hide/collapse Settings instead of drawing it.
    recording.cancel();
    assertTrue(recording.suppressed());
    recording.beginFrame();
    assertFalse(recording.suppressed());
    assertEquals("F1", session.values().get("Help"));
  }

  @Test
  void invalidRequestsAndRepeatedCancelCannotCorruptTheCurrentOwner() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    var recording = new ShortcutRecording(user);
    recording.start(session, "Find");
    assertThrows(IllegalArgumentException.class, () -> recording.start(session, "Unknown"));
    assertEquals("Find", recording.action());
    recording.cancel();
    recording.cancel();
    assertFalse(recording.active());
    recording.beginFrame();
    assertFalse(recording.suppressed());
    assertTrue(recording.poll(session, true, true, false, PRESS).isEmpty());
    assertFalse(session.changed());
  }
}
