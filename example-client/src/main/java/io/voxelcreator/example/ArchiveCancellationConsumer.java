package io.voxelcreator.example;

import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Deferred engine-JAR-only archive cancellation/retry with independent semantic cell oracles. */
public final class ArchiveCancellationConsumer {
  private static final ChunkPos UNIFORM = new ChunkPos(-1, 0, 0), PATTERN = new ChunkPos(0, 0, 0),
      SPARSE = new ChunkPos(1, 0, 0), EMPTY = new ChunkPos(-2, 0, 0), ABSENT = new ChunkPos(99, 0, 0);
  private record Retained(VoxelChunk chunk, int kind) {}
  @FunctionalInterface private interface Action { void run() throws Exception; }

  private static void cancelled(Action action) throws Exception {
    require(!Thread.currentThread().isInterrupted(), "Replay entered with interruption");
    try {
      Thread.currentThread().interrupt(); boolean cancelled = false;
      try { action.run(); } catch (CancellationException expected) { cancelled = true; }
      require(cancelled && Thread.currentThread().isInterrupted(), "Decoder/write lost cancellation or interrupt flag");
    } finally { Thread.interrupted(); }
  }
  private static void cancelledRead(VoxelArchive.Reader reader, ChunkPos position) throws Exception {
    require(!Thread.currentThread().isInterrupted(), "Replay entered with interruption");
    try {
      Thread.currentThread().interrupt(); boolean cancelled = false;
      try { reader.read(position); } catch (CancellationException expected) { cancelled = true; }
      require(cancelled && Thread.currentThread().isInterrupted(), "Payload decode lost cancellation/interrupt state");
      require(reader.contains(position) && reader.coverage().size() == 4 && reader.hasStoredPayload(position)
          && reader.dictionary().equals(List.of("EMPTY", "stone", "snow")), "Cancellation changed immutable metadata");
      require(reader.read(EMPTY).orElseThrow().isEmpty() && reader.read(ABSENT).isEmpty(), "Non-decoding coverage queries changed");
      require(Thread.currentThread().isInterrupted(), "Coverage queries cleared interruption");
    } finally { Thread.interrupted(); }
  }
  private static int authored(int kind, int root, int x, int y, int z) {
    return switch (kind) {
      case 0 -> 1;
      case 1 -> (x + 2 * y + 3 * z) % 3;
      case 2 -> x == root - 1 && y == root - 1 && z == root - 1 ? 2 : 0;
      default -> 0;
    };
  }
  private static long exact(VoxelChunk chunk, int kind, boolean remap) {
    long count = 0; int root = chunk.size();
    for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
      int value = authored(kind, root, x, y, z); int expected = remap && value != 0 ? 3 - value : value;
      require(chunk.getVoxelTypeId(x, y, z) == expected, "Cancellation changed exact source/retained voxel"); count++;
    }
    return count;
  }
  private static Set<Path> files(Path directory) throws IOException {
    try (var paths = Files.list(directory)) { return new HashSet<>(paths.toList()); }
  }
  private static byte[] corrupt(byte[] original) {
    byte[] broken = original.clone(); var buffer = ByteBuffer.wrap(broken);
    int index = Math.toIntExact(buffer.getLong(broken.length - 16));
    for (int i = 0; i < buffer.getInt(index); i++) {
      int at = index + 4 + 28 * i;
      if (new ChunkPos(buffer.getInt(at), buffer.getInt(at + 4), buffer.getInt(at + 8)).equals(PATTERN)) {
        int offset = Math.toIntExact(buffer.getLong(at + 16)); require(buffer.getInt(at + 24) >= 2, "Short compressed payload");
        broken[offset] = broken[offset + 1] = 0; return broken;
      }
    }
    throw new AssertionError("Pattern payload was not indexed");
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var writing = VoxelRegistry.semantic("stone", "snow");
    var reading = VoxelRegistry.semantic("snow", "stone", "unused"); var names = List.copyOf(reading.semanticIds());
    var retained = new ArrayList<Retained>(); long cancelledReads = 0, cancelledAppends = 0, abortedWrites = 0,
        singletonReads = 0, corruptCancellations = 0, corruptReads = 0, closedReads = 0, sourceCells = 0;
    for (var format : VoxelArchive.Format.values()) for (int root : new int[] {2, 4, 8}) {
      var chunks = List.of(VoxelChunk.uniform(UNIFORM, root, 1),
          VoxelChunk.generate(PATTERN, root, (x, y, z) -> authored(1, root, x, y, z)),
          VoxelChunk.generate(SPARSE, root, (x, y, z) -> authored(2, root, x, y, z)),
          VoxelChunk.uniform(EMPTY, root, 0));
      var path = out.resolve("retry-" + format + "-" + root + ".pvworld"); byte[] previous = {8, 6, 4, 2}; Files.write(path, previous);
      try (var writer = VoxelArchive.openWorldWriter(path, writing, format)) {
        writer.append(chunks.get(0)); cancelled(() -> writer.append(chunks.get(1))); cancelledAppends++;
        require(Arrays.equals(previous, Files.readAllBytes(path)), "Cancelled append published output");
        for (int i = 1; i < chunks.size(); i++) writer.append(chunks.get(i)); writer.finish();
      }
      var control = out.resolve("control-" + format + "-" + root + ".pvworld");
      try (var writer = VoxelArchive.openWorldWriter(control, writing, format)) {
        for (var chunk : chunks) writer.append(chunk); writer.finish();
      }
      byte[] baseline = Files.readAllBytes(path);
      require(Arrays.equals(baseline, Files.readAllBytes(control)), "Append retry changed deterministic archive bytes");
      var before = files(out); cancelled(() -> VoxelArchive.writeSingleChunk(path, chunks.get(1), writing)); abortedWrites++;
      require(before.equals(files(out)) && Arrays.equals(baseline, Files.readAllBytes(path)), "Cancelled single write changed destination/staging");
      var reader = VoxelArchive.openWorldReader(path, reading);
      try {
        for (int kind = 0; kind < 3; kind++) {
          var pos = chunks.get(kind).position(); retained.add(new Retained(reader.read(pos).orElseThrow(), kind));
          for (int attempt = 0; attempt < 2; attempt++) {
            cancelledRead(reader, pos); cancelledReads++;
            retained.add(new Retained(reader.read(pos).orElseThrow(), kind));
          }
        }
        retained.add(new Retained(reader.read(EMPTY).orElseThrow(), 3));
        reader.close();
        try {
          Thread.currentThread().interrupt();
          try { reader.read(PATTERN); throw new AssertionError("Closed reader accepted decoding"); }
          catch (IOException expected) { require(Thread.currentThread().isInterrupted(), "Closed-reader failure cleared interruption"); closedReads++; }
        } finally { Thread.interrupted(); }
      } finally { reader.close(); }
      require(Arrays.equals(baseline, Files.readAllBytes(path)), "Decode cancellation changed archive bytes");

      var singleton = out.resolve("single-" + format + "-" + root + ".pvworld");
      try (var writer = VoxelArchive.openWorldWriter(singleton, writing, format)) { writer.append(chunks.get(1)); writer.finish(); }
      byte[] singletonBytes = Files.readAllBytes(singleton);
      cancelled(() -> VoxelArchive.readSingleChunk(singleton, reading)); singletonReads++;
      var moved = out.resolve("moved-single-" + format + "-" + root + ".pvworld"); Files.move(singleton, moved, StandardCopyOption.REPLACE_EXISTING);
      retained.add(new Retained(VoxelArchive.readSingleChunk(moved, reading), 1));
      require(Arrays.equals(singletonBytes, Files.readAllBytes(moved)), "Single-reader cancellation changed input bytes");

      byte[] broken = corrupt(baseline); var corruptPath = out.resolve("corrupt-" + format + "-" + root + ".pvworld"); Files.write(corruptPath, broken);
      try (var isolated = VoxelArchive.openWorldReader(corruptPath, reading)) {
        cancelled(() -> isolated.read(PATTERN)); corruptCancellations++;
        try { isolated.read(PATTERN); throw new AssertionError("Corrupt payload decoded after cancellation"); }
        catch (IOException expected) { corruptReads++; }
        exact(isolated.read(UNIFORM).orElseThrow(), 0, true); require(isolated.read(EMPTY).orElseThrow().isEmpty(), "Corruption altered other coverage");
      }
      require(Arrays.equals(broken, Files.readAllBytes(corruptPath)), "Cancelled/corrupt read changed bytes");
      for (int kind = 0; kind < chunks.size(); kind++) sourceCells += exact(chunks.get(kind), kind, false);
    }
    long cells = 0; for (var value : retained) cells += exact(value.chunk, value.kind, true);
    require(cancelledReads == 36 && cancelledAppends == 6 && abortedWrites == 6 && singletonReads == 6
        && corruptCancellations == 6 && corruptReads == 6 && closedReads == 6 && sourceCells == 4672
        && retained.size() == 66 && cells == 12848 && names.equals(reading.semanticIds())
        && writing.semanticIds().equals(List.of("EMPTY", "stone", "snow")), "Replay dimensions/registry ownership changed");
    Files.writeString(out.resolve("archive-cancellation-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "decodeCancellations", cancelledReads, "cancelledAppends", cancelledAppends, "abortedWrites", abortedWrites,
        "singleReadCancellations", singletonReads, "corruption", Map.of("cancelled", corruptCancellations, "rejected", corruptReads),
        "closedReads", closedReads, "sourceCells", sourceCells, "retainedChunks", retained.size(), "retainedCells", cells, "roots", List.of(2, 4, 8))));
    System.out.println("ARCHIVE_CANCELLATION_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
