package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** Deferred final-callback interruption: staged arrays cannot escape after withdrawal. */
class SnapshotPublicationTest {
  private static final ChunkPos POS = new ChunkPos(-2, 1, -3);
  private record Tree(int kind, int root, int calls, byte[] bytes) {}

  // Independent wire fixtures, including a branch with eight uniform children.
  private Tree tree(int kind) throws IOException {
    var bytes = new ByteArrayOutputStream(); int root = kind == 3 ? 8 : kind == 0 ? 4 : 2;
    try (var out = new DataOutputStream(bytes)) {
      if (kind == 0) { out.writeByte(1); out.writeInt(7); }
      else if (kind == 1) { out.writeByte(3); for (int i = 0; i < 8; i++) out.writeInt(i % 3); }
      else if (kind == 2) { out.writeByte(4); out.writeByte(2); out.writeInt(0); out.writeInt(1); out.writeInt(0xaa); }
      else { out.writeByte(2); for (int i = 0; i < 8; i++) { out.writeByte(1); out.writeInt(1 + i % 2); } }
    }
    return new Tree(kind, root, kind == 0 ? 2 : kind == 2 ? 3 : 9, bytes.toByteArray());
  }
  private VoxelChunk decode(Tree tree, IntUnaryOperator map) throws IOException {
    try (var input = new DataInputStream(new ByteArrayInputStream(tree.bytes))) {
      var chunk = VoxelChunk.readTree(POS, tree.root, input, map); assertEquals(-1, input.read()); return chunk;
    }
  }
  private DataInputStream lastByteInterrupt(int node) {
    return new DataInputStream(new ByteArrayInputStream(new byte[] {(byte) node}) {
      @Override public synchronized int read() {
        int value = super.read(); if (value >= 0) Thread.currentThread().interrupt(); return value;
      }
    });
  }
  private static int mapped(int value) { return value == 0 ? 0 : value + 10; }
  private int raw(Tree t, int x, int y, int z) {
    return switch (t.kind) {
      case 0 -> 7;
      case 1 -> ((y * 2 + z) * 2 + x) % 3;
      case 2 -> x & 1;
      default -> 1 + (x >= 4 ? 1 : 0);
    };
  }
  private int value(int kind, int root, int x, int y, int z) {
    return switch (kind) {
      case 0 -> 0; case 1 -> 7; case 2 -> (x + 2 * y + 3 * z) % 3;
      case 3 -> 1 + (y * root + z) * root + x;
      default -> x == root - 1 && y == root - 1 && z == root - 1 ? 2 : 0;
    };
  }

