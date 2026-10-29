package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred raw/display agreement, cache ownership and persistence retry definitions. */
class ShortcutDraftOwnershipTest {
  @TempDir Path directory;

  @Test
  void unchangedValuesAndFeedbackReuseOwnedSnapshotsUntilAnActualEdit() {
    var session = new ShortcutEditSession(Shortcuts.defaults());
    var values = session.values();
    var problems = session.problems();
    long version = session.version(), validations = session.validations();
    session.edit("Find", session.values().get("Find"));
    assertEquals(version, session.version());
    assertSame(values, session.values());
    assertSame(problems, session.problems());
    assertEquals(validations, session.validations());
    session.edit("Find", "Ctrl+");
    assertNotSame(values, session.values());
    assertFalse(session.problems().isEmpty());
    assertEquals(validations + 1, session.validations());
    assertEquals("Ctrl+F", values.get("Find"));
    assertThrows(UnsupportedOperationException.class, () -> session.values().clear());
    assertThrows(UnsupportedOperationException.class, () -> session.problems().clear());
  }

  @Test
  void completeExactDisplayedMapIsRequiredEvenWhenChordValuesAreEquivalent() throws Exception {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    session.edit("Find", " alt + f8 ");
    var display = new LinkedHashMap<>(session.values());
    display.put("Find", "Alt+F8");
    Path file = directory.resolve("bindings.json");
    assertThrows(IllegalStateException.class, () -> session.apply(user, file, display));
    display.put("Find", " alt + f8 ");
    display.remove("Help");
    assertThrows(IllegalStateException.class, () -> session.apply(user, file, display));
    display.put("Help", "F1");
    display.put("Unknown", "F6");
    assertThrows(IllegalStateException.class, () -> session.apply(user, file, display));
    assertFalse(Files.exists(file));
    display.remove("Unknown");
    session.apply(user, file, display);
    assertEquals("Alt+F8", user.input.shortcuts.get("Find"));
    assertFalse(session.changed());
  }

  @Test
  void provisionalAndHardBoundsNeverFallBackToTheEarlierChord() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    String edge = "x".repeat(ShortcutEditSession.MAX_BUFFER_CHARACTERS);
    session.edit("Find", edge);
    assertEquals(edge, session.values().get("Find"));
    assertFalse(session.problems().isEmpty());
    assertThrows(IllegalArgumentException.class, () -> session.edit("Find", edge + "x"));
    assertEquals(edge, session.values().get("Find"));
    var displayed = new LinkedHashMap<>(session.values());
    displayed.put("Find", edge + "x");
    assertThrows(
        IllegalStateException.class,
        () -> session.apply(user, directory.resolve("no.json"), displayed));
    session.edit("Find", "Ctrl+");
    assertThrows(
        IllegalArgumentException.class,
        () -> session.apply(user, directory.resolve("invalid.json"), session.values()));
    assertEquals("Ctrl+F", user.input.shortcuts.get("Find"));
    assertEquals("Ctrl+", session.values().get("Find"));
  }

  @Test
  void resetAndRevertInvalidateRecordingVersionsEvenForEqualValues() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    long initial = session.version();
    session.resetAll();
    assertTrue(session.version() > initial);
    long reset = session.version();
    session.revert(user.input.shortcuts);
    assertTrue(session.version() > reset);
    assertFalse(session.changed());
  }

  @Test
  void failedWriteRetainsRawDraftAndLatestOtherPreferencesMergeOnRetry() throws Exception {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    session.edit("Find", " alt + f8 ");
    var raw = session.values();
    long version = session.version();
    Path occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> session.apply(user, occupied, raw));
    assertSame(raw, session.values());
    assertEquals(version, session.version());
    assertEquals("original", Files.readString(occupied.resolve("keep")));
    user.workspace.width = 1234;
    user.appearance.theme = "Light";
    Path file = directory.resolve("valid.json");
    session.apply(user, file, raw);
    var saved = UserConfig.read(file);
    assertEquals("Alt+F8", saved.input.shortcuts.get("Find"));
    assertEquals(1234, saved.workspace.width);
    assertEquals("Light", saved.appearance.theme);
    assertFalse(session.changed());
  }

  @Test
  void staleActiveMapRequiresRevertButUnrelatedPreferencesDoNotInvalidateOwnership() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    session.edit("Find", "Alt+F8");
    user.workspace.width = 1234;
    assertTrue(session.current(user));
    user.input.shortcuts.put("Help", "F6");
    assertFalse(session.current(user));
    assertThrows(
        IllegalStateException.class, () -> session.apply(user, directory.resolve("stale.json")));
    assertEquals("Alt+F8", session.values().get("Find"));
    assertFalse(Files.exists(directory.resolve("stale.json")));
    session.revert(user.input.shortcuts);
    assertTrue(session.current(user));
    assertEquals("F6", session.values().get("Help"));
  }
}
