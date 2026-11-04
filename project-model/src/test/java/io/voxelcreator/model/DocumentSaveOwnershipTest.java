package io.voxelcreator.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deferred synchronous/prepared Save validation isolation and exact checkpoint receipt ownership.
 */
class DocumentSaveOwnershipTest {
  @TempDir Path directory;

  private static final class Boundary implements ProjectSaveFiles {
    final List<String> calls = new ArrayList<>();
    BiConsumer<Path, Path> published;
    Throwable replaceFailure, deleteFailure;
    Path temporary;

    public Path temporary(Path parent) throws IOException {
      calls.add("temporary");
      temporary = ProjectSaveFiles.super.temporary(parent);
      return temporary;
    }

    public void write(Path p, String text) throws IOException {
      calls.add("write");
      ProjectSaveFiles.super.write(p, text);
    }

    public void force(Path p) throws IOException {
      calls.add("force");
      ProjectSaveFiles.super.force(p);
    }

    public void replace(Path from, Path to) throws IOException {
      calls.add("replace");
      fail(replaceFailure);
      ProjectSaveFiles.super.replace(from, to);
      if (published != null) published.accept(from, to);
    }

    public void delete(Path p) throws IOException {
      calls.add("delete");
      fail(deleteFailure);
      ProjectSaveFiles.super.delete(p);
    }
  }

  private static void fail(Throwable failure) throws IOException {
    if (failure instanceof IOException e) throw e;
    if (failure instanceof RuntimeException e) throw e;
    if (failure instanceof Error e) throw e;
  }

  private static Throwable failure(String kind, String message) {
    return switch (kind) {
      case "IO" -> new IOException(message);
      case "RUNTIME" -> new IllegalStateException(message);
      default -> new AssertionError(message);
    };
  }

  private record State(
      ProjectData active,
      Object lifetime,
      Path path,
      long history,
      boolean undo,
      boolean redo,
      boolean dirty,
      JsonNode values) {}

  private State capture(DocumentSession d) {
    return new State(
        d.data(),
        d.sessionToken(),
        d.path(),
        d.historyBytes(),
        d.canUndo(),
        d.canRedo(),
        d.dirty(),
        ProjectIO.JSON.valueToTree(d.data()));
  }

  private void retained(DocumentSession d, State s) {
    assertSame(s.active(), d.data());
    assertSame(s.lifetime(), d.sessionToken());
    assertEquals(s.path(), d.path());
    assertEquals(s.history(), d.historyBytes());
    assertEquals(s.undo(), d.canUndo());
    assertEquals(s.redo(), d.canRedo());
    assertEquals(s.dirty(), d.dirty());
    assertEquals(s.values(), ProjectIO.JSON.valueToTree(d.data()));
  }

  private DocumentSession document(Boundary files, String id, boolean dirty) throws Exception {
    var d = new DocumentSession(new ProjectIO(files));
    assertTrue(d.edit(p -> p.project.seed = 17).isEmpty());
    d.save(directory.resolve(id + "-original.json"));
    if (dirty) assertTrue(d.edit(p -> p.project.seed = 29).isEmpty());
    files.calls.clear();
    return d;
  }

  private void save(DocumentSession d, Path file, boolean prepared) throws IOException {
    if (prepared) {
      try (var candidate = d.requestSave(file).prepare()) {
        d.applySave(candidate);
      }
    } else d.save(file);
  }

  private void noTemporaryFiles() throws IOException {
    try (var files = Files.list(directory)) {
      assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-save-")));
    }
  }

  private long seed(Path file) throws IOException {
    return new ProjectIO().load(file).project().project.seed;
  }

