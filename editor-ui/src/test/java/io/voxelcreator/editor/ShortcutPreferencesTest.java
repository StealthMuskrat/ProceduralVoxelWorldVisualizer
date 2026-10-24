package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShortcutPreferencesTest {
  @TempDir Path directory;

  @Test
  void draftEditsConflictsResetAndRevertNeverPublishBeforeApply() {
    var user = new UserConfig();
    var original = new LinkedHashMap<>(user.input.shortcuts);
    var edit = new ShortcutEditSession(original);
    edit.edit("Find", "Ctrl+S");
    assertFalse(edit.problems().isEmpty());
    assertEquals(original, user.input.shortcuts);
    assertTrue(edit.changed());
    edit.reset("Find");
    assertTrue(edit.problems().isEmpty());
    assertFalse(edit.changed());
    edit.unbind("Save");
    assertTrue(edit.changed());
    assertEquals("Ctrl+S", user.input.shortcuts.get("Save"));
    edit.revert(original);
    assertFalse(edit.changed());
    edit.resetAll();
    assertFalse(edit.changed());
    assertThrows(UnsupportedOperationException.class, () -> edit.values().clear());
  }

  @Test
  void validApplyWritesCanonicalSnapshotAndSurvivesRestart() throws Exception {
    var user = new UserConfig();
    user.appearance.theme = "Light";
    user.workspace.width = 1234;
    Path path = directory.resolve("user-config.json");
    var edit = new ShortcutEditSession(user.input.shortcuts);
    edit.edit("Save", " alt + ctrl + f5 ");
    edit.apply(user, path);
    assertEquals("Ctrl+Alt+F5", user.input.shortcuts.get("Save"));
    assertFalse(edit.changed());
    var loaded = UserConfig.read(path);
    assertEquals(user.input.shortcuts, loaded.input.shortcuts);
    assertEquals("Light", loaded.appearance.theme);
    assertEquals(1234, loaded.workspace.width);
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void invalidApplyPreservesLiveIdentityFileBytesAndDraft() throws Exception {
    var user = new UserConfig();
    Path path = directory.resolve("user-config.json");
    user.save(path);
    byte[] before = Files.readAllBytes(path);
    var active = user.input.shortcuts;
    var edit = new ShortcutEditSession(active);
    edit.edit("Find", "Ctrl+S");
    assertThrows(IllegalArgumentException.class, () -> edit.apply(user, path));
    assertSame(active, user.input.shortcuts);
    assertArrayEquals(before, Files.readAllBytes(path));
    assertTrue(edit.changed());
  }

  @Test
  void failedAtomicReplacementPreservesLiveBindingsAndDestinationContents() throws Exception {
    var user = new UserConfig();
    var active = user.input.shortcuts;
    var edit = new ShortcutEditSession(active);
    edit.edit("Find", "Alt+F8");
    Path occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> edit.apply(user, occupied));
    assertSame(active, user.input.shortcuts);
    assertEquals("original", Files.readString(occupied.resolve("keep")));
    assertTrue(edit.changed());
    try (var files = Files.list(directory)) {
      assertEquals(List.of(occupied), files.toList());
    }
  }

  @Test
  void staleDraftCannotOverwriteAnotherSuccessfulBindingChange() throws Exception {
    var user = new UserConfig();
    var first = new ShortcutEditSession(user.input.shortcuts);
    var stale = new ShortcutEditSession(user.input.shortcuts);
    Path path = directory.resolve("user.json");
    first.edit("Find", "Alt+F8");
    first.apply(user, path);
    byte[] before = Files.readAllBytes(path);
    stale.edit("Help", "F6");
    assertThrows(IllegalStateException.class, () -> stale.apply(user, path));
    assertArrayEquals(before, Files.readAllBytes(path));
    assertEquals("Alt+F8", user.input.shortcuts.get("Find"));
    stale.revert(user.input.shortcuts);
    stale.edit("Help", "F6");
    stale.apply(user, path);
    assertEquals("F6", UserConfig.read(path).input.shortcuts.get("Help"));
  }

  @Test
  void legacyReadUpgradesOnlyInputAndNeverRewritesItsFile() throws Exception {
    var user = new UserConfig();
    var json = ProjectIO.JSON.valueToTree(user);
    var shortcuts =
        (com.fasterxml.jackson.databind.node.ObjectNode) json.path("input").path("shortcuts");
    shortcuts.removeAll();
    shortcuts.put("Save", "Ctrl+N");
    shortcuts.put("Find", "Ctrl+F");
    ((com.fasterxml.jackson.databind.node.ObjectNode) json.path("appearance"))
        .put("globalScale", 1.75);
    Path path = directory.resolve("old.json");
    Files.writeString(path, json.toString());
    byte[] original = Files.readAllBytes(path);
    var upgraded = UserConfig.read(path);
    assertEquals(1.75, upgraded.appearance.globalScale);
    assertEquals("Ctrl+N", upgraded.input.shortcuts.get("Save"));
    assertEquals("", upgraded.input.shortcuts.get("New"));
    assertEquals("", upgraded.input.shortcuts.get("Undo"));
    assertEquals("F2", upgraded.input.shortcuts.get("Rename"));
    assertArrayEquals(original, Files.readAllBytes(path));
  }

  @Test
  void preferenceApplyAndSaveDoNotTouchProjectDraftHistoryOrDirtyState() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      String before = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision, history = state.document.historyBytes();
      Object draft = state.draft;
      var edit = new ShortcutEditSession(state.user.input.shortcuts);
      edit.edit("Profiler", "Alt+F9");
      Path path = directory.resolve("user.json");
      edit.apply(state.user, path);
      state.user.save(path);
      assertFalse(state.document.dirty());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertSame(draft, state.draft);
    }
  }

  @Test
  void invalidSerializedActionsValuesAndDuplicateJsonKeysRejectStrictly() throws Exception {
    var value = ProjectIO.JSON.valueToTree(new UserConfig());
    var map =
        (com.fasterxml.jackson.databind.node.ObjectNode) value.path("input").path("shortcuts");
    map.put("Unknown", "F3");
    Path path = directory.resolve("invalid.json");
    Files.writeString(path, value.toString());
    assertThrows(IllegalArgumentException.class, () -> UserConfig.read(path));
    map.remove("Unknown");
    map.put("Save", "Ctrl++S");
    Files.writeString(path, value.toString());
    assertThrows(IllegalArgumentException.class, () -> UserConfig.read(path));
    Files.writeString(path, "{\"formatVersion\":1,\"formatVersion\":1}");
    assertThrows(java.io.IOException.class, () -> UserConfig.read(path));
  }
}
