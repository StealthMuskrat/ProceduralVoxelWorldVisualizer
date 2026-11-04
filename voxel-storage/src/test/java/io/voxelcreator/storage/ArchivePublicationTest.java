package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred atomic-only publication and exact closed-preparation ownership across failure/retry. */
class ArchivePublicationTest {
  @TempDir Path directory;
  private final VoxelRegistry registry = VoxelRegistry.semantic("stone", "snow");
  private final VoxelChunk original = VoxelChunk.uniform(new ChunkPos(-2, 1, -3), 4, 1);
  private final VoxelChunk replacement =
      VoxelChunk.generate(new ChunkPos(1, -1, 2), 4, (x, y, z) -> (x + 2 * y + 3 * z) % 3);

  private static final class Counted implements ArchiveOutput {
    final ArchiveOutput output;
    int writes, syncs, closes;
    boolean closed;

    Counted(Path file) throws IOException {
      output = ArchiveOutput.open(file);
    }

    public long position() throws IOException {
      return output.position();
    }

    public void writeInt(int value) throws IOException {
      writes++;
      output.writeInt(value);
    }

    public void writeLong(long value) throws IOException {
      writes++;
      output.writeLong(value);
    }

    public void writeUTF(String value) throws IOException {
      writes++;
      output.writeUTF(value);
    }

    public void write(byte[] value) throws IOException {
      writes++;
      output.write(value);
    }

    public void rollback(long value) throws IOException {
      output.rollback(value);
    }

    public void sync() throws IOException {
      syncs++;
      output.sync();
    }

    public void close() throws IOException {
      closes++;
      output.close();
      closed = true;
    }
  }

  private VoxelArchive.Writer writer(
      Path path,
      VoxelArchive.Format format,
      AtomicReference<Counted> output,
      ArchivePublication publication)
      throws IOException {
    return new VoxelArchive.Writer(
        path,
        registry,
        format,
        temporary -> {
          var counted = new Counted(temporary);
          output.set(counted);
          return counted;
        },
        publication);
  }

  private byte[] baseline(Path path, VoxelArchive.Format format) throws IOException {
    try (var writer = VoxelArchive.openWorldWriter(path, registry, format)) {
      writer.append(original);
      writer.finish();
    }
    return Files.readAllBytes(path);
  }

  private List<Path> temporaries() throws IOException {
    try (var files = Files.list(directory)) {
      return files.filter(p -> p.getFileName().toString().startsWith(".voxel-archive-")).toList();
    }
  }

  private void exact(Path path, VoxelArchive.Format format, VoxelChunk expected)
      throws IOException {
    try (var reader = VoxelArchive.openWorldReader(path, registry)) {
      assertEquals(format.version(), reader.version());
      assertEquals(Set.of(expected.position()), reader.coverage());
      var decoded = reader.read(expected.position()).orElseThrow();
      for (int z = 0; z < expected.size(); z++)
        for (int y = 0; y < expected.size(); y++)
          for (int x = 0; x < expected.size(); x++)
            assertEquals(expected.getVoxelTypeId(x, y, z), decoded.getVoxelTypeId(x, y, z));
    }
  }

