package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred actual-file cleanup faults; no global injection or public test hooks. */
class ArchiveCleanupTest {
  @TempDir Path directory;
  private final VoxelRegistry registry = VoxelRegistry.semantic("stone", "snow");
  private final VoxelChunk chunk = VoxelChunk.uniform(new ChunkPos(-1, 0, -1), 2, 1);

  private static Throwable fault(String kind, String message) {
    return switch (kind) {
      case "IO" -> new IOException(message);
      case "RUNTIME" -> new IllegalStateException(message);
      default -> new AssertionError(message);
    };
  }

  private static void fail(Throwable failure) throws IOException {
    if (failure instanceof IOException checked) throw checked;
    if (failure instanceof RuntimeException unchecked) throw unchecked;
    if (failure instanceof Error error) throw error;
  }

  private static final class OutputFaults implements ArchiveOutput {
    final ArchiveOutput delegate;
    final Path path;
    Throwable header, payload, index, rollback, close;
    boolean obstructDeletion;
    int closes;

    OutputFaults(Path path) throws IOException {
      this.path = path;
      delegate = ArchiveOutput.open(path);
    }

    public long position() throws IOException {
      return delegate.position();
    }

    public void writeLong(long value) throws IOException {
      delegate.writeLong(value);
      var failure = header;
      header = null;
      fail(failure);
    }

    public void writeInt(int value) throws IOException {
      delegate.writeInt(value);
      var failure = index;
      index = null;
      fail(failure);
    }

    public void writeUTF(String value) throws IOException {
      delegate.writeUTF(value);
    }

    public void write(byte[] bytes) throws IOException {
      var failure = payload;
      payload = null;
      if (failure != null) {
        delegate.write(Arrays.copyOf(bytes, Math.max(1, bytes.length / 2)));
        fail(failure);
      } else delegate.write(bytes);
    }

    public void rollback(long offset) throws IOException {
      var failure = rollback;
      rollback = null;
      fail(failure);
      delegate.rollback(offset);
    }

    public void sync() throws IOException {
      delegate.sync();
    }

    public void close() throws IOException {
      closes++;
      delegate.close();
      if (obstructDeletion) {
        obstructDeletion = false;
        Files.delete(path);
        Files.createDirectory(path);
        Files.writeString(path.resolve("keep"), "owned cleanup obstruction");
      }
      var failure = close;
      close = null;
      fail(failure);
    }
  }

  private static final class InputFaults extends RandomAccessFile {
    Throwable opening, closing;
    boolean beforeClose;
    int closes;

    InputFaults(Path path) throws IOException {
      super(path.toFile(), "r");
    }

    @Override
    public long length() throws IOException {
      var failure = opening;
      opening = null;
      fail(failure);
      return super.length();
    }

    @Override
    public void close() throws IOException {
      closes++;
      var failure = closing;
      closing = null;
      if (beforeClose) fail(failure);
      super.close();
      if (!beforeClose) fail(failure);
    }
  }

  private Path valid(String name, VoxelArchive.Format format) throws IOException {
    var path = directory.resolve(name);
    try (var writer = VoxelArchive.openWorldWriter(path, registry, format)) {
      writer.append(chunk);
      writer.finish();
    }
    return path;
  }

