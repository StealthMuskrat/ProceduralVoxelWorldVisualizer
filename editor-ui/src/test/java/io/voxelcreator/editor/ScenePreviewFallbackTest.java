package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.render.ChunkScene;
import io.voxelcreator.render.SceneChunk;
import io.voxelcreator.storage.ChunkPos;
import io.voxelcreator.storage.VoxelChunk;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred fallback helper definitions with real CPU scenes and independent resource callbacks. */
class ScenePreviewFallbackTest {
  private record Event(String code, String detail) {}

  private static final class Workers extends AbstractExecutorService {
    final Queue<Runnable> tasks = new ArrayDeque<>();
    boolean stopped;
    public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); tasks.add(task); }
    public void shutdown() { shutdownNow(); }
    public List<Runnable> shutdownNow() { stopped = true; var old = new ArrayList<>(tasks); tasks.clear(); return old; }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped; }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
  }

  private static final class Source implements ChunkScene.Source {
    final int type;
    final VoxelChunk retained;
    int closes;
    Runnable closing;
    Error failure;
    Source(int type) { this.type = type; retained = VoxelChunk.uniform(new ChunkPos(-1, 0, 2), 4, type); }
    public SceneChunk load(ChunkPos position, CancellationToken token) {
      token.throwIfCancelled(); return SceneChunk.exact(VoxelChunk.uniform(position, 4, type));
    }
    public void close() { closes++; if (closing != null) closing.run(); if (failure != null) throw failure; }
  }

  private static final class Gpu {
    int closes;
    boolean released, after;
    Runnable closing;
    Throwable failure;
    void close() {
      closes++; if (closing != null) closing.run();
      if (failure != null && !after) raise(failure);
      released = true; if (failure != null) raise(failure);
    }
  }

  private static final class Model implements AutoCloseable {
    final Workers workers = new Workers();
    final List<Source> sources = new ArrayList<>();
    final List<ChunkScene> scenes = new ArrayList<>();
    final SourceCompilerTask<AutoCloseable> compiler = new SourceCompilerTask<>();
    final List<Event> events = new ArrayList<>();
    final List<String> attempts = new ArrayList<>();
    final int[] fixed = {7, 8, 17, 18};
    final Source oldSource = source(7);
    final ChunkScene oldScene = scene(oldSource);
    final Gpu oldGpu = new Gpu();
    ChunkScene scene = oldScene;
    Gpu gpu = oldGpu;
    Object failureOwner;
    long epoch = 7;
    int budget = 1024;
    boolean fallback, failed = true, dirty, closed, reportBefore;
    String key = "world", activeKey = "world", failedKey = "", message = "Streaming";
    Throwable reportFault;
    Consumer<String> reporting;

    Source source(int type) { var value = new Source(type); sources.add(value); return value; }
    ChunkScene scene(Source source) {
      var value = new ChunkScene(source, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, false, 125));
      scenes.add(value); return value;
    }

    boolean fail(Throwable primary) { return fail(scene, epoch, gpu, key, primary); }
    boolean fail(ChunkScene captured, long request, Gpu renderer, String requestedKey, Throwable primary) {
      return fail(captured, request, renderer, requestedKey, primary, compiler.generation());
    }
    boolean fail(ChunkScene captured, long request, Gpu renderer, String requestedKey, Throwable primary, long generation) {
      Object owner = new Object();
      return ScenePreviewFallback.fail(new ScenePreviewFallback.Owner() {
        public boolean eligible() {
          return !closed && compiler.current(generation) && captured != null && scene == captured && gpu == renderer
              && epoch == request && key.equals(requestedKey);
        }
        public void settle() {
          failureOwner = owner; scene = null; budget = 0; activeKey = ""; failedKey = requestedKey;
          fallback = true; failed = false; dirty = true; message = "Fixed preview";
        }
        public boolean current() {
          return !closed && failureOwner == owner && scene == null && gpu == renderer && epoch == request
              && compiler.current(generation) && key.equals(requestedKey) && failedKey.equals(requestedKey)
              && budget == 0 && activeKey.isEmpty() && fallback && !failed && dirty;
        }
      }, renderer::close, captured::close, primary,
          detail -> report("PRIMARY", detail), detail -> report("CLEANUP", detail));
    }

    void report(String code, String detail) {
      attempts.add(code); if (reportBefore && reportFault != null) raise(reportFault);
      events.add(new Event(code, detail)); if (reporting != null) reporting.accept(code);
      if (reportFault != null) raise(reportFault);
    }

    void successor() {
      scene = scene(source(23)); gpu = new Gpu(); failureOwner = null; budget = 2048;
      activeKey = key; failedKey = ""; fallback = failed = dirty = false; message = "Successor";
    }

    void healthySuccessor() {
      var current = scene; var source = sources.getLast();
      for (int i = 0; i < 200; i++) {
        current.tick(new Vector3d(1), new Vector3d(1), 8);
        while (!workers.tasks.isEmpty()) workers.tasks.remove().run();
        var uploads = current.uploads(8, 1 << 20);
        for (var upload : uploads) current.uploaded(upload);
        if (!uploads.isEmpty()) {
          assertTrue(current.hasDisplay()); assertEquals(0, source.closes);
          for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            assertEquals(23, source.retained.getVoxelTypeId(x, y, z));
          return;
        }
      }
      org.junit.jupiter.api.Assertions.fail("Successor did not produce an upload");
    }

    void closeDuringCallback() { closed = true; message = "Closed"; compiler.close(); }
    public void close() {
      closed = true; for (var source : sources) { source.closing = null; source.failure = null; }
      for (var scene : scenes) scene.close(); compiler.close(); workers.shutdownNow();
    }
  }

  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  private static Throwable fault(boolean error, AtomicInteger reads, Runnable reading) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); reading.run(); throw new AssertionError("unreadable"); }
    };
  }

  @Test
  void cleanupFaultsCannotPreventSettledFallbackOrPrimaryDiagnostic() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var m = new Model()) {
      var primaryReads = new AtomicInteger(); var gpuReads = new AtomicInteger(); var sourceReads = new AtomicInteger();
      var primary = fault(error, primaryReads, () -> {}); var cleanup = fault(!error, gpuReads, () -> {});
      m.oldGpu.failure = cleanup; m.oldGpu.after = after; m.oldSource.failure = (Error) fault(true, sourceReads, () -> {});
      var settled = new AtomicInteger();
      Runnable check = () -> {
        assertNull(m.scene); assertEquals(0, m.budget); assertEquals("", m.activeKey); assertEquals("world", m.failedKey);
        assertTrue(m.fallback); assertTrue(m.dirty); assertFalse(m.failed); assertEquals("Fixed preview", m.message);
        settled.incrementAndGet();
      };
      m.oldGpu.closing = check; m.oldSource.closing = check;
      assertTrue(m.fail(primary)); assertEquals(2, settled.get());
      assertEquals(1, m.oldGpu.closes); assertEquals(after, m.oldGpu.released); assertEquals(1, m.oldSource.closes);
      assertEquals(List.of(new Event("PRIMARY", primary.getClass().getName()), new Event("CLEANUP", cleanup.getClass().getName())), m.events);
      assertEquals(1, primaryReads.get()); assertEquals(1, gpuReads.get()); assertEquals(0, sourceReads.get());
      assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      assertArrayEquals(new int[] {7, 8, 17, 18}, m.fixed); m.successor(); m.healthySuccessor();
    }
  }

  @Test
  void sourceOnlyAndSharedCleanupFaultsRemainSeparateWithoutReplayOrThrowableMutation() {
    for (boolean shared : new boolean[] {false, true}) try (var m = new Model()) {
      var primaryReads = new AtomicInteger(); var cleanupReads = new AtomicInteger();
      var primary = fault(false, primaryReads, () -> {});
      Throwable cleanup;
      if (shared) { cleanup = primary; m.oldGpu.failure = primary; }
      else { cleanup = fault(true, cleanupReads, () -> {}); m.oldSource.failure = (Error) cleanup; }
      assertTrue(m.fail(primary)); assertEquals(1, m.oldGpu.closes); assertEquals(1, m.oldSource.closes);
      assertEquals(List.of(new Event("PRIMARY", primary.getClass().getName()), new Event("CLEANUP", cleanup.getClass().getName())), m.events);
      assertEquals(shared ? 2 : 1, primaryReads.get()); assertEquals(shared ? 0 : 1, cleanupReads.get());
      assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      assertFalse(m.fail(m.oldScene, 7, m.oldGpu, "world", primary));
      assertEquals(1, m.oldGpu.closes); assertEquals(1, m.oldSource.closes); assertEquals(2, m.events.size());
    }
  }

  @Test
  void obsoleteSceneEpochRendererKeyAndClosureCannotRetireCurrentResources() {
    for (String obsolete : List.of("scene", "epoch", "renderer", "key", "compiler", "close")) try (var m = new Model()) {
      var reads = new AtomicInteger(); var primary = fault(true, reads, () -> {});
      switch (obsolete) {
        case "scene" -> m.successor(); case "epoch" -> m.epoch++; case "renderer" -> m.gpu = new Gpu();
        case "key" -> m.key = "changed"; case "compiler" -> m.compiler.begin("world"); default -> m.closeDuringCallback();
      }
      String message = m.message;
      assertFalse(m.fail(m.oldScene, 7, m.oldGpu, "world", primary, 0)); assertEquals(message, m.message);
      assertEquals(0, m.oldGpu.closes); assertEquals(0, m.oldSource.closes); assertEquals(0, reads.get()); assertTrue(m.events.isEmpty());
    }
  }

  @Test
  void gpuCleanupReentryCannotCancelTheReplacementSceneOrLaunchOldFixedWork() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); m.oldGpu.closing = () -> { if (close) m.closeDuringCallback(); else m.successor(); };
      m.oldGpu.failure = fault(true, reads, () -> {}); m.oldSource.failure = new AssertionError("old source");
      assertFalse(m.fail(fault(false, reads, () -> {}))); assertEquals(1, m.oldSource.closes); assertEquals(1, m.oldGpu.closes);
      assertEquals(0, reads.get()); assertTrue(m.events.isEmpty()); assertEquals(close ? "Closed" : "Successor", m.message);
      if (!close) { assertEquals(0, m.gpu.closes); m.healthySuccessor(); }
    }
  }

  @Test
  void sourceCloseReentryPreservesItsNewSceneAndDoesNotReportOldFaults() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); m.oldSource.closing = () -> {
        assertEquals(1, m.oldGpu.closes); if (close) m.closeDuringCallback(); else m.successor();
      };
      m.oldSource.failure = (Error) fault(true, reads, () -> {});
      assertFalse(m.fail(fault(false, reads, () -> {}))); assertEquals(1, m.oldSource.closes); assertEquals(0, reads.get());
      assertTrue(m.events.isEmpty()); assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthySuccessor();
    }
  }

  @Test
  void primaryDescriptionReentryCannotPublishOntoCloseOrSameEpochSuccessor() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var cleanupReads = new AtomicInteger();
      var primary = fault(false, reads, () -> { if (close) m.closeDuringCallback(); else m.successor(); });
      m.oldGpu.failure = fault(true, cleanupReads, () -> {});
      assertFalse(m.fail(primary)); assertEquals(1, reads.get()); assertEquals(0, cleanupReads.get()); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthySuccessor();
    }
  }

  @Test
  void unavailableDiagnosticsPreserveFallbackAndCannotOverwriteAReportAdmittedSuccessor() {
    for (boolean before : new boolean[] {false, true}) for (boolean replace : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); m.reportBefore = before; m.reportFault = new AssertionError("diagnostic");
      m.oldGpu.failure = fault(true, reads, () -> {}); m.reporting = code -> { if (replace) m.successor(); };
      boolean current = m.fail(new IllegalStateException("primary"));
      assertEquals(before || !replace, current); assertEquals(before || !replace ? 1 : 0, reads.get());
      assertEquals(before ? List.of("PRIMARY", "CLEANUP") : replace ? List.of("PRIMARY") : List.of("PRIMARY", "CLEANUP"), m.attempts);
      assertEquals(before ? 0 : replace ? 1 : 2, m.events.size());
      if (!before && replace) { assertEquals("Successor", m.message); m.healthySuccessor(); }
      else { assertEquals("Fixed preview", m.message); assertTrue(m.fallback); }
    }
  }

  @Test
  void cleanupDescriptionNestedFallbackUsesANewTicketEvenAtTheSameEpoch() {
    try (var m = new Model()) {
      var reads = new AtomicInteger(); var owner = new AtomicReference<Object>();
      m.oldGpu.failure = fault(true, reads, () -> {
        owner.set(m.failureOwner); m.successor(); assertTrue(m.fail(new IllegalStateException("nested")));
      });
      assertFalse(m.fail(new IllegalStateException("outer"))); assertEquals(1, reads.get()); assertNotSame(owner.get(), m.failureOwner);
      assertEquals(List.of(new Event("PRIMARY", "outer"), new Event("PRIMARY", "nested")), m.events);
      assertEquals("Fixed preview", m.message); assertEquals(1, m.oldSource.closes); assertEquals(1, m.sources.getLast().closes);
    }
  }

  @Test
  void compilerOpeningDuringCleanupSuppressesObsoleteFallbackReportingAndFixedAdmission() {
    try (var m = new Model()) {
      var reads = new AtomicInteger(); m.oldSource.closing = () -> m.compiler.begin("world");
      assertFalse(m.fail(fault(true, reads, () -> {}))); assertEquals(0, reads.get()); assertTrue(m.events.isEmpty());
      assertTrue(m.compiler.pending()); assertEquals("world", m.compiler.key()); assertEquals(1, m.oldSource.closes);
    }
  }
}
