package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred known-window scaling, exact drafts, active ownership and save-failure recovery. */
class UiScaleEditSessionTest {
  @TempDir Path directory;

  @Test
  void catalogIsUniqueImmutableAndInheritsGlobalWithoutMaterializingWindowEntries() {
    assertEquals(19, UiScalePreferences.WINDOWS.size());
    assertEquals(19, new HashSet<>(UiScalePreferences.WINDOWS).size());
    assertThrows(UnsupportedOperationException.class, () -> UiScalePreferences.WINDOWS.clear());
    var user = new UserConfig();
    user.appearance.globalScale = 1.25f;
    for (var name : UiScalePreferences.WINDOWS)
      assertEquals(1f, UiScalePreferences.window(user, name));
    assertTrue(user.appearance.windowScales.isEmpty());
    assertThrows(
        IllegalArgumentException.class, () -> UiScalePreferences.window(user, "Missing window"));
    assertEquals(1f, UiScalePreferences.window(null, "Curve Editor"));
  }

  @Test
  void subsetValuesOwnTheirCopiesAndPreserveExplicitOneVersusInherit() {
    var source = new HashMap<>(Map.of("Help", 1f));
    var values = new UiScalePreferences.Values(.5f, source);
    source.clear();
    assertEquals(Map.of("Help", 1f), values.overrides());
    assertThrows(UnsupportedOperationException.class, () -> values.overrides().clear());
    assertNotEquals(values, new UiScalePreferences.Values(.5f, Map.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new UiScalePreferences.Values(1, Map.of("Future", 1f)));
    for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, .49f, 2.51f})
      assertThrows(
          IllegalArgumentException.class, () -> new UiScalePreferences.Values(invalid, Map.of()));
  }

  @Test
  void invalidIntermediateStringsNeverPublishThePreviousValidScale() {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.editGlobal("1.25");
    assertNotNull(session.feedback().value());
    for (var raw :
        List.of(
            "",
            "-",
            ".",
            "NaN",
            "Infinity",
            "0x1p0",
            "1f",
            " 1",
            "1 ",
            "1e999",
            "2.50000001",
            ".49999999")) {
      session.editGlobal(raw);
      assertEquals(raw, session.text().global());
      assertNull(session.feedback().value());
      assertThrows(
          IllegalArgumentException.class, () -> session.apply(user, file(), session.text()));
      assertEquals(1f, user.appearance.globalScale);
    }
    assertFalse(Files.exists(file()));
    assertEquals(.5f, UiScaleEditSession.parse(".5"));
    assertEquals(2.5f, UiScaleEditSession.parse("+2.5e0"));
  }

  @Test
  void feedbackAndRawValuesAreCachedOwnedAndBounded() {
    var session = new UiScaleEditSession(new UserConfig());
    var text = session.text();
    var feedback = session.feedback();
    long count = session.validations();
    session.editGlobal("1.0");
    assertSame(text, session.text());
    assertSame(feedback, session.feedback());
    assertEquals(count, session.validations());
    assertThrows(UnsupportedOperationException.class, () -> text.windows().clear());
    assertThrows(UnsupportedOperationException.class, () -> text.enabled().clear());
    assertThrows(UnsupportedOperationException.class, () -> feedback.problems().clear());
    assertThrows(IllegalArgumentException.class, () -> session.editGlobal("1".repeat(129)));
    assertSame(text, session.text());
    session.editGlobal("9".repeat(128));
    assertNull(session.feedback().value());
    assertEquals(count + 1, session.validations());
  }

  @Test
  void invalidHiddenEnabledWindowStillBlocksApplyAndInheritIsAnExplicitRemoval() throws Exception {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.override("Help", true);
    session.editWindow("Help", "-");
    session.override("Logs", true);
    session.editWindow("Logs", "2");
    assertTrue(session.feedback().problems().containsKey("Help"));
    assertThrows(IllegalArgumentException.class, () -> session.apply(user, file(), session.text()));
    session.override("Help", false);
    assertEquals("-", session.text().windows().get("Help"));
    assertNotNull(session.feedback().value());
    session.apply(user, file(), session.text());
    assertEquals(Map.of("Logs", 2f), user.appearance.windowScales);
    assertEquals("1.0", session.text().windows().get("Help"));
    assertFalse(session.changed());
  }

  @Test
  void allDisplayedFieldsAndEnabledFlagsMustMatchEvenWhenNotSelected() {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.editGlobal("1.25");
    var windows = new LinkedHashMap<>(session.text().windows());
    windows.put("Help", "1.000");
    assertThrows(
        IllegalStateException.class,
        () -> session.apply(user, file(), new UiScaleEditSession.Text("1.25", windows, Set.of())));
    assertThrows(
        IllegalStateException.class,
        () ->
            session.apply(
                user,
                file(),
                new UiScaleEditSession.Text("1.25", session.text().windows(), Set.of("Help"))));
    assertThrows(
        IllegalStateException.class,
        () ->
            session.apply(
                user,
                file(),
                new UiScaleEditSession.Text("1.250", session.text().windows(), Set.of())));
    windows.remove("Logs");
    assertThrows(
        IllegalArgumentException.class,
        () -> new UiScaleEditSession.Text("1.25", windows, Set.of()));
    assertFalse(Files.exists(file()));
  }

  @Test
  void failedSaveRetainsRawCandidateAndLatestUnrelatedPreferencesAreMergedOnRetry()
      throws Exception {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.editGlobal("+1.2500");
    session.override("Help", true);
    session.editWindow("Help", "1.500");
    var raw = session.text();
    var candidate = session.feedback();
    Files.createDirectory(file());
    Files.writeString(file().resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> session.apply(user, file(), raw));
    assertEquals(1f, user.appearance.globalScale);
    assertTrue(user.appearance.windowScales.isEmpty());
    assertSame(raw, session.text());
    assertSame(candidate, session.feedback());
    assertEquals("original", Files.readString(file().resolve("keep")));
    user.appearance.theme = "Light";
    user.workspace.width = 1234;
    user.input.shortcuts.put("Profiler", "Alt+F9");
    user.performance.cacheMiB = 17;
    user.appearance.windowScales.put("Future Window", 1.75f);
    Files.delete(file().resolve("keep"));
    Files.delete(file());
    session.apply(user, file(), raw);
    var saved = UserConfig.read(file());
    assertEquals(1.25f, saved.appearance.globalScale);
    assertEquals(Map.of("Help", 1.5f, "Future Window", 1.75f), saved.appearance.windowScales);
    assertEquals("Light", saved.appearance.theme);
    assertEquals(1234, saved.workspace.width);
    assertEquals("Alt+F9", saved.input.shortcuts.get("Profiler"));
    assertEquals(17, saved.performance.cacheMiB);
    assertEquals("1.25", session.text().global());
    assertEquals("1.5", session.text().windows().get("Help"));
    try (var paths = Files.walk(directory)) {
      assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".user-config-")));
    }
  }

  @Test
  void globalAndEveryKnownWindowValueOrPresenceOwnTheActiveBase() {
    for (var name : UiScalePreferences.WINDOWS) {
      var user = new UserConfig();
      var session = new UiScaleEditSession(user);
      session.editGlobal("1.25");
      var raw = session.text();
      user.appearance.windowScales.put(name, 1f);
      assertFalse(session.current(user), name);
      assertThrows(IllegalStateException.class, () -> session.apply(user, file(), raw));
      assertSame(raw, session.text());
      session.revert(user);
      assertTrue(session.text().enabled().contains(name));
    }
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.editWindow("Help", "1.5");
    user.appearance.globalScale = 1.1f;
    assertFalse(session.current(user));
  }

  @Test
  void pendingInputsAreExcludedFromOrdinarySaveAndUnchangedApplyDoesNotWrite() throws Exception {
    var user = new UserConfig();
    var session = new UiScaleEditSession(user);
    session.apply(user, file(), session.text());
    assertFalse(Files.exists(file()));
    session.editGlobal("2.5");
    session.override("Help", true);
    session.editWindow("Help", "2");
    user.save(file());
    var saved = UserConfig.read(file());
    assertEquals(1f, saved.appearance.globalScale);
    assertTrue(saved.appearance.windowScales.isEmpty());
    session.revert(user);
    assertEquals("1.0", session.text().global());
    assertTrue(session.text().enabled().isEmpty());
  }

  @Test
  void openingAndReapplyingFloatRatiosDoNotIntroducePercentConversionDrift() throws Exception {
    for (float scale : new float[] {.5f, 1.01f, 1.3333334f, 2.5f}) {
      var user = new UserConfig();
      user.appearance.globalScale = scale;
      user.appearance.windowScales.put("Help", scale);
      var session = new UiScaleEditSession(user);
      assertEquals(
          Float.floatToRawIntBits(scale),
          Float.floatToRawIntBits(UiScaleEditSession.parse(session.text().global())));
      session.editWindow("Logs", "1.5");
      session.override("Logs", true);
      session.apply(user, file(), session.text());
      assertEquals(
          Float.floatToRawIntBits(scale),
          Float.floatToRawIntBits(UserConfig.read(file()).appearance.globalScale));
      assertEquals(
          Float.floatToRawIntBits(scale),
          Float.floatToRawIntBits(user.appearance.windowScales.get("Help")));
    }
  }

  @Test
  void strictStoredScalesRejectNullStringNonfiniteAndOutOfRangeKnownOrUnknownValues()
      throws Exception {
    for (var literal : List.of("null", "\"1.25\"", ".49", "2.51", "1e999")) {
      String normalized = literal.equals(".49") ? "0.49" : literal;
      Files.writeString(
          file(), "{\"formatVersion\":1,\"appearance\":{\"globalScale\":" + normalized + "}}");
      assertThrows(Exception.class, () -> UserConfig.read(file()));
      Files.writeString(
          file(),
          "{\"formatVersion\":1,\"appearance\":{\"windowScales\":{\"Future Window\":"
              + normalized
              + "}}}");
      assertThrows(Exception.class, () -> UserConfig.read(file()));
    }
    Files.writeString(
        file(), "{\"formatVersion\":1,\"appearance\":{\"windowScales\":{\"Future Window\":1.5}}}");
    assertEquals(
        1.5f, UserConfig.read(file()).appearance.windowScales.get("Future Window").floatValue());
  }

  @Test
  void reentrantApplyEditAndRevertAreRefusedWhileTheOuterSnapshotIsWritten() throws Exception {
    var holder = new UiScaleEditSession[1];
    var owner = new UserConfig[1];
    var writer =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temporary, byte[] bytes) throws IOException {
                assertThrows(
                    IllegalStateException.class,
                    () -> holder[0].apply(owner[0], file(), holder[0].text()));
                assertThrows(IllegalStateException.class, () -> holder[0].editGlobal("2"));
                assertThrows(IllegalStateException.class, () -> holder[0].revert(owner[0]));
                Files.write(temporary, bytes);
              }

              public void replace(Path temporary, Path target) throws IOException {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
              }
            });
    owner[0] = new UserConfig(writer);
    holder[0] = new UiScaleEditSession(owner[0]);
    holder[0].editGlobal("1.25");
    holder[0].apply(owner[0], file(), holder[0].text());
    assertEquals(1.25f, owner[0].appearance.globalScale);
    assertFalse(holder[0].changed());
  }

  @Test
  void userOnlyScalePublicationPreservesTheProjectHistoryAndCurrentEngine() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var engine = state.engine;
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var session = new UiScaleEditSession(state.user);
      session.override("Voxel Inspector", true);
      session.editWindow("Voxel Inspector", "1.5");
      session.apply(state.user, file(), session.text());
      assertSame(engine, state.engine);
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
      assertTrue(state.engine.profiler().current().isEmpty());
    }
  }

  private Path file() {
    return directory.resolve("user.json");
  }
}