  private List<Path> temps() throws IOException {
    try (var paths = Files.list(directory)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(".voxel-archive-"))
          .toList();
    }
  }

  @Test
  void constructorCloseFaultsNeverMaskInitializationAndStillRemoveTheTemporaryFile()
      throws Exception {
    for (String primaryKind : List.of("IO", "RUNTIME", "ERROR"))
      for (String closeKind : List.of("RUNTIME", "ERROR")) {
        var target = directory.resolve("constructor-" + primaryKind + closeKind);
        Files.writeString(target, "original");
        var primary = fault(primaryKind, "header");
        var cleanup = fault(closeKind, "close");
        var observed =
            assertThrows(
                Throwable.class,
                () ->
                    new VoxelArchive.Writer(
                        target,
                        registry,
                        VoxelArchive.Format.PACKED_V2,
                        path -> {
                          var output = new OutputFaults(path);
                          output.header = primary;
                          output.close = cleanup;
                          return output;
                        }));
        assertSame(primary, observed);
        assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
        assertEquals("original", Files.readString(target));
        assertTrue(temps().isEmpty());
      }
  }

  @Test
  void constructorKeepsBothCloseAndDeletionFaultsOnTheOriginalHeaderFailure() throws Exception {
    var target = directory.resolve("constructor-double");
    Files.writeString(target, "original");
    var primary = new IOException("header");
    var cleanup = new AssertionError("close");
    var reference = new AtomicReference<OutputFaults>();
    var observed =
        assertThrows(
            IOException.class,
            () ->
                new VoxelArchive.Writer(
                    target,
                    registry,
                    VoxelArchive.Format.PACKED_V2,
                    path -> {
                      var output = new OutputFaults(path);
                      reference.set(output);
                      output.header = primary;
                      output.close = cleanup;
                      output.obstructDeletion = true;
                      return output;
                    }));
    try {
      assertSame(primary, observed);
      assertSame(cleanup, observed.getSuppressed()[0]);
      assertInstanceOf(DirectoryNotEmptyException.class, observed.getSuppressed()[1]);
      assertEquals("original", Files.readString(target));
    } finally {
      Files.delete(reference.get().path.resolve("keep"));
      Files.delete(reference.get().path);
    }
  }

  @Test
  void uncheckedRollbackFaultsKeepTheWriteFailurePrimaryAndPreventPublication() throws Exception {
    for (var format : VoxelArchive.Format.values())
      for (boolean payload : new boolean[] {true, false})
        for (String kind : List.of("RUNTIME", "ERROR")) {
          var target = directory.resolve("rollback-" + format + payload + kind);
          Files.writeString(target, "original");
          var ref = new AtomicReference<OutputFaults>();
          try (var writer =
              new VoxelArchive.Writer(
                  target,
                  registry,
                  format,
                  path -> {
                    var output = new OutputFaults(path);
                    ref.set(output);
                    return output;
                  })) {
            writer.append(chunk);
            var primary = new IOException("mutation");
            var cleanup = fault(kind, "rollback");
            ref.get().rollback = cleanup;
            if (payload) ref.get().payload = primary;
            else ref.get().index = primary;
            var observed =
                payload
                    ? assertThrows(
                        IOException.class,
                        () -> writer.append(VoxelChunk.uniform(new ChunkPos(1, 0, 1), 2, 2)))
                    : assertThrows(IOException.class, writer::finish);
            assertSame(primary, observed);
            assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
            assertThrows(IOException.class, writer::finish);
            assertThrows(IOException.class, () -> writer.append(chunk));
            assertEquals("original", Files.readString(target));
          }
          assertTrue(temps().isEmpty());
        }
  }

  @Test
  void uncheckedAbortCloseStillDeletesAndAllowsHandleCloseRetry() throws Exception {
    for (String kind : List.of("RUNTIME", "ERROR")) {
      var target = directory.resolve("abort-" + kind);
      Files.writeString(target, "original");
      var ref = new AtomicReference<OutputFaults>();
      var writer =
          new VoxelArchive.Writer(
              target,
              registry,
              VoxelArchive.Format.PACKED_V2,
              path -> {
                var output = new OutputFaults(path);
                ref.set(output);
                return output;
              });
      writer.append(chunk);
      var failure = fault(kind, "close");
      ref.get().close = failure;
      assertSame(failure, assertThrows(Throwable.class, writer::close));
      assertTrue(temps().isEmpty());
      assertEquals("original", Files.readString(target));
      writer.close();
      writer.close();
      assertEquals(2, ref.get().closes);
      assertThrows(IOException.class, writer::finish);
    }
  }

  @Test
  void abortPreservesUncheckedCloseAndDeletionFaultsAndCanRetryBoth() throws Exception {
    for (String kind : List.of("IO", "RUNTIME", "ERROR")) {
      var ref = new AtomicReference<OutputFaults>();
      var target = directory.resolve("abort-double-" + kind);
      Files.writeString(target, "original");
      var writer =
          new VoxelArchive.Writer(
              target,
              registry,
              VoxelArchive.Format.PACKED_V2,
              path -> {
                var output = new OutputFaults(path);
                ref.set(output);
                return output;
              });
      var primary = fault(kind, "close");
      ref.get().close = primary;
      ref.get().obstructDeletion = true;
      assertSame(primary, assertThrows(Throwable.class, writer::close));
      assertInstanceOf(DirectoryNotEmptyException.class, primary.getSuppressed()[0]);
      Files.delete(ref.get().path.resolve("keep"));
      writer.close();
      writer.close();
      assertTrue(temps().isEmpty());
      assertEquals(2, ref.get().closes);
      assertEquals("original", Files.readString(target));
    }
  }

  @Test
  void uncheckedPreparationCloseNeverPublishesAndCleanupDoesNotSelfSuppress() throws Exception {
    for (String kind : List.of("RUNTIME", "ERROR")) {
      var target = directory.resolve("prepared-" + kind);
      Files.writeString(target, "original");
      var ref = new AtomicReference<OutputFaults>();
      var writer =
          new VoxelArchive.Writer(
              target,
              registry,
              VoxelArchive.Format.PACKED_V2,
              path -> {
                var output = new OutputFaults(path);
                ref.set(output);
                return output;
              });
      writer.append(chunk);
      var primary = fault(kind, "prepared-close");
      ref.get().close = primary;
      assertSame(primary, assertThrows(Throwable.class, writer::finish));
      assertThrows(IOException.class, writer::finish);
      assertThrows(IOException.class, () -> writer.append(chunk));
      writer.close();
      assertEquals("original", Files.readString(target));
      assertTrue(temps().isEmpty());
    }
    var same = new IOException("same object");
    assertSame(
        same,
        assertThrows(
            IOException.class,
            () ->
                new VoxelArchive.Writer(
                    directory.resolve("self"),
                    registry,
                    VoxelArchive.Format.PACKED_V2,
                    path -> {
                      var output = new OutputFaults(path);
                      output.header = same;
                      output.close = same;
                      return output;
                    })));
    assertEquals(0, same.getSuppressed().length);
    assertTrue(temps().isEmpty());
  }

  @Test
  void malformedReaderPreservesItsParseErrorAcrossEveryCloseFaultKind() throws Exception {
    for (String kind : List.of("IO", "RUNTIME", "ERROR")) {
      var path = directory.resolve("bad-header-" + kind);
      Files.write(path, new byte[40]);
      var input = new InputFaults(path);
      var cleanup = fault(kind, "reader-close");
      input.closing = cleanup;
      var observed =
          assertThrows(IOException.class, () -> new VoxelArchive.Reader(input, registry));
      assertEquals("Invalid archive signature", observed.getMessage());
      assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
      assertEquals(1, input.closes);
      assertThrows(IOException.class, input::length);
      Files.delete(path);
    }
  }

  @Test
  void uncheckedReaderParseCausesRemainWrappedWithoutBeingMutatedByCleanup() throws Exception {
    for (String kind : List.of("RUNTIME", "ERROR")) {
      var path = valid("bad-input-" + kind, VoxelArchive.Format.PACKED_V2);
      var input = new InputFaults(path);
      var primary = fault(kind, "input");
      var cleanup = new IOException("close");
      input.opening = primary;
      input.closing = cleanup;
      var observed =
          assertThrows(IOException.class, () -> new VoxelArchive.Reader(input, registry));
      assertEquals("Malformed archive", observed.getMessage());
      assertSame(primary, observed.getCause());
      assertArrayEquals(new Throwable[] {cleanup}, observed.getSuppressed());
      assertEquals(0, primary.getSuppressed().length);
      assertThrows(IOException.class, input::length);
    }
  }

  @Test
  void identicalReaderParseAndCloseFailuresAreNeverSelfSuppressedOrDuplicated() throws Exception {
    for (String kind : List.of("IO", "RUNTIME", "ERROR")) {
      var path = valid("reader-self-" + kind, VoxelArchive.Format.PACKED_V2);
      var input = new InputFaults(path);
      var primary = fault(kind, "same-input");
      input.opening = input.closing = primary;
      var observed =
          assertThrows(IOException.class, () -> new VoxelArchive.Reader(input, registry));
      if (primary instanceof IOException) assertSame(primary, observed);
      else assertSame(primary, observed.getCause());
      assertEquals(0, observed.getSuppressed().length);
      assertEquals(0, primary.getSuppressed().length);
    }
  }

  @Test
  void failedReaderCloseRetiresReadsButRetriesTheStillOpenHandle() throws Exception {
    for (String kind : List.of("IO", "RUNTIME", "ERROR")) {
      var path = valid("reader-close-before-" + kind, VoxelArchive.Format.PACKED_V2);
      byte[] before = Files.readAllBytes(path);
      try (var input = new InputFaults(path)) {
        var reader = new VoxelArchive.Reader(input, registry);
        var retained = reader.read(chunk.position()).orElseThrow();
        var dictionary = reader.dictionary();
        var coverage = reader.coverage();
        var primary = fault(kind, "close-before");
        input.closing = primary;
        input.beforeClose = true;
        assertSame(primary, assertThrows(Throwable.class, reader::close));
        assertTrue(input.length() > 0);
        assertThrows(IOException.class, () -> reader.read(chunk.position()));
        assertThrows(IOException.class, () -> reader.read(new ChunkPos(99, 0, 0)));
        reader.close();
        reader.close();
        assertEquals(2, input.closes);
        assertThrows(IOException.class, input::length);
        assertSame(dictionary, reader.dictionary());
        assertEquals(coverage, reader.coverage());
        assertEquals(1, retained.getVoxelTypeId(0, 0, 0));
        assertArrayEquals(before, Files.readAllBytes(path));
      }
    }
  }

  @Test
  void aReaderWhoseHandleAlreadyClosedCanRetryCleanupAndRetainCompletedSnapshots()
      throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var path = valid("reader-close-after-" + format, format);
      var input = new InputFaults(path);
      var reader = new VoxelArchive.Reader(input, registry);
      var retained = reader.read(chunk.position()).orElseThrow();
      input.closing = new AssertionError("close-after");
      assertThrows(AssertionError.class, reader::close);
      assertThrows(IOException.class, input::length);
      reader.close();
      reader.close();
      assertEquals(2, input.closes);
      assertEquals(format.version(), reader.version());
      assertTrue(reader.contains(chunk.position()));
      assertEquals(1, retained.getVoxelTypeId(1, 1, 1));
    }
  }

  @Test
  void aSuccessfulTransferredReaderClosesItsHandleOnlyOnceAndKeepsMetadataImmutable()
      throws Exception {
    var path = valid("reader-owned", VoxelArchive.Format.PACKED_V2);
    var input = new InputFaults(path);
    var reader = new VoxelArchive.Reader(input, registry);
    assertThrows(UnsupportedOperationException.class, () -> reader.coverage().clear());
    assertThrows(UnsupportedOperationException.class, () -> reader.dictionary().clear());
    reader.close();
    reader.close();
    assertEquals(1, input.closes);
    assertThrows(IOException.class, input::length);
    assertThrows(IOException.class, () -> reader.read(chunk.position()));
  }
}
