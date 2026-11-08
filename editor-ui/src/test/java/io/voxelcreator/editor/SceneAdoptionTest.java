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
import java.util.function.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred source-to-scene transfer definitions; GPU acceptance remains a separate contract. */
class SceneAdoptionTest {
  private record Event(String code, String detail) {}
  private record Attempt(SceneAdoption.Result<ChunkScene> result, BooleanSupplier failureCurrent,
                         BooleanSupplier sceneCurrent) {}

  private static final class Workers extends AbstractExecutorService {
    final Queue<Runnable> queue = new ArrayDeque<>();
    boolean stopped;
    public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); queue.add(task); }
    public void shutdown() { shutdownNow(); }
    public List<Runnable> shutdownNow() { stopped = true; var old = new ArrayList<>(queue); queue.clear(); return old; }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped; }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
  }

  private static final class Source implements ChunkScene.Source {
    final int type;
    final VoxelChunk retained;
    int closes;
    Throwable construction, cleanup;
    Runnable constructing, closing;
    Source(int type) { this.type = type; retained = VoxelChunk.uniform(new ChunkPos(-1, 0, 2), 4, type); }
    public boolean surfaces() { if (constructing != null) constructing.run(); if (construction != null) raise(construction); return false; }
    public SceneChunk load(ChunkPos position, CancellationToken token) {
      token.throwIfCancelled(); return SceneChunk.exact(VoxelChunk.uniform(position, 4, type));
    }
    public void close() { closes++; if (closing != null) closing.run(); if (cleanup != null) raise(cleanup); }
  }

  private static final class Model implements AutoCloseable {
    final Workers workers = new Workers();
    final List<Source> sources = new ArrayList<>();
    final List<ChunkScene> scenes = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final Source old = source(7), incoming = source(23);
    final ChunkScene previous = make(old);
    ChunkScene scene = previous;
    Object ticket;
    long epoch = 7, compiler = 9;
    boolean closed, failed, dirty, force = true, reportBefore;
    int factories, failures, publications, gpuSetups;
    String message = "Previous display";
    Throwable reportFault;
    Consumer<String> reporting;

    Source source(int type) { var result = new Source(type); sources.add(result); return result; }
    ChunkScene make(Source source) {
      var result = new ChunkScene(source, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, false, 125));
      scenes.add(result); return result;
    }

    Attempt adopt() { return adopt(this::make); }
    Attempt adopt(Function<Source, ChunkScene> factory) {
      return adopt(incoming, factory);
    }
    Attempt adopt(Source admitted, Function<Source, ChunkScene> factory) {
      var captured = scene; long request = epoch, generation = compiler; Object owner = new Object();
      ticket = owner;
      BooleanSupplier opening = () -> !closed && epoch == request && compiler == generation;
      var result = SceneAdoption.adopt(admitted, captured, new SceneAdoption.Owner<ChunkScene>() {
        public boolean eligible() { return opening.getAsBoolean() && ticket == owner && scene == captured; }
        public void failed() { failures++; ticket = owner; failed = dirty = true; force = false; message = "Scene setup failed; retry"; }
        public void publish(ChunkScene value) {
          publications++; scene = value; failed = dirty = force = false; message = "Adopted";
        }
      }, source -> { factories++; return factory.apply(source); });
      return new Attempt(result,
          () -> opening.getAsBoolean() && ticket == owner && scene == captured && failed && dirty && !force,
          () -> opening.getAsBoolean() && ticket == owner && scene == result.scene() && scene != null);
    }

    void finish(Attempt attempt) {
      if (attempt.result().scene() == null) {
        PreviewNotifications.report(attempt.result().failure(), attempt.failureCurrent(), detail -> report("PRIMARY", detail));
        PreviewNotifications.report(attempt.result().cleanup(), attempt.failureCurrent(), detail -> report("CLEANUP", detail));
      } else if (attempt.sceneCurrent().getAsBoolean()) {
        gpuSetups++; // Model only: actual GlViewport setup is source-traced.
        PreviewNotifications.report(attempt.result().cleanup(), attempt.sceneCurrent(), detail -> report("RETIREMENT", detail));
      }
    }

    void report(String code, String detail) {
      if (reportBefore && reportFault != null) raise(reportFault);
      events.add(new Event(code, detail)); if (reporting != null) reporting.accept(code);
      if (reportFault != null) raise(reportFault);
    }

    void replace() {
      var retired = scene; scene = make(source(41)); ticket = null; failed = dirty = force = false; message = "Successor";
      if (retired != null) retired.close();
    }

    void closeDuringCallback() {
      closed = true; var retired = scene; scene = null; ticket = null; message = "Closed";
      if (retired != null) retired.close();
    }

    void healthy(int type) {
      for (int i = 0; i < 200; i++) {
        scene.tick(new Vector3d(1), new Vector3d(1), 8);
        while (!workers.queue.isEmpty()) workers.queue.remove().run();
        var uploads = scene.uploads(8, 1 << 20);
        for (var upload : uploads) scene.uploaded(upload);
        if (!uploads.isEmpty()) {
          assertTrue(scene.hasDisplay()); var source = sources.getLast(); assertEquals(0, source.closes);
          for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
            assertEquals(type, source.retained.getVoxelTypeId(x, y, z));
          return;
        }
      }
      org.junit.jupiter.api.Assertions.fail("Current scene did not prepare an upload");
    }

    public void close() {
      closed = true; for (var source : sources) { source.constructing = source.closing = null; source.construction = source.cleanup = null; }
      for (var value : scenes) value.close(); workers.shutdownNow();
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
  void constructorFaultSettlesRetryBeforeRawSourceCleanupAndRetainsThePreviousScene() {
    for (boolean error : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var cleanupReads = new AtomicInteger(); var settled = new AtomicBoolean();
      var primary = fault(error, reads, () -> {}); var cleanup = fault(!error, cleanupReads, () -> {});
      m.incoming.construction = primary; m.incoming.cleanup = cleanup;
      m.incoming.closing = () -> {
        assertSame(m.previous, m.scene); assertTrue(m.failed); assertTrue(m.dirty); assertFalse(m.force);
        assertEquals("Scene setup failed; retry", m.message); settled.set(true);
      };
      var attempt = m.adopt(); assertTrue(settled.get()); assertNull(attempt.result().scene());
      assertSame(primary, attempt.result().failure()); assertSame(cleanup, attempt.result().cleanup());
      assertEquals(1, m.incoming.closes); assertEquals(0, m.old.closes); assertEquals(0, reads.get());
      m.finish(attempt); assertEquals(0, m.gpuSetups); assertEquals(1, reads.get()); assertEquals(1, cleanupReads.get());
      assertEquals(List.of(new Event("PRIMARY", primary.getClass().getName()), new Event("CLEANUP", cleanup.getClass().getName())), m.events);
      assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      assertEquals(7, m.old.retained.getVoxelTypeId(3, 2, 1));
    }
  }

  @Test
  void successfulTransferPublishesBeforeOldRetirementAndSurvivesItsError() {
    try (var m = new Model()) {
      var reads = new AtomicInteger(); var observed = new AtomicBoolean(); var cleanup = fault(true, reads, () -> {});
      m.old.cleanup = cleanup; m.old.closing = () -> {
        assertNotSame(m.previous, m.scene); assertFalse(m.failed); assertFalse(m.dirty); assertFalse(m.force);
        assertEquals("Adopted", m.message); observed.set(true);
      };
      var attempt = m.adopt(); assertTrue(observed.get()); assertSame(m.scene, attempt.result().scene());
      assertNull(attempt.result().failure()); assertSame(cleanup, attempt.result().cleanup());
      assertEquals(1, m.old.closes); assertEquals(0, m.incoming.closes); m.finish(attempt);
      assertEquals(1, m.gpuSetups); assertEquals(List.of(new Event("RETIREMENT", cleanup.getClass().getName())), m.events);
      assertEquals(1, reads.get()); assertEquals(0, cleanup.getSuppressed().length); m.healthy(23);
    }
  }

  @Test
  void constructorReentryThatReturnsNormallyDisposesOnlyTheUnpublishedCandidate() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      m.incoming.constructing = () -> { if (close) m.closeDuringCallback(); else m.replace(); };
      var attempt = m.adopt(); assertNull(attempt.result().scene()); assertNull(attempt.result().failure());
      assertEquals(1, m.incoming.closes); assertEquals(1, m.old.closes); assertEquals(0, m.publications); assertEquals(0, m.failures);
      m.finish(attempt); assertEquals(0, m.gpuSetups); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthy(41);
    }
  }

  @Test
  void constructorReentryThatThrowsCannotMarkItsSuccessorFailed() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var primary = fault(true, reads, () -> {});
      m.incoming.constructing = () -> { if (close) m.closeDuringCallback(); else m.replace(); }; m.incoming.construction = primary;
      var attempt = m.adopt(); assertSame(primary, attempt.result().failure()); assertEquals(0, m.failures);
      assertEquals(1, m.incoming.closes); m.finish(attempt); assertEquals(0, reads.get()); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthy(41);
    }
  }

  @Test
  void nestedSameEpochAdoptionRetiresTheOuterCapabilityAndFailedInnerWorkCanRetry() {
    for (boolean innerFails : new boolean[] {false, true}) try (var m = new Model()) {
      var inner = new AtomicReference<Attempt>();
      m.incoming.constructing = () -> {
        m.incoming.constructing = null;
        var source = m.source(41); if (innerFails) source.construction = new IllegalStateException("inner");
        inner.set(m.adopt(source, m::make)); m.finish(inner.get());
      };
      var outer = m.adopt(); assertNull(outer.result().scene()); assertNull(outer.result().failure());
      assertEquals(1, m.incoming.closes); assertEquals(innerFails ? 0 : 1, m.old.closes);
      m.finish(outer); assertEquals(innerFails ? 0 : 1, m.gpuSetups); assertEquals(innerFails ? 1 : 0, m.failures);
      if (innerFails) {
        assertSame(m.previous, m.scene); assertEquals(List.of(new Event("PRIMARY", "inner")), m.events);
        var retry = m.source(59); m.epoch++; m.compiler++;
        var healthy = m.adopt(retry, m::make); m.finish(healthy); m.finish(inner.get());
        assertEquals(1, m.gpuSetups); assertEquals(1, m.old.closes); assertEquals(0, retry.closes);
        assertEquals(1, m.events.size()); m.healthy(59);
      } else m.healthy(41);
    }
  }

  @Test
  void oldSourceRetirementReentryTransfersPublishedSceneResponsibilityWithoutLateSetup() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      m.old.closing = () -> { if (close) m.closeDuringCallback(); else m.replace(); }; m.old.cleanup = new AssertionError("old retirement");
      var attempt = m.adopt(); assertNotNull(attempt.result().scene()); assertFalse(attempt.sceneCurrent().getAsBoolean());
      assertEquals(1, m.publications); assertEquals(1, m.old.closes); assertEquals(1, m.incoming.closes);
      m.finish(attempt); assertEquals(0, m.gpuSetups); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthy(41);
    }
  }

  @Test
  void initiallyObsoleteOpeningDisposesRawSourceWithoutInvokingFactoryOrSettlingFailure() {
    try (var m = new Model()) {
      var current = new AtomicBoolean(false); var factories = new AtomicInteger(); var settlements = new AtomicInteger();
      m.incoming.closing = m::replace;
      var result = SceneAdoption.adopt(m.incoming, m.previous, new SceneAdoption.Owner<ChunkScene>() {
        public boolean eligible() { return current.get(); }
        public void failed() { settlements.incrementAndGet(); }
        public void publish(ChunkScene scene) { settlements.incrementAndGet(); }
      }, source -> { factories.incrementAndGet(); return m.make(source); });
      assertNull(result.scene()); assertNull(result.failure()); assertEquals(0, factories.get()); assertEquals(0, settlements.get());
      assertEquals(1, m.incoming.closes); assertEquals("Successor", m.message); m.healthy(41);
    }
  }

  @Test
  void primaryDescriptionReentryAfterRawDisposalCannotReportOverCloseOrSuccessor() {
    for (boolean close : new boolean[] {false, true}) try (var m = new Model()) {
      var reads = new AtomicInteger(); var cleanupReads = new AtomicInteger();
      m.incoming.construction = fault(false, reads, () -> { if (close) m.closeDuringCallback(); else m.replace(); });
      m.incoming.cleanup = fault(true, cleanupReads, () -> {}); var attempt = m.adopt();
      assertEquals(1, m.incoming.closes); assertEquals(0, m.old.closes); m.finish(attempt);
      assertEquals(1, reads.get()); assertEquals(0, cleanupReads.get()); assertTrue(m.events.isEmpty());
      assertEquals(close ? "Closed" : "Successor", m.message); if (!close) m.healthy(41);
    }
  }

  @Test
  void unavailableRetirementDiagnosticsCannotUndoPublicationOrOverwriteReportAdmittedSuccessor() {
    for (boolean before : new boolean[] {false, true}) try (var m = new Model()) {
      m.old.cleanup = new AssertionError("retirement"); m.reportBefore = before; m.reportFault = new AssertionError("delivery");
      m.reporting = code -> m.replace(); var attempt = m.adopt(); m.finish(attempt);
      assertEquals(1, m.gpuSetups); assertEquals(before ? 0 : 1, m.events.size());
      assertEquals(before ? "Adopted" : "Successor", m.message); assertEquals(0, m.failures);
      assertEquals(1, m.old.closes); assertEquals(before ? 0 : 1, m.incoming.closes); m.healthy(before ? 23 : 41);
    }
  }

  @Test
  void firstSceneTransferAndNullFactoryFailureRetainTheirDistinctOwnership() {
    for (boolean missing : new boolean[] {false, true}) try (var m = new Model()) {
      if (missing) {
        var attempt = m.adopt(source -> null); assertInstanceOf(NullPointerException.class, attempt.result().failure());
        assertEquals(1, m.incoming.closes); assertEquals(0, m.old.closes); assertSame(m.previous, m.scene); assertEquals(1, m.failures);
      } else {
        m.scene = null; var attempt = m.adopt(); assertNotNull(attempt.result().scene()); assertNull(attempt.result().cleanup());
        assertEquals(0, m.old.closes); assertEquals(0, m.incoming.closes); m.finish(attempt); m.healthy(23);
      }
    }
  }

  @Test
  void interruptedRawCleanupPreservesInterruptAndPrimaryWithoutClosingPreviousOwner() {
    var primary = new IllegalStateException("construction"); var cleanup = new InterruptedException("cleanup");
    var previousCloses = new AtomicInteger(); var settled = new AtomicBoolean();
    AutoCloseable raw = () -> { assertTrue(settled.get()); throw cleanup; };
    AutoCloseable previous = previousCloses::incrementAndGet;
    try {
      var result = SceneAdoption.adopt(raw, previous, new SceneAdoption.Owner<AutoCloseable>() {
        public boolean eligible() { return true; }
        public void failed() { settled.set(true); }
        public void publish(AutoCloseable scene) { fail("Unexpected publication"); }
      }, source -> { throw primary; });
      assertSame(primary, result.failure()); assertSame(cleanup, result.cleanup()); assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, previousCloses.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
    } finally { Thread.interrupted(); }
  }
}
