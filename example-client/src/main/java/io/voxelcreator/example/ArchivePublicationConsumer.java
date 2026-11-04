package io.voxelcreator.example;

import io.voxelcreator.storage.*;
import java.nio.file.*;
import java.util.*;

/** Deferred public engine-only replay of explicit replacement, abort and terminal ownership. */
final class ArchivePublicationConsumer {
  static void run(Path out) throws Exception {
    Files.createDirectories(out);
    var writerRegistry = VoxelRegistry.semantic("stone", "snow");
    var readerRegistry = VoxelRegistry.semantic("snow", "stone", "unused");
    var names = List.copyOf(readerRegistry.semanticIds());
    var position = new ChunkPos(-3, 1, -2);
    var original = VoxelChunk.uniform(position, 4, 1);
    var replacement = VoxelChunk.generate(position, 4, (x, y, z) -> (x + 2 * y + 3 * z) % 3);
    var empty = VoxelChunk.uniform(new ChunkPos(2, -1, 3), 2, 0);
    long cells = 0;
    var cases = new ArrayList<Map<String, Object>>();
    for (var format : VoxelArchive.Format.values()) {
      var target = out.resolve("replacement-" + format + ".pvworld");
      try (var baseline = VoxelArchive.openWorldWriter(target, writerRegistry, format)) {
        baseline.append(original);
        baseline.finish();
      }
      byte[] originalBytes = Files.readAllBytes(target);
      var writer = VoxelArchive.openWorldWriter(target, writerRegistry, format);
      try {
        writer.append(replacement);
        writer.append(empty);
        require(
            Arrays.equals(originalBytes, Files.readAllBytes(target)),
            "Append replaced the prior archive");
        try (var reader = VoxelArchive.openWorldReader(target, readerRegistry)) {
          require(
              reader.version() == format.version() && reader.coverage().equals(Set.of(position)),
              "Unpublished candidate changed prior coverage");
          var decoded = reader.read(position).orElseThrow();
          for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4; y++)
              for (int x = 0; x < 4; x++) {
                require(
                    decoded.getVoxelTypeId(x, y, z) == 2,
                    "Prior archive was not independently readable");
                cells++;
              }
        }
        // No reader handle spans replacement: physical provider sharing/locking is separate.
        writer.finish();
        writer.finish();
        writer.close();
        writer.finish();
      } finally {
        writer.close();
      }
      byte[] published = Files.readAllBytes(target);
      var reference = out.resolve("reference-" + format + ".pvworld");
      try (var fresh = VoxelArchive.openWorldWriter(reference, writerRegistry, format)) {
        fresh.append(replacement);
        fresh.append(empty);
        fresh.finish();
      }
      require(
          Arrays.equals(published, Files.readAllBytes(reference)),
          "Atomic replacement changed canonical archive bytes");
      VoxelChunk retained;
      try (var reader = VoxelArchive.openWorldReader(target, readerRegistry)) {
        require(
            reader.version() == format.version()
                && reader.coverage().equals(Set.of(position, empty.position()))
                && !reader.hasStoredPayload(empty.position()),
            "Replacement lost exact sparse coverage");
        retained = reader.read(position).orElseThrow();
        for (int z = 0; z < 4; z++)
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int authored = (x + 2 * y + 3 * z) % 3;
              int expected = authored == 0 ? 0 : authored == 1 ? 2 : 1;
              require(
                  retained.getVoxelTypeId(x, y, z) == expected,
                  "Replacement changed independent pattern/semantic remapping");
              cells++;
            }
        var decodedEmpty = reader.read(empty.position()).orElseThrow();
        for (int z = 0; z < 2; z++)
          for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++) {
              require(
                  decodedEmpty.getVoxelTypeId(x, y, z) == 0,
                  "Omitted payload lost EMPTY semantics");
              cells++;
            }
      }
      try (var aborted = VoxelArchive.openWorldWriter(target, writerRegistry, format)) {
        aborted.append(original);
      }
      require(
          Arrays.equals(published, Files.readAllBytes(target)),
          "Abort changed the published archive");
      // A completed writer must not perform IO against a path subsequently owned by another writer.
      var terminal = out.resolve("terminal-" + format + ".pvworld");
      try (var completed = VoxelArchive.openWorldWriter(terminal, writerRegistry, format)) {
        completed.append(original);
        completed.finish();
        Files.write(terminal, published);
        completed.finish();
        completed.close();
        completed.finish();
      }
      require(
          Arrays.equals(published, Files.readAllBytes(terminal)),
          "Terminal writer overwrote a later owner");
      require(
          retained.getVoxelTypeId(3, 3, 3) == 0 && names.equals(readerRegistry.semanticIds()),
          "Completed snapshot or supplied registry changed");
      try (var files = Files.list(out)) {
        require(
            files.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-archive-")),
            "Successful publication or abort retained a temporary archive");
      }
      cases.add(
          Map.of(
              "version",
              format.version(),
              "oldCells",
              64,
              "newCells",
              64,
              "emptyCells",
              8,
              "preparedOldFilePreserved",
              true,
              "terminalOwnership",
              true));
    }
    require(cells == 272, "Unexpected publication replay dimensions");
    Files.writeString(
        out.resolve("atomic-publication.json"),
        io.voxelcreator.model.ProjectIO.JSON.writeValueAsString(
            Map.of("exactCells", cells, "cases", cases)));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
