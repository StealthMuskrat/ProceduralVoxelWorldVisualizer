package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.Profiler;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred CPU backend fault/owner definitions; no native context or runtime evidence. */
class GpuTimerRetirementTest {
  private static final Profiler.Options OPTIONS = new Profiler.Options(60000, 512, 32, 1);
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("text"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static final class Backend implements GpuTimerQueries.Backend {
    final List<String> operations = new ArrayList<>();
    final Set<Integer> live = new HashSet<>();
    final List<Integer> deleted = new ArrayList<>();
    final Map<Integer, Long> values = new HashMap<>();
    Consumer<String> event = operation -> {};
    Consumer<Integer> deleting = id -> {};
    int creates, stamps, available, results;
    long ticks = 1000;
    boolean ready;
    public int counterBits() { operations.add("bits"); event.accept("bits"); return 64; }
    public int create() {
      int id = creates + 1;
      event.accept("create-before" + id); creates++; live.add(id); operations.add("create" + id);
      event.accept("create" + id); return id;
    }
    public void timestamp(int id) {
      assertTrue(live.contains(id)); values.put(id, ticks); ticks += 17;
      String name = ++stamps % 2 == 1 ? "start" : "end";
      operations.add(name); event.accept(name);
    }
    public boolean available(int id) {
      assertTrue(live.contains(id)); String name = "available" + (++available);
      operations.add(name); event.accept(name); return ready;
    }
    public long result(int id) {
      assertTrue(live.contains(id)); assertTrue(ready);
      String name = "result" + (++results); operations.add(name); event.accept(name);
      return values.get(id);
    }
    public void delete(int id) { operations.add("delete" + id); deleted.add(id); deleting.accept(id); }
  }
  private static Profiler.Scope capture(Profiler profiler) {
    profiler.start(OPTIONS); return profiler.scope(0, new UUID(0, 73), null);
  }
  private static long issue(GpuTimerQueries timer, Profiler.Scope scope) {
    long token = timer.begin(scope, "draw"); assertNotEquals(0, token); timer.end(token, true); return token;
  }

  @Test
  void everyCloseFaultAttemptsAllEightNamesAfterTheEntireRingBecomesTerminal() {
    for (int slot = 1; slot <= 8; slot++) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var reads = new AtomicInteger(); var primary = fault(error, reads); var gpu = new Backend();
        try (var profiler = new Profiler()) {
          var scope = capture(profiler); var timer = new GpuTimerQueries(gpu, () -> 0);
          for (int i = 0; i < 4; i++) issue(timer, scope);
          int failed = slot;
          gpu.deleting = id -> {
            assertEquals(GpuTimerQueries.State.CLOSED, timer.statistics().state());
            assertEquals(0, timer.statistics().allocatedQueries()); assertEquals(0, timer.statistics().pending());
            timer.close(); timer.synchronize(scope); assertEquals(0, timer.begin(scope, "nested"));
            assertTrue(timer.poll(scope).isEmpty()); timer.end(1, true);
            if (id == failed && !after) raise(primary);
            assertTrue(gpu.live.remove(id));
            if (id == failed) raise(primary);
          };
          assertSame(primary, assertThrows(Throwable.class, timer::close));
          assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), gpu.deleted);
          assertEquals(after ? Set.of() : Set.of(failed), gpu.live);
          assertDoesNotThrow(timer::close); assertEquals(8, gpu.deleted.size());
          assertEquals(4, timer.statistics().discarded()); assertEquals(0, reads.get());
          assertEquals(0, primary.getSuppressed().length);
        }
      }
  }

  @Test
  void stopReplacementAndExpiryRetireAllNamesDespiteErrorsAndDoNotReadUnavailableResults() {
    for (String path : List.of("stop", "replace", "expire")) for (boolean error : new boolean[] {false, true}) {
      var gpu = new Backend(); var time = new AtomicLong(); var reads = new AtomicInteger();
      var primary = fault(error, reads);
      try (var profiler = new Profiler(); var timer = new GpuTimerQueries(gpu, time::get)) {
        var scope = capture(profiler); issue(timer, scope); issue(timer, scope);
        gpu.deleting = id -> { gpu.live.remove(id); raise(primary); };
        Runnable operation;
        if (path.equals("stop")) operation = () -> timer.synchronize(null);
        else if (path.equals("replace")) {
          var next = capture(profiler); operation = () -> timer.synchronize(next);
        } else {
          time.set(GpuTimerQueries.MAX_AGE_NANOS); operation = () -> timer.poll(scope);
        }
        if (error) assertSame(primary, assertThrows(AssertionError.class, operation::run));
        else assertDoesNotThrow(operation::run);
        assertEquals(List.of(1, 2, 3, 4), gpu.deleted); assertTrue(gpu.live.isEmpty());
        assertEquals(0, gpu.results); assertEquals(0, timer.statistics().pending());
        assertEquals(GpuTimerQueries.State.FAILED, timer.statistics().state());
        assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
        gpu.deleting = id -> assertTrue(gpu.live.remove(id)); gpu.ready = true;
        var retry = capture(profiler); issue(timer, retry);
        assertEquals(17, timer.poll(retry).getFirst().elapsedNanos());
        assertEquals(6, gpu.creates); assertEquals(1, timer.statistics().recorded());
      }
    }
  }

  @Test
  void everyWorkPrimaryKeepsItsPolicyAndIdentityThroughUnreadableCleanupFailures() {
    for (String point : List.of("bits", "create-before1", "create-before2", "start", "end",
        "available1", "available2", "result1", "result2", "clock-begin", "clock-poll"))
      for (boolean error : new boolean[] {false, true}) {
        var gpu = new Backend(); var reads = new AtomicInteger(); var primary = fault(error, reads);
        var cleanup = fault(!error, reads); var clockCalls = new AtomicInteger();
        try (var profiler = new Profiler(); var timer = new GpuTimerQueries(gpu, () -> {
          int n = clockCalls.incrementAndGet();
          if (point.equals(n == 1 ? "clock-begin" : "clock-poll")) raise(primary);
          return 0;
        })) {
          var scope = capture(profiler); gpu.ready = true;
          gpu.event = name -> { if (name.equals(point)) raise(primary); };
          gpu.deleting = id -> { assertTrue(gpu.live.remove(id)); raise(cleanup); };
          Runnable work = () -> { long token = timer.begin(scope, "fault"); timer.end(token, true); timer.poll(scope); };
          if (error) assertSame(primary, assertThrows(AssertionError.class, work::run));
          else assertDoesNotThrow(work::run);
          assertEquals(GpuTimerQueries.State.FAILED, timer.statistics().state());
          assertEquals(0, timer.statistics().allocatedQueries()); assertEquals(0, timer.statistics().pending());
          assertTrue(gpu.live.isEmpty()); assertEquals(gpu.creates, gpu.deleted.size());
          assertTrue(profiler.current().orElseThrow().snapshot().samples().isEmpty());
          assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
          assertEquals(0, cleanup.getSuppressed().length);
        }
      }
  }

  @Test
  void closureAtEveryBackendOrClockBoundaryCannotReattachNamesOrPublishAReceipt() {
    for (String point : List.of("bits", "create1", "create2", "start", "end", "available1",
        "available2", "result1", "result2", "clock-begin", "clock-poll")) {
      var gpu = new Backend(); var clockCalls = new AtomicInteger();
      var ref = new AtomicReference<GpuTimerQueries>();
      try (var profiler = new Profiler(); var timer = new GpuTimerQueries(gpu, () -> {
        int n = clockCalls.incrementAndGet();
        if (point.equals(n == 1 ? "clock-begin" : "clock-poll")) ref.get().close();
        return 0;
      })) {
        ref.set(timer); var scope = capture(profiler); gpu.ready = true;
        gpu.event = name -> { if (name.equals(point)) timer.close(); };
        gpu.deleting = id -> assertTrue(gpu.live.remove(id));
        long token = timer.begin(scope, "close"); timer.end(token, true);
        assertTrue(timer.poll(scope).isEmpty());
        assertEquals(GpuTimerQueries.State.CLOSED, timer.statistics().state());
        assertEquals(0, timer.statistics().pending()); assertEquals(0, timer.statistics().allocatedQueries());
        assertTrue(gpu.live.isEmpty()); assertEquals(gpu.creates, gpu.deleted.size());
        assertTrue(profiler.current().orElseThrow().snapshot().samples().isEmpty());
        int calls = gpu.operations.size(); timer.synchronize(scope); timer.end(token, true);
        assertEquals(0, timer.begin(scope, "late")); assertTrue(timer.poll(scope).isEmpty()); timer.close();
        assertEquals(calls, gpu.operations.size());
      }
    }
  }

  @Test
  void recursiveOperationsCannotStealOrReplaceAnAdmittedPairOrItsCapture() {
    var gpu = new Backend();
    try (var profiler = new Profiler(); var timer = new GpuTimerQueries(gpu, () -> 0)) {
      var scope = capture(profiler); var seen = new AtomicInteger();
      gpu.event = name -> {
        timer.synchronize(null); timer.end(1, false);
        assertEquals(0, timer.begin(scope, "nested")); assertTrue(timer.poll(scope).isEmpty());
        seen.incrementAndGet();
      };
      gpu.deleting = id -> { timer.synchronize(scope); assertEquals(0, timer.begin(scope, "retiring")); gpu.live.remove(id); };
      issue(timer, scope); gpu.ready = true;
      var receipt = timer.poll(scope).getFirst(); assertTrue(seen.get() >= 9);
      assertEquals(17, receipt.elapsedNanos()); assertEquals("draw", receipt.label()); assertTrue(receipt.recorded());
      var sample = profiler.current().orElseThrow().snapshot().samples().getFirst();
      assertEquals(Profiler.Kind.GPU_DRAW, sample.kind()); assertEquals(Profiler.Outcome.MEASURED, sample.outcome());
      assertEquals(17, sample.value()); assertEquals(2, gpu.creates); assertEquals(1, timer.statistics().submitted());
      timer.synchronize(null); assertEquals(List.of(1, 2), gpu.deleted);
    }
  }

  @Test
  void cleanupReentryCannotReviveClosedOrReplacedOwnersAndNeverReadsFailureText() {
    var gpu = new Backend(); var reads = new AtomicInteger(); var primary = fault(true, reads);
    try (var profiler = new Profiler(); var timer = new GpuTimerQueries(gpu, () -> 0)) {
      var scope = capture(profiler); issue(timer, scope); issue(timer, scope);
      var replacement = capture(profiler);
      gpu.deleting = id -> {
        assertEquals(0, timer.statistics().allocatedQueries()); assertEquals(0, timer.statistics().pending());
        timer.close(); assertEquals(0, timer.begin(replacement, "nested"));
        gpu.live.remove(id); raise(primary);
      };
      assertSame(primary, assertThrows(AssertionError.class, () -> timer.synchronize(replacement)));
      assertEquals(GpuTimerQueries.State.CLOSED, timer.statistics().state());
      assertEquals(List.of(1, 2, 3, 4), gpu.deleted); assertEquals(0, reads.get());
      assertEquals(0, primary.getSuppressed().length); assertDoesNotThrow(timer::close);
    }
  }

  @Test
  void duplicateSignedQueryNamesAreRetiredOnceWithoutCrossNamespaceCoalescing() {
    var deletes = new ArrayList<Integer>();
    var backend = new GpuTimerQueries.Backend() {
      public int counterBits() { return 64; }
      public int create() { return -7; }
      public void timestamp(int id) { assertEquals(-7, id); }
      public boolean available(int id) { return false; }
      public long result(int id) { throw new AssertionError("Unavailable result read"); }
      public void delete(int id) { deletes.add(id); }
    };
    try (var profiler = new Profiler(); var timer = new GpuTimerQueries(backend, () -> 0)) {
      var scope = capture(profiler); for (int i = 0; i < 4; i++) issue(timer, scope);
      timer.close(); timer.close(); assertEquals(List.of(-7), deletes);
    }
    var handles = new ArrayList<RenderRetirement.Handle>();
    new RenderRetirement(handles::add, new RenderRetirement.Handle[] {
      new RenderRetirement.Handle(RenderRetirement.Kind.QUERY, -7),
      new RenderRetirement.Handle(RenderRetirement.Kind.BUFFER, -7),
      new RenderRetirement.Handle(RenderRetirement.Kind.TEXTURE, -7)
    }).run();
    assertEquals(3, handles.size());
  }
}
