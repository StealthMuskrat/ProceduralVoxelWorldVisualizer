package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GpuTimerQueriesTest {
  private static final Profiler.Options OPTIONS = new Profiler.Options(60000, 512, 32, 1);
  private final Profiler profiler = new Profiler();
  private final Fake gpu = new Fake();
  private final AtomicLong time = new AtomicLong();
  private final GpuTimerQueries timer = new GpuTimerQueries(gpu, time::get);

  private Profiler.Scope capture() {
    profiler.start(OPTIONS);
    return profiler.scope(0, new UUID(0, 17), null);
  }

  private long issue(Profiler.Scope scope, String label) {
    long token = timer.begin(scope, label);
    assertNotEquals(0, token);
    timer.end(token, true);
    return token;
  }

  @Test
  void disabledAndUnsupportedPathsAllocateNothingAndDoNotReadTheClock() {
    var reads = new AtomicLong();
    try (var disabled =
        new GpuTimerQueries(
            gpu,
            () -> {
              reads.incrementAndGet();
              return 0;
            })) {
      disabled.synchronize(null);
      assertEquals(0, disabled.begin(null, "off"));
      assertTrue(disabled.poll(null).isEmpty());
      assertEquals(0, reads.get());
      assertEquals(0, gpu.checks);
      assertEquals(0, gpu.creates);
      gpu.bits = 0;
      var scope = capture();
      for (int i = 0; i < 100; i++) assertEquals(0, disabled.begin(scope, "unsupported"));
      assertEquals(GpuTimerQueries.State.UNAVAILABLE, disabled.statistics().state());
      assertEquals(1, gpu.checks);
      assertEquals(0, gpu.creates);
      assertEquals(0, reads.get());
    }
  }

  @Test
  void fourPairsBoundPressureWithoutReadingUnavailableResultsOrOverwritingNames() {
    var scope = capture();
    for (int i = 0; i < 4; i++) issue(scope, "draw");
    for (int i = 0; i < 100; i++) assertEquals(0, timer.begin(scope, "full"));
    assertEquals(8, gpu.creates);
    assertEquals(4, timer.statistics().pending());
    assertEquals(100, timer.statistics().skipped());
    assertTrue(timer.poll(scope).isEmpty());
    assertEquals(0, gpu.reads);
    gpu.readyAll();
    var receipts = timer.poll(scope);
    assertEquals(4, receipts.size());
    assertEquals(4, timer.statistics().recorded());
    assertTrue(receipts.stream().allMatch(r -> r.recorded() && r.elapsedNanos() == 17));
    issue(scope, "reused");
    assertEquals(8, gpu.creates);
    gpu.readyAll();
    assertEquals(1, timer.poll(scope).size());
    timer.close();
    assertEquals(gpu.created, gpu.deleted);
    assertEquals(0, timer.statistics().allocatedQueries());
    assertEquals(GpuTimerQueries.State.CLOSED, timer.statistics().state());
    timer.close();
  }

  @Test
  void completionRequiresBothQueriesAndNestedOrWrongEndCannotStealTheActivePair() {
    var scope = capture();
    long token = timer.begin(scope, "draw");
    assertEquals(0, timer.begin(scope, "nested"));
    timer.end(token + 1, true);
    assertTrue(timer.poll(scope).isEmpty());
    assertEquals(0, gpu.reads);
    timer.end(token, false);
    gpu.ready.add(1);
    assertTrue(timer.poll(scope).isEmpty());
    assertEquals(0, gpu.reads);
    gpu.ready.add(2);
    var receipt = timer.poll(scope).getFirst();
    assertEquals(17, receipt.elapsedNanos());
    var sample = profiler.current().orElseThrow().snapshot().samples().getFirst();
    assertEquals(Profiler.Kind.GPU_DRAW, sample.kind());
    assertEquals(Profiler.Unit.NANOSECONDS, sample.unit());
    assertEquals(Profiler.Outcome.FAILED, sample.outcome());
    assertEquals(receipt.offsetNanos(), sample.offsetNanos());
    assertEquals(17, sample.value());
    timer.end(token, true);
    assertEquals(1, timer.statistics().recorded());
  }

  @Test
  void unsignedTimestampHighBitsAndCounterWrapArePreserved() {
    var scope = capture();
    issue(scope, "64-bit");
    gpu.values.put(1, Long.MAX_VALUE - 9);
    gpu.values.put(2, Long.MIN_VALUE + 15);
    gpu.readyAll();
    var r = timer.poll(scope).getFirst();
    assertEquals(25, r.elapsedNanos());
    assertEquals("7ffffffffffffff6", r.startHex());
    assertEquals("800000000000000f", r.endHex());
    timer.close();
    var backend = new Fake();
    backend.bits = 30;
    try (var shortCounter = new GpuTimerQueries(backend, time::get)) {
      long token = shortCounter.begin(scope, "wrap");
      shortCounter.end(token, true);
      backend.values.put(1, (1L << 30) - 5);
      backend.values.put(2, 7L);
      backend.readyAll();
      assertEquals(12, shortCounter.poll(scope).getFirst().elapsedNanos());
    }
  }

  @Test
  void expiredQueriesRetireTheirNamesBeforeAnyReadAndSuccessorsUseFreshNames() {
    var scope = capture();
    issue(scope, "late");
    time.set(GpuTimerQueries.MAX_AGE_NANOS);
    assertTrue(timer.poll(scope).isEmpty());
    assertEquals(0, gpu.reads);
    assertEquals(Set.of(1, 2), gpu.deleted);
    assertEquals(1, timer.statistics().discarded());
    issue(scope, "successor");
    assertEquals(4, gpu.creates);
    gpu.readyAll();
    assertEquals(1, timer.poll(scope).size());
    assertEquals(
        "successor", profiler.current().orElseThrow().snapshot().samples().getFirst().label());
  }

  @Test
  void narrowCounterExpiryBackwardsClockAndInvalidLargeDeltasProduceNoFakeSamples() {
    for (int mode = 0; mode < 3; mode++) {
      var backend = new Fake();
      backend.bits = 30;
      time.set(100);
      try (var local = new GpuTimerQueries(backend, time::get)) {
        var scope = capture();
        long token = local.begin(scope, "invalid");
        local.end(token, true);
        if (mode == 0) time.addAndGet(1L << 30);
        if (mode == 1) time.set(99);
        if (mode == 2) {
          backend.values.put(1, 0L);
          backend.values.put(2, (1L << 30) - 1);
          backend.readyAll();
        }
        assertTrue(local.poll(scope).isEmpty());
        assertEquals(1, local.statistics().discarded());
        assertTrue(profiler.current().orElseThrow().snapshot().samples().isEmpty());
        if (mode < 2) assertEquals(0, backend.reads);
      }
    }
  }

  @Test
  void replacedCaptureDropsOldResultsAndLateEndCannotClearItsSuccessor() {
    var oldScope = capture();
    var old = profiler.current().orElseThrow();
    long oldToken = issue(oldScope, "old");
    var nextScope = capture();
    timer.poll(nextScope);
    assertEquals(Set.of(1, 2), gpu.deleted);
    assertTrue(old.snapshot().samples().isEmpty());
    long nextToken = timer.begin(nextScope, "new");
    timer.end(oldToken, true);
    assertEquals(1, timer.statistics().pending());
    timer.end(nextToken, true);
    gpu.readyAll();
    var r = timer.poll(nextScope).getFirst();
    assertEquals(nextScope.captureId(), r.captureId());
    assertEquals("new", profiler.current().orElseThrow().snapshot().samples().getFirst().label());
    assertEquals(Profiler.State.REPLACED, old.snapshot().state());
  }

  @Test
  void stopClearAndClosureReleasePendingResourcesAndCapturedScopes() {
    var scope = capture();
    issue(scope, "stop");
    profiler.stop();
    assertTrue(timer.poll(scope).isEmpty());
    assertEquals(0, timer.statistics().pending());
    assertEquals(0, timer.statistics().allocatedQueries());
    assertEquals(0, gpu.reads);
    assertTrue(profiler.current().orElseThrow().snapshot().samples().isEmpty());
    scope = capture();
    issue(scope, "clear");
    profiler.clear();
    timer.synchronize(null);
    assertEquals(0, timer.statistics().pending());
    scope = capture();
    issue(scope, "close");
    timer.close();
    assertEquals(gpu.created, gpu.deleted);
    assertEquals(0, timer.begin(scope, "closed"));
  }

  @Test
  void backendFailuresAreIsolatedAndNewCaptureCanRetry() {
    for (String operation : List.of("bits", "create-end", "start", "end", "available", "result")) {
      var backend = new Fake();
      backend.failure = operation;
      try (var local = new GpuTimerQueries(backend, time::get)) {
        var scope = capture();
        long token = local.begin(scope, "failure");
        local.end(token, true);
        backend.readyAll();
        local.poll(scope);
        assertEquals(GpuTimerQueries.State.FAILED, local.statistics().state(), operation);
        assertEquals(0, local.statistics().allocatedQueries());
        assertTrue(profiler.current().orElseThrow().snapshot().samples().isEmpty());
        backend.failure = "";
        scope = capture();
        token = local.begin(scope, "retry");
        local.end(token, true);
        backend.readyAll();
        assertEquals(1, local.poll(scope).size());
      }
      assertEquals(backend.created, backend.deleted);
    }
  }

  @Test
  void zeroMeasuredDurationIsValidAndCaptureLimitsStillOwnPublication() {
    var scope = capture();
    issue(scope, "zero");
    gpu.values.put(2, gpu.values.get(1));
    gpu.readyAll();
    assertEquals(0, timer.poll(scope).getFirst().elapsedNanos());
    assertEquals(1, timer.statistics().recorded());
    profiler.start(new Profiler.Options(60000, 64, 1, 1));
    scope = profiler.scope(0, null, null);
    scope.gauge(Profiler.Kind.FRAME, Profiler.Unit.NANOSECONDS, 1, "existing");
    issue(scope, "new-series");
    gpu.readyAll();
    var r = timer.poll(scope).getFirst();
    assertFalse(r.recorded());
    assertEquals(Profiler.State.SERIES_LIMIT, profiler.current().orElseThrow().snapshot().state());
    assertEquals(0, timer.statistics().recorded());
    assertEquals(1, timer.statistics().discarded());
  }

  @Test
  void explicitOptOutCanResumeTheSameCaptureWithoutLosingCapabilityState() {
    var scope = capture();
    issue(scope, "first");
    gpu.readyAll();
    assertEquals(1, timer.poll(scope).size());
    timer.synchronize(null);
    assertEquals(0, timer.statistics().allocatedQueries());
    issue(scope, "resumed");
    gpu.readyAll();
    assertEquals(1, timer.poll(scope).size());
    assertEquals(1, gpu.checks);
    assertEquals(2, timer.statistics().recorded());
  }

  private static final class Fake implements GpuTimerQueries.Backend {
    int bits = 64, checks, creates, reads, stamps;
    long clock = 1L << 40;
    String failure = "";
    Set<Integer> created = new HashSet<>(), deleted = new HashSet<>(), ready = new HashSet<>();
    Map<Integer, Long> values = new HashMap<>();

    void fail(String operation) {
      if (failure.equals(operation)) throw new IllegalStateException(operation);
    }

    public int counterBits() {
      checks++;
      fail("bits");
      return bits;
    }

    public int create() {
      if (creates % 2 == 1) fail("create-end");
      int id = ++creates;
      created.add(id);
      return id;
    }

    public void timestamp(int id) {
      fail(++stamps % 2 == 1 ? "start" : "end");
      assertTrue(created.contains(id) && !deleted.contains(id));
      values.put(id, clock);
      clock += 17;
      ready.remove(id);
    }

    public boolean available(int id) {
      fail("available");
      return ready.contains(id);
    }

    public long result(int id) {
      fail("result");
      assertTrue(ready.contains(id));
      assertFalse(deleted.contains(id));
      reads++;
      return values.get(id);
    }

    public void delete(int id) {
      assertTrue(created.contains(id));
      assertTrue(deleted.add(id), "Double deletion");
      ready.remove(id);
    }

    void readyAll() {
      created.stream().filter(id -> !deleted.contains(id)).forEach(ready::add);
    }
  }
}
