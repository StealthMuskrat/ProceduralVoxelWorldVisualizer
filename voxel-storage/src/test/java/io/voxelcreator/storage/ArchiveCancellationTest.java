package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Deferred archive/raw decoder cancellation equivalence and explicit same-reader retry. */
class ArchiveCancellationTest {
  @TempDir Path directory;
  private final VoxelRegistry writing = VoxelRegistry.semantic("stone", "snow");
  private final VoxelRegistry reading = VoxelRegistry.semantic("snow", "stone", "unused");
  private static final ChunkPos POS = new ChunkPos(-1, 0, 1), EMPTY = new ChunkPos(2, 0, 1);

  private VoxelChunk pattern(int root) { return VoxelChunk.generate(POS, root, (x, y, z) -> (x + 2 * y + 3 * z) % 3); }
  private void write(Path path, VoxelArchive.Format format, VoxelChunk chunk) throws IOException {
    try (var writer = VoxelArchive.openWorldWriter(path, writing, format)) {
      writer.append(chunk); writer.append(VoxelChunk.uniform(EMPTY, chunk.size(), 0)); writer.finish();
    }
  }
  private void interrupted(Executable action) {
    assertFalse(Thread.currentThread().isInterrupted());
    try {
      Thread.currentThread().interrupt(); assertThrows(CancellationException.class, action);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); }
  }
  private void exact(VoxelChunk chunk) {
    for (int z = 0; z < chunk.size(); z++) for (int y = 0; y < chunk.size(); y++) for (int x = 0; x < chunk.size(); x++) {
      int authored = (x + 2 * y + 3 * z) % 3;
      assertEquals(authored == 0 ? 0 : 3 - authored, chunk.getVoxelTypeId(x, y, z));
    }
  }

  @Test
  void interruptedRawAndArchiveDecodingRetainCancellationTypeAndAllowSameReaderRetry() throws Exception {
    for (var format : VoxelArchive.Format.values()) for (int root : new int[] {2, 4, 8}) {
      var chunk = pattern(root); var path = directory.resolve(format + "-" + root); write(path, format, chunk);
      byte[] baseline = Files.readAllBytes(path); var registry = List.copyOf(reading.semanticIds());
      var bytes = new ByteArrayOutputStream();
      try (var out = new DataOutputStream(bytes)) {
        if (format == VoxelArchive.Format.LEGACY_V1) chunk.writeTree(out, value -> value);
        else chunk.writePackedTree(out, value -> value);
      }
      interrupted(() -> VoxelChunk.readTree(POS, root,
          new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), value -> value));
      VoxelChunk retained;
      try (var reader = VoxelArchive.openWorldReader(path, reading)) {
        retained = reader.read(POS).orElseThrow();
        for (int attempt = 0; attempt < 3; attempt++) {
          interrupted(() -> reader.read(POS)); exact(retained); exact(reader.read(POS).orElseThrow());
          assertEquals(Set.of(POS, EMPTY), reader.coverage()); assertTrue(reader.read(EMPTY).orElseThrow().isEmpty());
        }
      }
      exact(retained); assertEquals(registry, reading.semanticIds()); assertArrayEquals(baseline, Files.readAllBytes(path));
    }
  }

  private static final class InterruptingInput extends RandomAccessFile {
    boolean armed;
    int interruptions, closes;
    InterruptingInput(Path path) throws IOException { super(path.toFile(), "r"); }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      int count = super.read(bytes, offset, length);
      if (armed && count > 0) { armed = false; interruptions++; Thread.currentThread().interrupt(); }
      return count;
    }
    @Override public void close() throws IOException { closes++; super.close(); }
  }

  @Test
  void interruptionAfterPayloadTransferStillEscapesTheCorruptionWrapperAndKeepsHandleOwnership() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var path = directory.resolve("transfer-" + format); write(path, format, pattern(8));
      try (var input = new InterruptingInput(path)) {
        try (var reader = new VoxelArchive.Reader(input, reading)) {
          input.armed = true;
          try {
            assertThrows(CancellationException.class, () -> reader.read(POS));
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(1, input.interruptions); assertEquals(0, input.closes);
          } finally { Thread.interrupted(); }
          exact(reader.read(POS).orElseThrow());
        }
        assertEquals(1, input.closes);
      }
    }
  }

  @Test
  void singleChunkHelperClosesAfterCancellationAndCanReopenUnchangedBytes() throws Exception {
    var path = directory.resolve("single.pvworld"); VoxelArchive.writeSingleChunk(path, pattern(4), writing);
    byte[] baseline = Files.readAllBytes(path);
    interrupted(() -> VoxelArchive.readSingleChunk(path, reading));
    var moved = directory.resolve("moved.pvworld"); Files.move(path, moved);
    var retained = VoxelArchive.readSingleChunk(moved, reading); exact(retained);
    assertArrayEquals(baseline, Files.readAllBytes(moved));
  }

  @Test
  void cancellationDoesNotRepairCorruptionAndOtherCoverageRemainsReadable() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var path = directory.resolve("corrupt-" + format); write(path, format, pattern(4));
      byte[] broken = Files.readAllBytes(path); var buffer = ByteBuffer.wrap(broken);
      int index = Math.toIntExact(buffer.getLong(broken.length - 16));
      int payload = Math.toIntExact(buffer.getLong(index + 4 + 16)); // POS sorts before EMPTY.
      broken[payload] = broken[payload + 1] = 0; Files.write(path, broken);
      try (var reader = VoxelArchive.openWorldReader(path, reading)) {
        interrupted(() -> reader.read(POS));
        assertThrows(IOException.class, () -> reader.read(POS)); assertTrue(reader.read(EMPTY).orElseThrow().isEmpty());
        assertTrue(reader.contains(POS)); assertEquals(2, reader.coverage().size());
      }
      assertArrayEquals(broken, Files.readAllBytes(path));
    }
  }

  @Test
  void cancelledAppendCanRetryWithoutChangingDeterministicArchiveBytes() throws Exception {
    for (var format : VoxelArchive.Format.values()) {
      var path = directory.resolve("retry-" + format); byte[] old = {4, 3, 2, 1}; Files.write(path, old);
      var chunk = pattern(4);
      try (var writer = VoxelArchive.openWorldWriter(path, writing, format)) {
        interrupted(() -> writer.append(chunk)); assertArrayEquals(old, Files.readAllBytes(path));
        writer.append(chunk); writer.append(VoxelChunk.uniform(EMPTY, 4, 0)); writer.finish();
      }
      var control = directory.resolve("control-" + format); write(control, format, pattern(4));
      assertArrayEquals(Files.readAllBytes(control), Files.readAllBytes(path));
      try (var reader = VoxelArchive.openWorldReader(path, reading)) { exact(reader.read(POS).orElseThrow()); }
    }
  }

  @Test
  void cancelledSingleWriteAbortsOwnedStagingAndPreservesPreviousDestination() throws Exception {
    var path = directory.resolve("retained.pvworld"); byte[] old = {9, 8, 7}; Files.write(path, old);
    var chunk = pattern(4);
    Set<Path> before; try (var paths = Files.list(directory)) { before = new HashSet<>(paths.toList()); }
    interrupted(() -> VoxelArchive.writeSingleChunk(path, chunk, writing));
    assertArrayEquals(old, Files.readAllBytes(path));
    try (var paths = Files.list(directory)) { assertEquals(before, new HashSet<>(paths.toList())); }
  }
}
