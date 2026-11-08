package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred CPU-scene completion, terminal failure, closure and reservation ownership definitions. */
class ChunkSceneCompletionTest {
  private static final long TIMEOUT = 10;
  private static final class Workers extends AbstractExecutorService {
    final Queue<Runnable> tasks = new ArrayDeque<>();
    final List<Future<?>> futures = new ArrayList<>();
    volatile Runnable afterBody;
    volatile java.util.function.Consumer<Future<?>> cancelAction;
    volatile Error cancelFailure;
    Error admission;
    boolean inline, stopped;
    int readFault;
    protected <T> RunnableFuture<T> newTaskFor(Runnable body, T result) {
      final int fault = readFault;
      var future = new FutureTask<T>(() -> { body.run(); var after = afterBody; if (after != null) after.run(); return result; }) {
        public boolean cancel(boolean interrupt) {
          var action = cancelAction; var failure = cancelFailure; boolean accepted = super.cancel(interrupt);
          if (action != null) action.accept(this); if (failure != null) throw failure; return accepted;
        }
        public boolean isDone() { if (fault == 1) throw new AssertionError("status"); return fault > 1 || super.isDone(); }
        public T get() throws InterruptedException, ExecutionException {
          if (fault == 2) throw new AssertionError("read"); if (fault == 3) throw new InterruptedException("read"); return super.get();
        }
      };
      futures.add(future); return future;
    }
    public void execute(Runnable task) {
      if (stopped) throw new RejectedExecutionException("closed fixture");
      if (admission != null && !inline) throw admission;
      if (inline) task.run(); else tasks.add(task);
      if (admission != null) throw admission;
    }
    void run() { tasks.remove().run(); }
    public void shutdown() { shutdownNow(); }
    public List<Runnable> shutdownNow() {
      stopped = true; cancelAction = null; cancelFailure = null;
      var queued = new ArrayList<>(tasks); tasks.clear(); futures.forEach(f -> f.cancel(true)); return queued;
    }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped; }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
  }

  private static final class Form implements AutoCloseable {
    final Workers workers = new Workers();
    final RenderPressure pressure = new RenderPressure();
    final AtomicReference<Error> profileFailure = new AtomicReference<>(), sourceFailure = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger(), profiles = new AtomicInteger(), sourceCloses = new AtomicInteger();
    java.util.function.Consumer<CancellationToken> loaded;
    final ChunkScene scene;
    final boolean surface;
    final Vector3d center = new Vector3d(1), eye = new Vector3d(1);
    Form(int kind) { this(kind, false); }
    Form(int kind, boolean preload) {
      surface = kind == 2;
      if (!preload) for (int i = 1; i <= 3; i++) pressure.observe(i * 2_000_000_000L, 1, 0, 0, true);
      var source = new ChunkScene.Source() {
        public boolean surfaces() { return surface; }
        public SceneChunk load(ChunkPos position, CancellationToken token) {
          calls.incrementAndGet(); var setup = loaded; if (setup != null) setup.accept(token);
          var failure = sourceFailure.get(); if (failure != null) throw failure;
          return surface ? SceneChunk.isosurface(position, 4, token, (x, y, z) -> x - 1.25, 0)
              : SceneChunk.exact(VoxelChunk.uniform(position, 4, 1));
        }
        public void close() { sourceCloses.incrementAndGet(); }
      };
      scene = new ChunkScene(source, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, kind == 1, 125), pressure,
          position -> { profiles.incrementAndGet(); var failure = profileFailure.get(); if (failure != null) throw failure; return null; });
    }
    void tick() { scene.tick(center, eye, 8); }
    void meshQueued() {
      for (int i = 0; i < 200; i++) {
        tick(); if (scene.statistics().generated() == (surface ? 1 : 27) && scene.statistics().activeJobs() == 1) return;
        assertFalse(workers.tasks.isEmpty()); workers.run();
      }
      fail("Mesh did not reach its queue");
    }
    void settle() {
      for (int i = 0; i < 200; i++) {
        tick(); for (var upload : scene.uploads(8, 1 << 20)) scene.uploaded(upload); scene.displaySet();
        if (scene.statistics().meshes() > 0 && scene.statistics().activeJobs() == 0) return;
        assertFalse(workers.tasks.isEmpty()); workers.run();
      }
      fail("Scene did not settle");
    }
    void noReservation() { assertEquals(0, scene.statistics().activeJobs()); assertEquals(0, scene.statistics().reserved()); }
    public void close() { scene.close(); workers.shutdownNow(); }
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try { assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS)); return; }
      catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private static Error unreadableCancellation(AtomicInteger reads) {
    return new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    };
  }

  @Test
  void tokenListenerClosingDuringCameraEvictionCannotDoubleSettleOrInvalidateTraversal() {
    for (int kind = 0; kind < 3; kind++) try (var form = new Form(kind)) {
      form.loaded = token -> token.onCancel(form.scene::close);
      form.tick(); form.workers.run(); form.pressure.pause(); form.center.set(1000); form.eye.set(1000);
      assertDoesNotThrow(() -> form.scene.tick(form.center, form.eye, 0));
      form.noReservation(); assertEquals(0, form.scene.statistics().resident()); assertEquals(0, form.scene.statistics().cpuBytes());
      assertEquals(1, form.sourceCloses.get()); assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
    }
  }

  @Test
  void throwingTokenAndFutureNotificationsCannotStrandOtherJobsOrSourceClosure() {
    try (var form = new Form(0, true)) {
      var reads = new AtomicInteger(); var events = new ArrayList<String>();
      var tokenFault = unreadableCancellation(reads); var futureFault = unreadableCancellation(reads);
      form.loaded = token -> { int index = form.calls.get() - 1; token.onCancel(() -> { events.add("token-" + index); throw tokenFault; }); };
      form.workers.cancelAction = future -> events.add("future-" + form.workers.futures.indexOf(future));
      form.workers.cancelFailure = futureFault;
      form.tick(); assertEquals(2, form.scene.statistics().activeJobs()); form.workers.run(); form.workers.run();
      assertDoesNotThrow(form.scene::close); form.noReservation();
      assertEquals(List.of("token-0", "future-0", "token-1", "future-1"), events);
      assertEquals(1, form.sourceCloses.get()); assertEquals(0, form.scene.statistics().resident()); assertEquals(0, form.scene.statistics().cpuBytes());
      assertEquals(0, reads.get()); assertEquals(0, tokenFault.getSuppressed().length); assertEquals(0, futureFault.getSuppressed().length);
    }
  }

  @Test
  void terminalDataAndMeshFaultsKeepPriorityWhenCancellationNotificationsAlsoFail() {
    for (int kind = 0; kind < 3; kind++) for (boolean mesh : new boolean[] {false, true}) try (var form = new Form(kind)) {
      var reads = new AtomicInteger(); var attempts = new AtomicInteger(); var tokenFault = unreadableCancellation(reads); var futureFault = unreadableCancellation(reads);
      if (mesh) form.meshQueued(); else form.loaded = token -> token.onCancel(() -> { form.noReservation(); throw tokenFault; });
      if (!mesh) form.tick();
      long bytes = form.scene.statistics().cpuBytes(); var primary = new AssertionError("primary terminal");
      form.workers.afterBody = () -> { throw primary; };
      form.workers.cancelAction = future -> { form.noReservation(); attempts.incrementAndGet(); };
      form.workers.cancelFailure = futureFault; form.workers.run();
      form.pressure.observe(8_000_000_000L, 1, 0, 0, true); // Defer new data without hiding failure feedback.
      assertDoesNotThrow(form::tick); form.noReservation();
      assertTrue(form.scene.statistics().status().contains("primary terminal")); assertEquals(bytes, form.scene.statistics().cpuBytes());
      assertEquals(1, attempts.get()); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      assertEquals(0, tokenFault.getSuppressed().length); assertEquals(0, futureFault.getSuppressed().length);
    }
  }

  @Test
  void inlineAdmissionFaultSettlesBeforeNotifyingTheRegisteredSourceListener() {
    for (int kind = 0; kind < 3; kind++) for (boolean mesh : new boolean[] {false, true}) try (var form = new Form(kind)) {
      var reads = new AtomicInteger(); var notifications = new AtomicInteger(); var secondary = unreadableCancellation(reads);
      if (mesh) { form.settle(); form.eye.set(1000); }
      else form.loaded = token -> token.onCancel(() -> { form.noReservation(); notifications.incrementAndGet(); throw secondary; });
      long generated = form.scene.statistics().generated(), meshes = form.scene.statistics().meshes(), bytes = form.scene.statistics().cpuBytes();
      var primary = new AssertionError("primary admission"); form.workers.inline = true; form.workers.admission = primary;
      form.workers.afterBody = () -> form.pressure.observe(8_000_000_000L, 1, 0, 0, true);
      assertDoesNotThrow(form::tick); form.noReservation();
      assertTrue(form.scene.statistics().status().contains("primary admission")); assertEquals(mesh ? 0 : 1, notifications.get());
      assertEquals(generated, form.scene.statistics().generated()); assertEquals(meshes, form.scene.statistics().meshes()); assertEquals(bytes, form.scene.statistics().cpuBytes());
      assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, secondary.getSuppressed().length);
    }
  }

  @Test
  void futureCancellationCanAdmitASameEntrySuccessorWithoutOldEvictionRemovingIt() {
    try (var form = new Form(2)) {
      form.tick(); form.workers.run(); var reentered = new AtomicBoolean();
      form.workers.cancelAction = future -> {
        if (reentered.compareAndSet(false, true)) {
          form.noReservation(); form.center.set(1); form.eye.set(1); form.scene.tick(form.center, form.eye, 0);
        }
      };
      form.center.set(1000); form.eye.set(1000); assertDoesNotThrow(() -> form.scene.tick(form.center, form.eye, 0));
      assertTrue(reentered.get()); assertEquals(2, form.workers.futures.size()); assertFalse(form.workers.futures.getLast().isCancelled());
      assertEquals(1, form.scene.statistics().activeJobs()); assertTrue(form.scene.statistics().reserved() > 0);
      form.workers.run(); form.pressure.pause(); form.tick(); form.noReservation();
      assertEquals(1, form.scene.statistics().generated()); assertEquals(1, form.scene.statistics().resident()); assertEquals(0, form.sourceCloses.get());
    }
  }

  @Test
  void aLateSourceReceiptCannotReviveAClosedSceneOrCloseTheSharedExecutor() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var closes = new AtomicInteger();
    var retained = new AtomicReference<VoxelChunk>();
    var source = new ChunkScene.Source() {
      public SceneChunk load(ChunkPos position, CancellationToken token) {
        var chunk = VoxelChunk.uniform(position, 4, 1); retained.set(chunk); entered.countDown();
        await(release); // Deliberately returns after cancellation; close does not preempt host work.
        return SceneChunk.exact(chunk);
      }
      public void close() { closes.incrementAndGet(); }
    };
    try (var workers = Executors.newSingleThreadExecutor();
        var scene = new ChunkScene(source, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 1, 64L << 20, false, 125))) {
      scene.tick(new Vector3d(1), new Vector3d(1), 8);
      try {
        assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); scene.close(); scene.close(); assertEquals(1, closes.get());
      } finally { release.countDown(); }
      workers.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
      for (int i = 0; i < 12; i++) scene.tick(new Vector3d(1), new Vector3d(1), 8);
      assertEquals(0, scene.statistics().resident()); assertEquals(0, scene.statistics().activeJobs());
      assertEquals(0, scene.statistics().reserved()); assertEquals(0, scene.statistics().cpuBytes());
      assertTrue(scene.uploads(8, 1 << 20).isEmpty()); assertFalse(scene.hasDisplay());
      for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) assertEquals(1, retained.get().getVoxelTypeId(x, y, z));
    }
    assertEquals(1, closes.get());
  }

  @Test
  void preparedDataKeepsItsReservationUntilRealWorkerReturnsNormally() throws Exception {
    for (int kind : new int[] {0, 2}) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      try (var real = Executors.newSingleThreadExecutor(); var form = new Form(kind)) {
        form.workers.afterBody = () -> { entered.countDown(); await(release); }; form.tick();
        long reserved = form.scene.statistics().reserved(); real.execute(form.workers.tasks.remove());
        try {
          assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); form.pressure.pause();
          for (int i = 0; i < 12; i++) { form.tick(); assertEquals(0, form.scene.statistics().generated()); assertEquals(reserved, form.scene.statistics().reserved()); }
          assertEquals(1, form.scene.statistics().activeJobs()); release.countDown(); real.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.tick(); assertEquals(1, form.scene.statistics().generated()); form.noReservation();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void lateWorkerFaultDiscardsAlreadyPreparedDataAndReleasesCapacity() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var real = Executors.newSingleThreadExecutor(); var form = new Form(2)) {
      form.workers.afterBody = () -> { entered.countDown(); await(release); throw new AssertionError("late data"); };
      form.tick(); real.execute(form.workers.tasks.remove());
      try {
        assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); form.tick(); assertEquals(0, form.scene.statistics().generated());
        release.countDown(); real.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.tick(); form.noReservation();
        assertEquals(0, form.scene.statistics().cpuBytes()); assertTrue(form.scene.statistics().status().contains("late data"));
        for (int i = 0; i < 12; i++) form.tick(); assertEquals(1, form.calls.get()); assertTrue(form.workers.tasks.isEmpty());
      } finally { release.countDown(); }
    }
  }

  @Test
  void preparedGreedyPointAndSurfaceMeshesWaitForRealCompleteWorkerReturn() throws Exception {
    for (int kind = 0; kind < 3; kind++) for (boolean late : new boolean[] {false, true}) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
      try (var real = Executors.newSingleThreadExecutor(); var form = new Form(kind)) {
        form.meshQueued(); long bytes = form.scene.statistics().cpuBytes(), reserved = form.scene.statistics().reserved();
        form.workers.afterBody = () -> { entered.countDown(); await(release); if (late) throw new AssertionError("late mesh"); };
        real.execute(form.workers.tasks.remove());
        try {
          assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); form.pressure.pause();
          for (int i = 0; i < 12; i++) {
            form.tick(); assertEquals(0, form.scene.statistics().meshes()); assertEquals(bytes, form.scene.statistics().cpuBytes());
            assertEquals(reserved, form.scene.statistics().reserved()); assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
          }
          release.countDown(); real.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.tick(); form.noReservation();
          if (late) {
            assertEquals(0, form.scene.statistics().meshes()); assertEquals(bytes, form.scene.statistics().cpuBytes());
            assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
          } else {
            assertEquals(1, form.scene.statistics().meshes()); var upload = form.scene.uploads(8, 1 << 20).getFirst();
            assertTrue(upload.mesh().byteSize() > 0); assertEquals(form.surface, upload.cells() == null);
          }
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void profilingFailureBeforeAnyMeshReceiptSettlesAndDoesNotRetryEveryFrame() {
    for (int kind = 0; kind < 3; kind++) try (var form = new Form(kind)) {
      form.meshQueued(); form.profileFailure.set(new AssertionError("mesh profiling")); form.workers.run(); form.tick(); form.noReservation();
      assertEquals(0, form.scene.statistics().meshes()); assertTrue(form.scene.statistics().status().contains("mesh profiling"));
      for (int i = 0; i < 12; i++) form.tick(); assertEquals(1, form.profiles.get()); assertTrue(form.workers.tasks.isEmpty());
    }
  }

  @Test
  void failedLodReplacementPreservesPreviouslyAcceptedMeshAndCpuAccounting() {
    for (int kind = 0; kind < 3; kind++) try (var form = new Form(kind)) {
      form.settle(); var before = form.scene.statistics(); var visible = form.scene.displaySet();
      form.profileFailure.set(new AssertionError("replacement profiling")); form.eye.set(1000); form.tick();
      assertEquals(1, form.scene.statistics().activeJobs()); form.workers.run(); form.tick(); form.noReservation();
      assertEquals(before.meshes(), form.scene.statistics().meshes()); assertEquals(before.meshed(), form.scene.statistics().meshed());
      assertEquals(before.cpuBytes(), form.scene.statistics().cpuBytes()); assertEquals(visible, form.scene.displaySet());
      assertTrue(form.scene.statistics().status().contains("replacement profiling"));
    }
  }

  @Test
  void dataAndMeshAdmissionErrorsBeforeOrAfterInlineWorkCannotLeakReservations() {
    for (boolean mesh : new boolean[] {false, true}) for (boolean inline : new boolean[] {false, true})
      try (var form = new Form(2)) {
        if (mesh) { form.settle(); form.eye.set(1000); }
        long generated = form.scene.statistics().generated(), meshes = form.scene.statistics().meshes(), bytes = form.scene.statistics().cpuBytes();
        form.workers.inline = inline; form.workers.admission = new AssertionError("submission"); form.tick(); form.noReservation();
        form.tick(); form.noReservation(); assertEquals(generated, form.scene.statistics().generated()); assertEquals(meshes, form.scene.statistics().meshes());
        assertEquals(bytes, form.scene.statistics().cpuBytes()); assertTrue(form.scene.statistics().status().contains("submission"));
      }
  }

  @Test
  void externalFutureCancellationSettlesOnceAndRetainsItsExistingExplicitCapacityPolicy() {
    try (var form = new Form(2)) {
      form.tick(); var future = form.workers.futures.getFirst(); future.cancel(false); form.pressure.pause(); form.tick(); form.noReservation();
      assertEquals(0, form.scene.statistics().generated()); form.workers.run(); form.pressure.resume(10_000_000_000L); form.tick();
      assertEquals(1, form.scene.statistics().activeJobs()); form.workers.run(); form.pressure.pause(); form.tick(); form.noReservation();
      assertEquals(1, form.scene.statistics().generated());
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsDoNotStrandAnUnrunSourceTask() {
    for (int fault = 1; fault <= 3; fault++) try (var form = new Form(2)) {
      form.workers.readFault = fault; form.tick(); form.tick(); form.noReservation();
      assertEquals(fault == 3, Thread.currentThread().isInterrupted()); Thread.interrupted();
      assertEquals(0, form.calls.get()); assertEquals(0, form.scene.statistics().generated()); assertTrue(form.workers.futures.getFirst().isCancelled());
    } finally { Thread.interrupted(); }
  }

  @Test
  void receiptAndTerminalSettlementBothRespectTheFrameCompletionBudget() {
    try (var form = new Form(0, true)) {
      form.tick(); assertEquals(2, form.scene.statistics().activeJobs()); form.workers.run(); form.workers.run(); form.pressure.pause();
      form.scene.tick(form.center, form.eye, 1); assertEquals(1, form.scene.statistics().generated()); assertEquals(1, form.scene.statistics().activeJobs());
      long reserved = form.scene.statistics().reserved(); form.scene.tick(form.center, form.eye, 0); assertEquals(reserved, form.scene.statistics().reserved());
      form.scene.tick(form.center, form.eye, 1); assertEquals(2, form.scene.statistics().generated()); form.noReservation();
    }
  }

  @Test
  void terminalFailureCannotConsumeAnExtraHealthyCompletionBeyondTheFrameBudget() {
    try (var form = new Form(0, true)) {
      var calls = new AtomicInteger(); form.workers.afterBody = () -> { if (calls.incrementAndGet() == 1) throw new AssertionError("first job"); };
      form.tick(); form.workers.run(); form.workers.run(); form.pressure.pause();
      form.scene.tick(form.center, form.eye, 1); assertEquals(0, form.scene.statistics().generated()); assertEquals(1, form.scene.statistics().activeJobs());
      form.scene.tick(form.center, form.eye, 1); assertEquals(1, form.scene.statistics().generated()); form.noReservation();
    }
  }

  @Test
  void closingSceneWithHeldPreparedPayloadDropsOwnershipAndReservation() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var real = Executors.newSingleThreadExecutor(); var form = new Form(2)) {
      form.workers.afterBody = () -> { entered.countDown(); await(release); throw new AssertionError("late closed"); };
      form.tick(); real.execute(form.workers.tasks.remove());
      try {
        assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); form.tick(); form.scene.close(); release.countDown(); real.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        form.tick(); form.noReservation(); assertEquals(0, form.scene.statistics().resident()); assertEquals(0, form.scene.statistics().cpuBytes());
        assertTrue(form.scene.wanted().isEmpty()); assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
      } finally { release.countDown(); }
    }
  }

  @Test
  void failureMessageClosureCannotRepopulateOrRescheduleTheClosedScene() {
    try (var form = new Form(2)) {
      form.sourceFailure.set(new AssertionError() { public String getMessage() { form.scene.close(); return "closed feedback"; } });
      form.tick(); form.workers.run(); form.tick(); form.noReservation(); assertEquals(0, form.scene.statistics().resident());
      assertTrue(form.scene.wanted().isEmpty()); assertTrue(form.workers.tasks.isEmpty()); assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
    }
  }

  @Test
  void travellingAwayFromHeldWorkCannotSettleItsNewerReservation() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    try (var real = Executors.newSingleThreadExecutor(); var form = new Form(2)) {
      form.workers.afterBody = () -> { entered.countDown(); await(release); }; form.tick(); real.execute(form.workers.tasks.remove());
      try {
        assertTrue(entered.await(TIMEOUT, TimeUnit.SECONDS)); form.tick(); form.center.set(1000); form.eye.set(1000); form.tick();
        long reserved = form.scene.statistics().reserved(); assertTrue(reserved > 0); assertEquals(1, form.scene.statistics().activeJobs());
        release.countDown(); real.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.tick();
        assertEquals(reserved, form.scene.statistics().reserved()); assertEquals(1, form.scene.statistics().activeJobs()); assertEquals(0, form.scene.statistics().generated());
      } finally { release.countDown(); }
    }
  }

  @Test
  void unreadableBlankAndLongFailureMessagesStayUsableBoundedAndUnmodified() {
    for (int mode = 0; mode < 3; mode++) try (var form = new Form(2)) {
      final int kind = mode;
      var failure = new AssertionError() { public String getMessage() { if (kind == 0) throw new AssertionError("reader"); return kind == 1 ? " " : "雪😀".repeat(2000); } };
      form.sourceFailure.set(failure); form.tick(); form.workers.run(); form.tick(); form.noReservation();
      String prefix = "Chunk failed; Regenerate to retry: "; assertTrue(form.scene.statistics().status().startsWith(prefix));
      String detail = form.scene.statistics().status().substring(prefix.length()); assertFalse(detail.isBlank()); assertTrue(detail.length() <= 2048);
      if (mode == 2) assertFalse(Character.isHighSurrogate(detail.charAt(detail.length() - 2)));
      assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void closureDuringInlineSubmissionCannotAttachReturnedFutureOrContinueChunkAdmission() {
    for (int kind = 0; kind < 3; kind++) for (boolean mesh : new boolean[] {false, true}) try (var form = new Form(kind)) {
      if (mesh) { form.settle(); form.eye.set(1000); }
      form.workers.inline = true; form.workers.afterBody = form.scene::close; form.tick(); form.noReservation();
      assertEquals(0, form.scene.statistics().resident()); assertEquals(0, form.scene.statistics().cpuBytes());
      assertTrue(form.scene.wanted().isEmpty()); assertTrue(form.workers.tasks.isEmpty()); assertTrue(form.scene.uploads(8, 1 << 20).isEmpty());
    }
  }
}
