package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.ChunkPos;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProfilerTest {
  private final AtomicLong nanos = new AtomicLong();
  private final Profiler profiler =
      new Profiler(nanos::get, Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));
  private static final Profiler.Options OPTIONS = new Profiler.Options(1000, 1024, 64, 4);

  @Test
  void disabledPathNeverReadsClockAndZeroIsAValidMonotonicOrigin() {
    var reads = new AtomicLong();
    try (var p =
        new Profiler(
            () -> {
              reads.incrementAndGet();
              return 0;
            },
            Clock.systemUTC())) {
      assertNull(p.scope(1, null, null));
      assertEquals(0, reads.get());
    }
    var c = profiler.start(OPTIONS);
    var scope = profiler.scope(7, new UUID(0, 1), new ChunkPos(-2, 3, 4));
    long stamp = scope.begin();
    assertEquals(1, stamp);
    nanos.set(25);
    scope.finish(stamp, Profiler.Kind.CHUNK_GENERATION, null, "chunk", Profiler.Outcome.SUCCESS);
    var sample = c.snapshot().samples().getFirst();
    assertEquals(0, sample.offsetNanos());
    assertEquals(25, sample.value());
    assertEquals(7, sample.contextId());
    assertEquals(new ChunkPos(-2, 3, 4), sample.chunk());
    assertEquals(Thread.currentThread().threadId(), sample.threadId());
    assertEquals(Thread.currentThread().getName(), sample.threadName());
  }

  @Test
  void exactNearestRankPercentilesMeanAndOutcomesDoNotOverflowLong() {
    var c = profiler.start(OPTIONS);
    var s = profiler.scope(0, null, null);
    for (int i = 1; i <= 100; i++) {
      long stamp = s.begin();
      nanos.addAndGet(i);
      s.finish(
          stamp,
          Profiler.Kind.OPERATION,
          null,
          "op",
          i == 1
              ? Profiler.Outcome.FAILED
              : i == 2 ? Profiler.Outcome.CANCELLED : Profiler.Outcome.SUCCESS);
    }
    var summary = c.snapshot().series().getFirst();
    assertEquals(100, summary.count());
    assertEquals(1, summary.minimum());
    assertEquals(100, summary.maximum());
    assertEquals(50.5, summary.mean());
    assertEquals(50, summary.p50());
    assertEquals(95, summary.p95());
    assertEquals(99, summary.p99());
    assertEquals(1, summary.failed());
    assertEquals(1, summary.cancelled());
    s.gauge(Profiler.Kind.HEAP_BYTES, Profiler.Unit.BYTES, Long.MAX_VALUE, "large");
    s.gauge(Profiler.Kind.HEAP_BYTES, Profiler.Unit.BYTES, Long.MAX_VALUE, "large");
    assertEquals((double) Long.MAX_VALUE, c.snapshot().series().getLast().mean());
    assertEquals(List.of(), Profiler.summarize(List.of()));
  }

  @Test
  void sampleAndSeriesLimitsStopWithoutEvictingOrGrowing() {
    var c = profiler.start(new Profiler.Options(1000, 64, 2, 1));
    var s = profiler.scope(0, null, null);
    for (int i = 0; i < 1000; i++)
      s.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, i, "same");
    assertEquals(64, c.snapshot().samples().size());
    assertEquals(Profiler.State.SAMPLE_LIMIT, c.snapshot().state());
    assertEquals(63, c.snapshot().samples().getLast().value());
    c = profiler.start(new Profiler.Options(1000, 64, 1, 1));
    s = profiler.scope(0, null, null);
    s.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 1, "first");
    s.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 2, "new");
    assertEquals(Profiler.State.SERIES_LIMIT, c.snapshot().state());
    assertEquals(1, c.snapshot().samples().size());
  }

  @Test
  void durationStopsOnObservationAndRetainsExactBoundedElapsed() {
    var c = profiler.start(new Profiler.Options(100, 64, 8, 1));
    var s = profiler.scope(0, null, null);
    long stamp = s.begin();
    nanos.set(100_000_000L);
    s.finish(stamp, Profiler.Kind.OPERATION, null, "late", Profiler.Outcome.SUCCESS);
    assertNull(profiler.scope(0, null, null));
    assertEquals(Profiler.State.TIME_LIMIT, c.snapshot().state());
    assertEquals(100_000_000L, c.snapshot().elapsedNanos());
    assertTrue(c.snapshot().samples().isEmpty());
  }

  @Test
  void samplingStrideIsPerThreadAndUnsampledOperationsDoNotReadClock() throws Exception {
    var reads = new AtomicLong();
    try (var p =
        new Profiler(
            () -> {
              reads.incrementAndGet();
              return 0;
            },
            Clock.systemUTC())) {
      var c = p.start(OPTIONS);
      var scope = p.scope(0, null, null);
      long baseline = reads.get();
      assertEquals(0, scope.beginOperation());
      assertEquals(0, scope.beginOperation());
      assertEquals(0, scope.beginOperation());
      assertEquals(baseline, reads.get());
      assertTrue(scope.beginOperation() > 0);
      try (var workers = Executors.newFixedThreadPool(2)) {
        var gate = new CyclicBarrier(2);
        var jobs = new ArrayList<Future<?>>();
        for (int t = 0; t < 2; t++)
          jobs.add(
              workers.submit(
                  () -> {
                    gate.await();
                    for (int i = 0; i < 8; i++) {
                      long stamp = scope.beginOperation();
                      scope.finish(
                          stamp, Profiler.Kind.OPERATION, null, "op", Profiler.Outcome.SUCCESS);
                    }
                    return null;
                  }));
        for (var job : jobs) job.get(5, TimeUnit.SECONDS);
      }
      assertEquals(4, c.snapshot().samples().size());
      assertEquals(
          2, c.snapshot().samples().stream().map(Profiler.Sample::threadId).distinct().count());
    }
  }

  @Test
  void snapshotsAreImmutableAndOldScopesNeverEnterNewCapture() {
    var old = profiler.start(OPTIONS);
    var scope = profiler.scope(0, null, null);
    long stamp = scope.begin();
    scope.gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 5, "cache");
    var frozen = old.snapshot();
    var next = profiler.start(OPTIONS);
    scope.finish(stamp, Profiler.Kind.OPERATION, null, "old", Profiler.Outcome.SUCCESS);
    assertEquals(Profiler.State.REPLACED, old.snapshot().state());
    assertTrue(next.snapshot().samples().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> frozen.samples().clear());
    assertThrows(UnsupportedOperationException.class, () -> frozen.series().clear());
    profiler.stop();
    nanos.set(2_000_000_000L);
    assertEquals(Profiler.State.STOPPED, next.snapshot().state());
    profiler.clear();
    assertTrue(profiler.current().isEmpty());
    assertEquals(1, frozen.samples().size());
    var last = profiler.start(OPTIONS);
    profiler.close();
    profiler.close();
    assertEquals(Profiler.State.CLOSED, last.snapshot().state());
    assertThrows(IllegalStateException.class, () -> profiler.start(OPTIONS));
  }

  @Test
  void concurrentRecordingAndSnapshotsRespectGlobalBounds() throws Exception {
    var c = profiler.start(new Profiler.Options(1000, 512, 8, 1));
    var s = profiler.scope(0, null, null);
    try (var workers = Executors.newFixedThreadPool(8)) {
      var jobs = new ArrayList<Future<?>>();
      for (int t = 0; t < 8; t++)
        jobs.add(
            workers.submit(
                () -> {
                  for (int i = 0; i < 400; i++) {
                    s.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, i, "queue");
                    if (i % 50 == 0) assertTrue(c.snapshot().samples().size() <= 512);
                  }
                }));
      for (var job : jobs) job.get(5, TimeUnit.SECONDS);
    }
    assertEquals(512, c.snapshot().samples().size());
    assertEquals(512, c.snapshot().series().getFirst().count());
    assertEquals(Profiler.State.SAMPLE_LIMIT, c.snapshot().state());
  }

  @Test
  void identitiesSeparateUnitsSourcesProjectsAndContextsAndLabelsAreBounded() {
    var c = profiler.start(OPTIONS);
    for (int i = 0; i < 3; i++)
      profiler
          .scope(i, new UUID(0, i), null)
          .gauge(Profiler.Kind.CACHE_BYTES, Profiler.Unit.BYTES, 1, "x".repeat(1000));
    var s = profiler.scope(0, null, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> s.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, -1, "bad"));
    assertEquals(3, c.snapshot().series().size());
    assertTrue(c.snapshot().samples().stream().allMatch(v -> v.label().length() == 256));
  }

  @Test
  void invalidLimitsRejectBeforeReplacingCurrentCapture() {
    var c = profiler.start(OPTIONS);
    int[][] values = {
      {99, 64, 1, 1},
      {60001, 64, 1, 1},
      {100, 63, 1, 1},
      {100, 65537, 1, 1},
      {100, 64, 0, 1},
      {100, 64, 1025, 1},
      {100, 64, 1, 0},
      {100, 64, 1, 65537}
    };
    for (var v : values)
      assertThrows(
          IllegalArgumentException.class, () -> new Profiler.Options(v[0], v[1], v[2], v[3]));
    assertThrows(NullPointerException.class, () -> profiler.start(null));
    assertTrue(c.recording());
    assertSame(c, profiler.current().orElseThrow());
  }

  @Test
  void externallyMeasuredDurationsKeepIssueStampAndNeverUseCpuElapsedTime() {
    var c = profiler.start(OPTIONS);
    var project = new UUID(0, 15);
    var source = new UUID(0, 16);
    var scope = profiler.scope(9, project, new ChunkPos(-1, 2, 3));
    assertEquals(c.snapshot().captureId(), scope.captureId());
    assertTrue(scope.recording());
    nanos.set(100);
    long stamp = scope.begin();
    nanos.set(5000000);
    assertTrue(
        scope.recordDuration(
            stamp, Profiler.Kind.GPU_DRAW, source, "draw", 1234, Profiler.Outcome.MEASURED));
    var sample = c.snapshot().samples().getFirst();
    assertEquals(100, sample.offsetNanos());
    assertEquals(1234, sample.value());
    assertEquals(project, sample.projectUuid());
    assertEquals(source, sample.sourceUuid());
    assertEquals(9, sample.contextId());
    assertEquals(Profiler.Unit.NANOSECONDS, sample.unit());
  }

  @Test
  void asynchronousCompletionsCannotReopenStoppedOrReplacedCaptures() {
    var old = profiler.start(OPTIONS);
    var scope = profiler.scope(0, null, null);
    long stamp = scope.begin();
    var next = profiler.start(OPTIONS);
    assertFalse(
        scope.recordDuration(
            stamp, Profiler.Kind.GPU_DRAW, null, "old", 5, Profiler.Outcome.MEASURED));
    assertFalse(scope.recording());
    assertTrue(old.snapshot().samples().isEmpty());
    assertTrue(next.snapshot().samples().isEmpty());
    scope = profiler.scope(0, null, null);
    stamp = scope.begin();
    profiler.stop();
    assertFalse(
        scope.recordDuration(
            stamp, Profiler.Kind.GPU_DRAW, null, "stopped", 5, Profiler.Outcome.MEASURED));
    assertTrue(next.snapshot().samples().isEmpty());
  }

  @Test
  void asynchronousDurationValidationAndGlobalLimitsRemainExact() {
    var c = profiler.start(new Profiler.Options(1000, 64, 1, 1));
    var scope = profiler.scope(0, null, null);
    assertFalse(scope.recordDuration(0, null, null, null, -1, null));
    long stamp = scope.begin();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scope.recordDuration(
                -1, Profiler.Kind.GPU_DRAW, null, "bad", 1, Profiler.Outcome.MEASURED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scope.recordDuration(
                stamp, Profiler.Kind.GPU_DRAW, null, "bad", -1, Profiler.Outcome.MEASURED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            scope.recordDuration(
                10, Profiler.Kind.GPU_DRAW, null, "future", 1, Profiler.Outcome.MEASURED));
    for (int i = 0; i < 64; i++)
      assertTrue(
          scope.recordDuration(
              stamp, Profiler.Kind.GPU_DRAW, null, "draw", i, Profiler.Outcome.MEASURED));
    assertFalse(
        scope.recordDuration(
            stamp, Profiler.Kind.GPU_DRAW, null, "draw", 1, Profiler.Outcome.MEASURED));
    assertEquals(Profiler.State.SAMPLE_LIMIT, c.snapshot().state());
    assertEquals(64, c.snapshot().samples().size());
  }
}
