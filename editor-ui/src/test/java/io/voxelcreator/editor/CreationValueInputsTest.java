package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred raw-profile inputs, hidden-error publication, precision and recovery definitions. */
class CreationValueInputsTest {
  private static final String SEED = "noise2D.seedOffset";
  private static final String VALUE = "noise2D.algorithm.CONSTANT.value";
  private static final String VECTOR = "processing.DOT.input.a";
  private static final String TEXT = "processing.EXPRESSION.parameter.expression";
  @TempDir Path directory;

  private MetadataEditSession session() {
    return new MetadataEditSession(AppMetadata.defaults());
  }

  private CreationValueInputs enabled(MetadataEditSession session, String id) {
    var inputs = session.inputs();
    inputs.override(id, true);
    inputs.text(id);
    return inputs;
  }

  @Test
  void incompleteSeedSurvivesOtherControlsAndCannotPublishAnEarlierParsedValue() throws Exception {
    var active = AppMetadata.defaults();
    Path file = directory.resolve("app.json");
    active.save(file);
    var bytes = Files.readAllBytes(file);
    var session = new MetadataEditSession(active);
    var input = enabled(session, SEED);
    input.edit(SEED, 0, "17");
    for (String raw : List.of("-", "+", "9223372036854775808", " 3", "3 ", "١٢", "1e3", "0x10")) {
      input.edit(SEED, 0, raw);
      input.text(VALUE);
      assertEquals(List.of(raw), input.text(SEED));
      assertEquals(17, session.draft().creationDefaults.get(SEED).longValue());
      assertTrue(input.errors().containsKey(SEED));
      assertTrue(session.changed());
      assertThrows(IllegalStateException.class, () -> session.apply(active, file));
      assertArrayEquals(bytes, Files.readAllBytes(file));
    }
  }

  @Test
  void exactSignedLongExtremesAndAuthoredSpellingSurvivePrivateParsing() {
    var session = session();
    var input = enabled(session, SEED);
    for (long value : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
      input.edit(SEED, 0, Long.toString(value));
      assertEquals(value, session.draft().creationDefaults.get(SEED).longValue());
      assertTrue(input.errors().isEmpty());
    }
    input.edit(SEED, 0, "+00017");
    assertEquals(List.of("+00017"), input.text(SEED));
    assertEquals(17, session.draft().creationDefaults.get(SEED).longValue());
  }

  @Test
  void tinyAdjacentAndSignedZeroDoublesKeepTheirExactParsedBitsAndRoundTrip() throws Exception {
    for (double value : new double[] {1e-9, Double.MIN_VALUE, Math.nextUp(1.), -0.0}) {
      var active = AppMetadata.defaults();
      var session = new MetadataEditSession(active);
      var input = enabled(session, VALUE);
      String raw = Double.toString(value);
      input.edit(VALUE, 0, raw);
      assertTrue(input.errors().isEmpty());
      assertEquals(
          Double.doubleToRawLongBits(value),
          Double.doubleToRawLongBits(session.draft().creationDefaults.get(VALUE).doubleValue()));
      Path file = directory.resolve("double.json");
      var published = session.apply(active, file);
      assertEquals(
          Double.doubleToRawLongBits(value),
          Double.doubleToRawLongBits(
              AppMetadata.read(file).creationDefaults.get(VALUE).doubleValue()));
      assertEquals(
          Double.doubleToRawLongBits(value),
          Double.doubleToRawLongBits(published.creationDefaults.get(VALUE).doubleValue()));
    }
  }

  @Test
  void invalidDecimalAndIntegralInputsRetainTextWithoutReplacingAcceptedValues() {
    var session = session();
    var input = enabled(session, VALUE);
    input.edit(VALUE, 0, "0.125");
    for (String raw : List.of("-", ".", "1e", "1e-", "NaN", "Infinity", "1e309", "0x1p0", " 2")) {
      input.edit(VALUE, 0, raw);
      assertFalse(input.error(VALUE).isEmpty());
      assertEquals(List.of(raw), input.text(VALUE));
      assertEquals(.125, session.draft().creationDefaults.get(VALUE).doubleValue());
    }
    var retained = input.text(VALUE);
    var oversized = "1".repeat(CreationValueInputs.MAX_NUMBER_CHARACTERS + 1);
    assertThrows(IllegalArgumentException.class, () -> input.edit(VALUE, 0, oversized));
    assertEquals(retained, input.text(VALUE));
    assertThrows(
        IllegalStateException.class,
        () -> input.requireDisplayed(Map.of(VALUE, List.of(oversized))));
    String integer = "voxel.color.r";
    input.override(integer, true);
    input.edit(integer, 0, "17");
    for (String raw : List.of("1.5", "1e2", "256", "-1")) {
      input.edit(integer, 0, raw);
      assertFalse(input.error(integer).isEmpty());
      assertEquals(17, session.draft().creationDefaults.get(integer).intValue());
    }
  }

