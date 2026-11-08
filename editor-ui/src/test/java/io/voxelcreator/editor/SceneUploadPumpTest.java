package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred acknowledgement definitions with real CPU scenes, packed payloads and controlled GPU effects. */
class SceneUploadPumpTest {
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
  private static final class Source implements ChunkScene.Source {
    final int type;
    final List<VoxelChunk> retained = new ArrayList<>();
    Source(int type) { this.type = type; }
    public SceneChunk load(ChunkPos position, CancellationToken token) {
      token.throwIfCancelled();
      var chunk = VoxelChunk.uniform(position, 4, type); retained.add(chunk);
      return SceneChunk.exact(chunk);
    }
  }
  private static final class Gpu implements SceneUploadPump.Backend {
    final List<String> calls = new ArrayList<>();
    final Map<ChunkPos, ChunkScene.Upload> resident = new HashMap<>();
    final Map<ChunkPos, byte[]> memory = new HashMap<>();
    Consumer<String> before = event -> {}, after = event -> {};
    ChunkScene.Upload pending, returned;
    byte[] staging;
    long uploaded;
    int progress, maintenance, advances, stages, lastBudget;
    boolean compacting, stageAccepted = true, retainStage = true, publish = true;

    void call(String event, Runnable effect) {
      calls.add(event); before.accept(event); effect.run(); after.accept(event);
    }
    public long uploadedBytes() { call("bytes", () -> {}); return uploaded; }
    public int compact(int budget) { call("compact", () -> lastBudget = budget); return maintenance; }
    public boolean compacting() { call("compacting", () -> {}); return compacting; }
    public ChunkScene.Upload pending() { call("pending", () -> {}); return pending; }
    public boolean contains(ChunkScene.Upload upload) {
      call("contains", () -> {}); var value = resident.get(upload.position());
      return value != null && value.epoch() == upload.epoch() && value.version() == upload.version();
    }
    public boolean stage(ChunkScene.Upload upload) {
      call("stage", () -> {
        stages++;
        if (stageAccepted && retainStage) {
          pending = upload; progress = 0; staging = new byte[upload.mesh().byteSize()];
        }
      });
      return stageAccepted;
    }
    public ChunkScene.Upload advance(int budget) {
      var attempted = pending;
      ChunkScene.Upload[] result = {null};
      call("advance", () -> {
        advances++; lastBudget = budget;
        int count = Math.min(budget, staging.length - progress);
        var buffer = ByteBuffer.wrap(staging, progress, count);
        attempted.mesh().writeRangeTo(buffer, progress, count);
        progress += count; uploaded += count;
        if (progress == staging.length) {
          if (publish) resident.put(attempted.position(), attempted);
          memory.put(attempted.position(), staging.clone());
          pending = null;
          result[0] = returned == null ? attempted : returned;
        }
      });
      return result[0];
    }
  }
  private static final class Model implements AutoCloseable {
    final Workers workers = new Workers();
    final List<ChunkScene> scenes = new ArrayList<>();
    final Source source = new Source(23);
    final SceneUploadPump pump = new SceneUploadPump();
    final Gpu gpu = new Gpu();
    final ChunkScene scene;
    Object owner = new Object();
    long activation = 1;
    boolean closed;