  @Test
  void lastSamplerInterruptionCannotPublishEmptyUniformPackedDenseOrBranchSnapshots() {
    for (int root : new int[] {2, 4, 8}) for (int kind = 0; kind < 5; kind++) {
      int shape = kind; var calls = new AtomicInteger(); var result = new AtomicReference<VoxelChunk>();
      try {
        assertThrows(CancellationException.class, () -> result.set(VoxelChunk.generate(POS, root, (x, y, z) -> {
          if (calls.incrementAndGet() == root * root * root) Thread.currentThread().interrupt();
          return value(shape, root, x, y, z);
        })));
        assertEquals(root * root * root, calls.get()); assertNull(result.get());
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
      var retry = VoxelChunk.generate(POS, root, (x, y, z) -> value(shape, root, x, y, z));
      for (int z = 0; z < root; z++) for (int y = 0; y < root; y++) for (int x = 0; x < root; x++)
        assertEquals(value(shape, root, x, y, z), retry.getVoxelTypeId(x, y, z));
    }
  }

  @Test
  void lastMappingInterruptionCannotPublishAnyIndependentlyEncodedTreeShape() throws Exception {
    for (int kind = 0; kind < 4; kind++) {
      var t = tree(kind); byte[] before = t.bytes.clone(); var calls = new AtomicInteger();
      try {
        assertThrows(CancellationException.class, () -> decode(t, value -> {
          if (calls.incrementAndGet() == t.calls) Thread.currentThread().interrupt(); return mapped(value);
        }));
        assertEquals(t.calls, calls.get()); assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
      assertArrayEquals(before, t.bytes); var retry = decode(t, SnapshotPublicationTest::mapped);
      for (int z = 0; z < t.root; z++) for (int y = 0; y < t.root; y++) for (int x = 0; x < t.root; x++)
        assertEquals(mapped(raw(t, x, y, z)), retry.getVoxelTypeId(x, y, z));
    }
    try (var input = lastByteInterrupt(0)) {
      try {
        assertThrows(CancellationException.class, () -> VoxelChunk.readTree(POS, 2, input, SnapshotPublicationTest::mapped));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void crossThreadInterruptionDuringTheFinalCallbackIsObservedBeforePublication() throws Exception {
    for (boolean mapping : new boolean[] {false, true}) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var thread = new AtomicReference<Thread>();
      var calls = new AtomicInteger(); var t = tree(1);
      Runnable hold = () -> {
        thread.set(Thread.currentThread()); entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Callback was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      };
      try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
        var attempt = workers.submit(() -> {
          try {
            if (mapping) decode(t, value -> { if (calls.incrementAndGet() == t.calls) hold.run(); return mapped(value); });
            else VoxelChunk.generate(POS, 4, (x, y, z) -> { if (calls.incrementAndGet() == 64) hold.run(); return 7; });
            return (Throwable) null;
          } catch (Throwable failure) { assertTrue(Thread.currentThread().isInterrupted()); return failure; }
          finally { Thread.interrupted(); }
        });
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); thread.get().interrupt(); }
        finally { release.countDown(); }
        assertInstanceOf(CancellationException.class, attempt.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void callbackFaultsAndInvalidValuesKeepPriorityOverFinalCancellation() throws Exception {
    for (Throwable failure : List.of(new IllegalStateException("callback"), new AssertionError("callback"))) {
      try {
        assertSame(failure, assertThrows(Throwable.class, () -> VoxelChunk.generate(POS, 2, (x, y, z) -> {
          if (x == 1 && y == 1 && z == 1) {
            Thread.currentThread().interrupt(); if (failure instanceof RuntimeException runtime) throw runtime; throw (Error) failure;
          }
          return 1;
        })));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
      var t = tree(0);
      try {
        assertSame(failure, assertThrows(Throwable.class, () -> decode(t, value -> {
          if (value != 0) { Thread.currentThread().interrupt(); if (failure instanceof RuntimeException runtime) throw runtime; throw (Error) failure; }
          return 0;
        })));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
      assertEquals(0, failure.getSuppressed().length);
    }
    try {
      assertThrows(IllegalArgumentException.class, () -> VoxelChunk.generate(POS, 2, (x, y, z) -> { Thread.currentThread().interrupt(); return -1; }));
    } finally { Thread.interrupted(); }
    var t = tree(0);
    try {
      assertThrows(IOException.class, () -> decode(t, value -> { if (value == 0) return 0; Thread.currentThread().interrupt(); return -1; }));
    } finally { Thread.interrupted(); }
    try (var input = lastByteInterrupt(255)) {
      try {
        assertThrows(IOException.class, () -> VoxelChunk.readTree(POS, 2, input, SnapshotPublicationTest::mapped));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void failedFinalizationPreservesOlderSnapshotsAndHealthyEditRetryOwnsItsStorage() {
    var original = VoxelChunk.generate(POS, 8, (x, y, z) -> (x + y + z) % 3);
    var palette = original.paletteRuntimeIds(); var stats = original.storageStatistics();
    try {
      assertThrows(CancellationException.class, () -> VoxelChunk.generate(POS, 2, (x, y, z) -> {
        if (x == 1 && y == 1 && z == 1) Thread.currentThread().interrupt(); return 2;
      }));
      assertEquals(2, original.getVoxelTypeId(7, 7, 6));
    } finally { Thread.interrupted(); }
    var edited = original.withVoxel(7, 7, 7, 99); var copy = edited.paletteRuntimeIds(); Arrays.fill(copy, 42);
    for (int z = 0; z < 8; z++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
      assertEquals((x + y + z) % 3, original.getVoxelTypeId(x, y, z));
      assertEquals(x == 7 && y == 7 && z == 7 ? 99 : (x + y + z) % 3, edited.getVoxelTypeId(x, y, z));
    }
    assertArrayEquals(palette, original.paletteRuntimeIds()); assertEquals(stats, original.storageStatistics());
  }

  @Test
  void immediateUniformNoopAndReadPathsKeepTheirExistingInterruptionBehavior() {
    var original = VoxelChunk.uniform(POS, 256, 7);
    try {
      Thread.currentThread().interrupt();
      assertSame(original, original.withVoxel(255, 255, 255, 7));
      assertEquals(9, original.withFilledBox(0, 0, 0, 255, 255, 255, 9).getVoxelTypeId(255, 255, 255));
      assertEquals(0, VoxelChunk.uniform(POS, 2, 0).getVoxelTypeId(0, 0, 0));
      assertEquals(7, original.getVoxelTypeId(255, 255, 255)); assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); }
  }
}
