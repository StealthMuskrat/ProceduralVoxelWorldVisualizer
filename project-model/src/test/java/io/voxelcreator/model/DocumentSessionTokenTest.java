package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred lifetime identity definitions; a document replacement differs from an ordinary edit. */
class DocumentSessionTokenTest {
  @TempDir Path directory;

  @Test
  void editsHistoryNoOpsAndSavingRetainTheOpeningToken() throws Exception {
    var document = new DocumentSession();
    var token = document.sessionToken();
    assertTrue(document.edit(p -> p.project.seed = 17).isEmpty());
    assertSame(token, document.sessionToken());
    document.undo();
    assertSame(token, document.sessionToken());
    document.redo();
    assertSame(token, document.sessionToken());
    assertTrue(document.edit(p -> {}).isEmpty());
    document.save(directory.resolve("saved.json"));
    assertSame(token, document.sessionToken());
    assertThrows(
        IllegalStateException.class,
        () ->
            document.edit(
                p -> {
                  throw new IllegalStateException();
                }));
    assertSame(token, document.sessionToken());
    assertEquals(17, document.data().project.seed);
  }

  @Test
  void reopeningTheSameLogicalProjectAndNewEachCreateADistinctToken() throws Exception {
    var document = new DocumentSession();
    var token = document.sessionToken();
    var project = document.data().project.uuid;
    var world = document.data().worldGenerator.uuid;
    Path file = directory.resolve("same.json");
    document.save(file);
    assertTrue(document.open(file).isEmpty());
    assertEquals(project, document.data().project.uuid);
    assertEquals(world, document.data().worldGenerator.uuid);
    assertNotSame(token, document.sessionToken());
    var reopened = document.sessionToken();
    document.newProject();
    assertNotSame(token, document.sessionToken());
    assertNotSame(reopened, document.sessionToken());
    assertFalse(ProjectIO.JSON.valueToTree(document.data()).has("sessionToken"));
  }

  @Test
  void failedOpenParsingValidationAndIoNeverAdvanceTheTokenOrReplaceTheDocument() throws Exception {
    var document = new DocumentSession();
    Path file = directory.resolve("valid.json");
    document.save(file);
    assertTrue(document.edit(p -> p.project.seed = 17).isEmpty());
    var active = document.data();
    var token = document.sessionToken();
    var before = ProjectIO.JSON.valueToTree(active);
    long history = document.historyBytes();
    Path invalid = directory.resolve("invalid.json");
    Files.writeString(invalid, "{");
    assertFalse(document.open(invalid).isEmpty());
    document.setValidator(p -> List.of(Diagnostic.error("REJECT", "", "Rejected")));
    assertFalse(document.open(file).isEmpty());
    document.setValidator(
        p -> {
          throw new IllegalStateException();
        });
    assertThrows(IllegalStateException.class, () -> document.open(file));
    assertThrows(java.io.IOException.class, () -> document.open(directory.resolve("missing.json")));
    assertSame(active, document.data());
    assertSame(token, document.sessionToken());
    assertEquals(before, ProjectIO.JSON.valueToTree(document.data()));
    assertEquals(history, document.historyBytes());
    assertTrue(document.dirty());
    assertEquals(file, document.path());
  }
}
