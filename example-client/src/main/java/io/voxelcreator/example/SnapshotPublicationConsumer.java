package io.voxelcreator.example;

import io.voxelcreator.model.ProjectIO;
import io.voxelcreator.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntUnaryOperator;

/** Deferred engine-JAR-only final-callback withdrawal with independent tree/cell expectations. */
public final class SnapshotPublicationConsumer {
  private static final ChunkPos POS = new ChunkPos(-2, 1, -3);
  private record Tree(int kind, int root, int calls, byte[] bytes) {}
  private record Retained(VoxelChunk chunk, int kind, boolean mapped, boolean edited) {}
  @FunctionalInterface private interface Build { VoxelChunk run(Runnable interrupt) throws Exception; }
  @FunctionalInterface private interface Action { void run() throws Exception; }

  private static Tree tree(int kind) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var output = new DataOutputStream(bytes)) {
      if (kind == 0) { output.writeByte(1); output.writeInt(7); }
      else if (kind == 1) { output.writeByte(3); for (int i = 0; i < 8; i++) output.writeInt(i % 3); }
      else if (kind == 2) { output.writeByte(4); output.writeByte(2); output.writeInt(0); output.writeInt(1); output.writeInt(0xaa); }
      else { output.writeByte(2); for (int i = 0; i < 8; i++) { output.writeByte(1); output.writeInt(1 + i % 2); } }
    }
    return new Tree(kind, kind == 0 ? 4 : kind == 3 ? 8 : 2, kind == 0 ? 2 : kind == 2 ? 3 : 9, bytes.toByteArray());
  }
  private static int mapped(int value) { return value == 0 ? 0 : value + 10; }
  private static VoxelChunk decode(Tree tree, IntUnaryOperator mapping) throws IOException {
    try (var input = new DataInputStream(new ByteArrayInputStream(tree.bytes))) {
      var result = VoxelChunk.readTree(POS, tree.root, input, mapping);
      require(input.read() == -1, "Independent tree has unread bytes"); return result;
    }
  }
  private static int generated(int kind, int root, int x, int y, int z) {
    return switch (kind) {
      case 0 -> 0; case 1 -> 7; case 2 -> (x + 2 * y + 3 * z) % 3;
      case 3 -> 1 + (y * root + z) * root + x;
      default -> x == root - 1 && y == root - 1 && z == root - 1 ? 2 : 0;
    };
  }
  private static int expected(Retained value, int x, int y, int z) {
    int root = value.chunk.size();
    if (value.edited && x == root - 1 && y == root - 1 && z == root - 1) return 99;
    if (!value.mapped) return generated(value.kind, root, x, y, z);
    return mapped(switch (value.kind) {
      case 0 -> 7; case 1 -> ((y * 2 + z) * 2 + x) % 3; case 2 -> x & 1; default -> 1 + (x >= 4 ? 1 : 0);
    });
  }
  private static void reject(ExecutorService workers, boolean held, Build build) throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var owner = new AtomicReference<Thread>();
    Runnable signal = () -> {
      if (!held) { Thread.currentThread().interrupt(); return; }
      owner.set(Thread.currentThread()); entered.countDown();
      try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Final callback was not released"); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    };
    Callable<Throwable> attempt = () -> {
      require(!Thread.currentThread().isInterrupted(), "Attempt entered interrupted");
      try { build.run(signal); return null; }
      catch (Throwable failure) { require(Thread.currentThread().isInterrupted(), "Completion cleared interruption"); return failure; }
      finally { Thread.interrupted(); }
    };
    Throwable outcome;
    if (held) {
      var pending = workers.submit(attempt);
      try { require(entered.await(3, TimeUnit.SECONDS), "Final callback not reached"); owner.get().interrupt(); }
      finally { release.countDown(); }
      outcome = pending.get(5, TimeUnit.SECONDS);
    } else outcome = attempt.call();
    require(outcome instanceof CancellationException, "Staged snapshot escaped after final-callback interruption");
  }
  private static Throwable failure(Action action) throws Exception {
    require(!Thread.currentThread().isInterrupted(), "Fault control entered interrupted");
    try {
      Throwable observed = null; try { action.run(); } catch (Throwable fault) { observed = fault; }
      require(Thread.currentThread().isInterrupted(), "Fault control lost interruption"); return observed;
    } finally { Thread.interrupted(); }
  }

  public static void main(String[] args) throws Exception { run(Path.of(args[0]).toAbsolutePath()); }
  static void run(Path out) throws Exception {
    Files.createDirectories(out); var retained = new ArrayList<Retained>(); long samples = 0, mappings = 0, faults = 0, invalid = 0;
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int root : new int[] {2, 4, 8}) for (int kind = 0; kind < 5; kind++) {
        int shape = kind;
        var original = VoxelChunk.generate(POS, root, (x, y, z) -> generated(shape, root, x, y, z));
        retained.add(new Retained(original, kind, false, false));
        var palette = original.paletteRuntimeIds(); var statistics = original.storageStatistics();
        for (boolean held : new boolean[] {false, true}) {
          var calls = new AtomicInteger();
          reject(workers, held, signal -> VoxelChunk.generate(POS, root, (x, y, z) -> {
            if (calls.incrementAndGet() == root * root * root) signal.run(); return generated(shape, root, x, y, z);
          }));
          require(calls.get() == root * root * root, "Sampler changed traversal cardinality"); samples++;
          retained.add(new Retained(VoxelChunk.generate(POS, root, (x, y, z) -> generated(shape, root, x, y, z)), kind, false, false));
        }
        var edited = original.withVoxel(root - 1, root - 1, root - 1, 99);
        var exposed = edited.paletteRuntimeIds(); Arrays.fill(exposed, 12345);
        retained.add(new Retained(edited, kind, false, true));
        require(Arrays.equals(palette, original.paletteRuntimeIds()) && statistics.equals(original.storageStatistics()), "Original snapshot changed");
      }
      for (int kind = 0; kind < 4; kind++) {
        var t = tree(kind); byte[] before = t.bytes.clone(); Files.write(out.resolve("independent-tree-" + kind + ".bin"), before);
        retained.add(new Retained(decode(t, SnapshotPublicationConsumer::mapped), kind, true, false));
        for (boolean held : new boolean[] {false, true}) {
          var calls = new AtomicInteger();
          reject(workers, held, signal -> decode(t, value -> { if (calls.incrementAndGet() == t.calls) signal.run(); return mapped(value); }));
          require(calls.get() == t.calls && Arrays.equals(before, t.bytes), "Mapping changed fixture/traversal"); mappings++;
          retained.add(new Retained(decode(t, SnapshotPublicationConsumer::mapped), kind, true, false));
        }
      }
    }
    for (Throwable fault : List.of(new IllegalStateException("callback"), new AssertionError("callback"))) {
      require(failure(() -> VoxelChunk.generate(POS, 2, (x, y, z) -> {
        if (x == 1 && y == 1 && z == 1) { Thread.currentThread().interrupt(); if (fault instanceof RuntimeException runtime) throw runtime; throw (Error) fault; }
        return 1;
      })) == fault, "Sampler fault was replaced by cancellation"); faults++;
      var t = tree(0);
      require(failure(() -> decode(t, value -> {
        if (value != 0) { Thread.currentThread().interrupt(); if (fault instanceof RuntimeException runtime) throw runtime; throw (Error) fault; }
        return 0;
      })) == fault && fault.getSuppressed().length == 0, "Mapping fault was replaced/mutated"); faults++;
    }
    require(failure(() -> VoxelChunk.generate(POS, 2, (x, y, z) -> { Thread.currentThread().interrupt(); return -1; })) instanceof IllegalArgumentException,
        "Negative sampler value lost validation priority"); invalid++;
    var t = tree(0);
    require(failure(() -> decode(t, value -> { if (value == 0) return 0; Thread.currentThread().interrupt(); return -1; })) instanceof IOException,
        "Negative mapping value lost validation priority"); invalid++;
    var immediate = VoxelChunk.uniform(POS, 256, 7);
    try {
      Thread.currentThread().interrupt();
      require(immediate.withVoxel(255, 255, 255, 7) == immediate, "Immediate no-op changed");
      require(immediate.withFilledBox(0, 0, 0, 255, 255, 255, 9).getVoxelTypeId(255, 255, 255) == 9, "Immediate full replacement changed");
      require(VoxelChunk.uniform(POS, 2, 0).isEmpty() && immediate.getVoxelTypeId(255, 255, 255) == 7
          && Thread.currentThread().isInterrupted(), "Immediate uniform/read behavior changed");
    } finally { Thread.interrupted(); }
    long cells = 0, occupied = 0;
    for (var value : retained) {
      int root = value.chunk.size(); var seen = new BitSet(root * root * root); long expectedOccupied = 0;
      for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++) {
        int exact = expected(value, x, y, z); require(value.chunk.getVoxelTypeId(x, y, z) == exact, "Retained cell differs");
        if (exact != 0) expectedOccupied++; cells++;
      }
      value.chunk.forEachOccupied((x, y, z, type) -> {
        require(x >= 0 && x < root && y >= 0 && y < root && z >= 0 && z < root, "Occupied coordinate out of range");
        int index = (y * root + z) * root + x;
        require(!seen.get(index) && type != 0 && type == expected(value, x, y, z), "Duplicate/incorrect occupied value"); seen.set(index);
      });
      require(seen.cardinality() == expectedOccupied && value.chunk.storageStatistics().occupiedVoxels() == expectedOccupied,
          "Occupied iteration/statistics differ from independent cells"); occupied += seen.cardinality();
    }
    require(samples == 30 && mappings == 8 && faults == 4 && invalid == 2 && retained.size() == 72 && cells == 13456, "Replay dimensions changed");
    Files.writeString(out.resolve("snapshot-publication-report.json"), ProjectIO.JSON.writeValueAsString(Map.of(
        "samplerCancellations", samples, "mappingCancellations", mappings, "callbackFaults", faults, "invalidValues", invalid,
        "immediateControls", 4, "retainedChunks", retained.size(), "retainedCells", cells, "occupiedObservations", occupied, "roots", List.of(2, 4, 8))));
    System.out.println("SNAPSHOT_PUBLICATION_CONSUMER_PASS");
  }
  private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