  @Test
  void vectorsPublishAllComponentsTogetherAndNeverUseAnEarlierInvalidComponent() {
    var session = session();
    var input = enabled(session, VECTOR);
    var before = session.draft().creationDefaults.get(VECTOR).deepCopy();
    input.edit(VECTOR, 1, "-");
    input.edit(VECTOR, 0, "3.5");
    input.edit(VECTOR, 2, "9");
    assertEquals(before, session.draft().creationDefaults.get(VECTOR));
    assertEquals(List.of("3.5", "-", "9"), input.text(VECTOR));
    assertThrows(IllegalStateException.class, input::requireValid);
    input.edit(VECTOR, 1, "2.25");
    assertEquals(
        ProjectIO.JSON.valueToTree(new double[] {3.5, 2.25, 9}),
        session.draft().creationDefaults.get(VECTOR));
    assertTrue(input.errors().isEmpty());
    var accepted = session.draft().creationDefaults.get(VECTOR).deepCopy();
    var oversized = "1".repeat(CreationValueInputs.MAX_NUMBER_CHARACTERS + 1);
    assertThrows(
        IllegalArgumentException.class, () -> input.editAll(VECTOR, List.of("4", oversized, "9")));
    assertThrows(
        IllegalArgumentException.class, () -> input.editAll(VECTOR, List.of("5", oversized, "9")));
    assertEquals(accepted, session.draft().creationDefaults.get(VECTOR));
  }

  @Test
  void changedPrivateValuesAreNotSilentlyRetargetedAndReloadIsExplicit() {
    var session = session();
    var input = enabled(session, VALUE);
    input.edit(VALUE, 0, "-");
    session.draft().creationDefaults.put(VALUE, ProjectIO.JSON.valueToTree(.75));
    assertTrue(input.error(VALUE).contains("changed elsewhere"));
    assertEquals(List.of("-"), input.text(VALUE));
    assertThrows(IllegalStateException.class, () -> input.edit(VALUE, 0, "9"));
    assertThrows(IllegalStateException.class, input::requireValid);
    input.reload(VALUE);
    assertEquals(List.of("0.75"), input.text(VALUE));
    input.edit(VALUE, 0, "0.5");
    assertEquals(.5, session.draft().creationDefaults.get(VALUE).doubleValue());
  }

  @Test
  void textAndHardBoundsAreUtf16AndRejectedEditsNeverReplaceTheRetainedBuffer() {
    var session = session();
    var input = enabled(session, TEXT);
    var edge = "😀".repeat(4096);
    input.edit(TEXT, 0, edge);
    assertTrue(input.errors().isEmpty());
    input.edit(TEXT, 0, edge + "x");
    assertFalse(input.error(TEXT).isEmpty());
    assertEquals(edge, session.draft().creationDefaults.get(TEXT).asText());
    String provisional = "x".repeat(CreationValueInputs.MAX_TEXT_CHARACTERS);
    input.edit(TEXT, 0, provisional);
    assertThrows(IllegalArgumentException.class, () -> input.edit(TEXT, 0, provisional + "x"));
    assertEquals(List.of(provisional), input.text(TEXT));
    input.edit(TEXT, 0, "source+x");
    assertTrue(input.errors().isEmpty());
    assertDoesNotThrow(session.draft()::validate);
  }

  @Test
  void displayedFieldsIncludeHiddenValuesAndReturnedCollectionsCannotMutateOwnership() {
    var session = session();
    var input = enabled(session, SEED);
    input.edit(SEED, 0, "17");
    input.override(VALUE, true);
    input.edit(VALUE, 0, "0.25");
    var shown = new LinkedHashMap<String, List<String>>();
    shown.put(SEED, List.of("17"));
    assertThrows(IllegalStateException.class, () -> input.requireDisplayed(shown));
    shown.put(VALUE, List.of("0.250"));
    assertThrows(IllegalStateException.class, () -> input.requireDisplayed(shown));
    shown.put(VALUE, List.of("0.25"));
    assertDoesNotThrow(() -> input.requireDisplayed(shown));
    assertThrows(UnsupportedOperationException.class, () -> input.text(SEED).clear());
    assertThrows(UnsupportedOperationException.class, () -> input.ids().clear());
    assertThrows(UnsupportedOperationException.class, () -> input.errors().clear());
    assertThrows(IllegalArgumentException.class, () -> input.text("unknown"));
    assertThrows(IllegalArgumentException.class, () -> input.edit(SEED, 1, "1"));
  }

