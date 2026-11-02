package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deferred actual-file recovery with deterministic faults at the package-private write boundary.
 */
class ArchiveWriterRecoveryTest {
  @TempDir Path directory;
  private final VoxelRegistry registry = VoxelRegistry.semantic("stone", "snow");
  private final VoxelChunk a = VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 1);
  private final VoxelChunk b = VoxelChunk.uniform(new ChunkPos(-1, 0, -1), 4, 2);

  private static final class Faults implements ArchiveOutput {
    final ArchiveOutput delegate;
    boolean payload, index, sync, rollback, close, uncheckedPayload, uncheckedIndex;

    Faults(Path path) throws IOException {
      delegate = ArchiveOutput.open(path);
    }

    public long position() throws IOException {
      return delegate.position();
    }

    public void writeLong(long value) throws IOException {
      delegate.writeLong(value);
    }

    public void writeUTF(String value) throws IOException {
      delegate.writeUTF(value);
    }

    public void writeInt(int value) throws IOException {
      delegate.writeInt(value);
      if (index) {
        index = false;
        throw new IOException("index fault");
      }
      if (uncheckedIndex) {
        uncheckedIndex = false;
        throw new AssertionError("index fatal");
      }
    }

    public void write(byte[] value) throws IOException {
      if (payload || uncheckedPayload) {
        delegate.write(Arrays.copyOf(value, Math.max(1, value.length / 2)));
        if (payload) {
          payload = false;
          throw new IOException("payload fault");
        }
        uncheckedPayload = false;
        throw new AssertionError("payload fatal");
      }
      delegate.write(value);
    }

    public void rollback(long position) throws IOException {
      if (rollback) {
        rollback = false;
        throw new IOException("rollback fault");
      }
      delegate.rollback(position);
    }

    public void sync() throws IOException {
      if (sync) {
        sync = false;
        throw new IOException("sync fault");
      }
      delegate.sync();
    }

    public void close() throws IOException {
      delegate.close();
      if (close) {
        close = false;
        throw new IOException("close fault");
      }
    }
  }

  private VoxelArchive.Writer writer(
      Path target, VoxelArchive.Format format, AtomicReference<Faults> ref) throws IOException {
    return new VoxelArchive.Writer(
        target,
        registry,
        format,
        path -> {
          var io = new Faults(path);
          ref.set(io);
          return io;
        });
  }

  private List<Path> temps() throws IOException {
    try (var files = Files.list(directory)) {
      return files.filter(p -> p.getFileName().toString().startsWith(".voxel-archive-")).toList();
    }
  }

  private void sameArchive(Path actual, VoxelArchive.Format format, VoxelChunk... chunks)
      throws IOException {
    Path reference = directory.resolve("reference-" + format + ".pvworld");
    try (var writer = VoxelArchive.openWorldWriter(reference, registry, format)) {
      for (var chunk : chunks) writer.append(chunk);
      writer.finish();
    }
    assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(actual));
    try (var reader = VoxelArchive.openWorldReader(actual, registry)) {
      assertEquals(format.version(), reader.version());
      for (var chunk : chunks) {
        var decoded = reader.read(chunk.position()).orElseThrow();
        for (int z = 0; z < chunk.size(); z++)
          for (int y = 0; y < chunk.size(); y++)
            for (int x = 0; x < chunk.size(); x++)
              assertEquals(chunk.getVoxelTypeId(x, y, z), decoded.getVoxelTypeId(x, y, z));
      }
    }
    assertTrue(temps().isEmpty());
  }

  @Test
  void outputOpenFailureRemovesTheAlreadyCreatedTemporaryFile() throws Exception {
    Path target = directory.resolve("existing");
    Files.writeString(target, "original");
    var temp = new AtomicReference<Path>();
    var failure =
        assertThrows(
            IOException.class,
            () ->
                new VoxelArchive.Writer(
                    target,
                    registry,
                    VoxelArchive.Format.PACKED_V2,
                    path -> {
                      temp.set(path);
                      throw new IOException("open fault");
                    }));
    assertEquals("open fault", failure.getMessage());
    assertFalse(Files.exists(temp.get()));
    assertEquals("original", Files.readString(target));
  }

  @Test
  void headerFailureKeepsItsPrimaryErrorAndStillDeletesAfterACloseError() throws Exception {
    Path target = directory.resolve("header");
    Files.writeString(target, "original");
    var failure =
        assertThrows(
            IOException.class,
            () ->
                new VoxelArchive.Writer(
                    target,
                    registry,
                    VoxelArchive.Format.PACKED_V2,
                    path -> {
                      var io = new Faults(path);
                      io.index = io.close = true;
                      return io;
                    }));
    assertEquals("index fault", failure.getMessage());
    assertEquals("close fault", failure.getSuppressed()[0].getMessage());
    assertTrue(temps().isEmpty());
    assertEquals("original", Files.readString(target));
  }

  @Test
  void partialPayloadRollbackAllowsTheSamePositionToBeAppendedAgain() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("payload-" + format);
      var ref = new AtomicReference<Faults>();
      try (var writer = writer(target, format, ref)) {
        writer.append(a);
        ref.get().payload = true;
        assertThrows(IOException.class, () -> writer.append(b));
        writer.append(b);
        writer.finish();
      }
      sameArchive(target, format, a, b);
    }
  }

  @Test
  void partialIndexRollbackAllowsMorePayloadBeforeAValidFinishRetry() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("index-" + format);
      Files.writeString(target, "original");
      var ref = new AtomicReference<Faults>();
      try (var writer = writer(target, format, ref)) {
        writer.append(a);
        ref.get().index = true;
        assertThrows(IOException.class, writer::finish);
        assertEquals("original", Files.readString(target));
        writer.append(b);
        writer.finish();
      }
      sameArchive(target, format, a, b);
    }
  }

  @Test
  void syncFailureRollsBackPreparationAndSuccessfulFinishRemainsIdempotent() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("sync-" + format);
      var ref = new AtomicReference<Faults>();
      try (var writer = writer(target, format, ref)) {
        writer.append(a);
        ref.get().sync = true;
        assertThrows(IOException.class, writer::finish);
        assertFalse(Files.exists(target));
        writer.finish();
        writer.finish();
        writer.close();
        writer.finish();
      }
      sameArchive(target, format, a);
    }
  }

  @Test
  void failedRollbackPreventsAnyLaterAppendOrPublication() throws Exception {
    for (var format : VoxelArchive.Format.values())
      for (boolean payload : new boolean[] {false, true}) {
        var target = directory.resolve("rollback-" + format + payload);
        Files.writeString(target, "original");
        var ref = new AtomicReference<Faults>();
        try (var writer = writer(target, format, ref)) {
          writer.append(a);
          ref.get().rollback = true;
          ref.get().payload = payload;
          ref.get().index = !payload;
          IOException failure =
              payload
                  ? assertThrows(IOException.class, () -> writer.append(b))
                  : assertThrows(IOException.class, writer::finish);
          assertEquals("rollback fault", failure.getSuppressed()[0].getMessage());
          assertThrows(IOException.class, () -> writer.append(b));
          assertThrows(IOException.class, writer::finish);
          assertEquals("original", Files.readString(target));
        }
        assertTrue(temps().isEmpty());
      }
  }

  @Test
  void uncheckedMutationFailuresCannotLeaveAPublishableWriter() throws Exception {
    for (boolean payload : new boolean[] {false, true}) {
      var target = directory.resolve("fatal-" + payload);
      Files.writeString(target, "original");
      var ref = new AtomicReference<Faults>();
      try (var writer = writer(target, VoxelArchive.Format.PACKED_V2, ref)) {
        writer.append(a);
        ref.get().uncheckedPayload = payload;
        ref.get().uncheckedIndex = !payload;
        if (payload) assertThrows(AssertionError.class, () -> writer.append(b));
        else assertThrows(AssertionError.class, writer::finish);
        assertThrows(IOException.class, writer::finish);
        assertThrows(IOException.class, () -> writer.append(b));
      }
      assertEquals("original", Files.readString(target));
      assertTrue(temps().isEmpty());
    }
  }

  @Test
  void preparationCloseFailureRequiresAbortEvenIfTheUnderlyingHandleDidClose() throws Exception {
    var target = directory.resolve("prepared-close");
    Files.writeString(target, "original");
    var ref = new AtomicReference<Faults>();
    var writer = writer(target, VoxelArchive.Format.PACKED_V2, ref);
    writer.append(a);
    ref.get().close = true;
    assertEquals("close fault", assertThrows(IOException.class, writer::finish).getMessage());
    assertThrows(IOException.class, writer::finish);
    assertThrows(IOException.class, () -> writer.append(b));
    writer.close();
    writer.close();
    assertTrue(temps().isEmpty());
    assertEquals("original", Files.readString(target));
  }

  @Test
  void abortAttemptsDeletionEvenWhenClosingReportsAnError() throws Exception {
    var target = directory.resolve("abort-close");
    Files.writeString(target, "original");
    var ref = new AtomicReference<Faults>();
    var writer = writer(target, VoxelArchive.Format.PACKED_V2, ref);
    writer.append(a);
    ref.get().close = true;
    assertEquals("close fault", assertThrows(IOException.class, writer::close).getMessage());
    assertTrue(temps().isEmpty());
    writer.close();
    assertThrows(IOException.class, writer::finish);
    assertEquals("original", Files.readString(target));
  }

  @Test
  void publicationFailureRetainsPreparedBytesAndRetryDoesNotRewriteTheIndex() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("publish-" + format);
      Files.createDirectory(target);
      var marker = target.resolve("keep");
      Files.writeString(marker, "original");
      try (var writer = VoxelArchive.openWorldWriter(target, registry, format)) {
        writer.append(a);
        assertThrows(IOException.class, writer::finish);
        assertEquals("original", Files.readString(marker));
        var prepared = Files.readAllBytes(temps().getFirst());
        assertThrows(IOException.class, () -> writer.append(b));
        Files.delete(marker);
        Files.delete(target);
        writer.finish();
        writer.finish();
        assertArrayEquals(prepared, Files.readAllBytes(target));
      }
      sameArchive(target, format, a);
    }
  }

  @Test
  void closingAPreparedButUnpublishedWriterAbortsAndCannotLaterPublish() throws Exception {
    var target = directory.resolve("abort-prepared");
    Files.createDirectory(target);
    var marker = target.resolve("keep");
    Files.writeString(marker, "original");
    var writer = VoxelArchive.openWorldWriter(target, registry);
    writer.append(a);
    assertThrows(IOException.class, writer::finish);
    writer.close();
    writer.close();
    assertThrows(IOException.class, writer::finish);
    assertTrue(temps().isEmpty());
    assertEquals("original", Files.readString(marker));
  }
}
