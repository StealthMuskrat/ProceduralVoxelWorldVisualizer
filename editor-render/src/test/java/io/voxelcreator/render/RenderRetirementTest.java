package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred CPU plan/owner definitions; actual native owner fields and GL dispatch are source-traced. */
class RenderRetirementTest {
  private static RenderRetirement.Handle handle(RenderRetirement.Kind kind, int id) {
    return new RenderRetirement.Handle(kind, id);
  }
  private static final RenderRetirement.Handle[] TEN = {
    handle(RenderRetirement.Kind.PROGRAM, 1), handle(RenderRetirement.Kind.PROGRAM, 2),
    handle(RenderRetirement.Kind.FRAMEBUFFER, 3), handle(RenderRetirement.Kind.FRAMEBUFFER, 4),
    handle(RenderRetirement.Kind.BUFFER, 5), handle(RenderRetirement.Kind.VERTEX_ARRAY, 6),
    handle(RenderRetirement.Kind.TEXTURE, 7), handle(RenderRetirement.Kind.TEXTURE, 8),
    handle(RenderRetirement.Kind.RENDERBUFFER, 9), handle(RenderRetirement.Kind.FRAMEBUFFER, 10)
  };
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      @Override public String getMessage() { reads.incrementAndGet(); throw new AssertionError("Text read"); }
      @Override public String toString() { reads.incrementAndGet(); throw new AssertionError("Text read"); }
    } : new IllegalStateException() {
      @Override public String getMessage() { reads.incrementAndGet(); throw new AssertionError("Text read"); }
      @Override public String toString() { reads.incrementAndGet(); throw new AssertionError("Text read"); }
    };
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }

  @Test
  void everyChildAndHandleFaultAttemptsTheWholePlanAndKeepsItsOriginalPrimary() {
    for (int point = 0; point < 14; point++) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) {
        var reads = new AtomicInteger(); var primary = fault(error, reads);
        var attempts = new ArrayList<Integer>(); var effects = new HashSet<Integer>();
        int failed = point;
        java.util.function.IntConsumer action = index -> {
          attempts.add(index);
          if (index == failed && !after) raise(primary);
          assertTrue(effects.add(index));
          if (index == failed) raise(primary);
        };
        var plan = new RenderRetirement(h -> action.accept(h.id() + 3), TEN,
            () -> action.accept(0), () -> action.accept(1), () -> action.accept(2), () -> action.accept(3));
        assertSame(primary, assertThrows(Throwable.class, plan::run));
        assertEquals(java.util.stream.IntStream.range(0, 14).boxed().toList(), attempts);
        assertEquals(after ? 14 : 13, effects.size());
        assertDoesNotThrow(plan::run); assertEquals(14, attempts.size());
        assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void sharedAndDistinctUnreadableFailuresDoNotReadTextOrMutateSuppression() {
    for (boolean shared : new boolean[] {false, true}) {
      var reads = new AtomicInteger(); var first = fault(true, reads);
      var later = shared ? first : fault(false, reads); var calls = new ArrayList<String>();
      var plan = new RenderRetirement(h -> { calls.add("delete" + h.id()); raise(later); },
          new RenderRetirement.Handle[] {TEN[0], TEN[1]},
          () -> { calls.add("child"); raise(first); });
      assertSame(first, assertThrows(AssertionError.class, plan::run));
      assertEquals(List.of("child", "delete1", "delete2"), calls);
      assertEquals(0, reads.get()); assertEquals(0, first.getSuppressed().length);
      assertEquals(0, later.getSuppressed().length); assertDoesNotThrow(plan::run);
    }
  }

  @Test
  void namespacesStayIndependentWhileZeroAndSameKindDuplicatesAreCoalesced() {
    var names = new ArrayList<RenderRetirement.Handle>();
    for (var kind : RenderRetirement.Kind.values()) {
      names.add(handle(kind, 7)); names.add(handle(kind, 0)); names.add(handle(kind, 7));
    }
    names.add(handle(RenderRetirement.Kind.TEXTURE, -7));
    var deleted = new ArrayList<RenderRetirement.Handle>();
    var plan = new RenderRetirement(deleted::add, names.toArray(RenderRetirement.Handle[]::new));
    plan.run(); plan.run();
    assertEquals(7, deleted.size());
    for (var kind : RenderRetirement.Kind.values()) assertTrue(deleted.contains(handle(kind, 7)));
    assertEquals(handle(RenderRetirement.Kind.TEXTURE, -7), deleted.getLast());
  }

  @Test
  void nestedExecutionCannotReplayAmbiguousDeletionAgainstAReusedName() {
    var plan = new AtomicReference<RenderRetirement>();
    var calls = new ArrayList<String>(); var live = new HashSet<>(List.of(TEN[6]));
    var primary = new AssertionError("after native effect");
    plan.set(new RenderRetirement(h -> {
      calls.add("delete"); assertTrue(live.remove(h));
      live.add(h); // A successor obtains the same numeric name after this deletion.
      plan.get().run(); throw primary;
    }, new RenderRetirement.Handle[] {TEN[6]}, () -> {
      calls.add("child"); plan.get().run();
    }));
    assertSame(primary, assertThrows(AssertionError.class, plan.get()::run));
    assertEquals(List.of("child", "delete"), calls); assertEquals(Set.of(TEN[6]), live);
    assertDoesNotThrow(plan.get()::run); assertEquals(Set.of(TEN[6]), live);
  }

  @Test
  void retirementCapturesCallerArraysBeforeLaterMutationAndPreservesSuccessors() {
    var handles = new RenderRetirement.Handle[] {TEN[0], TEN[6]};
    var calls = new ArrayList<String>(); var successor = new AtomicInteger();
    Runnable[] children = {() -> { calls.add("original"); successor.set(41); }};
    var plan = new RenderRetirement(h -> calls.add(h.kind() + ":" + h.id()), handles, children);
    handles[0] = handle(RenderRetirement.Kind.BUFFER, 999); handles[1] = null;
    children[0] = () -> fail("Replaced child invoked");
    plan.run(); assertEquals(List.of("original", "PROGRAM:1", "TEXTURE:7"), calls);
    assertEquals(41, successor.get()); plan.run(); assertEquals(3, calls.size());
  }

  private static final class VolumeMemory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> data = new HashMap<>();
    final List<Integer> deleted = new ArrayList<>();
    int next;
    java.util.function.IntConsumer deleting = id -> {};
    public int allocate(int side) { int id = ++next; data.put(id, new float[side * side * side]); return id; }
    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      for (int k = 0; k < d; k++) for (int j = 0; j < h; j++) for (int i = 0; i < w; i++)
        data.get(id)[((z + k) * 2 + y + j) * 2 + x + i] = values.get();
    }
    public void delete(int id) {
      deleted.add(id); assertNotNull(data.remove(id)); deleting.accept(id);
    }
  }

  @Test
  void realSceneArenaAndVolumeOwnersRetireEvenAfterEarlierSiblingAndNestedFailures() {
    var arena = new ArenaAllocator(64);
    var a = arena.allocate(16); var b = arena.allocate(32);
    assertEquals(0, a.offset()); assertEquals(16, b.offset()); assertEquals(48, arena.usedBytes());
    var reads = new AtomicInteger(); var primary = fault(true, reads); var nested = fault(false, reads);
    var calls = new ArrayList<String>();
    var slot = new SceneRendererOwner<ArenaAllocator>(new SceneRendererOwner.Backend<>() {
      public ArenaAllocator create(int bytes) { assertEquals(64, bytes); return arena; }
      public void epoch(ArenaAllocator value, long epoch) { assertEquals(7, epoch); }
      public void close(ArenaAllocator value) { throw new AssertionError("Captured retirement owns this close"); }
    });
    slot.activate(7, 64, () -> {}, value -> {}); var captured = slot.closeOwner();
    assertSame(arena, captured); assertNull(slot.renderer()); assertEquals(0, slot.budget());
    assertThrows(IllegalStateException.class, () -> slot.activate(8, 64, () -> {}, value -> {}));
    var memory = new VolumeMemory(); var volume = new VolumeTextureUpload(memory);
    float[] expected = {1, -2, 3, 4, -5, 6, 7, 8};
    volume.stage(new VolumeData(2, expected), 64); assertEquals(32, volume.advance(32));
    assertArrayEquals(expected, memory.data.get(1));
    volume.stage(new VolumeData(2, new float[] {9, 10, 11, 12, 13, 14, 15, 16}), 64);
    assertEquals(4, volume.advance(4)); assertEquals(9, memory.data.get(2)[0]);
    memory.deleting = id -> {
      assertNull(volume.resident()); assertFalse(volume.statistics().pending());
      assertEquals(0, volume.statistics().cpuBytes()); assertDoesNotThrow(volume::close);
      assertThrows(IllegalStateException.class, () -> volume.stage(new VolumeData(2, expected), 64));
    };
    var plan = new RenderRetirement(h -> calls.add("root" + h.id()), TEN,
        () -> { calls.add("shadow"); raise(primary); },
        () -> calls.add("timer"),
        () -> new RenderRetirement(h -> {
          calls.add("scene" + h.id()); if (h.id() == 31) raise(nested);
        }, new RenderRetirement.Handle[] {handle(RenderRetirement.Kind.PROGRAM, 31),
            handle(RenderRetirement.Kind.BUFFER, 32)}, captured::reset).run(),
        volume::close);
    assertSame(primary, assertThrows(AssertionError.class, plan::run));
    assertEquals(0, arena.usedBytes()); assertEquals(64, arena.freeBytes());
    assertEquals(List.of(2, 1), memory.deleted); assertTrue(memory.data.isEmpty());
    assertTrue(calls.containsAll(List.of("shadow", "timer", "scene31", "scene32", "root10")));
    assertEquals(14, calls.size()); assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
    assertDoesNotThrow(plan::run); assertEquals(14, calls.size());
    assertEquals(0, volume.statistics().activeBytes()); assertEquals(0, volume.statistics().cpuBytes());
  }

  @Test
  void realAllocationEpochPrimarySurvivesCandidateRetirementFailureAfterAllAttempts() {
    var reads = new AtomicInteger(); var primary = fault(true, reads); var cleanup = fault(false, reads);
    var attempts = new ArrayList<Integer>(); var arena = new ArenaAllocator(64); arena.allocate(16);
    var owner = new SceneRendererOwner<ArenaAllocator>(new SceneRendererOwner.Backend<>() {
      public ArenaAllocator create(int bytes) { return arena; }
      public void epoch(ArenaAllocator value, long epoch) { raise(primary); }
      public void close(ArenaAllocator value) {
        new RenderRetirement(h -> {
          attempts.add(h.id()); if (h.id() == 1) raise(cleanup);
        }, new RenderRetirement.Handle[] {handle(RenderRetirement.Kind.BUFFER, 1),
            handle(RenderRetirement.Kind.VERTEX_ARRAY, 2)}, value::reset).run();
      }
    });
    assertSame(primary, assertThrows(AssertionError.class, () -> owner.activate(7, 64, () -> {}, value -> fail())));
    assertEquals(List.of(1, 2), attempts); assertNull(owner.renderer()); assertEquals(0, arena.usedBytes());
    assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
    assertEquals(0, cleanup.getSuppressed().length);
    assertNull(owner.closeOwner()); assertEquals(List.of(1, 2), attempts);
  }

  @Test
  void emptyPartialAndDeferredPlansConsumeTheirCapturedCallbacksBeforeReentry() {
    assertDoesNotThrow(() -> new RenderRetirement(h -> fail(), new RenderRetirement.Handle[0]).run());
    var calls = new ArrayList<String>(); var deferred = new AtomicReference<Runnable>();
    var primary = new IllegalStateException("child");
    Runnable[] callbacks = {() -> { calls.add("first"); deferred.get().run(); throw primary; },
        () -> calls.add("tail")};
    deferred.set(RenderCleanup.deferred(callbacks)); callbacks[1] = () -> fail("Changed callback");
    assertSame(primary, assertThrows(IllegalStateException.class, deferred.get()::run));
    assertDoesNotThrow(deferred.get()::run); assertEquals(List.of("first", "tail"), calls);
    var partial = new ArrayList<RenderRetirement.Handle>();
    var plan = new RenderRetirement(partial::add, new RenderRetirement.Handle[] {
      handle(RenderRetirement.Kind.PROGRAM, 0), handle(RenderRetirement.Kind.BUFFER, -3),
      handle(RenderRetirement.Kind.VERTEX_ARRAY, 0)});
    plan.run(); plan.run(); assertEquals(List.of(handle(RenderRetirement.Kind.BUFFER, -3)), partial);
  }
}
