package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred production coordinator/state definitions with independent foreign state and names. */
class ShadowTransactionTest {
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
  private static final List<String> A_RESTORES = allocationRestores();
  private static List<String> allocationRestores() {
    var values = new ArrayList<>(List.of("a-restore-read", "a-restore-draw"));
    for (var item : TextureUploadState.Item.values()) values.add("a-restore-" + item);
    return List.copyOf(values);
  }
  private static final List<String> R_RESTORES = List.of("r-restore-VERTEX_ARRAY", "r-restore-attribute",
      "r-restore-PROGRAM", "r-restore-DEPTH_MASK", "r-restore-DEPTH_FUNCTION", "r-restore-clearDepth",
      "r-restore-range", "r-restore-DEPTH_TEST", "r-restore-CULL_FACE", "r-restore-SCISSOR_TEST",
      "r-restore-POLYGON_OFFSET_FILL", "r-restore-RASTERIZER_DISCARD", "r-restore-READ_FRAMEBUFFER",
      "r-restore-DRAW_FRAMEBUFFER", "r-restore-viewport");

  private static final class Fixture implements AutoCloseable, ShadowAllocationState.Backend,
      ShadowRasterState.Backend, ShadowMapWork.Allocation {
    final EnumMap<TextureUploadState.Item, Integer> texture = new EnumMap<>(TextureUploadState.Item.class);
    final EnumMap<ShadowRasterState.Binding, Integer> bindings = new EnumMap<>(ShadowRasterState.Binding.class);
    final EnumMap<ShadowRasterState.Flag, Boolean> flags = new EnumMap<>(ShadowRasterState.Flag.class);
    final Set<RenderRetirement.Handle> live = new HashSet<>();
    final List<RenderRetirement.Handle> deleted = new ArrayList<>();
    final List<String> calls = new ArrayList<>();
    final ShadowMapWork work = new ShadowMapWork(this::delete);
    Consumer<String> before = name -> {}, after = name -> {};
    int[] viewport = {2, 3, 37, 41}; float[] attribute = {1, 2, 3, 4}; double[] range = {.2, .8};
    double clear = .25;
    int next = 20, maximum = 2048, draws, clears;
    boolean aRestore, rRestore, mutateArguments;
    Fixture() {
      int[] words = {0x84c3, -11, -13, 8, 1, 7, 9, 2, 3, 4};
      for (var item : TextureUploadState.Item.values()) texture.put(item, words[item.ordinal()]);
      int[] words2 = {-17, -19, -23, -29, 0x0206};
      for (var item : ShadowRasterState.Binding.values()) bindings.put(item, words2[item.ordinal()]);
      for (var item : ShadowRasterState.Flag.values()) flags.put(item, item != ShadowRasterState.Flag.DEPTH_TEST
          && item != ShadowRasterState.Flag.DEPTH_MASK);
    }
    void call(String name, Runnable effect) { calls.add(name); before.accept(name); effect.run(); after.accept(name); }
    void readCall(String name) { call(name, () -> {}); }
    public int read(TextureUploadState.Item item) { readCall("a-read-" + item); return texture.get(item); }
    public void write(TextureUploadState.Item item, int value) {
      call("a-" + (aRestore ? "restore-" : "normal-") + item, () -> texture.put(item, value));
    }
    public int readFramebuffer() { readCall("a-read-read"); return bindings.get(ShadowRasterState.Binding.READ_FRAMEBUFFER); }
    public int drawFramebuffer() { readCall("a-read-draw"); return bindings.get(ShadowRasterState.Binding.DRAW_FRAMEBUFFER); }
    public void readFramebuffer(int value) {
      aRestore = true; call("a-restore-read", () -> bindings.put(ShadowRasterState.Binding.READ_FRAMEBUFFER, value));
    }
    public void drawFramebuffer(int value) { call("a-restore-draw", () -> bindings.put(ShadowRasterState.Binding.DRAW_FRAMEBUFFER, value)); }
    public int binding(ShadowRasterState.Binding item) { readCall("r-read-" + item); return bindings.get(item); }
    public void binding(ShadowRasterState.Binding item, int value) {
      if (item == ShadowRasterState.Binding.VERTEX_ARRAY) rRestore = true;
      call("r-" + (rRestore ? "restore-" : "normal-") + item, () -> bindings.put(item, value));
    }
    public boolean flag(ShadowRasterState.Flag item) { readCall("r-read-" + item); return flags.get(item); }
    public void flag(ShadowRasterState.Flag item, boolean value) {
      call("r-" + (rRestore ? "restore-" : "normal-") + item, () -> flags.put(item, value));
    }
    public int[] viewport() { readCall("r-read-viewport"); return viewport; }
    public void viewport(int[] value) {
      call("r-" + (rRestore ? "restore-" : "normal-") + "viewport", () -> viewport = value.clone());
      if (mutateArguments) Arrays.fill(value, 999);
    }
    public float[] attribute() { readCall("r-read-attribute"); return attribute; }
    public void attribute(float[] value) {
      call("r-restore-attribute", () -> attribute = value.clone());
      if (mutateArguments) Arrays.fill(value, 999);
    }
    public double clearDepth() { readCall("r-read-clearDepth"); return clear; }
    public void clearDepth(double value) { call("r-" + (rRestore ? "restore-" : "normal-") + "clearDepth", () -> clear = value); }
    public double[] depthRange() { readCall("r-read-range"); return range; }
    public void depthRange(double[] value) {
      call("r-" + (rRestore ? "restore-" : "normal-") + "range", () -> range = value.clone());
      if (mutateArguments) Arrays.fill(value, 999);
    }
    public void clear() { call("r-clear", () -> clears++); }
    private int create(RenderRetirement.Kind kind) {
      int id = ++next; call("a-create-" + kind, () -> assertTrue(live.add(new RenderRetirement.Handle(kind, id))));
      return id;
    }
    public int texture() { return create(RenderRetirement.Kind.TEXTURE); }
    public int framebuffer() { return create(RenderRetirement.Kind.FRAMEBUFFER); }
    public int program() { return create(RenderRetirement.Kind.PROGRAM); }
    public void storage(int textureName, int framebuffer, int resolution) {
      call("a-storage", () -> {
        assertEquals(0, work.resources.resolution); assertEquals(0, work.resources.texture);
        assertEquals(0x84c3, texture.get(TextureUploadState.Item.ACTIVE_TEXTURE).intValue());
        for (var item : TextureUploadState.Item.values()) if (item != TextureUploadState.Item.ACTIVE_TEXTURE
            && item != TextureUploadState.Item.BINDING)
          assertEquals(item == TextureUploadState.Item.ALIGNMENT ? 4 : 0, texture.get(item).intValue());
        texture.put(TextureUploadState.Item.BINDING, textureName);
        bindings.put(ShadowRasterState.Binding.READ_FRAMEBUFFER, framebuffer);
        bindings.put(ShadowRasterState.Binding.DRAW_FRAMEBUFFER, framebuffer);
      });
    }
    public void complete() { call("a-complete", () -> {
      assertEquals(A_RESTORES, calls.stream().filter(s -> s.startsWith("a-restore-")).toList());
      assertEquals(0, work.resources.resolution);
    }); }
    void delete(RenderRetirement.Handle handle) {
      deleted.add(handle);
      call("delete-" + handle.kind(), () -> {
        assertTrue(live.remove(handle));
        if (handle.kind() == RenderRetirement.Kind.TEXTURE && texture.get(TextureUploadState.Item.BINDING) == handle.id())
          texture.put(TextureUploadState.Item.BINDING, 0);
        if (handle.kind() == RenderRetirement.Kind.FRAMEBUFFER) {
          for (var item : List.of(ShadowRasterState.Binding.READ_FRAMEBUFFER, ShadowRasterState.Binding.DRAW_FRAMEBUFFER))
            if (bindings.get(item) == handle.id()) bindings.put(item, 0);
        }
      });
    }
    void reset() { before = name -> {}; after = name -> {}; calls.clear(); deleted.clear(); aRestore = rRestore = false; }
    void allocate(int size) { aRestore = false; work.allocate(size, () -> { readCall("maximum"); return maximum; },
        () -> ShadowAllocationState.capture(this), this); }
    int render() { rRestore = false; return work.render(() -> ShadowRasterState.capture(this), program -> {
      call("r-draw", () -> {
        assertEquals(work.resources.program, program); assertEquals(0x0201, bindings.get(ShadowRasterState.Binding.DEPTH_FUNCTION).intValue());
        assertEquals(work.resources.framebuffer, bindings.get(ShadowRasterState.Binding.READ_FRAMEBUFFER).intValue());
        assertEquals(work.resources.framebuffer, bindings.get(ShadowRasterState.Binding.DRAW_FRAMEBUFFER).intValue());
        assertArrayEquals(new int[] {0, 0, 512, 512}, viewport); assertEquals(1, clear);
        assertArrayEquals(new double[] {0, 1}, range);
        for (var item : ShadowRasterState.Flag.values()) assertEquals(item == ShadowRasterState.Flag.DEPTH_TEST
            || item == ShadowRasterState.Flag.DEPTH_MASK, flags.get(item).booleanValue());
        bindings.put(ShadowRasterState.Binding.PROGRAM, program);
        bindings.put(ShadowRasterState.Binding.VERTEX_ARRAY, 99); attribute = new float[] {0, 0, 0, 1}; draws++;
      });
      return 7;
    }, () -> readCall("r-complete")); }
    void foreign() {
      assertArrayEquals(new int[] {0x84c3, -11, -13, 8, 1, 7, 9, 2, 3, 4},
          Arrays.stream(TextureUploadState.Item.values()).mapToInt(texture::get).toArray());
      assertArrayEquals(new int[] {-17, -19, -23, -29, 0x0206},
          Arrays.stream(ShadowRasterState.Binding.values()).mapToInt(bindings::get).toArray());
      assertArrayEquals(new int[] {2, 3, 37, 41}, viewport); assertArrayEquals(new float[] {1, 2, 3, 4}, attribute);
      assertArrayEquals(new double[] {.2, .8}, range); assertEquals(.25, clear);
      for (var item : ShadowRasterState.Flag.values()) assertEquals(item != ShadowRasterState.Flag.DEPTH_TEST
          && item != ShadowRasterState.Flag.DEPTH_MASK, flags.get(item).booleanValue());
    }
    public void close() { before = after = name -> {}; work.close(); }
  }

