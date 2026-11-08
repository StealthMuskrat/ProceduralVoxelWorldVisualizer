package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class MembershipShortcutsTest {
  @Test
  void localCommandsFollowTheExistingBindingsAndExactModifiers() {
    var bindings = new HashMap<>(Shortcuts.defaults());
    assertEquals("Undo", PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.CTRL, "Z"::equals));
    assertEquals("Redo", PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.CTRL, "Y"::equals));
    bindings.put("Undo", "Alt+U");
    bindings.put("Redo", "Super+Shift+R");
    assertEquals("Undo", PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.ALT, "U"::equals));
    assertEquals(
        "Redo",
        PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.SUPER | Shortcuts.SHIFT, "R"::equals));
    assertNull(PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.CTRL, "Z"::equals));
    assertNull(
        PrivateHistoryShortcuts.match(
            bindings, true, false, Shortcuts.ALT | Shortcuts.SHIFT, "U"::equals));
  }

  @Test
  void unfocusedOrEditingInputsRetainTheirKeysAndInvalidOrUnboundActionsDoNotMatch() {
    var bindings = new HashMap<>(Shortcuts.defaults());
    assertNull(
        PrivateHistoryShortcuts.match(
            bindings, false, false, Shortcuts.CTRL, k -> {
              throw new AssertionError();
            }));
    assertNull(
        PrivateHistoryShortcuts.match(
            bindings, true, true, Shortcuts.CTRL, k -> {
              throw new AssertionError();
            }));
    bindings.put("Undo", "not-a-key");
    bindings.put("Redo", "");
    assertNull(PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.CTRL, k -> true));
    bindings.put("Redo", "Ctrl+Y");
    assertEquals("Redo", PrivateHistoryShortcuts.match(bindings, true, false, Shortcuts.CTRL, "Y"::equals));
  }
}