  @Test
  void unsupportedAtomicReplacementPreservesExistingArchiveAndReadablePreparedBytes()
      throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("unsupported-" + format);
      var old = baseline(target, format);
      var output = new AtomicReference<Counted>();
      var prepared = new AtomicReference<Path>();
      var failure = new AtomicMoveNotSupportedException("temporary", "destination", "injected");
      var attempts = new AtomicInteger();
      try (var writer =
          writer(
              target,
              format,
              output,
              (temporary, destination) -> {
                attempts.incrementAndGet();
                prepared.set(temporary);
                assertEquals(target.toAbsolutePath(), destination);
                assertTrue(output.get().closed);
                throw failure;
              })) {
        writer.append(replacement);
        var issue = assertThrows(IOException.class, writer::finish);
        assertSame(failure, issue.getCause());
        assertTrue(issue.getMessage().contains("atomic replacement"));
        assertEquals(1, attempts.get());
        assertArrayEquals(old, Files.readAllBytes(target));
        assertEquals(1, output.get().syncs);
        assertEquals(1, output.get().closes);
        exact(target, format, original);
        exact(prepared.get(), format, replacement);
        assertThrows(IOException.class, () -> writer.append(original));
      }
      assertFalse(Files.exists(prepared.get()));
      assertArrayEquals(old, Files.readAllBytes(target));
    }
  }

  @Test
  void unsupportedPublicationOfANewArchiveKeepsTheDestinationAbsentUntilAbort() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("absent-" + format);
      var output = new AtomicReference<Counted>();
      try (var writer =
          writer(
              target,
              format,
              output,
              (temporary, destination) -> {
                throw new AtomicMoveNotSupportedException(
                    temporary.toString(), destination.toString(), "unsupported");
              })) {
        writer.append(replacement);
        assertThrows(IOException.class, writer::finish);
        assertFalse(Files.exists(target));
        assertEquals(1, temporaries().size());
      }
      assertFalse(Files.exists(target));
      assertTrue(temporaries().isEmpty());
    }
  }

  @Test
  void repeatedPublicationFailuresRetryExactlyTheSameBytesWithoutRewritingOrResyncing()
      throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("retry-" + format);
      var old = baseline(target, format);
      var output = new AtomicReference<Counted>();
      var attempts = new AtomicInteger();
      var bytes = new AtomicReference<byte[]>();
      try (var writer =
          writer(
              target,
              format,
              output,
              (temporary, destination) -> {
                var current = Files.readAllBytes(temporary);
                if (bytes.get() == null) bytes.set(current);
                else assertArrayEquals(bytes.get(), current);
                if (attempts.incrementAndGet() < 3)
                  throw new AccessDeniedException(destination.toString());
                ArchivePublication.atomic(temporary, destination);
              })) {
        writer.append(replacement);
        assertThrows(IOException.class, writer::finish);
        int writes = output.get().writes;
        assertThrows(IOException.class, writer::finish);
        assertArrayEquals(old, Files.readAllBytes(target));
        writer.finish();
        writer.finish();
        writer.close();
        writer.finish();
        assertEquals(3, attempts.get());
        assertEquals(writes, output.get().writes);
        assertEquals(1, output.get().syncs);
        assertEquals(1, output.get().closes);
        assertArrayEquals(bytes.get(), Files.readAllBytes(target));
      }
      exact(target, format, replacement);
      assertTrue(temporaries().isEmpty());
    }
  }

  @Test
  void unsupportedAttemptMayRetryAtomicallyWhenThePublicationBoundaryRecovers() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var target = directory.resolve("recover-" + format);
      var output = new AtomicReference<Counted>();
      var attempts = new AtomicInteger();
      try (var writer =
          writer(
              target,
              format,
              output,
              (temporary, destination) -> {
                if (attempts.getAndIncrement() == 0)
                  throw new AtomicMoveNotSupportedException(
                      temporary.toString(), destination.toString(), "transient capability");
                ArchivePublication.atomic(temporary, destination);
              })) {
        writer.append(replacement);
        assertThrows(IOException.class, writer::finish);
        writer.finish();
        assertEquals(2, attempts.get());
      }
      exact(target, format, replacement);
    }
  }

  @Test
  void uncheckedPublicationFailuresRetainPreparedOwnershipWithoutTouchingTheDestination()
      throws Exception {
    for (var failure :
        List.<Throwable>of(new IllegalStateException("rejected"), new AssertionError("fatal"))) {
      var target = directory.resolve(failure.getClass().getSimpleName());
      var old = baseline(target, VoxelArchive.Format.PACKED_V2);
      var output = new AtomicReference<Counted>();
      var attempts = new AtomicInteger();
      try (var writer =
          writer(
              target,
              VoxelArchive.Format.PACKED_V2,
              output,
              (temporary, destination) -> {
                if (attempts.getAndIncrement() == 0) {
                  if (failure instanceof RuntimeException runtime) throw runtime;
                  throw (Error) failure;
                }
                ArchivePublication.atomic(temporary, destination);
              })) {
        writer.append(replacement);
        assertSame(failure, assertThrows(failure.getClass(), writer::finish));
        assertArrayEquals(old, Files.readAllBytes(target));
        assertThrows(IOException.class, () -> writer.append(original));
        writer.finish();
      }
      exact(target, VoxelArchive.Format.PACKED_V2, replacement);
    }
  }

  @Test
  void abortAfterFailedReplacementDeletesOnlyTheOwnedTemporaryAndCannotPublishLater()
      throws Exception {
    var target = directory.resolve("abort");
    var old = baseline(target, VoxelArchive.Format.PACKED_V2);
    var output = new AtomicReference<Counted>();
    var attempts = new AtomicInteger();
    var writer =
        writer(
            target,
            VoxelArchive.Format.PACKED_V2,
            output,
            (temporary, destination) -> {
              attempts.incrementAndGet();
              throw new IOException("denied");
            });
    writer.append(replacement);
    assertThrows(IOException.class, writer::finish);
    writer.close();
    writer.close();
    assertEquals(1, attempts.get());
    assertTrue(temporaries().isEmpty());
    assertArrayEquals(old, Files.readAllBytes(target));
    assertThrows(IOException.class, writer::finish);
    assertEquals(1, output.get().closes);
  }

  @Test
  void successfulPublicationIsTerminalEvenIfTheDestinationIsReplacedLater() throws Exception {
    var target = directory.resolve("terminal");
    var output = new AtomicReference<Counted>();
    var attempts = new AtomicInteger();
    var writer =
        writer(
            target,
            VoxelArchive.Format.PACKED_V2,
            output,
            (temporary, destination) -> {
              attempts.incrementAndGet();
              ArchivePublication.atomic(temporary, destination);
            });
    writer.append(replacement);
    writer.finish();
    Files.writeString(target, "later owner");
    writer.finish();
    writer.close();
    writer.close();
    writer.finish();
    assertEquals("later owner", Files.readString(target));
    assertEquals(1, attempts.get());
    assertEquals(1, output.get().closes);
    assertTrue(temporaries().isEmpty());
  }

  @Test
  void emptyCoverageAndEmptyArchivesStillUseAtomicOnlyPublication() throws Exception {
    for (boolean covered : List.of(false, true)) {
      var target = directory.resolve("empty-" + covered);
      var output = new AtomicReference<Counted>();
      try (var writer =
          writer(
              target,
              VoxelArchive.Format.PACKED_V2,
              output,
              (temporary, destination) -> {
                throw new AtomicMoveNotSupportedException(
                    temporary.toString(), destination.toString(), "unsupported");
              })) {
        if (covered) writer.append(VoxelChunk.uniform(new ChunkPos(-1, 0, 1), 256, 0));
        assertThrows(IOException.class, writer::finish);
        assertFalse(Files.exists(target));
        try (var reader = VoxelArchive.openWorldReader(temporaries().getFirst(), registry)) {
          assertEquals(covered ? 1 : 0, reader.coverage().size());
          if (covered) assertFalse(reader.hasStoredPayload(new ChunkPos(-1, 0, 1)));
        }
      }
    }
    assertTrue(temporaries().isEmpty());
  }

  @Test
  void publicationCanInspectTheWholeClosedSortedArchiveBeforeItsSingleAtomicMove()
      throws Exception {
    var target = directory.resolve("sorted");
    var output = new AtomicReference<Counted>();
    try (var writer =
        writer(
            target,
            VoxelArchive.Format.PACKED_V2,
            output,
            (temporary, destination) -> {
              assertTrue(output.get().closed);
              assertEquals(1, output.get().syncs);
              try (var reader = VoxelArchive.openWorldReader(temporary, registry)) {
                assertEquals(
                    List.of(original.position(), replacement.position()),
                    List.copyOf(reader.coverage()));
              }
              ArchivePublication.atomic(temporary, destination);
            })) {
      writer.append(replacement);
      writer.append(original);
      writer.finish();
    }
  }

  @Test
  void aNullPublicationBoundaryRejectsBeforeCreatingAnyTemporaryFile() {
    assertThrows(
        NullPointerException.class,
        () ->
            new VoxelArchive.Writer(
                directory.resolve("null"),
                registry,
                VoxelArchive.Format.PACKED_V2,
                ArchiveOutput::open,
                null));
    assertDoesNotThrow(() -> assertTrue(temporaries().isEmpty()));
  }
}