    Model() { this(0); }
    Model(int radius) { scene = create(source, radius); }
    ChunkScene create(Source source, int radius) {
      var scene = new ChunkScene(source, workers,
          new ChunkScene.Config(4, radius, 0, 2, 1, 2, 64L << 20, true, 125));
      scenes.add(scene); return scene;
    }
    List<ChunkScene.Upload> ready(ChunkScene scene, int count) {
      for (int pass = 0; pass < 300; pass++) {
        scene.tick(new Vector3d(1), new Vector3d(1), 16); workers.drain();
        var uploads = scene.uploads(128, 1 << 20);
        if (uploads.size() >= count) return uploads;
      }
      throw new AssertionError("Scene did not prepare required packed uploads");
    }
    boolean run(int budget) {
      Object captured = owner; long receipt = activation;
      return pump.run(scene, () -> !closed && owner == captured && activation == receipt, gpu, budget);
    }
    void pending(ChunkScene.Upload upload, boolean expected) {
      assertEquals(expected, scene.uploads(128, 1 << 20).stream()
          .anyMatch(value -> value.position().equals(upload.position()) && value.version() == upload.version()));
    }
    public void close() { closed = true; for (var scene : scenes) scene.close(); workers.shutdownNow(); }
  }
  private static Throwable fault(boolean error) {
    return error ? new AssertionError() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
      @Override public String toString() { throw new AssertionError("Unexpected text read"); }
    } : new IllegalStateException() {
      @Override public String getMessage() { throw new AssertionError("Unexpected text read"); }
      @Override public String toString() { throw new AssertionError("Unexpected text read"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Consumer<String> once(String point, Runnable callback) {
    var fired = new AtomicBoolean();
    return event -> { if (event.equals(point) && fired.compareAndSet(false, true)) callback.run(); };
  }
  private static void exactPoints(ChunkScene.Upload upload, byte[] data, int type) {
    assertEquals(64, upload.mesh().vertexCount()); assertEquals(1024, data.length);
    var bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
      int index = (z * 16 + y * 4 + x) * 16;
      assertEquals(2 * x + 1, Short.toUnsignedInt(bytes.getShort(index)));
      assertEquals(2 * y + 1, Short.toUnsignedInt(bytes.getShort(index + 2)));
      assertEquals(2 * z + 1, Short.toUnsignedInt(bytes.getShort(index + 4)));
      assertEquals(type, bytes.getInt(index + 12));
      assertEquals(type, upload.cells().get(x, y, z));
    }
  }

  @Test
  void completeCurrentReceiptAcknowledgesExactPackedPointsAndStableCpuCells() {
    try (var m = new Model()) {
      var upload = m.ready(m.scene, 1).getFirst();
      assertTrue(m.run(1024)); m.pending(upload, false);
      assertEquals(1, m.gpu.stages); assertEquals(1, m.gpu.advances); assertEquals(1024, m.gpu.uploaded);
      exactPoints(upload, m.gpu.memory.get(upload.position()), 23);
      for (var chunk : m.source.retained) for (int i = 0; i < 64; i++)
        assertEquals(23, chunk.getVoxelTypeId(i % 4, i / 4 % 4, i / 16));
    }
  }

  @Test
  void maintenanceAndPartialTransfersShareTheBudgetAndResumeTheExactPendingReceipt() {
    try (var m = new Model()) {
      var upload = m.ready(m.scene, 1).getFirst(); m.gpu.maintenance = 16;
      assertTrue(m.run(100)); m.pending(upload, true);
      assertSame(upload.mesh(), m.gpu.pending.mesh()); assertEquals(84, m.gpu.uploaded);
      var pending = m.gpu.pending;
      m.gpu.uploaded = 0; m.gpu.maintenance = 0;
      assertTrue(m.run(940)); m.pending(upload, false);
      assertSame(pending, m.gpu.resident.get(upload.position())); assertEquals(940, m.gpu.uploaded);
      exactPoints(upload, m.gpu.memory.get(upload.position()), 23);
    }
  }

  @Test
  void everyCallbackBoundaryRevokesOldAcknowledgementAfterReplacementOrClosure() {
    for (String point : List.of("bytes", "compact", "compacting", "pending", "contains", "stage", "advance"))
      for (boolean close : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) {
        try (var m = new Model()) {
          var upload = m.ready(m.scene, 1).getFirst();
          var hook = once(point, () -> { if (close) m.closed = true; else m.owner = new Object(); });
          if (after) m.gpu.after = hook; else m.gpu.before = hook;
          assertFalse(m.run(1024)); m.pending(upload, true);
          assertFalse(m.scene.rejected());
        }
      }
  }

  @Test
  void completionVerificationCannotAcknowledgeAfterSameObjectReactivation() {
    try (var m = new Model()) {
      var upload = m.ready(m.scene, 1).getFirst(); var once = new AtomicBoolean();
      m.gpu.after = event -> {
        if (event.equals("contains") && m.gpu.advances == 1 && once.compareAndSet(false, true))
          m.activation++;
      };
      assertFalse(m.run(1024)); m.pending(upload, true);
      assertSame(upload.mesh(), m.gpu.resident.get(upload.position()).mesh());
      m.gpu.after = event -> {};
      assertTrue(m.run(16)); m.pending(upload, false); assertEquals(1, m.gpu.advances);
    }
  }

  @Test
  void mismatchedEquivalentForeignOrUnpublishedCompletionsCannotAcknowledge() {
    for (int mode = 0; mode < 3; mode++) {
      try (var m = new Model()) {
        var u = m.ready(m.scene, 1).getFirst();
        if (mode == 0) m.gpu.returned = new ChunkScene.Upload(u.epoch(), u.position(), u.version(), u.mesh(), u.cells(), u.level());
        if (mode == 1) m.gpu.returned = new ChunkScene.Upload(u.epoch() + 1, u.position(), u.version(), u.mesh(), u.cells(), u.level());
        if (mode == 2) m.gpu.publish = false;
        assertThrows(IllegalStateException.class, () -> m.run(1024)); m.pending(u, true);
      }
    }
  }

  @Test
  void foreignPendingAndStageWithoutTheExactRetainedReceiptRejectBeforeAdvance() {
    try (var m = new Model()) {
      var own = m.ready(m.scene, 1).getFirst();
      var foreign = m.create(new Source(41), 0); var other = m.ready(foreign, 1).getFirst();
      m.gpu.stage(other); assertThrows(IllegalStateException.class, () -> m.run(1024));
      assertEquals(0, m.gpu.advances); m.pending(own, true);
    }
    try (var m = new Model()) {
      var u = m.ready(m.scene, 1).getFirst(); m.gpu.retainStage = false;
      assertThrows(IllegalStateException.class, () -> m.run(1024));
      assertEquals(0, m.gpu.advances); m.pending(u, true);
    }
  }

  @Test
  void currentStageRejectionMarksPressureButCompactionAndObsoleteReturnsDoNot() {
    for (int mode = 0; mode < 3; mode++) {
      try (var m = new Model()) {
        var u = m.ready(m.scene, 1).getFirst(); m.gpu.stageAccepted = false;
        int selected = mode;
        m.gpu.after = once("stage", () -> {
          if (selected == 1) m.gpu.compacting = true;
          if (selected == 2) m.owner = new Object();
        });
        assertEquals(mode != 2, m.run(1024)); assertEquals(mode == 0, m.scene.rejected());
        m.pending(u, true); assertEquals(0, m.gpu.advances);
      }
    }
  }

  @Test
  void backendFaultsRetainUnreadableIdentityWithoutAcknowledgementOrSuppression() {
    for (String point : List.of("bytes", "compact", "compacting", "pending", "contains", "stage", "advance"))
      for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
        try (var m = new Model()) {
          var u = m.ready(m.scene, 1).getFirst(); var primary = fault(error);
          Consumer<String> hook = once(point, () -> raise(primary));
          if (after) m.gpu.after = hook; else m.gpu.before = hook;
          assertSame(primary, assertThrows(Throwable.class, () -> m.run(1024)));
          m.pending(u, true); assertEquals(0, primary.getSuppressed().length); assertFalse(m.pump.busy());
        }
      }
  }

  @Test
  void callbackSuccessorKeepsItsOwnSceneAndCanCompleteAfterTheOldBackendThrows() {
    try (var m = new Model()) {
      var old = m.ready(m.scene, 1).getFirst();
      var successor = m.create(new Source(41), 0); var next = m.ready(successor, 1).getFirst();
      var primary = fault(true); var backend = new Gpu();
      m.gpu.after = once("advance", () -> { m.owner = new Object(); raise(primary); });
      assertSame(primary, assertThrows(AssertionError.class, () -> m.run(1024))); m.pending(old, true);
      assertTrue(m.pump.run(successor, () -> true, backend, 1024));
      exactPoints(next, backend.memory.get(next.position()), 41);
      assertTrue(successor.uploads(1, 1024).isEmpty()); m.pending(old, true);
    }
  }

  @Test
  void reentryRejectsBeforeCallbacksAndTheOuterWorkCanStillFinish() {
    try (var m = new Model()) {
      var u = m.ready(m.scene, 1).getFirst();
      m.gpu.before = event -> {
        int calls = m.gpu.calls.size();
        assertThrows(IllegalStateException.class, () -> m.run(1024));
        assertEquals(calls, m.gpu.calls.size());
      };
      assertTrue(m.run(1024)); m.pending(u, false); assertFalse(m.pump.busy());
    }
  }

  @Test
  void invalidCountersBudgetsAndInitiallyObsoleteFramesCannotAcknowledge() {
    for (int maintenance : new int[] {-1, 1025}) try (var m = new Model()) {
      var u = m.ready(m.scene, 1).getFirst(); m.gpu.maintenance = maintenance;
      assertThrows(IllegalStateException.class, () -> m.run(1024)); m.pending(u, true);
      assertEquals(0, m.gpu.stages);
    }
    try (var m = new Model()) {
      var u = m.ready(m.scene, 1).getFirst();
      assertThrows(IllegalArgumentException.class, () -> m.run(-1)); assertTrue(m.gpu.calls.isEmpty());
      assertFalse(m.pump.run(m.scene, () -> false, m.gpu, 1024)); assertTrue(m.gpu.calls.isEmpty());
      m.gpu.after = once("advance", () -> m.gpu.uploaded = 1025);
      assertThrows(IllegalStateException.class, () -> m.run(1024)); m.pending(u, true);
    }
  }

  @Test
  void residentFastPathAcknowledgesAtMostFourCandidatesWithoutAdditionalTransfer() {
    try (var m = new Model()) {
      var surface = new ChunkScene(new ChunkScene.Source() {
        public boolean surfaces() { return true; }
        public SceneChunk load(ChunkPos position, CancellationToken token) {
          return SceneChunk.isosurface(position, 4, token, (x, y, z) -> z - 1.257, 0);
        }
      }, m.workers, new ChunkScene.Config(4, 1, 0, 2, 1, 2, 64L << 20, false, 125));
      m.scenes.add(surface);
      var uploads = m.ready(surface, 5);
      for (var upload : uploads) m.gpu.resident.put(upload.position(), upload);
      int before = uploads.size();
      assertTrue(m.pump.run(surface, () -> true, m.gpu, 16));
      assertEquals(before - 4, surface.uploads(128, 1 << 20).size());
      assertEquals(0, m.gpu.stages); assertEquals(0, m.gpu.advances); assertEquals(0, m.gpu.uploaded);
    }
  }
}
