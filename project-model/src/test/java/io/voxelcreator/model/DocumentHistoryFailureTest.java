package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred definitions: failed history reconstruction or validation cannot consume a delta. */
class DocumentHistoryFailureTest {
  private DocumentSession document() {
    var document = new DocumentSession();
    assertTrue(document.edit(p -> p.project.seed = 41).isEmpty());
    return document;
  }

  private List<Diagnostic> rejected(ProjectData ignored) {
    return List.of(
        new Diagnostic(
            "HISTORY_FIXTURE",
            Diagnostic.Severity.ERROR,
            "Rejected by fixture",
            "/project/seed",
            null,
            null,
            "seed"));
  }

  @Test
  void rejectedUndoPreservesActiveIdentityStacksAccountingAndCanBeRetried() {
    var document = document();
    var active = document.data();
    long bytes = document.historyBytes();
    document.setValidator(this::rejected);
    assertThrows(IllegalStateException.class, document::undo);
    assertSame(active, document.data());
    assertEquals(41, document.data().project.seed);
    assertTrue(document.canUndo());
    assertFalse(document.canRedo());
    assertEquals(bytes, document.historyBytes());
    document.setValidator(Validator::validate);
    document.undo();
    assertEquals(0, document.data().project.seed);
    document.redo();
    assertEquals(41, document.data().project.seed);
    assertEquals(bytes, document.historyBytes());
  }

  @Test
  void rejectedAndThrowingRedoPreserveItsDeltaAndOriginalActiveDocument() {
    var document = document();
    document.undo();
    var active = document.data();
    long bytes = document.historyBytes();
    document.setValidator(this::rejected);
    assertThrows(IllegalStateException.class, document::redo);
    document.setValidator(
        p -> {
          throw new IllegalArgumentException("fixture validation exception");
        });
    assertThrows(IllegalArgumentException.class, document::redo);
    assertSame(active, document.data());
    assertFalse(document.canUndo());
    assertTrue(document.canRedo());
    assertEquals(bytes, document.historyBytes());
    document.setValidator(Validator::validate);
    document.redo();
    assertEquals(41, document.data().project.seed);
  }

  @Test
  void malformedReconstructionCannotPopTheUndoDeltaBeforeItFails() {
    var document = document();
    var active = document.data();
    var metadata = active.project;
    long bytes = document.historyBytes();
    // Deliberate unsupported model corruption injects a failure inside delta reconstruction.
    active.project = null;
    assertThrows(RuntimeException.class, document::undo);
    assertSame(active, document.data());
    assertTrue(document.canUndo());
    assertFalse(document.canRedo());
    assertEquals(bytes, document.historyBytes());
    active.project = metadata;
    document.undo();
    assertEquals(0, document.data().project.seed);
  }

  @Test
  void emptyHistoryDoesNotValidateAllocateOrChangeTheDocument() {
    var document = new DocumentSession();
    var active = document.data();
    document.setValidator(
        p -> {
          throw new AssertionError("Empty history validation");
        });
    document.undo();
    document.redo();
    assertSame(active, document.data());
    assertEquals(0, document.historyBytes());
    assertFalse(document.canUndo());
    assertFalse(document.canRedo());
  }
}
