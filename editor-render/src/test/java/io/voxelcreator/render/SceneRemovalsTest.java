package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred bounded receipt/CPU retirement definitions; native dispatch remains source-traced. */
class SceneRemovalsTest {
  private static final ChunkPos A = new ChunkPos(-1, 0, 2), B = new ChunkPos(3, 0, -4),
      C = new ChunkPos(5, 0, 6), D = new ChunkPos(7, 0, 8);

  @Test
  void coalescingKeepsTheHighestWatermarkAndOnlyTheExactRetainedObjectAcknowledges() {
    var queue = new SceneRemovals(42, 3);
    queue.offer(A, 4); var first = queue.pending().getFirst();
    queue.offer(B, 5); var snapshot = queue.pending();
    queue.offer(A, 7); var current = queue.pending().getFirst();
    queue.offer(A, 6); queue.offer(A, 7);
    assertSame(current, queue.pending().getFirst()); assertEquals(2, queue.pending().size());
    assertEquals(4, snapshot.getFirst().version()); assertSame(first, snapshot.getFirst());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    assertFalse(queue.acknowledge(first));
    assertFalse(queue.acknowledge(new ChunkScene.Removal(42, A, 7)));
    assertFalse(queue.acknowledge(new ChunkScene.Removal(43, A, 7)));
    assertTrue(queue.acknowledge(current)); assertFalse(queue.acknowledge(current));
    assertEquals(List.of(B), queue.pending().stream().map(ChunkScene.Removal::position).toList());
    assertTrue(queue.hasPending());
  }

  @Test
  void overflowAndLaterRetirementsCoalesceToOneResetWithAnInvalidationBarrier() {
    var queue = new SceneRemovals(42, 3);
    queue.offer(A, 1); queue.offer(B, 2); queue.offer(C, 3); assertNull(queue.reset());
    queue.offer(D, 4); var first = queue.reset();
    assertNotNull(first); assertEquals(4, first.version()); assertTrue(queue.pending().isEmpty());
    assertFalse(queue.acknowledge(first)); queue.uploadsInvalidated();
    queue.offer(A, 5); var second = queue.reset();
    assertNotSame(first, second); assertFalse(queue.acknowledge(first)); assertFalse(queue.acknowledge(second));
    queue.uploadsInvalidated();
    assertFalse(queue.acknowledge(new ChunkScene.RemovalReset(42, 5)));
    assertTrue(queue.acknowledge(second)); assertFalse(queue.hasPending());
    queue.offer(B, 6); assertNull(queue.reset()); assertEquals(1, queue.pending().size());
  }

  @Test
  void limitShrinkRequiresResetAndClosedOwnershipCannotReopenItsQueue() {
    var queue = new SceneRemovals(42, 5);
    queue.offer(A, 1); queue.offer(B, 2); queue.offer(C, 3); queue.offer(D, 4);
    queue.limit(3, 9); var reset = queue.reset();
    assertEquals(9, reset.version()); assertTrue(queue.pending().isEmpty());
    queue.close(); queue.offer(A, 10); queue.limit(1, 11); queue.uploadsInvalidated();
    assertFalse(queue.hasPending()); assertNull(queue.reset()); assertFalse(queue.acknowledge(reset));
    assertThrows(IllegalArgumentException.class, () -> new SceneRemovals(42, 0));
    assertThrows(IllegalArgumentException.class, () -> new ChunkScene.Removal(42, A, -1));
    assertThrows(NullPointerException.class, () -> new ChunkScene.Removal(42, null, 1));
    assertThrows(IllegalArgumentException.class, () -> new ChunkScene.RemovalReset(42, -1));
  }

  @Test
  void legacyDrainDeduplicatesPositionsButCannotLoseARequiredReset() {
    var queue = new SceneRemovals(42, 2);
    queue.offer(A, 1); queue.offer(A, 2); queue.offer(B, 3); var receipt = queue.pending().getFirst();
    assertEquals(List.of(A, B), queue.drainPositions()); assertFalse(queue.hasPending());
    assertFalse(queue.acknowledge(receipt));
    queue.offer(A, 4); queue.offer(B, 5); queue.offer(C, 6); var reset = queue.reset();
    assertThrows(IllegalStateException.class, queue::drainPositions);
    assertSame(reset, queue.reset()); assertTrue(queue.hasPending());
    queue.uploadsInvalidated(); assertTrue(queue.acknowledge(reset)); assertTrue(queue.drainPositions().isEmpty());
  }

  @Test
  void actualTravelBoundsRetirementMetadataAndCloseDetachesBeforeSourceCallbacks() {
    var queue = new ArrayDeque<Runnable>();
    var workers = new AbstractExecutorService() {
      boolean closed;
      public void execute(Runnable task) { queue.add(task); }
      public void shutdown() { closed = true; }
      public List<Runnable> shutdownNow() { closed = true; var old = new ArrayList<>(queue); queue.clear(); return old; }
      public boolean isShutdown() { return closed; }
      public boolean isTerminated() { return closed; }
      public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    };
    var reference = new AtomicReference<ChunkScene>();
    var source = new ChunkScene.Source() {
      public SceneChunk load(ChunkPos p, io.voxelcreator.api.CancellationToken token) {
        throw new AssertionError("Deferred workers must not execute");
      }
      public void close() {
        assertFalse(reference.get().hasPendingRemovals()); assertNull(reference.get().pendingRemovalReset());
      }
    };
    try (var scene = new ChunkScene(source, workers,
        new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, true, 27))) {
      reference.set(scene);
      for (int i = 0; i < 80; i++) {
        var center = new Vector3d(i * 1000 + 1, 1, 1); scene.tick(center, center, 4);
        assertTrue(scene.pendingRemovals().size() <= 27);
        if (scene.pendingRemovalReset() != null) assertTrue(scene.pendingRemovals().isEmpty());
      }
      assertNotNull(scene.pendingRemovalReset());
      assertThrows(IllegalStateException.class, scene::removed);
    } finally { workers.shutdownNow(); }
  }

  @Test
  void versionPolicyKeepsNewerAllocationsAcrossReplayAndRejectsForeignEpochs() {
    var removal = new ChunkScene.Removal(42, A, 7);
    for (long version : new long[] {Long.MIN_VALUE, 0, 4, 7})
      assertTrue(SceneRemovalPolicy.removes(42, removal, version));
    for (long version : new long[] {8, 11, Long.MAX_VALUE})
      assertFalse(SceneRemovalPolicy.removes(42, removal, version));
    assertFalse(SceneRemovalPolicy.removes(43, removal, 4));
    var arena = new ArenaAllocator(64);
    var slices = List.of(arena.allocate(16), arena.allocate(16), arena.allocate(16));
    long[] versions = {4, 7, 11};
    for (int i = 0; i < versions.length; i++)
      if (SceneRemovalPolicy.removes(42, removal, versions[i])) arena.release(slices.get(i));
    assertEquals(16, arena.usedBytes()); assertEquals(48, arena.freeBytes());
    assertEquals(32, slices.get(2).offset()); assertEquals(0, arena.allocate(32).offset());
    assertFalse(SceneRemovalPolicy.removes(42, removal, versions[2]));
  }
}
