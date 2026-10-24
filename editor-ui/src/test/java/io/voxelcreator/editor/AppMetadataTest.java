package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppMetadataTest {
  @TempDir Path directory;

  @Test
  void historicalTopicsAndMissingFieldsMergeWithoutLosingAuthoredTextOrRewritingFile()
      throws Exception {
    Path path = directory.resolve("legacy.json");
    String text =
        "{\"formatVersion\":1,\"controls\":{\"noise\":{\"label\":\"My noise\",\"description\":\"My"
            + " help\"}}}";
    Files.writeString(path, text);
    var metadata = AppMetadata.read(path);
    assertEquals("My noise", metadata.controls.get("noise").label);
    assertEquals("My help", metadata.controls.get("noise").description);
    assertEquals("Overview", metadata.controls.get("noise").group);
    assertEquals(
        AppMetadata.defaults().controls.get("noise").tooltip,
        metadata.controls.get("noise").tooltip);
    assertTrue(metadata.creationDefaults.isEmpty());
    assertEquals(AppMetadata.defaults().controls.keySet(), metadata.controls.keySet());
    assertEquals(text, Files.readString(path));
  }

  @Test
  void creationProfileAndPresentationOptionsSurviveStrictRoundTrip() throws Exception {
    var metadata = CreationProfileProbe.profile();
    metadata.controls.get("noise").group = "Custom";
    metadata.controls.get("noise").order = -3;
    Path path = directory.resolve("app.json");
    metadata.save(path);
    var read = AppMetadata.read(path);
    assertEquals(ProjectIO.JSON.valueToTree(metadata), ProjectIO.JSON.valueToTree(read));
    assertEquals("Flat preset", read.option("noise2D.algorithm", "CONSTANT"));
    assertEquals("Flat field", read.label("algorithm.CONSTANT", "fallback"));
    try (var paths = Files.list(directory)) {
      assertEquals(1, paths.count());
    }
  }

  @Test
  void presentationCannotIntroduceUnknownIdsOptionsOrUnsafeClippedLabels() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("noise2D.algorithm").options.put("FAKE", "Pretend");
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.controls.get("noise2D.algorithm").options.clear();
    metadata.controls.get("noise").label = "bad\nline";
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.controls.get("noise").label = "Good";
    metadata.controls.get("noise").group = "bad\tgroup";
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.controls.get("noise").group = "Good";
    metadata.controls.get("noise").order = 10001;
    assertThrows(IllegalArgumentException.class, metadata::validate);
    metadata.controls.get("noise").order = 0;
    metadata.controls.put("operation.NEW_SIGNATURE", new AppMetadata.Control());
    assertThrows(IllegalArgumentException.class, metadata::validate);
  }

  @Test
  void labelsAreEscapedForUiIdentityButPreservedInStoredMetadata() {
    var metadata = AppMetadata.defaults();
    metadata.controls.get("algorithm.CONSTANT").label = "Name###other";
    metadata.controls.get("noise2D.algorithm").options.put("CONSTANT", "Alias##value");
    metadata.validate();
    assertEquals("Name# # #other", metadata.label("algorithm.CONSTANT", ""));
    assertEquals("Alias# #value", metadata.option("noise2D.algorithm", "CONSTANT"));
    assertEquals("Name###other", metadata.controls.get("algorithm.CONSTANT").label);
  }

  @Test
  void privateDraftAndAtomicApplyPreserveCleanProjectAndExistingPropertyDraft() throws Exception {
    try (var state = new EditorState(new UserConfig(), false)) {
      state.metadata = AppMetadata.defaults();
      String before = ProjectIO.JSON.valueToTree(state.project()).toString();
      long revision = state.revision, history = state.document.historyBytes();
      Object draft = state.draft;
      var edit = new MetadataEditSession(state.metadata);
      edit.draft().controls.get("noise").label = "Custom noise";
      edit.draft()
          .creationDefaults
          .put("noise2D.algorithm.CONSTANT.value", ProjectIO.JSON.valueToTree(.75));
      assertTrue(edit.changed());
      assertNotEquals("Custom noise", state.metadata.controls.get("noise").label);
      state.metadata = edit.apply(state.metadata, directory.resolve("app.json"));
      assertFalse(edit.changed());
      assertEquals("Custom noise", state.metadata.controls.get("noise").label);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()).toString());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertSame(draft, state.draft);
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void invalidDefaultApplyPreservesFileAndActiveMetadata() throws Exception {
    var active = AppMetadata.defaults();
    Path path = directory.resolve("app.json");
    active.save(path);
    byte[] old = Files.readAllBytes(path);
    var edit = new MetadataEditSession(active);
    edit.draft().creationDefaults.put("voxel.color.r", ProjectIO.JSON.valueToTree(999));
    assertThrows(IllegalArgumentException.class, () -> edit.apply(active, path));
    assertArrayEquals(old, Files.readAllBytes(path));
    assertTrue(active.creationDefaults.isEmpty());
    assertTrue(edit.changed());
    edit.revert(active);
    assertFalse(edit.changed());
  }

  @Test
  void failedReplacementPreservesDestinationAndCurrentProfile() throws Exception {
    var active = AppMetadata.defaults();
    var edit = new MetadataEditSession(active);
    edit.draft().controls.get("noise").label = "Staged";
    Path occupied = directory.resolve("occupied");
    Files.createDirectory(occupied);
    Files.writeString(occupied.resolve("keep"), "original");
    assertThrows(IllegalStateException.class, () -> edit.apply(active, occupied));
    assertNotEquals("Staged", active.controls.get("noise").label);
    assertEquals("original", Files.readString(occupied.resolve("keep")));
    try (var paths = Files.list(directory)) {
      assertEquals(List.of(occupied), paths.toList());
    }
  }

  @Test
  void staleSessionCannotOverwriteAnotherAppliedProfile() throws Exception {
    var current = AppMetadata.defaults();
    var first = new MetadataEditSession(current);
    var stale = new MetadataEditSession(current);
    Path path = directory.resolve("app.json");
    first.draft().controls.get("noise").label = "First";
    var published = first.apply(current, path);
    byte[] before = Files.readAllBytes(path);
    stale.draft().controls.get("noise").label = "Stale";
    assertThrows(IllegalStateException.class, () -> stale.apply(published, path));
    assertArrayEquals(before, Files.readAllBytes(path));
  }

  @Test
  void strictReaderRejectsMalformedNullUnknownVersionAndOversizedProfiles() throws Exception {
    Path path = directory.resolve("invalid.json");
    for (String json :
        List.of(
            "{\"formatVersion\":2}",
            "{\"formatVersion\":1,\"unknown\":true}",
            "{\"formatVersion\":1,\"controls\":null}",
            "{\"formatVersion\":1,\"creationDefaults\":null}",
            "{\"formatVersion\":1,\"controls\":{\"noise\":{\"kind\":\"NEW\"}}}",
            "{\"formatVersion\":1,\"formatVersion\":1}",
            "{\"formatVersion\":1} {\"formatVersion\":1}",
            "{\"formatVersion\":1} trailing",
            "")) {
      Files.writeString(path, json);
      assertThrows(Exception.class, () -> AppMetadata.read(path), json);
    }
    Files.writeString(path, " ".repeat(8 * 1024 * 1024 + 1));
    assertThrows(IllegalArgumentException.class, () -> AppMetadata.read(path));
  }

  @Test
  void copiedMetadataIsIndependentAndOrderingNeverReordersComputation() throws Exception {
    var original = CreationProfileProbe.profile();
    var edit = new MetadataEditSession(original);
    var reversed = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
    var keys = new ArrayList<>(original.creationDefaults.keySet());
    Collections.reverse(keys);
    for (String key : keys) reversed.put(key, original.creationDefaults.get(key));
    original.creationDefaults = reversed;
    assertDoesNotThrow(() -> edit.apply(original, directory.resolve("reordered.json")));
    var copy = AppMetadata.copy(original);
    copy.controls.get("noise").description = "Changed";
    copy.controls.get("noise2D.algorithm").options.put("CONSTANT", "Changed");
    copy.creationDefaults.put("processing.MULTIPLY.input.b", ProjectIO.JSON.valueToTree(8));
    assertNotEquals("Changed", original.controls.get("noise").description);
    assertEquals("Flat preset", original.controls.get("noise2D.algorithm").options.get("CONSTANT"));
    assertEquals(4, original.creationDefaults.get("processing.MULTIPLY.input.b").doubleValue());
    var project = CreationProfileProbe.created(original);
    String before = ProjectIO.JSON.valueToTree(project).toString();
    original.controls.get("processing.MULTIPLY").group = "A";
    original.controls.get("processing.MULTIPLY").order = -999;
    assertEquals(before, ProjectIO.JSON.valueToTree(project).toString());
    assertEquals(2.5, new CompiledProject(project).noise2D("created-multiply", 1).sample(1, 1));
  }
}
