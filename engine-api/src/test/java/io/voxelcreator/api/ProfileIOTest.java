package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.ChunkPos;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileIOTest {
  @TempDir Path directory;

  private static Profiler.Snapshot capture() {
    try (var p = new Profiler()) {
      var c = p.start(Profiler.Options.defaults());
      p.scope(17, new UUID(0, 3), new ChunkPos(-2, 0, 1))
          .gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 123, "comma,quote\"\r\nUnicode 雪");
      p.stop();
      return c.snapshot();
    }
  }

  @Test
  void jsonPreservesEnvelopeSamplesSummaryAndUnicodeAndReplacesExplicitly() throws Exception {
    var snapshot = capture();
    Path output = directory.resolve("capture.json");
    Files.writeString(output, "old");
    ProfileIO.writeJSON(output, snapshot);
    var value = ProjectIO.JSON.readTree(output.toFile());
    assertEquals(1, value.path("formatVersion").intValue());
    assertEquals(snapshot.captureId().toString(), value.path("captureId").textValue());
    assertEquals(snapshot.startedAt().toString(), value.path("startedAt").textValue());
    assertEquals("STOPPED", value.path("state").textValue());
    assertEquals(123, value.path("samples").get(0).path("value").longValue());
    assertEquals(
        snapshot.samples().getFirst().label(),
        value.path("samples").get(0).path("label").textValue());
    assertEquals(-2, value.path("samples").get(0).path("chunk").path("x").intValue());
    assertEquals(123, value.path("series").get(0).path("p99").longValue());
    assertEquals(
        Thread.currentThread().threadId(),
        value.path("samples").get(0).path("threadId").longValue());
    try (var files = Files.list(directory)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void csvQuotesMultilineFieldsAndRetainsCaptureAndThreadMetadata() throws Exception {
    var snapshot = capture();
    Path output = directory.resolve("capture.csv");
    ProfileIO.writeCSV(output, snapshot);
    String csv = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(csv.startsWith("capture_id,state,started_at,context_id,"));
    assertTrue(csv.contains("chunk_x,chunk_y,chunk_z,thread_id,thread_name\r\n"));
    assertTrue(csv.contains("\"comma,quote\"\"\r\nUnicode 雪\""));
    assertTrue(csv.contains(snapshot.captureId() + ",STOPPED,"));
    assertTrue(csv.endsWith("\r\n"));
  }

  @Test
  void failedReplacementPreservesDestinationAndCleansTemporaryFile() throws Exception {
    Path destination = directory.resolve("keep");
    Files.createDirectory(destination);
    Files.writeString(destination.resolve("sentinel"), "retained");
    assertThrows(java.io.IOException.class, () -> ProfileIO.writeJSON(destination, capture()));
    assertEquals("retained", Files.readString(destination.resolve("sentinel")));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(destination), files.toList());
    }
  }

  @Test
  void unsupportedAtomicReplacementPreservesOldJsonAndCsvWithoutFallback() throws Exception {
    for (boolean csv : new boolean[] {false, true}) {
      Path target = directory.resolve(csv ? "capture.csv" : "capture.json");
      Files.writeString(target, "prior complete file");
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      ProfileIO.Replacement unsupported =
          (temporary, destination) -> {
            calls.incrementAndGet();
            assertEquals(target, destination);
            assertTrue(Files.size(temporary) > 0);
            throw new AtomicMoveNotSupportedException(
                temporary.toString(), destination.toString(), "fixture");
          };
      if (csv)
        assertThrows(
            java.io.IOException.class, () -> ProfileIO.writeCSV(target, capture(), unsupported));
      else
        assertThrows(
            java.io.IOException.class, () -> ProfileIO.writeJSON(target, capture(), unsupported));
      assertEquals(1, calls.get());
      assertEquals("prior complete file", Files.readString(target));
      try (var files = Files.list(directory)) {
        assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".profile-")));
      }
    }
  }

  @Test
  void runtimePublicationFailureCleansTemporaryContentAndRetainsDestination() throws Exception {
    Path target = directory.resolve("capture.json");
    Files.writeString(target, "prior complete file");
    var failure = new IllegalStateException("publication rejected");
    var caught =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProfileIO.writeJSON(
                    target,
                    capture(),
                    (temporary, destination) -> {
                      throw failure;
                    }));
    assertSame(failure, caught);
    assertEquals("prior complete file", Files.readString(target));
    try (var files = Files.list(directory)) {
      assertEquals(List.of(target), files.toList());
    }
  }

  @Test
  void rootDestinationRejectsBeforeCreatingTemporaryOutput() {
    Path root = directory.toAbsolutePath().getRoot();
    var error = assertThrows(java.io.IOException.class, () -> ProfileIO.writeJSON(root, capture()));
    assertTrue(error.getMessage().contains("file path"));
  }
}
