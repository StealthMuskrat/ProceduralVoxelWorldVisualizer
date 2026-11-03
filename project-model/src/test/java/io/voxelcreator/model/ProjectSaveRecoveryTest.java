package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred actual-file transaction faults; no filesystem provider or global hook is replaced. */
class ProjectSaveRecoveryTest {
  @TempDir Path directory;

  private static final class FilesWithFaults implements ProjectSaveFiles {
    final List<String> calls = new ArrayList<>();
    String phase = "";
    Throwable writeFailure, deleteFailure;
    boolean failDelete, rejectDeleteAfterPublication;
    Runnable afterReplacement;
    Path temporary;

    public Path temporary(Path parent) throws IOException {
      calls.add("temporary");
      temporary = ProjectSaveFiles.super.temporary(parent);
      return temporary;
    }

    public void write(Path path, String contents) throws IOException {
      calls.add("write");
      if (phase.equals("WRITE") || writeFailure != null) {
        java.nio.file.Files.writeString(path, "partial");
        if (writeFailure instanceof IOException io) throw io;
        if (writeFailure instanceof RuntimeException runtime) throw runtime;
        if (writeFailure instanceof Error error) throw error;
        throw new IOException("write fault");
      }
      ProjectSaveFiles.super.write(path, contents);
    }

    public void force(Path path) throws IOException {
      calls.add("force");
      if (phase.equals("FORCE")) throw new IOException("force fault");
      ProjectSaveFiles.super.force(path);
    }

    public void replace(Path from, Path to) throws IOException {
      calls.add("replace");
      if (phase.equals("MOVE")) throw new IOException("move fault");
      if (phase.equals("UNSUPPORTED"))
        throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "injected");
      ProjectSaveFiles.super.replace(from, to);
      if (afterReplacement != null) afterReplacement.run();
    }

