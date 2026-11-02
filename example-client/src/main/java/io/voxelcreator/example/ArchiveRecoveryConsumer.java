package io.voxelcreator.example;

import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Deferred engine-JAR-only archive publication/retry and exact semantic-remapping replay. */
public final class ArchiveRecoveryConsumer {
  public static void main(String[] args) throws Exception {
    var out = Path.of(args[0]).toAbsolutePath();
    Files.createDirectories(out);
    var writerRegistry = VoxelRegistry.semantic("stone", "snow");
    var readerRegistry = VoxelRegistry.semantic("snow", "stone", "unused");
    var names = List.copyOf(readerRegistry.semanticIds());
    var patterned =
        VoxelChunk.generate(new ChunkPos(-1, 0, -1), 4, (x, y, z) -> (x + 2 * y + 3 * z) % 3);
    var empty = VoxelChunk.uniform(new ChunkPos(-3, 2, -3), 2, 0);
    var uniform = VoxelChunk.uniform(new ChunkPos(0, 0, 0), 256, 1);
    long cells = 0;
    int publicationFailures = 0;
    for (var format : VoxelArchive.Format.values()) {
      var target = out.resolve("recovery-" + format + ".pvworld");
      Files.createDirectory(target);
      var marker = target.resolve("keep");
      Files.writeString(marker, "original destination");
      try (var writer = VoxelArchive.openWorldWriter(target, writerRegistry, format)) {
        writer.append(patterned);
        writer.append(empty);
        writer.append(uniform);
        for (int attempt = 0; attempt < 2; attempt++) {
          try {
            writer.finish();
            throw new AssertionError("Publication unexpectedly replaced a nonempty directory");
          } catch (IOException expected) {
            publicationFailures++;
          }
          require(
              Files.readString(marker).equals("original destination"),
              "Failed publication damaged the destination");
        }
        try {
          writer.append(empty);
          throw new AssertionError("Prepared writer accepted more payload");
        } catch (IOException expected) {
        }
        Files.delete(marker);
        Files.delete(target);
        writer.finish();
        byte[] published = Files.readAllBytes(target);
        writer.finish();
        require(
            Arrays.equals(published, Files.readAllBytes(target)),
            "Repeated finish changed published bytes");
      }
      var reference = out.resolve("reference-" + format + ".pvworld");
      try (var writer = VoxelArchive.openWorldWriter(reference, writerRegistry, format)) {
        writer.append(patterned);
        writer.append(empty);
        writer.append(uniform);
        writer.finish();
      }
      byte[] baseline = Files.readAllBytes(target);
      require(
          Arrays.equals(baseline, Files.readAllBytes(reference)),
          "Retry rewrote or duplicated archive structure");
      // Aborting an ordinary replacement retains the complete previous archive.
      try (var writer = VoxelArchive.openWorldWriter(target, writerRegistry, format)) {
        writer.append(empty);
      }
      require(
          Arrays.equals(baseline, Files.readAllBytes(target)), "Abort replaced the prior archive");
      try (var reader = VoxelArchive.openWorldReader(target, readerRegistry)) {
        require(reader.version() == format.version(), "Archive format changed");
        require(
            reader
                .coverage()
                .equals(Set.of(patterned.position(), empty.position(), uniform.position())),
            "Coverage changed");
        require(
            !reader.hasStoredPayload(empty.position()) && reader.contains(empty.position()),
            "Sparse EMPTY coverage lost");
        require(
            !reader.contains(new ChunkPos(99, 0, 0))
                && reader.read(new ChunkPos(99, 0, 0)).isEmpty(),
            "Outside coverage became archived");
        var decoded = reader.read(patterned.position()).orElseThrow();
        for (int z = 0; z < 4; z++)
          for (int y = 0; y < 4; y++)
            for (int x = 0; x < 4; x++) {
              int authored = (x + 2 * y + 3 * z) % 3;
              int expected = authored == 0 ? 0 : authored == 1 ? 2 : 1;
              require(
                  decoded.getVoxelTypeId(x, y, z) == expected,
                  "Pattern/semantic remapping changed");
              cells++;
            }
        decoded = reader.read(empty.position()).orElseThrow();
        for (int z = 0; z < 2; z++)
          for (int y = 0; y < 2; y++)
            for (int x = 0; x < 2; x++) {
              require(
                  decoded.getVoxelTypeId(x, y, z) == 0, "Omitted payload did not decode as EMPTY");
              cells++;
            }
        decoded = reader.read(uniform.position()).orElseThrow();
        for (int z : new int[] {0, 127, 255})
          for (int y : new int[] {0, 127, 255})
            for (int x : new int[] {0, 127, 255}) {
              require(
                  decoded.getVoxelTypeId(x, y, z) == 2,
                  "Uniform boundary or semantic mapping changed");
              cells++;
            }
      }
      var missing = VoxelRegistry.semantic("snow");
      var missingNames = List.copyOf(missing.semanticIds());
      try (var reader = VoxelArchive.openWorldReader(target, missing)) {
        require(
            reader.read(empty.position()).orElseThrow().isEmpty(),
            "Unrelated EMPTY decoding failed");
        try {
          reader.read(uniform.position());
          throw new AssertionError("Unresolved semantic type was exposed");
        } catch (IOException unresolved) {
          require(
              unresolved.getMessage().contains("stone"), "Missing semantic ID was not reported");
        }
      }
      require(
          missingNames.equals(missing.semanticIds()) && names.equals(readerRegistry.semanticIds()),
          "Reader mutated a supplied registry");
      require(
          Arrays.equals(baseline, Files.readAllBytes(target)), "Read/abort modified archive bytes");
      var aborted = out.resolve("aborted-" + format);
      Files.createDirectory(aborted);
      var kept = aborted.resolve("keep");
      Files.writeString(kept, "retained");
      var writer = VoxelArchive.openWorldWriter(aborted, writerRegistry, format);
      writer.append(patterned);
      try {
        writer.finish();
        throw new AssertionError("Publication unexpectedly succeeded");
      } catch (IOException expected) {
      }
      writer.close();
      writer.close();
      try {
        writer.finish();
        throw new AssertionError("Explicitly aborted writer published later");
      } catch (IOException expected) {
      }
      require(Files.readString(kept).equals("retained"), "Prepared abort changed the destination");
    }
    require(cells == 198 && publicationFailures == 4, "Unexpected replay scope");
    Files.writeString(
        out.resolve("archive-recovery.txt"),
        "formats=1,2\npublicationFailures=4\nexactCells=198\n");
    System.out.println("ARCHIVE_RECOVERY_CONSUMER_PASS");
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
