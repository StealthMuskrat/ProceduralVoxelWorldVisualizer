package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred definitions: complete private budgets, exact input, persistence and ownership. */
class PerformanceEditSessionTest {
  @TempDir Path directory;

  @Test
  void loweringMaximumKeepsActiveConfigValidUntilTheCoupledExtentIsCorrected() throws Exception {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    session.edit(Field.MAX_DIMENSION, "16");
    assertNull(session.feedback().value());
    assertTrue(session.feedback().problems().containsKey(Field.DEFAULT_EXTENT));
    assertEquals(96, user.performance.maxPreviewDimension);
    assertEquals(32, user.visualization.previewSize);
    user.validate();
    assertThrows(IllegalArgumentException.class, () -> session.apply(user, file(), session.text()));
    assertFalse(Files.exists(file()));
    session.edit(Field.DEFAULT_EXTENT, "16");
    session.apply(user, file(), session.text());
    assertEquals(16, user.performance.maxPreviewDimension);
    assertEquals(16, user.visualization.previewSize);
    var restored = UserConfig.read(file());
    assertEquals(PerformancePreferences.from(user), PerformancePreferences.from(restored));
  }

  @Test
  void invalidOrIntermediateRawTextNeverFallsBackToTheLastValidCandidate() {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    session.edit(Field.CACHE_MIB, "17");
    assertNotNull(session.feedback().value());
    for (var raw : List.of("", "-", "+", "1e3", "1.5", " 17", "17 ", "0x10", "１２")) {
      session.edit(Field.CACHE_MIB, raw);
      assertEquals(raw, session.text().get(Field.CACHE_MIB));
      assertNull(session.feedback().value(), raw);
      assertThrows(
          IllegalArgumentException.class, () -> session.apply(user, file(), session.text()));
      assertEquals(128, user.performance.cacheMiB);
    }
    assertFalse(Files.exists(file()));
  }

  @Test
  void fullSigned64BitCellBudgetsRoundTripWithoutFloatingPointOrIntNarrowing() throws Exception {
    for (var raw : List.of("9007199254740993", "9223372036854775807")) {
      var user = new UserConfig();
      var session = new PerformanceEditSession(user);
      session.edit(Field.PREVIEW_CELLS, raw);
      session.apply(user, file(), session.text());
      assertEquals(Long.parseLong(raw), user.performance.previewCellBudget);
      assertEquals(Long.parseLong(raw), UserConfig.read(file()).performance.previewCellBudget);
      assertEquals(raw, session.text().get(Field.PREVIEW_CELLS));
    }
    var session = new PerformanceEditSession(new UserConfig());
    for (var raw : List.of("9223372036854775808", "-9223372036854775809", "0", "-1")) {
      session.edit(Field.PREVIEW_CELLS, raw);
      assertNull(session.feedback().value(), raw);
    }
  }

  @Test
  void everyFieldHasExactInclusiveBoundsAndCrossFieldValuesStayComplete() {
    var user = new UserConfig();
    for (var field : Field.values()) {
      var session = new PerformanceEditSession(user);
      session.edit(Field.DEFAULT_EXTENT, "2");
      for (long bound : new long[] {field.minimum(), field.maximum()}) {
        if (field == Field.DEFAULT_EXTENT) session.edit(Field.MAX_DIMENSION, "256");
        session.edit(field, Long.toString(bound));
        assertNotNull(session.feedback().value(), field + "=" + bound);
      }
      session.edit(field, Long.toString(field.minimum() - 1));
      assertNull(session.feedback().value(), field.name());
      if (field.maximum() != Long.MAX_VALUE) {
        session.edit(field, Long.toString(field.maximum() + 1));
        assertNull(session.feedback().value(), field.name());
      }
    }
  }

  @Test
  void textFeedbackAndCompleteCandidateAreOwnedImmutableAndCached() {
    var session = new PerformanceEditSession(new UserConfig());
    var original = session.text();
    assertThrows(UnsupportedOperationException.class, () -> original.clear());
    var feedback = session.feedback();
    assertThrows(UnsupportedOperationException.class, () -> feedback.problems().clear());
    assertThrows(UnsupportedOperationException.class, () -> feedback.value().fields().clear());
    long count = session.validations();
    session.edit(Field.CACHE_MIB, "128");
    assertSame(original, session.text());
    assertSame(feedback, session.feedback());
    assertEquals(count, session.validations());
    session.edit(Field.CACHE_MIB, "0017");
    assertEquals("128", original.get(Field.CACHE_MIB));
    assertNotSame(feedback, session.feedback());
    assertEquals(count + 1, session.validations());
    assertEquals(17, session.feedback().value().get(Field.CACHE_MIB));
  }