    public void delete(Path path) throws IOException {
      calls.add("delete");
      if (deleteFailure instanceof IOException io) throw io;
      if (deleteFailure instanceof RuntimeException runtime) throw runtime;
      if (deleteFailure instanceof Error error) throw error;
      if (rejectDeleteAfterPublication)
        throw new AssertionError("Cleanup after successful publication");
      if (failDelete) throw new IOException("cleanup fault");
      ProjectSaveFiles.super.delete(path);
    }
  }

  private ProjectData data(long seed) {
    var p = new ProjectData();
    p.project.seed = seed;
    return p;
  }

  @Test
  void uncheckedCleanupFaultsRetainTheOriginalWriteFailure() throws Exception {
    var path = directory.resolve("unchecked-cleanup.json");
    Files.writeString(path, "previous destination");
    for (String kind : List.of("IO", "RUNTIME", "ERROR"))
      for (boolean errorCleanup : new boolean[] {false, true}) {
        Throwable primary =
            switch (kind) {
              case "IO" -> new IOException("write original");
              case "RUNTIME" -> new IllegalStateException("write original");
              default -> new AssertionError("write original");
            };
        Throwable cleanup =
            errorCleanup
                ? new AssertionError("cleanup Error")
                : new IllegalStateException("cleanup runtime");
        var files = new FilesWithFaults();
        files.writeFailure = primary;
        files.deleteFailure = cleanup;
        try {
          var observed =
              assertThrows(Throwable.class, () -> new ProjectIO(files).save(path, data(3)));
          assertSame(primary, observed);
          assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
          assertEquals("previous destination", Files.readString(path));
          assertEquals(List.of("temporary", "write", "delete"), files.calls);
        } finally {
          Files.deleteIfExists(files.temporary);
        }
      }
  }

  @Test
  void identicalPrimaryAndCleanupFailuresCannotMaskTheOriginalProjectSaveFailure()
      throws Exception {
    var path = directory.resolve("same-cleanup.json");
    Files.writeString(path, "previous destination");
    for (Throwable primary :
        List.of(
            new IOException("same IO"),
            new IllegalStateException("same runtime"),
            new AssertionError("same Error"))) {
      var files = new FilesWithFaults();
      files.writeFailure = files.deleteFailure = primary;
      try {
        var observed =
            assertThrows(Throwable.class, () -> new ProjectIO(files).save(path, data(3)));
        assertSame(primary, observed);
        assertEquals(0, observed.getSuppressed().length);
        assertEquals("previous destination", Files.readString(path));
        assertEquals(List.of("temporary", "write", "delete"), files.calls);
      } finally {
        Files.deleteIfExists(files.temporary);
      }
    }
  }

  @Test
  void partialWriteLeavesTheOldDestinationAndRetryPublishesTheCompletePayload() throws Exception {
    var path = directory.resolve("project.json");
    var files = new FilesWithFaults();
    var io = new ProjectIO(files);
    new ProjectIO().save(path, data(17));
    byte[] before = Files.readAllBytes(path);
    files.phase = "WRITE";
    assertThrows(IOException.class, () -> io.save(path, data(29)));
    assertArrayEquals(before, Files.readAllBytes(path));
    assertFalse(Files.exists(files.temporary));
    assertEquals(List.of("temporary", "write", "delete"), files.calls);
    files.phase = "";
    io.save(path, data(29));
    assertEquals(29, new ProjectIO().load(path).project().project.seed);
  }

  @Test
  void syncFailureNeverAttemptsReplacementAndRemovesTheStagedFile() throws Exception {
    var path = directory.resolve("sync.json");
    Files.writeString(path, "previous destination");
    var files = new FilesWithFaults();
    files.phase = "FORCE";
    assertThrows(IOException.class, () -> new ProjectIO(files).save(path, data(3)));
    assertEquals("previous destination", Files.readString(path));
    assertEquals(List.of("temporary", "write", "force", "delete"), files.calls);
    assertFalse(Files.exists(files.temporary));
  }

  @Test
  void unsupportedAtomicMoveNeverFallsBackToAnUnsafeReplacement() throws Exception {
    var path = directory.resolve("atomic.json");
    Files.writeString(path, "previous destination");
    var files = new FilesWithFaults();
    files.phase = "UNSUPPORTED";
    var failure = assertThrows(IOException.class, () -> new ProjectIO(files).save(path, data(3)));
    assertInstanceOf(AtomicMoveNotSupportedException.class, failure.getCause());
    assertTrue(failure.getMessage().contains("choose another location"));
    assertEquals(1, Collections.frequency(files.calls, "replace"));
    assertEquals("previous destination", Files.readString(path));
    assertFalse(Files.exists(files.temporary));
  }

  @Test
  void cleanupFailureRetainsThePrimaryIoRuntimeOrFatalWriteError() throws Exception {
    var path = directory.resolve("errors.json");
    Files.writeString(path, "previous destination");
    for (Throwable primary :
        List.of(
            new IOException("write original"),
            new IllegalStateException("runtime original"),
            new AssertionError("fatal original"))) {
      var files = new FilesWithFaults();
      files.writeFailure = primary;
      files.failDelete = true;
      try {
        var observed =
            assertThrows(primary.getClass(), () -> new ProjectIO(files).save(path, data(3)));
        assertSame(primary, observed);
        assertEquals("cleanup fault", observed.getSuppressed()[0].getMessage());
        assertEquals("previous destination", Files.readString(path));
        assertTrue(Files.exists(files.temporary));
      } finally {
        if (files.temporary != null) Files.deleteIfExists(files.temporary);
      }
    }
  }

  @Test
  void successfulPublicationHasNoFurtherCleanupThatCouldReportAFailedSave() throws Exception {
    var path = directory.resolve("success.json");
    var files = new FilesWithFaults();
    files.rejectDeleteAfterPublication = true;
    var p = data(31);
    String expected = ProjectIO.JSON.writeValueAsString(p);
    String checkpoint = new ProjectIO(files).saveAndGetCheckpoint(path, p);
    assertEquals(expected, checkpoint);
    assertEquals(expected, Files.readString(path));
    assertEquals(List.of("temporary", "write", "force", "replace"), files.calls);
    assertFalse(Files.exists(files.temporary));
  }

  @Test
  void invalidCommittedDataAndRootPathsAreRejectedBeforeFilesystemAdmission() throws Exception {
    var path = directory.resolve("invalid.json");
    Files.writeString(path, "previous destination");
    var files = new FilesWithFaults();
    var p = data(0);
    p.project.maxY = p.project.minY - 1;
    assertThrows(IOException.class, () -> new ProjectIO(files).save(path, p));
    assertEquals("previous destination", Files.readString(path));
    assertTrue(files.calls.isEmpty());
    assertThrows(IOException.class, () -> new ProjectIO(files).save(directory.getRoot(), data(0)));
    assertTrue(files.calls.isEmpty());
  }

  @Test
  void failedSaveAsRetainsTheActiveDocumentPathCheckpointLifetimeAndHistory() throws Exception {
    for (String phase : List.of("WRITE", "FORCE", "MOVE", "UNSUPPORTED")) {
      var files = new FilesWithFaults();
      var document = new DocumentSession(new ProjectIO(files));
      var original = directory.resolve("original-" + phase + ".json");
      var candidate = directory.resolve("candidate-" + phase + ".json");
      assertTrue(document.edit(p -> p.project.seed = 17).isEmpty());
      document.save(original);
      byte[] saved = Files.readAllBytes(original);
      assertTrue(document.edit(p -> p.project.seed = 29).isEmpty());
      var active = document.data();
      var token = document.sessionToken();
      long history = document.historyBytes();
      String before = ProjectIO.JSON.writeValueAsString(active);
      Files.writeString(candidate, "candidate destination");
      files.phase = phase;
      assertThrows(IOException.class, () -> document.save(candidate));
      assertSame(active, document.data());
      assertSame(token, document.sessionToken());
      assertEquals(original, document.path());
      assertEquals(history, document.historyBytes());
      assertEquals(before, ProjectIO.JSON.writeValueAsString(document.data()));
      assertTrue(document.dirty());
      assertArrayEquals(saved, Files.readAllBytes(original));
      assertEquals("candidate destination", Files.readString(candidate));
      document.undo();
      assertEquals(17, document.data().project.seed);
      assertFalse(document.dirty());
      document.redo();
      assertTrue(document.dirty());
      files.phase = "";
      document.save(candidate);
      assertEquals(candidate, document.path());
      assertFalse(document.dirty());
      assertSame(token, document.sessionToken());
      assertEquals(history, document.historyBytes());
      document.undo();
      assertTrue(document.dirty());
      document.redo();
      assertFalse(document.dirty());
    }
  }

  @Test
  void savedCheckpointMatchesThePublishedTextIfSourceChangesAtTheIoBoundary() throws Exception {
    var files = new FilesWithFaults();
    var document = new DocumentSession(new ProjectIO(files));
    assertTrue(document.edit(p -> p.project.seed = 29).isEmpty());
    files.afterReplacement = () -> document.data().project.seed = 31;
    var path = directory.resolve("checkpoint.json");
    document.save(path);
    assertEquals(29, new ProjectIO().load(path).project().project.seed);
    assertEquals(31, document.data().project.seed);
    assertTrue(document.dirty(), "The later source value must not be marked as persisted");
    assertEquals(path, document.path());
  }
}
