package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import imgui.flag.ImGuiCol;
import io.voxelcreator.model.ProjectIO;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred theme/binding ownership definitions; no native context or renderer is needed. */
class InteractivePreferenceScopeTest {
  @Test
  void rawThemeSnapshotsCopyArraysAndIgnoreUnrelatedAppearanceValues() {
    var user = new UserConfig();
    user.appearance.colors.put(ImGuiCol.Header, new float[] {.25f, .5f, .75f, 1});
    var original = ThemeActionValues.from(user, false);
    var scope = new PreferenceActionScope();
    long opening = scope.synchronize(original);
    user.appearance.globalScale = 1.5f;
    user.appearance.windowScales.put("Themes", 2f);
    user.input.shortcuts.put("Profiler", "Alt+F9");
    assertEquals(opening, scope.synchronize(ThemeActionValues.from(user, false)));
    user.appearance.colors.get(ImGuiCol.Header)[0] = .125f;
    assertFalse(scope.current(ThemeActionValues.from(user, false)));
    assertEquals(.25f, original.colors().get(ImGuiCol.Header).getFirst().floatValue());
    assertThrows(
        UnsupportedOperationException.class,
        () -> original.colors().get(ImGuiCol.Header).set(0, .5f));
    assertTrue(scope.synchronize(ThemeActionValues.from(user, false)) > opening);
  }

  @Test
  void malformedExternalColorsStillInvalidatePresentationWithoutBeingAccepted() {
    var user = new UserConfig();
    var session = new ThemeEditSession(user);
    var scope = new PreferenceActionScope();
    long opening = scope.synchronize(ThemeActionValues.from(user, false));
    user.appearance.colors.put(ImGuiCol.Header, new float[] {Float.NaN, 1});
    assertFalse(session.current(user));
    assertFalse(scope.current(ThemeActionValues.from(user, false)));
    long malformed = scope.synchronize(ThemeActionValues.from(user, false));
    assertTrue(malformed > opening);
    assertTrue(scope.synchronize(ThemeActionValues.from(user, true)) > malformed);
    user.appearance.colors.put(ImGuiCol.Header, null);
    assertFalse(scope.current(ThemeActionValues.from(user, true)));
    assertNotNull(ThemeActionValues.from(user, true));
  }

  @Test
  void themeAndBindingsMergeOnlyTheirOwnPendingValues(@TempDir Path directory) throws Exception {
    var user = new UserConfig();
    user.appearance.windowScales.put("extension-window", .75f);
    var theme = new ThemeEditSession(user);
    var palette =
        ThemePalette.starting("Light").status("Warning", new ThemePalette.Rgba(.25f, .5f, .75f, 1));
    theme.edit(palette);
    var scope = new PreferenceActionScope();
    long opening = scope.synchronize(ThemeActionValues.from(user, false));
    var bindings = new ShortcutEditSession(user.input.shortcuts);
    bindings.edit("Profiler", "Alt+F9");
    var file = directory.resolve("user-config.json");
    bindings.apply(user, file, bindings.values());
    assertEquals(opening, scope.synchronize(ThemeActionValues.from(user, false)));
    assertEquals(palette, theme.draft());
    theme.apply(user, file, theme.draft());
    var saved = UserConfig.read(file);
    assertEquals(palette, ThemePalette.from(saved.appearance));
    assertEquals("Alt+F9", saved.input.shortcuts.get("Profiler"));
    assertEquals(.75f, saved.appearance.windowScales.get("extension-window").floatValue());
  }

  @Test
  void failedThemeSaveRetainsPresentationAndPrivatePalette(@TempDir Path directory)
      throws Exception {
    var user = new UserConfig();
    var session = new ThemeEditSession(user);
    session.edit(ThemePalette.starting("Light"));
    var scope = new PreferenceActionScope();
    long opening = scope.synchronize(ThemeActionValues.from(user, false));
    var before = ProjectIO.JSON.valueToTree(user);
    var file = directory.resolve("user-config.json");
    Files.createDirectory(file);
    Files.writeString(file.resolve("keep"), "retained");
    assertThrows(RuntimeException.class, () -> session.apply(user, file, session.draft()));
    assertEquals(before, ProjectIO.JSON.valueToTree(user));
    assertEquals(ThemePalette.starting("Light"), session.draft());
    assertEquals(opening, scope.synchronize(ThemeActionValues.from(user, false)));
    assertEquals("retained", Files.readString(file.resolve("keep")));
    session.revert(user);
    scope.resetDraft();
    assertFalse(scope.current(ThemeActionValues.from(user, false)));
    assertTrue(scope.synchronize(ThemeActionValues.from(user, false)) > opening);
  }

  @Test
  void explicitCaptureCancellationKeepsItsFrameSuppressedAndDoesNotEditBindings() {
    var user = new UserConfig();
    var session = new ShortcutEditSession(user.input.shortcuts);
    session.edit("Profiler", "Alt+F9");
    var recording = new ShortcutRecording(user);
    recording.beginFrame();
    assertTrue(recording.start(session, "Find"));
    recording.cancel();
    assertTrue(recording.suppressed());
    assertNull(recording.action());
    assertEquals("Ctrl+F", session.values().get("Find"));
    assertEquals("Alt+F9", session.values().get("Profiler"));
    assertEquals("Ctrl+Shift+P", user.input.shortcuts.get("Profiler"));
    recording.beginFrame();
    assertFalse(recording.suppressed());
  }
}
