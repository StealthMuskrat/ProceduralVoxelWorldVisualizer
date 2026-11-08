package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred retained delivery with actual CPU retirement/reload and controlled backend effects. */
class SceneRemovalPumpTest {
  private static final class Workers extends AbstractExecutorService {
    final Queue<Runnable> jobs = new ArrayDeque<>();
    boolean stopped;
    public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); jobs.add(task); }
    public void shutdown() { shutdownNow(); }
    public List<Runnable> shutdownNow() { stopped = true; var old = new ArrayList<>(jobs); jobs.clear(); return old; }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped; }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    void drain() { while (!jobs.isEmpty()) jobs.remove().run(); }
  }

  private static final class Model implements AutoCloseable {
    final Workers workers = new Workers();
    final SceneRemovalPump pump = new SceneRemovalPump();
    final ChunkScene scene;
    final List<ChunkScene.Removal> attempts = new ArrayList<>();
    final Map<ChunkPos, Long> live = new HashMap<>();
    Consumer<String> before = event -> {}, after = event -> {};
    Object owner = new Object();
    boolean closed, accepted = true;
    int resets;
    Vector3d center = new Vector3d(1);

    Model(int limit) {
      scene = new ChunkScene((position, token) -> {
        token.throwIfCancelled(); return SceneChunk.exact(VoxelChunk.uniform(position, 4, 23));
      }, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, true, limit));
    }
    final SceneRemovalPump.Backend backend = new SceneRemovalPump.Backend() {
      public boolean remove(ChunkScene.Removal removal) {
        attempts.add(removal); before.accept("remove");
        if (accepted && removal.epoch() == scene.epoch())
          live.computeIfPresent(removal.position(), (p, version) -> version <= removal.version() ? null : version);
        after.accept("remove"); return accepted && removal.epoch() == scene.epoch();
      }
      public boolean reset(ChunkScene.RemovalReset reset) {
        resets++; before.accept("reset");
        if (accepted && reset.epoch() == scene.epoch()) live.clear();
        after.accept("reset"); return accepted && reset.epoch() == scene.epoch();
      }
    };
    void travel(double x) {
      center = new Vector3d(x, 1, 1); scene.tick(center, center, 4);
    }
    void light(int count) { for (int i = 0; i < count; i++) travel(i * 1000 + 1); }
    ChunkScene.Upload ready() {
      for (int pass = 0; pass < 300; pass++) {
        scene.tick(center, center, 16); workers.drain();
        var uploads = scene.uploads(1, 1 << 20);
        if (!uploads.isEmpty()) return uploads.getFirst();
      }
      throw new AssertionError("No current immutable upload");
    }
    boolean run() {
      Object captured = owner;
      return pump.run(scene, () -> !closed && owner == captured, backend);
    }
    public void close() { closed = true; scene.close(); workers.shutdownNow(); }
  }

  private static Throwable fault(boolean error) {
    return error ? new AssertionError() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
    } : new IllegalStateException() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  @Test
  void realRetirementAndReloadPreserveNewerGeometryAndAcknowledgeCapturedReceipts() {
    try (var m = new Model(2048)) {
      var old = m.ready(); m.scene.uploaded(old); m.live.put(old.position(), old.version());
      m.travel(10001);
      var removals = m.scene.pendingRemovals(); assertFalse(removals.isEmpty());
      var removal = removals.stream().filter(r -> r.position().equals(old.position())).findFirst().orElseThrow();
      assertTrue(removal.version() > old.version()); m.live.put(old.position(), removal.version() + 1);
      assertTrue(m.run()); assertFalse(m.scene.hasPendingRemovals());
      assertEquals(removal.version() + 1, m.live.get(old.position()).longValue());
      m.travel(1); var reloaded = m.ready();
      assertTrue(reloaded.version() > removal.version()); assertEquals(64, reloaded.mesh().vertexCount());
      assertEquals(1024, reloaded.mesh().byteSize());
      for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
        assertEquals(23, reloaded.cells().get(x, y, z));
      assertEquals(23, old.cells().get(0, 0, 0));
    }
  }

  @Test
  void everyBackendFailureRetainsFailedAndUnprocessedReceiptsForAnIdempotentRetry() {
    for (int slot : new int[] {0, 1, 2}) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) try (var m = new Model(2048)) {
        m.light(4); var saved = m.scene.pendingRemovals(); assertTrue(saved.size() >= 3);
        int index = slot == 2 ? saved.size() - 1 : slot;
        var primary = fault(error);
        Consumer<String> hook = event -> {
          if (event.equals("remove") && m.attempts.size() - 1 == index) raise(primary);
        };
        if (after) m.after = hook; else m.before = hook;
        assertSame(primary, assertThrows(Throwable.class, m::run));
        assertEquals(saved.subList(index, saved.size()), m.scene.pendingRemovals());
        assertFalse(m.pump.busy()); assertEquals(0, primary.getSuppressed().length);
        m.before = event -> {}; m.after = event -> {};
        assertTrue(m.run()); assertFalse(m.scene.hasPendingRemovals());
        assertEquals(saved.size() + 1, m.attempts.size());
      }
  }

  @Test
  void rejectedObsoleteAndRecursiveDeliveryCannotConsumeReceipts() {
    try (var m = new Model(2048)) {
      m.light(4); var saved = m.scene.pendingRemovals();
      assertFalse(m.pump.run(m.scene, () -> false, m.backend)); assertTrue(m.attempts.isEmpty());
      m.accepted = false; assertFalse(m.run()); assertEquals(saved, m.scene.pendingRemovals());
      m.accepted = true;
      m.before = event -> {
        int attempts = m.attempts.size(); assertThrows(IllegalStateException.class, m::run);
        assertEquals(attempts, m.attempts.size());
      };
      assertTrue(m.run()); assertFalse(m.scene.hasPendingRemovals());
    }
  }

  @Test
  void closureOrOwnerReplacementAfterEitherBackendPhaseCannotAcknowledgeOldWork() {
    for (boolean reset : new boolean[] {false, true}) for (boolean close : new boolean[] {false, true})
      for (boolean after : new boolean[] {false, true}) try (var m = new Model(reset ? 27 : 2048)) {
        m.light(reset ? 80 : 4); var receipts = m.scene.pendingRemovals();
        var pendingReset = m.scene.pendingRemovalReset();
        Consumer<String> hook = event -> {
          if (close) { m.closed = true; m.scene.close(); } else m.owner = new Object();
        };
        if (after) m.after = hook; else m.before = hook;
        assertFalse(m.run()); assertFalse(m.pump.busy());
        if (close) assertFalse(m.scene.hasPendingRemovals());
        else {
          assertEquals(receipts, m.scene.pendingRemovals()); assertSame(pendingReset, m.scene.pendingRemovalReset());
          assertTrue(m.scene.hasPendingRemovals());
        }
        assertEquals(reset ? 1 : 0, m.resets); assertEquals(reset ? 0 : 1, m.attempts.size());
      }
  }

  @Test
  void sameOwnerReceiptReplacementDuringDeliveryCannotClearTheReplacement() {
    try (var m = new Model(2048)) {
      m.light(4); var old = m.scene.pendingRemovals().getFirst(); var once = new AtomicBoolean();
      m.after = event -> {
        if (once.compareAndSet(false, true)) {
          var p = old.position();
          m.center = new Vector3d(p.x() * 4.0 + 1, p.y() * 4.0 + 1, p.z() * 4.0 + 1);
          m.scene.tick(m.center, m.center, 4); m.travel(1000001);
        }
      };
      assertFalse(m.run());
      var replacement = m.scene.pendingRemovals().stream()
          .filter(r -> r.position().equals(old.position())).findFirst().orElseThrow();
      assertNotSame(old, replacement); assertTrue(replacement.version() > old.version());
      assertFalse(m.scene.acknowledgeRemoval(old));
      m.after = event -> {}; assertTrue(m.run());
    }
    try (var m = new Model(27)) {
      m.light(80); var old = m.scene.pendingRemovalReset(); var once = new AtomicBoolean();
      m.after = event -> { if (once.compareAndSet(false, true)) m.travel(1000001); };
      assertFalse(m.run()); var replacement = m.scene.pendingRemovalReset();
      assertNotSame(old, replacement); assertTrue(replacement.version() > old.version());
      assertFalse(m.scene.acknowledgeRemovalReset(replacement));
      m.after = event -> {}; assertTrue(m.run()); assertFalse(m.scene.hasPendingRemovals());
    }
  }

  @Test
  void acceptedResetInvalidatesOldUploadsBeforeAcknowledgementAndRetainsExactCpuInputs() {
    try (var m = new Model(27)) {
      m.light(80); var old = m.ready(); m.scene.uploaded(old);
      var reset = m.scene.pendingRemovalReset(); assertNotNull(reset);
      m.before = event -> assertFalse(m.scene.acknowledgeRemovalReset(reset));
      assertTrue(m.run()); assertNull(m.scene.pendingRemovalReset());
      var fresh = m.scene.uploads(1, 1 << 20).getFirst();
      assertTrue(fresh.version() > old.version()); assertSame(old.mesh(), fresh.mesh()); assertSame(old.cells(), fresh.cells());
      m.scene.uploaded(old); assertFalse(m.scene.uploads(1, 1 << 20).isEmpty());
      for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
        assertEquals(23, fresh.cells().get(x, y, z));
      assertEquals(1024, fresh.mesh().byteSize()); assertEquals(1, m.resets);
    }
  }

  @Test
  void resetRefusalOrFaultNeverConsumesItsReceiptOrClaimsCpuInvalidation() {
    for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true})
      try (var m = new Model(27)) {
        m.light(80); var reset = m.scene.pendingRemovalReset();
        m.accepted = false; assertFalse(m.run()); assertSame(reset, m.scene.pendingRemovalReset());
        assertFalse(m.scene.acknowledgeRemovalReset(reset));
        m.accepted = true; var primary = fault(error);
        Consumer<String> hook = event -> raise(primary);
        if (after) m.after = hook; else m.before = hook;
        assertSame(primary, assertThrows(Throwable.class, m::run));
        assertSame(reset, m.scene.pendingRemovalReset()); assertFalse(m.scene.acknowledgeRemovalReset(reset));
        m.before = event -> {}; m.after = event -> {};
        assertTrue(m.run()); assertFalse(m.scene.hasPendingRemovals());
      }
  }
}