  @Test
  void boundedExactCompleteDisplayedFieldsCannotPublishHiddenOrTruncatedInput() {
    var session = new PerformanceEditSession(new UserConfig());
    session.edit(Field.CACHE_MIB, "17");
    var original = session.text();
    assertThrows(
        IllegalArgumentException.class, () -> session.edit(Field.CACHE_MIB, "1".repeat(129)));
    assertSame(original, session.text());
    session.edit(Field.PREVIEW_CELLS, "9".repeat(128));
    assertEquals(128, session.text().get(Field.PREVIEW_CELLS).length());
    assertNull(session.feedback().value());
    session.edit(Field.PREVIEW_CELLS, "32768");
    var displayed = new EnumMap<Field, String>(session.text());
    displayed.put(Field.CACHE_MIB, "017");
    var user = new UserConfig();
    assertThrows(IllegalStateException.class, () -> session.apply(user, file(), displayed));
    displayed.put(Field.CACHE_MIB, "17");
    displayed.remove(Field.WORKERS);
    assertThrows(IllegalStateException.class, () -> session.apply(user, file(), displayed));
    assertFalse(Files.exists(file()));
  }

  @Test
  void failedWriteKeepsRawCandidateAndActiveLimitsForRetryWithLatestOtherPreferences()
      throws Exception {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    var active = PerformancePreferences.from(user);
    session.edit(Field.MAX_DIMENSION, "+0016");
    session.edit(Field.DEFAULT_EXTENT, "0016");
    session.edit(Field.PREVIEW_CELLS, "9007199254740993");
    var raw = session.text();
    var candidate = session.feedback();
    Files.createDirectory(file());
    Files.writeString(file().resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> session.apply(user, file(), raw));
    assertEquals(active, PerformancePreferences.from(user));
    assertSame(raw, session.text());
    assertSame(candidate, session.feedback());
    assertEquals("original", Files.readString(file().resolve("keep")));
    user.workspace.width = 1234;
    user.appearance.theme = "Light";
    user.input.shortcuts.put("Profiler", "Alt+F9");
    user.performance.updateEndpoint = "https://updates.example.test/meta.json";
    user.performance.trustedOrigins.add("https://updates.example.test");
    user.performance.profiler = true;
    user.visualization.grid = false;
    user.editor.autoRegenerate = false;
    Files.delete(file().resolve("keep"));
    Files.delete(file());
    session.apply(user, file(), raw);
    var saved = UserConfig.read(file());
    assertEquals(candidate.value(), PerformancePreferences.from(saved));
    assertEquals(1234, saved.workspace.width);
    assertEquals("Light", saved.appearance.theme);
    assertEquals("Alt+F9", saved.input.shortcuts.get("Profiler"));
    assertEquals(user.performance.updateEndpoint, saved.performance.updateEndpoint);
    assertEquals(user.performance.trustedOrigins, saved.performance.trustedOrigins);
    assertTrue(saved.performance.profiler);
    assertFalse(saved.visualization.grid);
    assertFalse(saved.editor.autoRegenerate);
    assertEquals("16", session.text().get(Field.MAX_DIMENSION));
    assertFalse(session.changed());
    try (var paths = Files.walk(directory)) {
      assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".user-config-")));
    }
  }

  @Test
  void everyOwnedActiveValueInvalidatesTheDraftUntilExplicitRevert() {
    for (var field : Field.values()) {
      var user = new UserConfig();
      var session = new PerformanceEditSession(user);
      session.edit(Field.CACHE_MIB, "17");
      var raw = session.text();
      var changed = new EnumMap<Field, Long>(PerformancePreferences.from(user).fields());
      changed.put(field, field == Field.MAX_DIMENSION ? 97L : changed.get(field) + 1);
      new Values(changed).writeTo(user);
      assertFalse(session.current(user), field.name());
      assertThrows(IllegalStateException.class, () -> session.apply(user, file(), raw));
      assertSame(raw, session.text());
      session.revert(user);
      assertTrue(session.current(user));
      assertEquals(Long.toString(changed.get(field)), session.text().get(field));
    }
    assertFalse(Files.exists(file()));
  }

  @Test
  void privateInputsAreExcludedFromOrdinarySaveAndUnchangedApplyDoesNoIo() throws Exception {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    session.apply(user, file(), session.text());
    assertFalse(Files.exists(file()));
    session.edit(Field.CACHE_MIB, "17");
    user.save(file());
    assertEquals(128, UserConfig.read(file()).performance.cacheMiB);
    session.revert(user);
    assertEquals("128", session.text().get(Field.CACHE_MIB));
  }

  @Test
  void lowBudgetsRemainExplicitValidLimitsRatherThanSilentlyAllocatingOrClamping()
      throws Exception {
    var user = new UserConfig();
    var session = new PerformanceEditSession(user);
    session.edit(Field.PREVIEW_CELLS, "1");
    session.edit(Field.CACHE_MIB, "0");
    session.apply(user, file(), session.text());
    assertEquals(1, user.performance.previewCellBudget);
    assertEquals(0, user.performance.cacheMiB);
    var gate = new PreviewAdmission();
    assertFalse(
        gate.check(0, 256, "Grayscale", user.performance.previewCellBudget, 96, 128L << 20)
            .allowed());
    assertEquals(1, UserConfig.read(file()).performance.previewCellBudget);
  }

  @Test
  void strictReaderRejectsFractionStringNullOverflowAndOutOfRangeBeforeBinding() throws Exception {
    var user = new UserConfig();
    for (var field : Field.values()) {
      for (var literal : List.of("1.5", "\"16\"", "null", "9223372036854775808")) {
        var tree = ProjectIO.JSON.valueToTree(user);
        ((com.fasterxml.jackson.databind.node.ObjectNode)
                tree.path(field.section().equals("Performance") ? "performance" : "visualization"))
            .set(field.key(), ProjectIO.JSON.readTree(literal));
        Files.writeString(file(), ProjectIO.JSON.writeValueAsString(tree));
        assertThrows(
            IllegalArgumentException.class, () -> UserConfig.read(file()), field + "=" + literal);
      }
    }
    var tree = ProjectIO.JSON.valueToTree(user);
    ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("performance"))
        .put("sceneGpuMiB", 257);
    Files.writeString(file(), ProjectIO.JSON.writeValueAsString(tree));
    assertThrows(IllegalArgumentException.class, () -> UserConfig.read(file()));
  }

  @Test
  void historicalMissingLimitsInheritDefaultsButInvalidCouplingCannotBeRead() throws Exception {
    Files.writeString(file(), "{\"formatVersion\":1}");
    var restored = UserConfig.read(file());
    assertEquals(32, restored.visualization.previewSize);
    assertEquals(96, restored.performance.maxPreviewDimension);
    Files.writeString(file(), "{\"formatVersion\":1,\"performance\":{\"maxPreviewDimension\":16}}");
    assertThrows(IllegalArgumentException.class, () -> UserConfig.read(file()));
  }

  @Test
  void completeValueCopiesValidateBeforeAnyLiveFieldIsWritten() {
    var user = new UserConfig();
    var before = PerformancePreferences.from(user);
    var values = new EnumMap<Field, Long>(before.fields());
    var owned = new Values(values);
    values.put(Field.WORKERS, 64L);
    assertEquals(before, owned);
    values.put(Field.CACHE_MIB, -1L);
    assertThrows(
        IllegalArgumentException.class, () -> user.applyPerformance(new Values(values), file()));
    assertEquals(before, PerformancePreferences.from(user));
    values.remove(Field.ENGINE_MIB);
    assertThrows(IllegalArgumentException.class, () -> new Values(values));
    assertFalse(Files.exists(file()));
  }

  @Test
  void reentrantSaveEditRevertAndSecondApplyAreRefusedWithoutLosingOuterRequest() throws Exception {
    var holder = new PerformanceEditSession[1];
    var owner = new UserConfig[1];
    var files =
        new PreferenceFiles(
            new PreferenceFiles.Storage() {
              public void write(Path temporary, byte[] bytes) throws IOException {
                var session = holder[0];
                assertThrows(
                    IllegalStateException.class,
                    () -> session.apply(owner[0], file(), session.text()));
                assertThrows(
                    IllegalStateException.class, () -> session.edit(Field.CACHE_MIB, "99"));
                assertThrows(IllegalStateException.class, () -> session.revert(owner[0]));
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
    owner[0] = new UserConfig(files);
    holder[0] = new PerformanceEditSession(owner[0]);
    holder[0].edit(Field.CACHE_MIB, "17");
    holder[0].apply(owner[0], file(), holder[0].text());
    assertEquals(17, owner[0].performance.cacheMiB);
    assertEquals(17, UserConfig.read(file()).performance.cacheMiB);
    assertFalse(holder[0].changed());
  }

  @Test
  void settingsPublicationPreservesProjectPrivateInputHistoryAndEngineIdentity() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      var engine = state.engine;
      var project = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var session = new PerformanceEditSession(state.user);
      session.edit(Field.WORKERS, "64");
      session.edit(Field.ENGINE_MIB, "64");
      session.apply(state.user, file(), session.text());
      assertSame(engine, state.engine);
      assertTrue(state.engine.profiler().current().isEmpty());
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
    }
  }

  private Path file() {
    return directory.resolve("user.json");
  }
}
