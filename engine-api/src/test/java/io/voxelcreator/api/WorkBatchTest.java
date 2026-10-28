package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.ChunkPos;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class WorkBatchTest {
  private final AtomicLong nanos = new AtomicLong();
  private final Profiler profiler = new Profiler(nanos::get, Clock.systemUTC());
  private final UUID project = UUID.randomUUID();
  private final Profiler.Options options = new Profiler.Options(1000, 1024, 64, 65536, true);

  private Profiler.WorkSource layer(String label) {
    return new Profiler.WorkSource(UUID.randomUUID(), label, null);
  }

  private Profiler.WorkSource op(String label, Profiler.WorkSource layer) {
    return new Profiler.WorkSource(UUID.randomUUID(), label, layer);
  }

  private void interval(Profiler.WorkBatch work, Profiler.WorkSource source, long elapsed) {
    long stamp = work.begin();
    nanos.addAndGet(elapsed);
    work.finish(stamp, source);
  }

  @Test
  void legacyModeDoesNoLedgerClockWorkAndRetainsTheOldConstructor() {
    var reads = new AtomicLong();
    try (var p =
        new Profiler(
            () -> {
              reads.incrementAndGet();
              return 0;
            },
            Clock.systemUTC())) {
      var capture = p.start(new Profiler.Options(1000, 1024, 64, 7));
      var scope = p.scope(1, project, null);
      long before = reads.get();
      assertFalse(capture.snapshot().options().worldContributions());
      before = reads.get();
      assertNull(scope.work());
      assertEquals(0, scope.contributionBytes());
      assertEquals(before, reads.get());
      assertFalse(Profiler.Options.defaults().worldContributions());
    }
  }

  @Test
  void peerIntervalsSumExactlyWithoutChargingGapsOrDoubleCountingLayers() {
    var capture = profiler.start(options);
    var scope = profiler.scope(4, project, new ChunkPos(-1, 0, 2));
    var work = scope.work();
    var a = layer("A");
    var b = layer("B");
    var first = op("first", a);
    var second = op("second", a);
    var third = op("third", b);
    interval(work, first, 10);
    nanos.addAndGet(1000);
    interval(work, first, 20);
    interval(work, second, 7);
    interval(work, third, 5);
    assertEquals(5, work.retained());
    work.flush(Profiler.Outcome.SUCCESS);
    var totals = new HashMap<UUID, Long>();
    for (var sample : capture.snapshot().samples()) totals.put(sample.sourceUuid(), sample.value());
    assertEquals(30L, totals.get(first.id));
    assertEquals(7L, totals.get(second.id));
    assertEquals(5L, totals.get(third.id));
    assertEquals(37L, totals.get(a.id));
    assertEquals(5L, totals.get(b.id));
    var firstSample = capture.snapshot().samples().getFirst();
    assertEquals(0, firstSample.offsetNanos());
    assertEquals(project, firstSample.projectUuid());
    assertEquals(new ChunkPos(-1, 0, 2), firstSample.chunk());
    assertEquals(4, firstSample.contextId());
    assertEquals(0, work.retained());
  }

  @Test
  void flushIsSingleUseAndFailedOrCancelledWorkRemainsAnExplicitPrefix() {
    for (var outcome : List.of(Profiler.Outcome.FAILED, Profiler.Outcome.CANCELLED)) {
      var capture = profiler.start(options);
      var work = profiler.scope(1, project, null).work();
      var source = op("first", layer("parent"));
      interval(work, source, 12);
      work.flush(outcome);
      work.flush(Profiler.Outcome.SUCCESS);
      assertEquals(2, capture.snapshot().samples().size());
      assertTrue(
          capture.snapshot().samples().stream()
              .allMatch(s -> s.outcome() == outcome && s.value() == 12));
      assertEquals(0, work.begin());
    }
  }

  @Test
  void stopReplacementAndTimeoutDiscardUnpublishedLedgers() {
    var old = profiler.start(options);
    var work = profiler.scope(1, project, null).work();
    interval(work, op("old", layer("old")), 1);
    var next = profiler.start(options);
    work.flush(Profiler.Outcome.SUCCESS);
    assertEquals(Profiler.State.REPLACED, old.snapshot().state());
    assertTrue(next.snapshot().samples().isEmpty());
    assertEquals(0, work.retained());
    work = profiler.scope(2, project, null).work();
    interval(work, op("stop", layer("stop")), 1);
    profiler.stop();
    work.flush(Profiler.Outcome.SUCCESS);
    assertTrue(next.snapshot().samples().isEmpty());
    var timed = profiler.start(new Profiler.Options(100, 64, 8, 1, true));
    work = profiler.scope(3, project, null).work();
    long stamp = work.begin();
    nanos.addAndGet(100_000_000L);
    work.finish(stamp, op("late", layer("late")));
    work.flush(Profiler.Outcome.SUCCESS);
    assertEquals(Profiler.State.TIME_LIMIT, timed.snapshot().state());
    assertTrue(timed.snapshot().samples().isEmpty());
  }

  @Test
  void sourceDiscoveryCannotExceedItsBoundAndStopsTheOwningCapture() {
    var capture = profiler.start(new Profiler.Options(1000, 64, 2, 65536, true));
    var work = profiler.scope(1, project, null).work();
    var parent = layer("parent");
    interval(work, op("first", parent), 1);
    assertEquals(2, work.retained());
    interval(work, op("second", parent), 1);
    assertEquals(Profiler.State.SERIES_LIMIT, capture.snapshot().state());
    assertEquals(2, work.retained());
    work.flush(Profiler.Outcome.SUCCESS);
    assertTrue(capture.snapshot().samples().isEmpty());
    assertEquals(0, work.retained());
  }

  @Test
  void publishedAggregatesShareTheGlobalSampleLimit() {
    var capture = profiler.start(new Profiler.Options(1000, 64, 8, 65536, true));
    var scope = profiler.scope(1, project, null);
    for (int i = 0; i < 63; i++)
      scope.gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, i, "queue");
    var work = scope.work();
    interval(work, op("operation", layer("layer")), 10);
    work.flush(Profiler.Outcome.SUCCESS);
    assertEquals(64, capture.snapshot().samples().size());
    assertEquals(Profiler.State.SAMPLE_LIMIT, capture.snapshot().state());
    assertEquals(Profiler.Kind.OPERATION_WORK, capture.snapshot().samples().getLast().kind());
    assertEquals(0, work.retained());
  }

  @Test
  void ledgerReservationAndLabelsHaveExplicitBounds() {
    var capture = profiler.start(options);
    var scope = profiler.scope(1, project, null);
    assertEquals(256 + 256L * 64, scope.contributionBytes());
    var work = scope.work();
    interval(work, op("x".repeat(1000), layer("y".repeat(1000))), 0);
    work.flush(Profiler.Outcome.SUCCESS);
    assertTrue(
        capture.snapshot().samples().stream()
            .allMatch(s -> s.label().length() <= 256 && s.value() == 0));
    profiler.stop();
    assertEquals(0, scope.contributionBytes());
  }
}
