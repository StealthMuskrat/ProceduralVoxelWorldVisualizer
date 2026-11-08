package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred CPU owner definitions; Viewports' native/field adapters remain source-traced. */
class UiShutdownTest {
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    };
  }

  private static final class Job extends CompletableFuture<Void> {
    Runnable callback = () -> {};
    Throwable failure;
    boolean after;
    int attempts;
    public boolean cancel(boolean interrupt) {
      assertTrue(interrupt); attempts++; callback.run();
      if (failure != null && !after) UiShutdown.rethrow(failure);
      boolean result = super.cancel(interrupt);
      UiShutdown.rethrow(failure);
      return result;
    }
  }

  private static final class Source implements AutoCloseable {
    final int[] samples;
    Runnable callback = () -> {};
    Throwable failure;
    int closes;
    Source(int... samples) { this.samples = samples.clone(); }
    public void close() { closes++; callback.run(); UiShutdown.rethrow(failure); }
  }

  @Test
  void everyCleanupFailureRunsAllCapturedTailsAfterEveryPeerDetaches() {
    for (int slot = 0; slot < 12; slot++) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var reads = new AtomicInteger(); var primary = fault(error, reads);
        var sealed = new AtomicInteger(); var attempts = new ArrayList<Integer>();
        var effects = new HashSet<Integer>(); var owners = new ArrayList<Supplier<Runnable>>();
        var plan = new AtomicReference<Runnable>(); int failed = slot;
        for (int i = 0; i < 12; i++) {
          int index = i;
          owners.add(() -> {
            sealed.incrementAndGet();
            return () -> {
              assertEquals(12, sealed.get()); plan.get().run(); attempts.add(index);
              if (index == failed && !after) UiShutdown.rethrow(primary);
              assertTrue(effects.add(index));
              if (index == failed) UiShutdown.rethrow(primary);
            };
          });
        }
        plan.set(UiShutdown.prepare(owners)); owners.clear(); assertTrue(attempts.isEmpty());
        assertSame(primary, assertThrows(Throwable.class, plan.get()::run));
        assertEquals(java.util.stream.IntStream.range(0, 12).boxed().toList(), attempts);
        assertEquals(after ? 12 : 11, effects.size()); assertDoesNotThrow(plan.get()::run);
        assertEquals(12, attempts.size()); assertEquals(0, reads.get());
        assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void preparationFaultsCannotSkipPeersOrCapturedCleanupAndKeepPriority() {
    var reads = new AtomicInteger(); var primary = fault(true, reads); var later = fault(false, reads);
    var events = new ArrayList<String>();
    var plan = UiShutdown.prepare(List.of(
        () -> { events.add("detach-a"); UiShutdown.rethrow(primary); return null; },
        () -> { events.add("detach-b"); return () -> { events.add("close-b"); UiShutdown.rethrow(later); }; },
        () -> { events.add("detach-c"); UiShutdown.rethrow(later); return null; },
        () -> { events.add("detach-d"); return () -> events.add("close-d"); }));
    assertEquals(List.of("detach-a", "detach-b", "detach-c", "detach-d"), events);
    assertSame(primary, assertThrows(AssertionError.class, plan::run));
    assertEquals(List.of("detach-a", "detach-b", "detach-c", "detach-d", "close-b", "close-d"), events);
    assertDoesNotThrow(plan::run); assertEquals(0, reads.get());
    assertEquals(0, primary.getSuppressed().length); assertEquals(0, later.getSuppressed().length);
  }

  @Test
  void realInspectorCompilerAndRendererPeersAreSealedBeforeAnyNotification() {
    var requests = new InspectorRequests(); var inspector = new InspectorTask();
    var request = requests.begin(3, true); inspector.begin(request, null);
    var ticket = inspector.ticket(); var inspectorJob = new Job(); inspector.attach(request, inspectorJob);
    var compilers = new ArrayList<SourceCompilerTask<Source>>();
    var tickets = new ArrayList<SourceCompilerTask.Ticket>();
    var renderers = new ArrayList<RendererStartup<Object>>();
    var boxes = new ArrayList<PreviewMailbox<int[]>>();
    var sources = new ArrayList<Source>(); var jobs = new ArrayList<Job>();
    var checked = new AtomicInteger(); var closedRenderers = new AtomicInteger();
    Runnable observe = () -> {
      assertFalse(requests.current(request, 3)); assertFalse(inspector.current(ticket));
      assertFalse(inspector.pending()); assertNull(inspector.coordinate());
      assertFalse(inspector.publish(ticket, InspectorTask.Result.miss()));
      for (int i = 0; i < 3; i++) {
        assertFalse(compilers.get(i).eligible(tickets.get(i)));
        assertNull(compilers.get(i).future()); assertFalse(compilers.get(i).pendingResult());
        assertThrows(IllegalStateException.class, () -> compilers.getFirst().begin("late"));
        assertTrue(renderers.get(i).closed()); assertNull(renderers.get(i).value());
        assertFalse(renderers.get(i).retry()); assertFalse(boxes.get(i).publish(0, new int[] {-1}));
        assertNull(boxes.get(i).take());
      }
      checked.incrementAndGet();
    };
    var owners = new ArrayList<Supplier<Runnable>>();
    owners.add(() -> { requests.close(); return inspector.retireForClose(); });
    inspectorJob.callback = observe;
    for (int i = 0; i < 3; i++) {
      var compiler = new SourceCompilerTask<Source>(); var owner = compiler.begin("view" + i).ticket();
      var source = new Source(7 + i, 17 + i, 107 + i); var job = new Job();
      source.callback = job.callback = observe; compiler.attach(owner, job); compiler.publish(owner, source);
      var renderer = new RendererStartup<>(Object::new, r -> { observe.run(); closedRenderers.incrementAndGet(); });
      assertTrue(renderer.initialize()); var box = new PreviewMailbox<int[]>(); box.publish(0, source.samples);
      compilers.add(compiler); tickets.add(owner); sources.add(source); jobs.add(job);
      renderers.add(renderer); boxes.add(box);
      owners.add(compiler::retireForClose); owners.add(renderer::retireForClose);
      owners.add(() -> { box.close(); return null; });
    }
    var plan = UiShutdown.prepare(owners);
    assertEquals(0, checked.get()); assertEquals(0, inspectorJob.attempts);
    assertTrue(sources.stream().allMatch(s -> s.closes == 0)); plan.run();
    assertEquals(10, checked.get()); assertEquals(3, closedRenderers.get());
    assertEquals(1, inspectorJob.attempts);
    for (int i = 0; i < 3; i++) {
      assertEquals(1, sources.get(i).closes); assertEquals(1, jobs.get(i).attempts);
      assertArrayEquals(new int[] {7 + i, 17 + i, 107 + i}, sources.get(i).samples);
      compilers.get(i).close(); renderers.get(i).close();
    }
    inspector.close(); plan.run(); assertEquals(10, checked.get());
  }

  @Test
  void sourceDisposalAndEveryFutureRemainIndependentWhenCancellationFails() {
    for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var primary = fault(error, reads); var tail = fault(!error, reads);
      var compiler = new SourceCompilerTask<Source>(); var owner = compiler.begin("source").ticket();
      var source = new Source(23, 41, 59); var job = new Job(); var inspectorJob = new Job();
      var inspector = new InspectorTask(); var request = new InspectorRequests().begin(0, true);
      inspector.begin(request, null); inspector.attach(request, inspectorJob);
      compiler.attach(owner, job); compiler.publish(owner, source);
      source.failure = tail; job.failure = tail; inspectorJob.failure = primary;
      job.after = inspectorJob.after = after; var end = new AtomicInteger();
      var plan = UiShutdown.prepare(List.of(inspector::retireForClose, compiler::retireForClose,
          () -> () -> { end.incrementAndGet(); UiShutdown.rethrow(primary); }));
      assertSame(primary, assertThrows(Throwable.class, plan::run));
      assertEquals(1, source.closes); assertEquals(1, job.attempts); assertEquals(1, inspectorJob.attempts);
      assertEquals(after, job.isCancelled()); assertEquals(after, inspectorJob.isCancelled());
      assertEquals(1, end.get()); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      assertEquals(0, tail.getSuppressed().length); assertDoesNotThrow(plan::run);
      assertDoesNotThrow(inspector::close); assertDoesNotThrow(compiler::close);
    }
  }

  @Test
  void ownedMailboxRetirementDefersDisposalAndKeepsItOutsideTheMonitor() {
    var box = new OwnedPreviewMailbox<Source>(); long version = box.begin();
    var source = new Source(1, 2, 3); var observed = new AtomicInteger();
    source.callback = () -> {
      assertFalse(Thread.holdsLock(box)); assertFalse(box.current(version));
      assertFalse(box.pending()); assertNull(box.take());
      assertThrows(IllegalStateException.class, box::begin); box.close(); observed.incrementAndGet();
    };
    box.publish(version, source); var plan = box.retireForClose();
    assertEquals(0, source.closes); box.close(); assertEquals(0, source.closes);
    plan.run(); plan.run(); assertEquals(1, observed.get()); assertEquals(1, source.closes);
    var late = new Source(-1); assertFalse(box.publish(version, late)); assertEquals(1, late.closes);
  }

  @Test
  void ignoredInterruptionCannotPublishLateSourceAfterAllPeersDetach() throws Exception {
    var compiler = new SourceCompilerTask<Source>(); var owner = compiler.begin("held").ticket();
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(1);
    var source = new Source(11, 21, 111, 121); var accepted = new AtomicBoolean(true);
    var interrupted = new CountDownLatch(1); var other = new PreviewMailbox<int[]>();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var future = worker.submit(() -> {
        entered.countDown();
        try {
          while (true) try { release.await(); break; }
          catch (InterruptedException ignored) { interrupted.countDown(); }
          accepted.set(compiler.publish(owner, source));
        } finally { done.countDown(); }
      });
      compiler.attach(owner, future);
      try {
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var cleanup = UiShutdown.prepare(List.of(compiler::retireForClose,
            () -> { other.close(); return null; }));
        source.callback = () -> assertFalse(other.publish(0, new int[] {-1}));
        cleanup.run(); assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        release.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
        worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertFalse(accepted.get()); assertEquals(1, source.closes); assertFalse(compiler.pendingResult());
        assertArrayEquals(new int[] {11, 21, 111, 121}, source.samples); assertNull(other.take());
        assertTrue(future.isCancelled()); cleanup.run(); assertEquals(1, source.closes);
      } finally { release.countDown(); compiler.close(); }
    }
  }

  private static final class CursorBackend implements FlightCursor.Backend {
    boolean live = true, suspended;
    int mode = 1, restores, unsuspends;
    Throwable restoreFailure, suspensionFailure;
    Runnable restoring = () -> {};
    public boolean live(FlightCursor.Window window) { return live; }
    public boolean focused(FlightCursor.Window window) { return true; }
    public int mode(FlightCursor.Window window) { return mode; }
    public void mode(FlightCursor.Window window, int value) {
      if (value == 1) { restores++; restoring.run(); UiShutdown.rethrow(restoreFailure); }
      mode = value;
    }
    public boolean cursorUpdatesSuspended() { return suspended; }
    public void cursorUpdatesSuspended(boolean value) {
      if (!value) { unsuspends++; UiShutdown.rethrow(suspensionFailure); }
      suspended = value;
    }
  }

  @Test
  void cursorRestorationAttemptsSuspensionTailWithoutOverridingItsFirstFailure() {
    var reads = new AtomicInteger(); var primary = fault(true, reads); var tail = fault(false, reads);
    var backend = new CursorBackend(); var cursor = new FlightCursor(backend, 3);
    assertTrue(cursor.sync(new FlightCursor.Window(1, 17))); assertTrue(backend.suspended);
    backend.restoreFailure = primary; backend.suspensionFailure = tail;
    backend.restoring = () -> { assertNull(cursor.window()); cursor.close(); };
    var plan = cursor.retireForClose(); assertNull(cursor.window()); assertEquals(0, backend.restores);
    assertThrows(IllegalStateException.class, () -> cursor.sync(null));
    assertSame(primary, assertThrows(AssertionError.class, plan::run));
    assertEquals(1, backend.restores); assertEquals(1, backend.unsuspends);
    plan.run(); cursor.close(); assertEquals(1, backend.restores); assertEquals(1, backend.unsuspends);
    assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
  }

  @Test
  void deadCursorWindowAndForeignModeAreNotRestoredButOwnedSuspensionIsReleased() {
    for (boolean dead : new boolean[] {false, true}) {
      var backend = new CursorBackend(); var cursor = new FlightCursor(backend, 3);
      cursor.sync(new FlightCursor.Window(1, 17)); var cleanup = cursor.retireForClose();
      if (dead) backend.live = false; else backend.mode = 8;
      cleanup.run(); assertEquals(0, backend.restores); assertEquals(1, backend.unsuspends);
      assertFalse(backend.suspended); cleanup.run(); assertEquals(1, backend.unsuspends);
    }
  }

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

  @Test
  void realCpuSceneIsEmptyAndTerminalBeforeSourceAndQueuedJobCleanup() {
    var workers = new Workers();
    var closes = new AtomicInteger(); var sceneRef = new AtomicReference<ChunkScene>();
    var source = new ChunkScene.Source() {
      public SceneChunk load(ChunkPos p, io.voxelcreator.api.CancellationToken token) {
        token.throwIfCancelled(); return SceneChunk.exact(VoxelChunk.uniform(p, 4, 23));
      }
      public void close() {
        var scene = sceneRef.get(); assertEquals(0, scene.statistics().activeJobs());
        assertEquals(0, scene.statistics().cpuBytes()); assertEquals(0, scene.statistics().reserved());
        assertTrue(scene.wanted().isEmpty()); assertFalse(scene.hasPendingRemovals());
        scene.close(); closes.incrementAndGet();
      }
    };
    var scene = new ChunkScene(source, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, true, 27));
    sceneRef.set(scene); scene.tick(new Vector3d(1), new Vector3d(1), 4);
    assertTrue(scene.statistics().activeJobs() > 0);
    var plan = scene.retireForClose(); assertEquals(0, closes.get());
    assertEquals(0, scene.statistics().resident()); assertEquals(0, scene.statistics().activeJobs());
    scene.tick(new Vector3d(1000), new Vector3d(1000), 4); assertEquals(0, scene.statistics().resident());
    plan.run(); workers.drain(); plan.run(); assertEquals(1, closes.get());
    assertEquals(0, scene.statistics().cpuBytes()); workers.shutdownNow();
  }

  @Test
  void emptyAndCapturedPlansAreOnceOnlyAndDoNotFollowCallerMutation() {
    UiShutdown.prepare(List.of()).run(); var calls = new ArrayList<String>();
    Runnable[] callbacks = {() -> calls.add("first"), () -> calls.add("tail")};
    var plan = UiShutdown.once(callbacks); callbacks[1] = () -> fail("changed callback");
    plan.run(); plan.run(); assertEquals(List.of("first", "tail"), calls);
  }
}
