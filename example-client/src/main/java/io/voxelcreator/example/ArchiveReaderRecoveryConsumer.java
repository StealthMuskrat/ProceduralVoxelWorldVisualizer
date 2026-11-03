package io.voxelcreator.example;

import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/**
 * Deferred public engine-JAR archive read/close/corrupt-input recovery with exact retained cells.
 */
public final class ArchiveReaderRecoveryConsumer {
  private record Retained(VoxelChunk chunk, int kind) {}

  public static void main(String[] args) throws Exception {
    run(Path.of(args[0]).toAbsolutePath());
  }

  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var writing = VoxelRegistry.semantic("stone", "snow");
    var reading = VoxelRegistry.semantic("snow", "stone", "unused");
    var registryBefore = List.copyOf(reading.semanticIds());
    var pattern =
        VoxelChunk.generate(new ChunkPos(-2, 1, -1), 4, (x, y, z) -> (x + 2 * y + 3 * z) % 3);
    var empty = VoxelChunk.uniform(new ChunkPos(-1, -1, -1), 2, 0);
    var uniform = VoxelChunk.uniform(new ChunkPos(1, -1, 2), 8, 1);
    var retained = new ArrayList<Retained>();
    int rejectedHeaders = 0, rejectedPayloads = 0, closedReads = 0;
    for (var format : VoxelArchive.Format.values()) {
      var valid = out.resolve("reader-" + format + ".pvworld");
      try (var writer = VoxelArchive.openWorldWriter(valid, writing, format)) {
        writer.append(pattern);
        writer.append(empty);
        writer.append(uniform);
        writer.finish();
      }
      byte[] baseline = Files.readAllBytes(valid);
      var reader = VoxelArchive.openWorldReader(valid, reading);
      try {
        require(reader.version() == format.version(), "Archive version changed");
        require(
            reader
                .coverage()
                .equals(Set.of(pattern.position(), empty.position(), uniform.position())),
            "Coverage changed");
        require(
            reader.dictionary().equals(List.of("EMPTY", "stone", "snow")),
            "Semantic dictionary changed");
        require(
            !reader.hasStoredPayload(empty.position())
                && reader.read(new ChunkPos(99, 0, 99)).isEmpty(),
            "EMPTY and unarchived coverage were confused");
        retained.add(new Retained(reader.read(pattern.position()).orElseThrow(), 0));
        retained.add(new Retained(reader.read(empty.position()).orElseThrow(), 1));
        retained.add(new Retained(reader.read(uniform.position()).orElseThrow(), 2));
        reader.close();
        reader.close();
        for (var position : List.of(pattern.position(), new ChunkPos(99, 0, 99))) {
          try {
            reader.read(position);
            throw new AssertionError("Closed reader accepted a read");
          } catch (IOException expected) {
            require(expected.getMessage().contains("closed"), "Closed read lost its diagnostic");
            closedReads++;
          }
        }
        require(
            reader.contains(pattern.position())
                && reader.dictionary().equals(List.of("EMPTY", "stone", "snow")),
            "Closing discarded immutable metadata");
      } finally {
        reader.close();
      }

      for (boolean signature : new boolean[] {true, false}) {
        byte[] broken = baseline.clone();
        if (signature) broken[0] ^= 0x7f;
        else ByteBuffer.wrap(broken).putInt(8, 77);
        var rejected = out.resolve("rejected-" + format + "-" + signature + ".pvworld");
        Files.write(rejected, broken);
        try {
          try (var unexpected = VoxelArchive.openWorldReader(rejected, reading)) {
            throw new AssertionError("Malformed header opened");
          }
        } catch (IOException expected) {
          require(
              expected.getMessage().contains(signature ? "signature" : "version"),
              "Parse failure was replaced by cleanup");
          rejectedHeaders++;
        }
        require(
            Arrays.equals(broken, Files.readAllBytes(rejected)),
            "Failed construction changed input bytes");
        Files.delete(rejected);
      }

      byte[] broken = baseline.clone();
      var bytes = ByteBuffer.wrap(broken);
      int index = Math.toIntExact(bytes.getLong(broken.length - 16));
      int entries = bytes.getInt(index);
      boolean changed = false;
      for (int i = 0; i < entries; i++) {
        int at = index + 4 + 28 * i;
        var pos = new ChunkPos(bytes.getInt(at), bytes.getInt(at + 4), bytes.getInt(at + 8));
        if (pos.equals(pattern.position())) {
          int offset = Math.toIntExact(bytes.getLong(at + 16));
          require(bytes.getInt(at + 24) >= 2, "Pattern payload is unexpectedly short");
          broken[offset] = broken[offset + 1] = 0; // Invalid zlib compression method/header.
          changed = true;
        }
      }
      require(changed, "Pattern index entry missing");
      var corrupt = out.resolve("corrupt-payload-" + format + ".pvworld");
      Files.write(corrupt, broken);
      try (var isolated = VoxelArchive.openWorldReader(corrupt, reading)) {
        try {
          isolated.read(pattern.position());
          throw new AssertionError("Corrupt payload exposed a chunk");
        } catch (IOException expected) {
          rejectedPayloads++;
        }
        retained.add(new Retained(isolated.read(empty.position()).orElseThrow(), 1));
        retained.add(new Retained(isolated.read(uniform.position()).orElseThrow(), 2));
        require(
            isolated.coverage().size() == 3 && isolated.contains(pattern.position()),
            "One corrupt payload altered coverage");
      }
      require(
          Arrays.equals(broken, Files.readAllBytes(corrupt))
              && Arrays.equals(baseline, Files.readAllBytes(valid)),
          "Read/close changed archive bytes");
      try (var reopened = VoxelArchive.openWorldReader(valid, reading)) {
        require(
            reopened.read(pattern.position()).orElseThrow().getVoxelTypeId(1, 0, 0) == 2,
            "Valid archive could not reopen after failures");
      }
    }
    long cells = 0;
    for (var value : retained) {
      var chunk = value.chunk();
      for (int z = 0; z < chunk.size(); z++)
        for (int y = 0; y < chunk.size(); y++)
          for (int x = 0; x < chunk.size(); x++) {
            int authored = (x + 2 * y + 3 * z) % 3;
            int expected =
                value.kind() == 1
                    ? 0
                    : value.kind() == 2 ? 2 : authored == 0 ? 0 : authored == 1 ? 2 : 1;
            require(
                chunk.getVoxelTypeId(x, y, z) == expected,
                "Retained exact snapshot changed after reader close/failure");
            cells++;
          }
    }
    require(
        cells == 2208
            && retained.size() == 10
            && rejectedHeaders == 4
            && rejectedPayloads == 2
            && closedReads == 4,
        "Unexpected reader recovery replay dimensions");
    require(
        registryBefore.equals(reading.semanticIds()),
        "Archive reader changed the supplied registry");
    Files.writeString(
        out.resolve("archive-reader-recovery.json"),
        "{\"formatVersion\":1,\"formats\":%d,\"retainedChunks\":%d,\"retainedCells\":%d,\"rejectedHeaders\":%d,\"rejectedPayloads\":%d,\"closedReads\":%d}\n"
            .formatted(
                VoxelArchive.Format.values().length,
                retained.size(),
                cells,
                rejectedHeaders,
                rejectedPayloads,
                closedReads));
    System.out.println("ARCHIVE_READER_RECOVERY_CONSUMER_PASS");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