  @Test
  void explicitOverrideRemovalReloadAndProfileResetHaveScopedDiscardSemantics() {
    var session = session();
    var input = enabled(session, SEED);
    input.edit(SEED, 0, "-");
    input.override(SEED, true);
    assertEquals(List.of("-"), input.text(SEED));
    input.override(VALUE, true);
    input.edit(VALUE, 0, "1e");
    input.override(SEED, false);
    assertFalse(session.draft().creationDefaults.containsKey(SEED));
    assertEquals(List.of("0"), input.text(SEED));
    assertTrue(input.errors().containsKey(VALUE));
    input.reload(VALUE);
    assertTrue(input.errors().isEmpty());
    input.edit(VALUE, 0, ".");
    session.stageBuiltIns();
    assertTrue(session.draft().creationDefaults.isEmpty());
    assertTrue(session.inputs().errors().isEmpty());
    assertFalse(session.changed());
    assertEquals(
        List.of("."), input.text(VALUE)); // Old ledger is detached, not silently rewritten.
  }

  @Test
  void failedWriteRetainsExactRawTextAndSuccessResetsOnlyAfterPersistence() throws Exception {
    var active = AppMetadata.defaults();
    var session = new MetadataEditSession(active);
    var input = enabled(session, SEED);
    input.edit(SEED, 0, "+00017");
    Path occupied = directory.resolve("occupied");
    Files.createDirectories(occupied);
    Files.writeString(occupied.resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> session.apply(active, occupied));
    assertSame(input, session.inputs());
    assertEquals(List.of("+00017"), input.text(SEED));
    assertEquals("original", Files.readString(occupied.resolve("keep")));
    var published = session.apply(active, directory.resolve("valid.json"));
    assertEquals(17, published.creationDefaults.get(SEED).longValue());
    assertNotSame(input, session.inputs());
    assertFalse(session.changed());
    assertEquals(List.of("17"), session.inputs().text(SEED));
  }

  @Test
  void staleActiveMetadataPreservesRawChoicesUntilExplicitRevert() {
    var active = AppMetadata.defaults();
    var session = new MetadataEditSession(active);
    enabled(session, SEED).edit(SEED, 0, "+17");
    active.controls.get("noise").label = "Changed elsewhere";
    assertThrows(
        IllegalStateException.class, () -> session.apply(active, directory.resolve("new.json")));
    assertEquals(List.of("+17"), session.inputs().text(SEED));
    assertFalse(Files.exists(directory.resolve("new.json")));
    session.revert(active);
    assertFalse(session.changed());
    assertEquals("Changed elsewhere", session.draft().controls.get("noise").label);
    assertEquals(List.of("0"), session.inputs().text(SEED));
  }

  @Test
  void applyingFutureDefaultsPreservesProjectPrivateSeedsAndExistingEvaluation() {
    try (var state = new EditorState(new UserConfig(), false)) {
      state.metadata = AppMetadata.defaults();
      assertTrue(
          state.change(
              p -> {
                var noise = new Noise();
                noise.id = "existing";
                noise.algorithm.key = "CONSTANT";
                noise.algorithm.parameters.clear();
                noise.algorithm.parameters.put("value", .125);
                p.noise2D.add(noise);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (Noise) state.draft;
      draft.id = "pending-id";
      state.seedInputs.edit(state, "seedOffset", "Seed", "+");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, history = state.document.historyBytes();
      var session = new MetadataEditSession(state.metadata);
      enabled(session, SEED).edit(SEED, 0, Long.toString(Long.MIN_VALUE));
      session.inputs().replace("noise2D.algorithm", ProjectIO.JSON.valueToTree("CONSTANT"));
      enabled(session, VALUE).edit(VALUE, 0, "3.5");
      state.metadata = session.apply(state.metadata, directory.resolve("app.json"));
      var created = EditorCreations.noise(state.metadata, 2, "future", null);
      assertEquals(Long.MIN_VALUE, created.seedOffset);
      assertEquals(3.5, created.algorithm.parameters.get("value"));
      assertEquals(
          .125, new NoiseCompiler(state.project(), 0).compile(state.selected).sample(7, 0, -9));
      assertSame(draft, state.draft);
      assertEquals("pending-id", draft.id);
      assertEquals("+", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
    }
  }
}