  @Test
  void healthyAllocationAndRasterRestoreIndependentForeignStateBeforeAcceptance() {
    try (var f = new Fixture()) {
      f.allocate(512); f.foreign(); assertEquals(512, f.work.resources.resolution); assertEquals(3, f.live.size());
      assertEquals(1 << 20, DirectionalShadow.bytes(f.work.resources.resolution));
      f.reset(); assertEquals(7, f.render()); f.foreign(); assertEquals(1, f.draws); assertEquals(1, f.clears);
      assertEquals(R_RESTORES, f.calls.stream().filter(s -> s.startsWith("r-restore-")).toList());
      f.reset(); f.allocate(512); assertTrue(f.calls.isEmpty());
    }
  }

  @Test
  void invalidSizesDeviceLimitsAndEverySnapshotFaultLeaveThePreviousAllocationUntouched() {
    var points = new ArrayList<String>(List.of("maximum", "a-read-read", "a-read-draw"));
    for (var item : TextureUploadState.Item.values()) points.add("a-read-" + item);
    for (var item : ShadowRasterState.Binding.values()) points.add("r-read-" + item);
    for (var item : ShadowRasterState.Flag.values()) points.add("r-read-" + item);
    points.addAll(List.of("r-read-viewport", "r-read-attribute", "r-read-clearDepth", "r-read-range"));
    for (var point : points) for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
      f.allocate(512); int accepted = f.work.resources.texture; var reads = new AtomicInteger(); var primary = fault(error, reads);
      f.reset(); f.before = name -> { if (name.equals(point)) raise(primary); };
      assertSame(primary, assertThrows(Throwable.class, () -> { if (point.startsWith("r-")) f.render(); else f.allocate(1024); }));
      assertEquals(accepted, f.work.resources.texture); assertEquals(512, f.work.resources.resolution);
      assertEquals(3, f.live.size()); assertTrue(f.deleted.isEmpty()); assertEquals(0, f.draws); f.foreign();
      assertFalse(f.calls.stream().anyMatch(s -> s.contains("normal-") || s.contains("restore-") || s.startsWith("a-create")));
      assertEquals(0, reads.get());
    }
    try (var f = new Fixture()) {
      f.allocate(512); f.reset(); int accepted = f.work.resources.texture;
      for (int size : new int[] {-1, 0, 256, 513, 4096}) assertThrows(IllegalArgumentException.class, () -> f.allocate(size));
      assertTrue(f.calls.isEmpty()); f.maximum = 512;
      assertThrows(IllegalStateException.class, () -> f.allocate(1024));
      assertEquals(accepted, f.work.resources.texture); assertTrue(f.deleted.isEmpty()); f.foreign();
    }
  }

  @Test
  void everyAllocationRestoreFaultAttemptsAllTwelveTailsAndRetiresTheUnpublishedCandidate() {
    for (var point : A_RESTORES) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
        var reads = new AtomicInteger(); var primary = fault(error, reads);
        Consumer<String> hook = name -> { if (name.equals(point)) raise(primary); };
        if (after) f.after = hook; else f.before = hook;
        assertSame(primary, assertThrows(Throwable.class, () -> f.allocate(512)));
        assertEquals(A_RESTORES, f.calls.stream().filter(s -> s.startsWith("a-restore-")).toList());
        assertEquals(0, f.work.resources.resolution); assertEquals(0, f.work.resources.texture);
        assertEquals(3, f.deleted.size()); assertTrue(f.live.isEmpty()); assertFalse(f.calls.contains("a-complete"));
        assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void everyRasterRestoreFaultAttemptsAllFifteenTailsAndRejectsThePassResult() {
    for (var point : R_RESTORES) for (boolean after : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
        f.allocate(512); f.reset(); var reads = new AtomicInteger(); var primary = fault(error, reads);
        Consumer<String> hook = name -> { if (name.equals(point)) raise(primary); };
        if (after) f.after = hook; else f.before = hook;
        assertSame(primary, assertThrows(Throwable.class, f::render));
        assertEquals(R_RESTORES, f.calls.stream().filter(s -> s.startsWith("r-restore-")).toList());
        assertEquals(1, f.draws); assertFalse(f.calls.contains("r-complete"));
        assertEquals(512, f.work.resources.resolution); assertEquals(3, f.live.size());
        assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length);
      }
  }

  @Test
  void bodyAndFirstRestorePrimariesSurviveSharedUnreadableRestoreAndRetirementFailures() {
    for (boolean raster : new boolean[] {false, true}) for (boolean shared : new boolean[] {false, true})
      for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
        if (raster) f.allocate(512); f.reset(); var reads = new AtomicInteger(); var primary = fault(error, reads);
        var cleanup = shared ? primary : fault(!error, reads);
        f.before = name -> { if (name.equals(raster ? "r-draw" : "a-storage")) raise(primary); };
        f.after = name -> { if (name.contains("restore-") || name.startsWith("delete-")) raise(cleanup); };
        assertSame(primary, assertThrows(Throwable.class, () -> { if (raster) f.render(); else f.allocate(512); }));
        assertEquals(raster ? R_RESTORES : A_RESTORES,
            f.calls.stream().filter(s -> s.startsWith(raster ? "r-restore-" : "a-restore-")).toList());
        if (!raster) { assertEquals(2, f.deleted.size()); assertTrue(f.live.isEmpty()); assertEquals(0, f.work.resources.resolution); }
        assertEquals(0, reads.get()); assertEquals(0, primary.getSuppressed().length); assertEquals(0, cleanup.getSuppressed().length);
      }
  }

  @Test
  void normalizationAndCompletionFaultsRestoreBeforeCandidateCleanupAndPermitExplicitRetry() {
    var points = new ArrayList<String>(List.of("a-storage", "a-complete"));
    for (var item : TextureUploadState.Item.values()) if (item != TextureUploadState.Item.BINDING) points.add("a-normal-" + item);
    for (boolean raster : new boolean[] {false, true}) {
      var selected = raster ? List.of("r-normal-READ_FRAMEBUFFER", "r-normal-DEPTH_MASK", "r-clear", "r-draw", "r-complete") : points;
      for (var point : selected) for (boolean error : new boolean[] {false, true}) try (var f = new Fixture()) {
        if (raster) f.allocate(512); f.reset(); var reads = new AtomicInteger(); var primary = fault(error, reads);
        f.after = name -> { if (name.equals(point)) raise(primary); };
        assertSame(primary, assertThrows(Throwable.class, () -> { if (raster) f.render(); else f.allocate(512); }));
        f.foreign(); assertEquals(0, reads.get()); f.reset();
        if (!raster) { assertTrue(f.live.isEmpty()); f.allocate(512); f.reset(); }
        assertEquals(7, f.render()); assertEquals(512, f.work.resources.resolution); f.foreign();
      }
    }
  }

  @Test
  void closureDuringCreationWorkOrRestorationRetiresOrphansAndCannotAcceptLateResults() {
    for (var point : List.of("a-create-TEXTURE", "a-create-FRAMEBUFFER", "a-storage", "a-create-PROGRAM",
        "a-restore-read", "a-restore-BINDING", "a-complete", "r-draw", "r-restore-PROGRAM", "r-complete"))
      try (var f = new Fixture()) {
        boolean raster = point.startsWith("r-"); if (raster) f.allocate(512); f.reset();
        f.after = name -> { if (name.equals(point)) f.work.close(); };
        assertThrows(RuntimeException.class, () -> { if (raster) f.render(); else f.allocate(512); });
        assertEquals(0, f.work.resources.resolution); assertEquals(0, f.work.resources.texture); assertTrue(f.live.isEmpty());
        int deleted = f.deleted.size(); f.work.close(); assertEquals(deleted, f.deleted.size());
        int calls = f.calls.size(); assertThrows(IllegalStateException.class, () -> f.allocate(512));
        assertThrows(IllegalStateException.class, f::render); assertEquals(calls, f.calls.size());
      }
  }

  @Test
  void recursiveWorkRejectsBeforeAnySnapshotAndResizeNeverOverlapsCompleteAllocations() {
    try (var f = new Fixture()) {
      var checked = new AtomicInteger();
      f.before = name -> {
        if (!name.equals("a-storage") && !name.equals("r-draw")) return;
        int calls = f.calls.size(); assertThrows(IllegalStateException.class, () -> f.allocate(512));
        assertThrows(IllegalStateException.class, f::render); assertEquals(calls, f.calls.size()); checked.incrementAndGet();
      };
      f.allocate(512); assertEquals(7, f.render()); f.reset(); f.before = name -> {
        if (name.equals("a-create-TEXTURE")) { assertTrue(f.live.isEmpty()); assertEquals(0, f.work.resources.resolution); checked.incrementAndGet(); }
      };
      f.allocate(1024); assertEquals(1024, f.work.resources.resolution); assertEquals(3, f.live.size());
      assertEquals(3, checked.get());
    }
  }

  @Test
  void rasterSnapshotsOwnArraysAndDoNotExposeThemToRestorationCallbacks() {
    try (var f = new Fixture()) {
      var state = ShadowRasterState.capture(f);
      Arrays.fill(f.viewport, 999); Arrays.fill(f.attribute, 999); Arrays.fill(f.range, 999);
      f.mutateArguments = true;
      for (int pass = 0; pass < 2; pass++) { f.rRestore = false; state.run(() -> {}, 81, 512, () -> 3); f.foreign(); }
    }
  }

  @Test
  void zeroHandlesAndFailedPredecessorRetirementCannotPublishOrStartAReplacement() {
    for (var zero : List.of(RenderRetirement.Kind.TEXTURE, RenderRetirement.Kind.FRAMEBUFFER, RenderRetirement.Kind.PROGRAM))
      try (var f = new Fixture()) {
        var backend = new ShadowMapWork.Allocation() {
          public int texture() { return zero == RenderRetirement.Kind.TEXTURE ? 0 : f.texture(); }
          public int framebuffer() { return zero == RenderRetirement.Kind.FRAMEBUFFER ? 0 : f.framebuffer(); }
          public void storage(int texture, int framebuffer, int size) { f.storage(texture, framebuffer, size); }
          public int program() { return zero == RenderRetirement.Kind.PROGRAM ? 0 : f.program(); }
          public void complete() { f.complete(); }
        };
        assertThrows(IllegalStateException.class,
            () -> f.work.allocate(512, () -> 2048, () -> ShadowAllocationState.capture(f), backend));
        assertEquals(0, f.work.resources.resolution); assertTrue(f.live.isEmpty()); f.foreign();
        assertEquals(A_RESTORES, f.calls.stream().filter(s -> s.startsWith("a-restore-")).toList());
      }
    for (var kind : List.of(RenderRetirement.Kind.TEXTURE, RenderRetirement.Kind.FRAMEBUFFER, RenderRetirement.Kind.PROGRAM))
      try (var f = new Fixture()) {
        f.allocate(512); f.reset(); var primary = new AssertionError("retirement before effect");
        f.before = name -> { if (name.equals("delete-" + kind)) throw primary; };
        assertSame(primary, assertThrows(AssertionError.class, () -> f.allocate(1024)));
        assertEquals(3, f.deleted.size()); assertEquals(1, f.live.size());
        assertEquals(0, f.work.resources.resolution); f.foreign();
        assertFalse(f.calls.stream().anyMatch(s -> s.startsWith("a-create-") || s.startsWith("a-normal-")));
        f.reset(); f.work.close(); assertTrue(f.deleted.isEmpty()); // Ambiguous physical residue is not replayed.
      }
  }

  @Test
  void retiredOwnedBindingsBecomeZeroWithoutRecreatingOrBindingReusedNames() {
    try (var f = new Fixture()) {
      f.allocate(512); int oldTexture = f.work.resources.texture, oldFramebuffer = f.work.resources.framebuffer;
      f.texture.put(TextureUploadState.Item.BINDING, oldTexture);
      f.bindings.put(ShadowRasterState.Binding.READ_FRAMEBUFFER, oldFramebuffer);
      f.bindings.put(ShadowRasterState.Binding.DRAW_FRAMEBUFFER, -19); f.reset();
      f.allocate(1024); assertEquals(0, f.texture.get(TextureUploadState.Item.BINDING).intValue());
      assertEquals(0, f.bindings.get(ShadowRasterState.Binding.READ_FRAMEBUFFER).intValue());
      assertEquals(-19, f.bindings.get(ShadowRasterState.Binding.DRAW_FRAMEBUFFER).intValue());
      assertEquals(1024, f.work.resources.resolution); assertEquals(3, f.live.size());
    }
  }
}
