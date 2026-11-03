package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred staged-save ownership and actual destination preservation at injected IO boundaries. */
class PreparedProjectSaveTest {
  @TempDir Path directory;

  private static final class Faults implements ProjectSaveFiles {
    Path temporary;
    String failure = "";
    boolean rejectCleanup;
    int replaces, deletes;
    Runnable replaced;
    Thread writer;

    public Path temporary(Path parent) throws IOException {
      return temporary = ProjectSaveFiles.super.temporary(parent);
    }

    public void write(Path path, String contents) throws IOException {
      writer = Thread.currentThread();
      if (failure.equals("WRITE")) {
        Files.writeString(path, "partial");
        throw new IOException("write fault");
      }
      ProjectSaveFiles.super.write(path, contents);
    }

    public void force(Path path) throws IOException {
      if (failure.equals("FORCE")) throw new IOException("force fault");
      ProjectSaveFiles.super.force(path);
    }

    public void replace(Path from, Path to) throws IOException {
      replaces++;
      if (failure.equals("MOVE")) throw new IOException("replace fault");
      if (failure.equals("UNSUPPORTED"))
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "unsupported");
      ProjectSaveFiles.super.replace(from, to);
      if (replaced != null) replaced.run();
    }

    public void delete(Path path) throws IOException {
      deletes++;
      if (rejectCleanup) throw new IOException("cleanup fault");
      ProjectSaveFiles.super.delete(path);
    }
  }

  private DocumentSession document(Faults files) {
    var document = new DocumentSession(new ProjectIO(files));
    assertTrue(document.edit(p -> p.project.seed = 29).isEmpty());
    return document;
  }

  @Test
  void preparationLeavesDestinationAndDocumentUntouchedUntilApply() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("saved.json");
    Files.writeString(path, "previous destination");
    var token = document.sessionToken();
    var active = document.data();
    long history = document.historyBytes();
    var prepared = document.requestSave(path).prepare();
    assertEquals("previous destination", Files.readString(path));
    assertNull(document.path());
    assertTrue(document.dirty());
    assertTrue(Files.exists(files.temporary));
    document.applySave(prepared);
    prepared.close();
    assertEquals(29, new ProjectIO().load(path).project().project.seed);
    assertFalse(document.dirty());
    assertSame(active, document.data());
    assertSame(token, document.sessionToken());
    assertEquals(history, document.historyBytes());
    assertEquals(0, files.deletes);
    document.undo();
    assertTrue(document.dirty());
    document.redo();
    assertFalse(document.dirty());
  }

  @Test
  void cancelledPreparationClosesOnlyItsTemporarySibling() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("cancelled.json");
    Files.writeString(path, "previous destination");
    var prepared = document.requestSave(path).prepare();
    prepared.close();
    prepared.close();
    assertFalse(Files.exists(files.temporary));
    assertEquals(1, files.deletes);
    assertEquals("previous destination", Files.readString(path));
    assertThrows(IllegalStateException.class, () -> document.applySave(prepared));
    assertNull(document.path());
    assertTrue(document.dirty());
  }

  @Test
  void partialWritesAndForceFailuresRemoveTheUnpublishedSibling() throws Exception {
    for (String phase : List.of("WRITE", "FORCE")) {
      var files = new Faults();
      var document = document(files);
      var path = directory.resolve(phase + ".json");
      Files.writeString(path, "previous destination");
      files.failure = phase;
      assertThrows(IOException.class, () -> document.requestSave(path).prepare());
      assertFalse(Files.exists(files.temporary));
      assertEquals(0, files.replaces);
      assertEquals("previous destination", Files.readString(path));
      assertTrue(document.dirty());
    }
  }

  @Test
  void stagingCleanupFailureIsSuppressedOnTheOriginalFailure() throws Exception {
    var files = new Faults();
    var document = document(files);
    files.failure = "WRITE";
    files.rejectCleanup = true;
    try {
      var error =
          assertThrows(
              IOException.class,
              () -> document.requestSave(directory.resolve("failed.json")).prepare());
      assertEquals("write fault", error.getMessage());
      assertEquals("cleanup fault", error.getSuppressed()[0].getMessage());
      assertTrue(Files.exists(files.temporary));
    } finally {
      Files.deleteIfExists(files.temporary);
    }
  }

  @Test
  void failedReplacementRetainsCheckpointAndAllowsFreshExplicitRetry() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("move.json");
    Files.writeString(path, "previous destination");
    try (var prepared = document.requestSave(path).prepare()) {
      files.failure = "MOVE";
      assertThrows(IOException.class, () -> document.applySave(prepared));
      assertEquals("previous destination", Files.readString(path));
      assertNull(document.path());
      assertTrue(document.dirty());
    }
    assertFalse(Files.exists(files.temporary));
    files.failure = "";
    try (var retry = document.requestSave(path).prepare()) {
      document.applySave(retry);
    }
    assertFalse(document.dirty());
  }

  @Test
  void unsupportedAtomicReplacementHasNoUnsafeFallback() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("unsupported.json");
    Files.writeString(path, "previous destination");
    try (var prepared = document.requestSave(path).prepare()) {
      files.failure = "UNSUPPORTED";
      var failure = assertThrows(IOException.class, () -> document.applySave(prepared));
      assertTrue(failure.getCause() instanceof AtomicMoveNotSupportedException);
      assertEquals(1, files.replaces);
      assertEquals("previous destination", Files.readString(path));
    }
  }

  @Test
  void compilerRejectionOccursBeforeFilesystemAdmission() throws Exception {
    var files = new Faults();
    var document = document(files);
    document.setValidator(p -> List.of(Diagnostic.error("INVALID", "/project/seed", "Rejected")));
    assertThrows(
        IOException.class, () -> document.requestSave(directory.resolve("invalid.json")).prepare());
    assertNull(files.temporary);
    assertEquals(0, files.replaces);
  }

  @Test
  void sameObjectRawMutationInvalidatesBeforeDestinationReplacement() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("raw.json");
    Files.writeString(path, "previous destination");
    try (var prepared = document.requestSave(path).prepare()) {
      document.data().project.seed = 31;
      assertThrows(IllegalStateException.class, () -> document.applySave(prepared));
      assertEquals(0, files.replaces);
      assertEquals("previous destination", Files.readString(path));
    }
  }

  @Test
  void foreignPolicyAndManagedDocumentChangesRejectUnpublishedPreparations() throws Exception {
    for (String change : List.of("FOREIGN", "POLICY", "EDIT", "NEW", "SAVE")) {
      var files = new Faults();
      var document = document(files);
      var path = directory.resolve(change + "-stale.json");
      Files.writeString(path, "previous destination");
      try (var prepared = document.requestSave(path).prepare()) {
        var target = document;
        if (change.equals("FOREIGN")) target = new DocumentSession();
        else if (change.equals("POLICY")) document.setValidator(p -> List.of());
        else if (change.equals("EDIT")) document.edit(p -> p.project.seed = 31);
        else if (change.equals("NEW")) document.newProject();
        else document.save(directory.resolve("other-save.json"));
        var receiver = target;
        assertThrows(IllegalStateException.class, () -> receiver.applySave(prepared));
        assertEquals("previous destination", Files.readString(path));
      }
    }
  }

  @Test
  void requestOwnsItsSnapshotWhileWorkerMayRunLater() throws Exception {
    var files = new Faults();
    var document = document(files);
    var request = document.requestSave(directory.resolve("snapshot.json"));
    document.data().project.seed = 31;
    try (var prepared = request.prepare()) {
      assertEquals(29, new ProjectIO().load(files.temporary).project().project.seed);
      assertThrows(IllegalStateException.class, () -> document.applySave(prepared));
    }
  }

  @Test
  void validationAndWriteRunOffThreadWithoutPublishingTheDestination() throws Exception {
    var files = new Faults();
    var document = document(files);
    var thread = Thread.currentThread();
    var validationThread = new AtomicReference<Thread>();
    document.setValidator(
        p -> {
          validationThread.set(Thread.currentThread());
          return List.of();
        });
    var path = directory.resolve("worker.json");
    var request = document.requestSave(path);
    var future = new FutureTask<>(request::prepare);
    Thread.ofPlatform().start(future);
    try (var prepared = future.get(5, TimeUnit.SECONDS)) {
      assertNotSame(thread, validationThread.get());
      assertSame(validationThread.get(), files.writer);
      assertFalse(Files.exists(path));
      assertNull(document.path());
      document.applySave(prepared);
    }
  }

  @Test
  void publishedPreparationCannotPublishTwiceAndClosePerformsNoIo() throws Exception {
    var files = new Faults();
    var document = document(files);
    var prepared = document.requestSave(directory.resolve("once.json")).prepare();
    document.applySave(prepared);
    files.rejectCleanup = true;
    prepared.close();
    assertThrows(IllegalStateException.class, () -> document.applySave(prepared));
    assertEquals(1, files.replaces);
    assertEquals(0, files.deletes);
  }

  @Test
  void failedCleanupRetainsOwnershipForAnExplicitRetry() throws Exception {
    var files = new Faults();
    var document = document(files);
    var prepared = document.requestSave(directory.resolve("cleanup.json")).prepare();
    files.rejectCleanup = true;
    assertThrows(IOException.class, prepared::close);
    assertTrue(Files.exists(files.temporary));
    files.rejectCleanup = false;
    prepared.close();
    assertFalse(Files.exists(files.temporary));
  }

  @Test
  void laterMutationAtReplacementDoesNotBecomeTheSavedCheckpoint() throws Exception {
    var files = new Faults();
    var document = document(files);
    var path = directory.resolve("checkpoint.json");
    try (var prepared = document.requestSave(path).prepare()) {
      files.replaced = () -> document.data().project.seed = 31;
      document.applySave(prepared);
    }
    assertEquals(29, new ProjectIO().load(path).project().project.seed);
    assertEquals(31, document.data().project.seed);
    assertTrue(document.dirty());
  }
}
