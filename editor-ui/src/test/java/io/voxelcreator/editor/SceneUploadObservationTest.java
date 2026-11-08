package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import io.voxelcreator.render.*;
import io.voxelcreator.storage.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/** Deferred optional-observer isolation using real CPU scenes, the pump and public profiler. */
class SceneUploadObservationTest {
  private static final List<String> OBSERVATIONS = List.of(
      "factory", "begin", "before-counter", "after-counter", "finish", "uploaded", "relocated");
  private static final Profiler.Options OPTIONS = new Profiler.Options(60000, 1024, 64, 1);
  private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000017");

  private static final class Workers extends AbstractExecutorService {
    final Queue<Runnable> jobs = new ArrayDeque<>();
    boolean stopped;
    public void execute(Runnable task) { if (stopped) throw new RejectedExecutionException(); jobs.add(task); }
    public void shutdown() { shutdownNow(); }
    public List<Runnable> shutdownNow() { stopped = true; var old = new ArrayList<>(jobs); jobs.clear(); return old; }
    public boolean isShutdown() { return stopped; }
    public boolean isTerminated() { return stopped; }
    public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
  }

  private static final class Frame implements AutoCloseable {
    final Workers workers = new Workers();
    final SceneUploadPump pump = new SceneUploadPump();
    final SceneUploadObservation observations = new SceneUploadObservation();
    final ChunkScene scene = new ChunkScene((position, token) -> {
      token.throwIfCancelled(); return SceneChunk.exact(VoxelChunk.uniform(position, 4, 23));
    }, workers, new ChunkScene.Config(4, 0, 0, 2, 1, 2, 64L << 20, true, 125));
    final List<String> calls = new ArrayList<>();
    Consumer<String> before = event -> {}, after = event -> {};
    Object owner = new Object();
    boolean closed, ready;
    int snapshots, workCalls, fallbacks, reports;
    long uploaded, relocated;
    byte[] data;
    ChunkScene.Upload pending, resident;
    final Observer original = new Observer();
    SceneUploadObservation.Observer selected = original;

    final class Observer implements SceneUploadObservation.Observer {
      long issue = 37, ended;
      Long uploadGauge, relocationGauge;
      Profiler.Outcome outcome;
      public long begin() { call("begin", () -> {}); return issue; }
      public void finish(long stamp, Profiler.Outcome outcome) {
        call("finish", () -> { ended = stamp; this.outcome = outcome; });
      }
      public void uploaded(long bytes) { call("uploaded", () -> uploadGauge = bytes); }
      public void relocated(long bytes) { call("relocated", () -> relocationGauge = bytes); }
    }

