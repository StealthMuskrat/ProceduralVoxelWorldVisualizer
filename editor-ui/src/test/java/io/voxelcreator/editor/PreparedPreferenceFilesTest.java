package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred private configuration ownership/fault definitions; no execution during owner pause. */
class PreparedPreferenceFilesTest {
  @TempDir Path directory;

  @Test
  void preparationLeavesDestinationUntouchedUntilSingleAtomicPublication() throws Exception {
    var target = directory.resolve("config.json");
    Files.writeString(target, "previous");
    try (var prepared = new PreferenceFiles().prepare(target, Map.of("value", 3), ".profile-")) {
      assertEquals("previous", Files.readString(target));
      prepared.publish();
      assertEquals(3, io.voxelcreator.model.ProjectIO.JSON.readTree(target.toFile()).path("value").asInt());
      assertThrows(IllegalStateException.class, prepared::publish);
    }
    try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
  }

  @Test
  void closeDisposesUnpublishedSiblingAndCannotPublishOrDeleteAgain() throws Exception {
    var deletes = new AtomicInteger();
    var files = new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) { fail("Cancelled preparation replaced target"); }
      public void delete(Path temp) throws IOException { deletes.incrementAndGet(); Files.delete(temp); }
    });
    var prepared = files.prepare(directory.resolve("config.json"), Map.of("value", 3), ".profile-");
    prepared.close();
    prepared.close();
    assertThrows(IllegalStateException.class, prepared::publish);
    assertEquals(1, deletes.get());
    try (var paths = Files.list(directory)) { assertEquals(0, paths.count()); }
  }

  @Test
  void failedPreparationPreservesPrimaryFaultAndCleanupSuppression() {
    var primary = new IOException("write");
    var cleanup = new AssertionError("cleanup");
    var files = new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { throw primary; }
      public void replace(Path temp, Path target) { fail("Incomplete write published"); }
      public void delete(Path temp) throws IOException { Files.delete(temp); throw cleanup; }
    });
    assertSame(primary, assertThrows(IOException.class,
        () -> files.prepare(directory.resolve("config.json"), Map.of("value", 3), ".profile-")));
    assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
  }

  @Test
  void successfulPublicationNeverUnlinksAReusedTemporaryPath() throws Exception {
    var deleted = new AtomicInteger();
    var files = new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(temp, "new owner");
      }
      public void delete(Path temp) { deleted.incrementAndGet(); }
    });
    try (var prepared = files.prepare(directory.resolve("config.json"), Map.of("value", 3), ".profile-")) {
      prepared.publish();
    }
    assertEquals(0, deleted.get());
    try (var paths = Files.list(directory)) {
      assertEquals(2, paths.count());
    }
  }

  @Test
  void failedReplacementRetainsPreviousBytesAndUnpublishedCleanupIsSingleUse() throws Exception {
    var target = directory.resolve("config.json");
    Files.writeString(target, "previous");
    var files = new PreferenceFiles(new PreferenceFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
      public void replace(Path temp, Path target) throws IOException {
        throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "provider");
      }
    });
    try (var prepared = files.prepare(target, Map.of("value", 3), ".profile-")) {
      assertThrows(IOException.class, prepared::publish);
      assertEquals("previous", Files.readString(target));
    }
    try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
  }
}
