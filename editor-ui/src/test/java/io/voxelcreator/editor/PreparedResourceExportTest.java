package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred real-file private staging, final active-project guards and cleanup ownership. */
class PreparedResourceExportTest {
  @TempDir Path directory;

  private static void raise(Throwable failure) throws IOException {
    if (failure instanceof IOException e) throw e;
    if (failure instanceof RuntimeException e) throw e;
    if (failure instanceof Error e) throw e;
  }

  private static final class Storage implements ResourcePackageFiles.Storage {
    final List<String> calls = new ArrayList<>();
    Throwable writeFailure, moveFailure, deleteFailure;
    Path temporary;

    public void write(Path temp, byte[] content) throws IOException {
      calls.add("write");
      temporary = temp;
      Files.write(temp, content);
      raise(writeFailure);
    }

    public void replace(Path temp, Path destination) throws IOException {
      calls.add("replace");
      raise(moveFailure);
      Files.move(
          temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(Path temp) throws IOException {
      calls.add("delete");
      raise(deleteFailure);
      Files.deleteIfExists(temp);
    }
  }

  static ProjectData resources() {
    var project = new ProjectData();
    var n = new Noise();
    n.id = "field";
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", .625);
    project.noise2D.add(n);
    return project;
  }

  private void noTemporaryFiles() throws IOException {
    try (var paths = Files.walk(directory)) {
      assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-package-")));
    }
  }

  @Test
  void preparationOwnsPayloadAndDoesNotReplaceUntilExplicitPublication() throws Exception {
    var storage = new Storage();
    var files = new ResourcePackageFiles(storage);
    var target = directory.resolve("资源 package.pvresources.json");
    Files.writeString(target, "prior complete destination");
    var resources = resources();
    var before = ProjectIO.JSON.valueToTree(resources);
    try (var prepared = files.prepare(target, resources, null)) {
      assertEquals(List.of("write"), storage.calls);
      assertEquals("prior complete destination", Files.readString(target));
      resources.noise2D.clear();
      prepared.publish(() -> null);
      assertEquals(before, ProjectIO.JSON.valueToTree(files.read(target)));
      assertThrows(IllegalStateException.class, () -> prepared.publish(() -> null));
    }
    assertEquals(List.of("write", "replace"), storage.calls);
    noTemporaryFiles();
  }

  @Test
  void unpublishedCloseIsIdempotentAndPreventsLaterReplacement() throws Exception {
    var storage = new Storage();
    var files = new ResourcePackageFiles(storage);
    var target = directory.resolve("cancel.pvresources.json");
    Files.writeString(target, "prior");
    var prepared = files.prepare(target, resources(), null);
    prepared.close();
    prepared.close();
    assertThrows(IllegalStateException.class, () -> prepared.publish(() -> null));
    assertEquals("prior", Files.readString(target));
    assertEquals(List.of("write", "delete"), storage.calls);
    noTemporaryFiles();
  }

  @Test
  void failedCloseRetainsExplicitCleanupOwnershipForRetry() throws Exception {
    var storage = new Storage();
    var files = new ResourcePackageFiles(storage);
    var prepared = files.prepare(directory.resolve("close.json"), resources(), null);
    var failure = new IOException("cleanup failed");
    storage.deleteFailure = failure;
    assertSame(failure, assertThrows(IOException.class, prepared::close));
    assertTrue(Files.exists(storage.temporary));
    storage.deleteFailure = null;
    prepared.close();
    noTemporaryFiles();
  }

  @Test
  void unsupportedOrFailedPublicationRetainsThePriorTargetAndCanRetryTheSameBytes()
      throws Exception {
    for (boolean unsupported : List.of(false, true)) {
      var storage = new Storage();
      var files = new ResourcePackageFiles(storage);
      var target = directory.resolve("retry-" + unsupported);
      Files.writeString(target, "prior");
      try (var prepared = files.prepare(target, resources(), null)) {
        storage.moveFailure =
            unsupported
                ? new AtomicMoveNotSupportedException("source", "target", "injected")
                : new IOException("move failed");
        assertThrows(IOException.class, () -> prepared.publish(() -> null));
        assertEquals("prior", Files.readString(target));
        assertEquals(List.of("write", "replace"), storage.calls);
        storage.moveFailure = null;
        prepared.publish(() -> null);
        assertEquals(
            Double.doubleToRawLongBits(.625),
            Double.doubleToRawLongBits(
                files.read(target).noise2D.getFirst().algorithm.parameters.get("value")));
      }
      noTemporaryFiles();
    }
  }

  @Test
  void latestActiveProjectPathProtectsAFileSavedAfterPreparation() throws Exception {
    var files = new ResourcePackageFiles();
    var target = directory.resolve("late-project.json");
    try (var prepared = files.prepare(target, resources(), null)) {
      new ProjectIO().save(target, resources());
      byte[] before = Files.readAllBytes(target);
      assertThrows(IOException.class, () -> prepared.publish(() -> target));
      assertArrayEquals(before, Files.readAllBytes(target));
      assertTrue(new ProjectIO().load(target).success());
    }
    noTemporaryFiles();
  }

  @Test
  void activeProjectAndInvalidResourcesRejectBeforeStaging() throws Exception {
    var storage = new Storage();
    var files = new ResourcePackageFiles(storage);
    var target = directory.resolve("protected.json");
    new ProjectIO().save(target, resources());
    byte[] before = Files.readAllBytes(target);
    assertThrows(IOException.class, () -> files.prepare(target, resources(), target));
    var invalid = resources();
    invalid.noise2D.getFirst().id = "";
    assertThrows(
        IOException.class,
        () -> files.prepare(directory.resolve("missing/result.json"), invalid, null));
    assertTrue(storage.calls.isEmpty());
    assertFalse(Files.exists(directory.resolve("missing")));
    assertArrayEquals(before, Files.readAllBytes(target));
    noTemporaryFiles();
  }

  @Test
  void partialWriteAndCleanupFailuresPreservePrimaryIdentityAndNeverReplace() throws Exception {
    for (boolean identical : List.of(false, true))
      for (Throwable primary :
          List.of(
              new IOException("write"),
              new IllegalStateException("write"),
              new AssertionError("write"))) {
        var storage = new Storage();
        var files = new ResourcePackageFiles(storage);
        var target = directory.resolve("failed.json");
        Files.writeString(target, "prior");
        var cleanup = identical ? primary : new IOException("cleanup");
        storage.writeFailure = primary;
        storage.deleteFailure = cleanup;
        try {
          assertSame(
              primary,
              assertThrows(Throwable.class, () -> files.prepare(target, resources(), null)));
          assertArrayEquals(
              identical ? new Throwable[0] : new Throwable[] {cleanup}, primary.getSuppressed());
          assertEquals("prior", Files.readString(target));
          assertEquals(List.of("write", "delete"), storage.calls);
        } finally {
          Files.deleteIfExists(storage.temporary);
        }
      }
    noTemporaryFiles();
  }

  @Test
  void successfulPublicationDoesNotDeleteANewOwnerOfTheOldTemporaryPath() throws Exception {
    var temporary = new java.util.concurrent.atomic.AtomicReference<Path>();
    var files =
        new ResourcePackageFiles(
            new ResourcePackageFiles.Storage() {
              public void write(Path temp, byte[] content) throws IOException {
                Files.write(temp, content);
              }

              public void replace(Path temp, Path target) throws IOException {
                Files.move(
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(temp, "new owner");
                temporary.set(temp);
              }

              public void delete(Path temp) {
                fail("Cleanup after successful publication");
              }
            });
    var target = directory.resolve("published.json");
    try (var prepared = files.prepare(target, resources(), null)) {
      prepared.publish(() -> null);
    }
    assertEquals("new owner", Files.readString(temporary.get()));
    assertEquals(
        Double.doubleToRawLongBits(.625),
        Double.doubleToRawLongBits(
            files.read(target).noise2D.getFirst().algorithm.parameters.get("value")));
    Files.delete(temporary.get());
    noTemporaryFiles();
  }
}