  @Test
  void rejectingValidatorMutationsCannotChangeLiveStateOrTouchEitherSaveDestination()
      throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "reject-" + prepared, true);
      var before = capture(d);
      var target = directory.resolve("reject-target-" + prepared);
      Files.writeString(target, "prior target");
      d.setValidator(
          p -> {
            p.project.seed = 99;
            return List.of(Diagnostic.error("REJECTED", "/project/seed", "rejected"));
          });
      assertThrows(IOException.class, () -> save(d, target, prepared));
      retained(d, before);
      assertEquals("prior target", Files.readString(target));
      assertTrue(files.calls.isEmpty());
      noTemporaryFiles();
    }
  }

  @Test
  void throwingValidatorMutationsKeepTheOriginalFailureWithoutChangingCommittedData()
      throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "throw-" + prepared, true);
      var before = capture(d);
      var failure = new AssertionError("validator failed");
      d.setValidator(
          p -> {
            p.project.seed = 99;
            throw failure;
          });
      assertSame(
          failure,
          assertThrows(
              AssertionError.class,
              () -> save(d, directory.resolve("throw-target-" + prepared), prepared)));
      retained(d, before);
      assertTrue(files.calls.isEmpty());
      noTemporaryFiles();
    }
  }

  @Test
  void aValidatorCannotSilentlyTransformASuccessfulSaveSnapshot() throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "mutate-" + prepared, true);
      var before = capture(d);
      var target = directory.resolve("mutate-target-" + prepared);
      Files.writeString(target, "prior target");
      d.setValidator(
          p -> {
            p.project.seed = 99;
            return List.of();
          });
      var failure = assertThrows(IOException.class, () -> save(d, target, prepared));
      assertTrue(failure.getMessage().contains("read-only"));
      retained(d, before);
      assertEquals("prior target", Files.readString(target));
      assertTrue(files.calls.isEmpty());
    }
  }

  @Test
  void aRetainedValidationCandidateCannotAliasTheSavedActiveDocument() throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "owned-" + prepared, true);
      var active = d.data();
      var candidate = new AtomicReference<ProjectData>();
      d.setValidator(
          p -> {
            candidate.set(p);
            return Validator.validate(p);
          });
      var target = directory.resolve("owned-target-" + prepared);
      save(d, target, prepared);
      assertNotSame(active, candidate.get());
      candidate.get().project.seed = 99;
      assertSame(active, d.data());
      assertEquals(29, d.data().project.seed);
      assertEquals(29, seed(target));
      assertFalse(d.dirty());
    }
  }

  @Test
  void ordinaryReadOnlySaveValidatesOnceAndRetainsModelLifetimeAndHistory() throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "ordinary-" + prepared, true);
      var active = d.data();
      var token = d.sessionToken();
      long history = d.historyBytes();
      var calls = new AtomicInteger();
      d.setValidator(
          p -> {
            calls.incrementAndGet();
            return Validator.validate(p);
          });
      var target = directory.resolve("ordinary-target-" + prepared);
      save(d, target, prepared);
      assertEquals(1, calls.get());
      assertSame(active, d.data());
      assertSame(token, d.sessionToken());
      assertEquals(history, d.historyBytes());
      assertEquals(target, d.path());
      assertFalse(d.dirty());
      assertEquals(List.of("temporary", "write", "force", "replace"), files.calls);
      d.undo();
      assertTrue(d.dirty());
      d.redo();
      assertFalse(d.dirty());
    }
  }

  @Test
  void validationThatMutatesLiveValuesRejectsBeforeReplacementAndKeepsTheNewerValues()
      throws Exception {
    var files = new Boundary();
    var d = document(files, "raw", true);
    var after = new AtomicReference<State>();
    var target = directory.resolve("raw-target");
    Files.writeString(target, "prior target");
    d.setValidator(
        p -> {
          d.data().project.seed = 31;
          after.set(capture(d));
          return List.of();
        });
    assertThrows(IllegalStateException.class, () -> d.save(target));
    retained(d, after.get());
    assertEquals("prior target", Files.readString(target));
    assertFalse(files.calls.contains("replace"));
    noTemporaryFiles();
  }

  @Test
  void replacementDuringValidationCannotReceiveTheOldSavePathOrCheckpoint() throws Exception {
    var files = new Boundary();
    var d = document(files, "new-before", true);
    var after = new AtomicReference<State>();
    var target = directory.resolve("new-before-target");
    Files.writeString(target, "prior target");
    d.setValidator(
        p -> {
          d.newProject();
          after.set(capture(d));
          return List.of();
        });
    assertThrows(IllegalStateException.class, () -> d.save(target));
    retained(d, after.get());
    assertEquals("prior target", Files.readString(target));
    assertFalse(files.calls.contains("replace"));
    noTemporaryFiles();
  }

  @Test
  void nestedSaveDuringValidationKeepsItsCheckpointAndRejectsTheOlderDestination()
      throws Exception {
    var files = new Boundary();
    var d = document(files, "save-before", true);
    var after = new AtomicReference<State>();
    var once = new AtomicBoolean();
    var newer = directory.resolve("save-before-newer");
    var target = directory.resolve("save-before-older");
    Files.writeString(target, "prior target");
    d.setValidator(
        p -> {
          if (once.compareAndSet(false, true)) {
            try {
              d.save(newer);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            after.set(capture(d));
          }
          return List.of();
        });
    assertThrows(IllegalStateException.class, () -> d.save(target));
    retained(d, after.get());
    assertEquals(newer, d.path());
    assertEquals(29, seed(newer));
    assertEquals("prior target", Files.readString(target));
    noTemporaryFiles();
  }

  @Test
  void aNewDocumentAfterReplacementKeepsItsOwnCheckpointWhileTheOldFileRemainsSaved()
      throws Exception {
    for (boolean prepared : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "new-after-" + prepared, true);
      var after = new AtomicReference<State>();
      var target = directory.resolve("new-after-target-" + prepared);
      files.published =
          (from, to) -> {
            d.newProject();
            after.set(capture(d));
          };
      save(d, target, prepared);
      retained(d, after.get());
      assertEquals(29, seed(target));
      assertNull(d.path());
      noTemporaryFiles();
    }
  }

  @Test
  void sameUuidOpenAfterReplacementCannotBeRelabelledWithTheOlderSaveCheckpoint() throws Exception {
    var files = new Boundary();
    var d = document(files, "open-after", true);
    var original = d.path();
    var after = new AtomicReference<State>();
    var target = directory.resolve("open-after-target");
    files.published =
        (from, to) -> {
          try {
            assertTrue(d.open(original).isEmpty());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          after.set(capture(d));
        };
    d.save(target);
    retained(d, after.get());
    assertEquals(original, d.path());
    assertEquals(17, d.data().project.seed);
    assertEquals(29, seed(target));
  }

  @Test
  void aNewerSaveAfterReplacementRetainsItsPathHistoryAndCleanCheckpoint() throws Exception {
    var files = new Boundary();
    var d = document(files, "save-after", true);
    var after = new AtomicReference<State>();
    var once = new AtomicBoolean();
    var target = directory.resolve("save-after-older");
    var newer = directory.resolve("save-after-newer");
    files.published =
        (from, to) -> {
          if (once.compareAndSet(false, true)) {
            assertTrue(d.edit(p -> p.project.seed = 31).isEmpty());
            try {
              d.save(newer);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            after.set(capture(d));
          }
        };
    d.save(target);
    retained(d, after.get());
    assertEquals(29, seed(target));
    assertEquals(31, seed(newer));
    assertEquals(newer, d.path());
    assertFalse(d.dirty());
  }

  @Test
  void evenASameValueSamePathSaveCompletionSupersedesAnOlderSaveAsReceipt() throws Exception {
    var files = new Boundary();
    var d = document(files, "same-after", false);
    var original = d.path();
    var after = new AtomicReference<State>();
    var once = new AtomicBoolean();
    var target = directory.resolve("same-after-older");
    files.published =
        (from, to) -> {
          if (once.compareAndSet(false, true)) {
            try {
              d.save(original);
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            after.set(capture(d));
          }
        };
    d.save(target);
    retained(d, after.get());
    assertEquals(original, d.path());
    assertEquals(17, seed(target));
    assertEquals(17, seed(original));
  }

  @Test
  void laterSameSessionEditsRemainDirtyAgainstTheExactPublishedSnapshot() throws Exception {
    for (boolean raw : List.of(false, true)) {
      var files = new Boundary();
      var d = document(files, "edit-after-" + raw, true);
      var token = d.sessionToken();
      var after = new AtomicReference<State>();
      var target = directory.resolve("edit-after-target-" + raw);
      files.published =
          (from, to) -> {
            if (raw) d.data().project.seed = 31;
            else assertTrue(d.edit(p -> p.project.seed = 31).isEmpty());
            after.set(capture(d));
          };
      d.save(target);
      assertSame(after.get().active(), d.data());
      assertSame(token, d.sessionToken());
      assertEquals(after.get().history(), d.historyBytes());
      assertEquals(29, seed(target));
      assertEquals(31, d.data().project.seed);
      assertEquals(target, d.path());
      assertTrue(d.dirty());
      if (!raw) {
        d.undo();
        assertFalse(d.dirty());
        d.redo();
        assertTrue(d.dirty());
      }
    }
  }

  @Test
  void aNewSameValueCheckpointRejectsAnOlderPreparedSaveBeforeAnyReplacement() throws Exception {
    var files = new Boundary();
    var d = document(files, "stale-save", false);
    var target = directory.resolve("stale-save-target");
    Files.writeString(target, "prior target");
    try (var older = d.requestSave(target).prepare()) {
      d.save(d.path());
      var after = capture(d);
      files.calls.clear();
      assertThrows(IllegalStateException.class, () -> d.applySave(older));
      retained(d, after);
      assertTrue(files.calls.isEmpty());
    }
    assertEquals("prior target", Files.readString(target));
    noTemporaryFiles();
  }

  @Test
  void aNewSameValueCheckpointRejectsAnOlderPreparedOpenWithoutReplacingTheDocument()
      throws Exception {
    var files = new Boundary();
    var d = document(files, "stale-open", false);
    var source = directory.resolve("stale-open-source");
    var incoming = new ProjectData();
    incoming.project.seed = 99;
    new ProjectIO().save(source, incoming);
    var older = d.requestOpen(source).prepare();
    d.save(d.path());
    var after = capture(d);
    assertThrows(IllegalStateException.class, () -> d.applyOpen(older));
    retained(d, after);
    d.applyOpen(d.requestOpen(source).prepare());
    assertEquals(99, d.data().project.seed);
  }

  @Test
  void aSameValueSaveInsideAnEditCallbackInvalidatesTheOlderTransactionOwner() throws Exception {
    var files = new Boundary();
    var d = document(files, "stale-edit", false);
    var after = new AtomicReference<State>();
    assertThrows(
        IllegalStateException.class,
        () ->
            d.edit(
                p -> {
                  p.project.seed = 42;
                  try {
                    d.save(d.path());
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                  after.set(capture(d));
                }));
    retained(d, after.get());
    assertEquals(17, d.data().project.seed);
    assertFalse(d.dirty());
  }

  @Test
  void synchronousPublicationFailuresRetainTheirIdentityAcrossEveryCleanupFailureKind()
      throws Exception {
    for (String primaryKind : List.of("IO", "RUNTIME", "ERROR"))
      for (String cleanupKind : List.of("IO", "RUNTIME", "ERROR")) {
        var files = new Boundary();
        var d = document(files, "cleanup-" + primaryKind + "-" + cleanupKind, true);
        var before = capture(d);
        var target = directory.resolve("cleanup-target-" + primaryKind + "-" + cleanupKind);
        Files.writeString(target, "prior target");
        var primary = failure(primaryKind, "publication failed");
        var cleanup = failure(cleanupKind, "cleanup failed");
        files.replaceFailure = primary;
        files.deleteFailure = cleanup;
        try {
          assertSame(primary, assertThrows(Throwable.class, () -> d.save(target)));
          assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
          retained(d, before);
          assertEquals("prior target", Files.readString(target));
          assertEquals(List.of("temporary", "write", "force", "replace", "delete"), files.calls);
          assertTrue(Files.exists(files.temporary));
        } finally {
          Files.deleteIfExists(files.temporary);
        }
        files.replaceFailure = files.deleteFailure = null;
        d.save(target);
        assertEquals(29, seed(target));
        assertFalse(d.dirty());
        noTemporaryFiles();
      }
  }

  @Test
  void identicalPublicationAndCleanupThrowablesCannotSelfSuppressTheOriginalFailure()
      throws Exception {
    for (String kind : List.of("IO", "RUNTIME", "ERROR")) {
      var files = new Boundary();
      var d = document(files, "same-cleanup-" + kind, true);
      var before = capture(d);
      var target = directory.resolve("same-cleanup-target-" + kind);
      Files.writeString(target, "prior target");
      var primary = failure(kind, "reused failure");
      files.replaceFailure = files.deleteFailure = primary;
      try {
        assertSame(primary, assertThrows(Throwable.class, () -> d.save(target)));
        assertEquals(0, primary.getSuppressed().length);
        retained(d, before);
        assertEquals("prior target", Files.readString(target));
        assertTrue(Files.exists(files.temporary));
      } finally {
        Files.deleteIfExists(files.temporary);
      }
      noTemporaryFiles();
    }
  }
}