    Frame() {
      for (int pass = 0; pass < 300; pass++) {
        scene.tick(new Vector3d(1), new Vector3d(1), 16);
        while (!workers.jobs.isEmpty()) workers.jobs.remove().run();
        if (!scene.uploads(1, 1024).isEmpty()) return;
      }
      throw new AssertionError("Scene did not prepare a packed upload");
    }
    void call(String event, Runnable effect) {
      calls.add(event); before.accept(event); effect.run(); after.accept(event);
    }
    SceneUploadObservation.Observer factory() {
      SceneUploadObservation.Observer[] result = {null};
      call("factory", () -> result[0] = selected); return result[0];
    }
    SceneUploadObservation.Counters counters() {
      var result = new SceneUploadObservation.Counters[1];
      call(++snapshots == 1 ? "before-counter" : "after-counter",
          () -> result[0] = new SceneUploadObservation.Counters(uploaded, relocated));
      return result[0];
    }
    final SceneUploadPump.Backend gpu = new SceneUploadPump.Backend() {
      public long uploadedBytes() { return uploaded; }
      public int compact(int budget) { relocated += 16; return 16; }
      public boolean compacting() { return false; }
      public ChunkScene.Upload pending() { return pending; }
      public boolean contains(ChunkScene.Upload upload) {
        return resident != null && resident.epoch() == upload.epoch() && resident.version() == upload.version();
      }
      public boolean stage(ChunkScene.Upload upload) { pending = upload; return true; }
      public ChunkScene.Upload advance(int budget) {
        var attempted = pending;
        call("transfer", () -> {
          assertEquals(1024, budget); assertEquals(64, attempted.mesh().vertexCount());
          var bytes = ByteBuffer.allocate(1024); attempted.mesh().writeTo(bytes);
          data = bytes.array(); uploaded += 1024; resident = attempted; pending = null;
        });
        return attempted;
      }
    };
    SceneUploadObservation.Result observe() { return observe(this::factory); }
    SceneUploadObservation.Result observe(Supplier<SceneUploadObservation.Observer> factory) {
      Object captured = owner;
      BooleanSupplier current = () -> !closed && owner == captured;
      return observations.run(current, factory, this::counters, () -> {
        workCalls++; return pump.run(scene, current, gpu, 1040);
      });
    }
    void settle(SceneUploadObservation.Result result) {
      if (result.failure() instanceof RuntimeException && !closed) fallbacks++;
      if (result.failure() == null && result.completed()) ready = true;
    }
    void report(SceneUploadObservation.Result result) {
      Object captured = owner;
      SceneUploadObservation.report(result.observationFailure(), () -> !closed && owner == captured,
          detail -> call("report", () -> { reports++; assertTrue(ready); }));
    }
    void exact() {
      assertEquals(1024, data.length);
      var bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
      for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
        int index = (z * 16 + y * 4 + x) * 16;
        assertEquals(2 * x + 1, Short.toUnsignedInt(bytes.getShort(index)));
        assertEquals(2 * y + 1, Short.toUnsignedInt(bytes.getShort(index + 2)));
        assertEquals(2 * z + 1, Short.toUnsignedInt(bytes.getShort(index + 4)));
        assertEquals(23, bytes.getInt(index + 12)); assertEquals(23, resident.cells().get(x, y, z));
      }
      assertTrue(scene.uploads(1, 1024).isEmpty());
    }
    public void close() { closed = true; scene.close(); workers.shutdownNow(); }
  }

  private static Throwable fault(boolean error) {
    return error ? new AssertionError() {
      @Override public String getMessage() { throw new AssertionError("Unreadable observation"); }
      @Override public String toString() { throw new AssertionError("Unreadable observation"); }
    } : new IllegalStateException() {
      @Override public String getMessage() { throw new AssertionError("Unreadable observation"); }
      @Override public String toString() { throw new AssertionError("Unreadable observation"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static Consumer<String> at(String point, Runnable action) {
    return event -> { if (event.equals(point)) action.run(); };
  }

  @Test
  void healthyObservationRecordsCapturedWorkAndIndependentByteCountsBeforeReadiness() {
    try (var f = new Frame()) {
      var result = f.observe(); f.settle(result);
      assertTrue(result.completed()); assertNull(result.failure()); assertNull(result.observationFailure());
      assertTrue(f.ready); assertEquals(0, f.fallbacks); assertEquals(1, f.workCalls);
      assertEquals(37, f.original.ended); assertEquals(Profiler.Outcome.SUCCESS, f.original.outcome);
      assertEquals(1024L, f.original.uploadGauge.longValue()); assertEquals(16L, f.original.relocationGauge.longValue());
      assertEquals(List.of("factory", "begin", "before-counter", "transfer", "after-counter",
          "finish", "uploaded", "relocated"), f.calls); f.exact();
    }
  }

  @Test
  void everyObservationFaultBeforeOrAfterEffectsLeavesRealUploadsAcknowledgedAndReady() {
    for (String point : OBSERVATIONS) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) try (var f = new Frame()) {
        var failure = fault(error); var hook = at(point, () -> raise(failure));
        if (after) f.after = hook; else f.before = hook;
        var result = f.observe(); f.settle(result);
        assertSame(failure, result.observationFailure()); assertNull(result.failure()); assertTrue(f.ready);
        assertEquals(0, f.fallbacks); assertEquals(0, failure.getSuppressed().length); f.exact();
        if (point.equals("finish") || point.equals("uploaded"))
          assertTrue(f.calls.containsAll(List.of("finish", "uploaded", "relocated")));
        if (point.equals("factory") || point.equals("begin")) {
          assertFalse(f.calls.contains("finish")); assertEquals(0, f.snapshots);
        }
      }
  }

  @Test
  void originalTransferFaultSurvivesAdmissionAndFinalizerErrorsIncludingSharedFaults() {
    for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true})
      for (boolean shared : new boolean[] {false, true}) try (var f = new Frame()) {
        var primary = fault(error); var observation = shared ? primary : fault(!error);
        Consumer<String> work = at("transfer", () -> raise(primary));
        if (after) f.after = work; else f.before = work;
        var previous = f.after;
        f.after = event -> {
          previous.accept(event);
          if (List.of("finish", "uploaded", "relocated").contains(event)) raise(observation);
        };
        var result = f.observe(); f.settle(result);
        assertSame(primary, result.failure()); assertSame(observation, result.observationFailure());
        assertFalse(f.ready); assertEquals(error ? 0 : 1, f.fallbacks);
        assertEquals(Profiler.Outcome.FAILED, f.original.outcome);
        assertEquals(0, primary.getSuppressed().length); assertEquals(0, observation.getSuppressed().length);
        assertFalse(f.scene.uploads(1, 1024).isEmpty());
      }
    try (var f = new Frame()) {
      var primary = fault(true); var admission = fault(false);
      f.before = event -> {
        if (event.equals("factory")) raise(admission);
        if (event.equals("transfer")) raise(primary);
      };
      var result = f.observe(); assertSame(primary, result.failure());
      assertSame(admission, result.observationFailure()); assertFalse(f.calls.contains("finish"));
    }
  }

  @Test
  void invalidOrUnavailableCounterSnapshotsOnlyOmitGaugesAndRetainSuccessfulWork() {
    for (int mode = 0; mode < 3; mode++) try (var f = new Frame()) {
      int selected = mode;
      var result = f.observations.run(() -> true, f::factory, () -> {
        if (selected == 0) return null;
        if (selected == 1) return new SceneUploadObservation.Counters(-1, 0);
        return f.workCalls == 0 ? new SceneUploadObservation.Counters(2048, 32)
            : new SceneUploadObservation.Counters(1024, 16);
      }, () -> { f.workCalls++; return f.pump.run(f.scene, () -> true, f.gpu, 1040); });
      f.settle(result); assertTrue(f.ready); assertNull(result.failure());
      assertNotNull(result.observationFailure()); assertTrue(f.calls.contains("finish"));
      assertFalse(f.calls.contains("uploaded")); assertFalse(f.calls.contains("relocated")); f.exact();
    }
  }

  @Test
  void replacementOrClosureAtEachBoundaryStopsOldPublicationAndForeignCounterReads() {
    for (String point : OBSERVATIONS) for (boolean close : new boolean[] {false, true})
      for (boolean after : new boolean[] {false, true}) try (var f = new Frame()) {
        var hook = at(point, () -> { if (close) f.closed = true; else f.owner = new Object(); });
        if (after) f.after = hook; else f.before = hook;
        var result = f.observe(); f.settle(result);
        assertFalse(result.completed()); assertFalse(f.ready); assertNull(result.failure());
        if (List.of("factory", "begin", "before-counter").contains(point)) assertEquals(0, f.workCalls);
        if (point.equals("finish")) assertFalse(f.calls.contains("uploaded"));
        if (point.equals("uploaded")) assertFalse(f.calls.contains("relocated"));
        assertFalse(f.observations.busy());
      }
  }

  @Test
  void finalizerMutationCannotResampleCountersOrRedirectGaugesToAnotherObserver() {
    try (var f = new Frame()) {
      var replacement = f.new Observer();
      f.after = at("finish", () -> { f.uploaded = 9999; f.relocated = 8888; f.selected = replacement; });
      var result = f.observe(); f.settle(result); assertTrue(f.ready);
      assertEquals(2, f.snapshots); assertEquals(1024L, f.original.uploadGauge.longValue());
      assertEquals(16L, f.original.relocationGauge.longValue());
      assertEquals(0, replacement.ended); assertNull(replacement.uploadGauge); assertNull(replacement.relocationGauge);
      f.exact();
    }
  }

  @Test
  void nestedObservationRejectsBeforeCallbacksWhileOuterAdmissionWorkAndFinalizationFinish() {
    try (var f = new Frame()) {
      f.before = event -> {
        int calls = f.calls.size(); assertThrows(IllegalStateException.class, f::observe);
        assertEquals(calls, f.calls.size());
      };
      var result = f.observe(); f.settle(result); assertTrue(f.ready); assertNull(result.observationFailure());
      assertFalse(f.observations.busy()); f.exact();
    }
  }

  @Test
  void deferredDiagnosticsCannotChangeSettledReadinessOrDeliverAfterTextReplacesItsOwner() {
    for (boolean after : new boolean[] {false, true}) for (boolean error : new boolean[] {false, true})
      try (var f = new Frame()) {
        f.before = at("finish", () -> raise(fault(false)));
        var result = f.observe(); f.settle(result); assertTrue(f.ready);
        f.before = event -> {}; f.after = event -> {};
        var hook = at("report", () -> raise(fault(error)));
        if (after) f.after = hook; else f.before = hook;
        assertDoesNotThrow(() -> f.report(result)); assertTrue(f.ready); assertEquals(0, f.fallbacks);
        assertNull(result.failure()); f.exact();
      }
    try (var f = new Frame()) {
      var fault = new IllegalStateException() {
        @Override public String getMessage() { f.owner = new Object(); f.ready = false; return "replacement"; }
      };
      f.before = at("finish", () -> { throw fault; });
      var result = f.observe(); f.settle(result); assertTrue(f.ready);
      f.before = event -> {}; f.report(result);
      assertEquals(0, f.reports); assertFalse(f.ready); assertTrue(result.completed());
    }
  }

  @Test
  void publicProfilerRetainsOriginalLabelsUnitsOutcomesAndExactByteSeries() {
    try (var f = new Frame(); var profiler = new Profiler()) {
      var capture = profiler.start(OPTIONS);
      var result = f.observe(() -> SceneUploadObservation.profiler(profiler.scope(17, PROJECT, null)));
      f.settle(result); assertTrue(f.ready); assertNull(result.observationFailure()); f.exact();
      var samples = capture.snapshot().samples(); assertEquals(3, samples.size());
      var timing = samples.getFirst(); assertEquals(Profiler.Kind.UPLOAD_CPU, timing.kind());
      assertEquals(Profiler.Unit.NANOSECONDS, timing.unit()); assertEquals(Profiler.Outcome.SUCCESS, timing.outcome());
      assertEquals("Streaming upload and arena maintenance", timing.label()); assertTrue(timing.value() >= 0);
      assertEquals(List.of(1024L, 16L), samples.subList(1, 3).stream().map(Profiler.Sample::value).toList());
      assertEquals(List.of("Streaming upload bytes per frame", "Arena relocation bytes per frame"),
          samples.subList(1, 3).stream().map(Profiler.Sample::label).toList());
      for (var sample : samples) { assertEquals(PROJECT, sample.projectUuid()); assertEquals(17, sample.contextId()); }
      for (var sample : samples.subList(1, 3)) {
        assertEquals(Profiler.Kind.UPLOAD_BYTES, sample.kind()); assertEquals(Profiler.Unit.BYTES, sample.unit());
      }
    }
  }

  @Test
  void replacingThePublicProfilerCaptureDoesNotSendOldSamplesIntoTheNewCapture() {
    try (var f = new Frame(); var profiler = new Profiler()) {
      var capture = profiler.start(OPTIONS); var next = new AtomicReference<Profiler.Capture>();
      f.after = at("transfer", () -> next.set(profiler.start(OPTIONS)));
      var result = f.observe(() -> SceneUploadObservation.profiler(profiler.scope(17, PROJECT, null)));
      f.settle(result); assertTrue(f.ready); assertNull(result.observationFailure()); f.exact();
      assertEquals(Profiler.State.REPLACED, capture.snapshot().state());
      assertTrue(capture.snapshot().samples().isEmpty()); assertTrue(next.get().snapshot().samples().isEmpty());
    }
  }

  @Test
  void absentOrZeroStampProfilingSkipsOptionalSnapshotsAndInitiallyObsoleteWork() {
    try (var f = new Frame(); var profiler = new Profiler()) {
      var result = f.observe(() -> SceneUploadObservation.profiler(profiler.scope(17, PROJECT, null)));
      f.settle(result); assertTrue(f.ready); assertEquals(0, f.snapshots); assertFalse(f.calls.contains("finish")); f.exact();
    }
    try (var f = new Frame()) {
      f.original.issue = 0; var result = f.observe(); f.settle(result);
      assertTrue(f.ready); assertEquals(0, f.snapshots); assertFalse(f.calls.contains("finish")); f.exact();
    }
    try (var f = new Frame()) {
      var result = f.observations.run(() -> false, f::factory, f::counters, () -> { fail(); return true; });
      assertFalse(result.completed()); assertNull(result.failure()); assertNull(result.observationFailure());
      assertTrue(f.calls.isEmpty()); assertFalse(f.observations.busy());
    }
  }
}
