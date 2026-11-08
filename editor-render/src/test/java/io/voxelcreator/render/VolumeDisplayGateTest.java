package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred presentation receipts against real texture uploads and independent retained pixels. */
class VolumeDisplayGateTest {
  private record Metadata(String source, int origin, int side, long version) {}
  private static final Metadata OLD = new Metadata("old", -7, 2, 1);
  private static final Metadata NEXT = new Metadata("next", 11, 3, 2);
  private static final Metadata NEWER = new Metadata("newer", 23, 4, 3);
  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, Integer> sides = new HashMap<>();
    final List<Integer> deletes = new ArrayList<>();
    int next;
    Throwable retirement;
    boolean releaseFirst;
    public int allocate(int side) {
      int id = ++next; textures.put(id, new float[side * side * side]); sides.put(id, side); return id;
    }
    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer pixels) {
      int side = sides.get(id); assertEquals(w * h * d, pixels.remaining());
      for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
        textures.get(id)[((z + dz) * side + y + dy) * side + x + dx] = pixels.get();
    }
    public void delete(int id) {
      deletes.add(id);
      if (id == 1 && retirement != null && !releaseFirst) raise(retirement);
      assertNotNull(textures.remove(id)); sides.remove(id);
      if (id == 1 && retirement != null) raise(retirement);
    }
  }
  private static final class Fixture implements AutoCloseable {
    final Memory memory = new Memory();
    final VolumeTextureUpload upload = new VolumeTextureUpload(memory);
    final VolumeDisplayGate gate = new VolumeDisplayGate();
    Metadata metadata = OLD;
    float[] frame;
    Fixture() { stage(data(2, 0), 1000, false); advance(32); render(); }
    boolean stage(VolumeData data, long budget, boolean defer) {
      try { if (!upload.stage(data, budget)) return false; }
      catch (RuntimeException | Error failure) { if (!upload.statistics().pending()) gate.cancel(); throw failure; }
      gate.staged(defer); return true;
    }
    int advance(int budget) {
      long before = upload.statistics().version();
      try { return upload.advance(budget); }
      finally { if (upload.statistics().version() != before) gate.committed(upload.statistics().version()); }
    }
    void render() {
      if (!gate.blocked() && !upload.statistics().pending() && upload.resident() != null)
        frame = memory.textures.get(upload.resident().texture()).clone();
    }
    boolean accept(Metadata next) {
      if (upload.statistics().pending() || upload.resident() == null
          || upload.statistics().version() != next.version || !gate.accept(next.version)) return false;
      metadata = next; return true;
    }
    void cancel() { gate.cancel(); upload.cancel(); }
    void clear() { gate.clear(); upload.clear(); }
    public void close() { memory.retirement = null; try { upload.close(); } finally { gate.clear(); memory.textures.clear(); memory.sides.clear(); } }
  }
  private static void raise(Throwable failure) {
    if (failure instanceof RuntimeException runtime) throw runtime;
    throw (Error) failure;
  }
  private static float[] values(int side, int base) {
    var result = new float[side * side * side];
    for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++)
      result[(z * side + y) * side + x] = base + x + 10 * y + 100 * z + .25f;
    return result;
  }
  private static VolumeData data(int side, int base) { return new VolumeData(side, values(side, base)); }

  @Test
  void completeTextureStillRetainsTheOldFrameUntilExactDisplayAcceptance() {
    try (var f = new Fixture()) {
      var held = f.frame; assertTrue(f.stage(data(3, 1000), 140, true)); f.advance(20); f.render();
      assertSame(held, f.frame); assertSame(OLD, f.metadata); assertFalse(f.gate.blocked());
      f.advance(88); f.render(); assertTrue(f.gate.blocked()); assertSame(held, f.frame); assertSame(OLD, f.metadata);
      assertFalse(f.accept(new Metadata("future", 11, 3, 3))); assertFalse(f.accept(OLD));
      assertTrue(f.accept(NEXT)); assertFalse(f.accept(NEXT)); f.render();
      assertArrayEquals(values(3, 1000), f.frame); assertArrayEquals(values(2, 0), held); assertSame(NEXT, f.metadata);
      assertEquals(140, f.upload.statistics().uploadedBytes()); assertEquals(List.of(1), f.memory.deletes);
    }
  }

  @Test
  void rejectedOrInvalidStagePreservesAnExistingDeferredCandidate() {
    try (var f = new Fixture()) {
      assertFalse(f.stage(data(3, 1000), 139, true)); assertEquals(1, f.memory.next); assertFalse(f.gate.blocked());
      assertTrue(f.stage(data(3, 1000), 140, true)); f.advance(4);
      assertThrows(NullPointerException.class, () -> f.stage(null, 1000, false));
      assertFalse(f.stage(data(4, 2000), 287, false)); assertEquals(2, f.memory.next);
      f.advance(104); f.render(); assertTrue(f.gate.blocked()); assertArrayEquals(values(2, 0), f.frame);
      assertTrue(f.accept(NEXT)); f.render(); assertArrayEquals(values(3, 1000), f.frame);
    }
  }

  @Test
  void cancellationKeepsTheHeldReceiptWhileExplicitReplacementRetiresOldVersions() {
    try (var f = new Fixture()) {
      f.stage(data(3, 1000), 140, true); f.advance(108); f.cancel(); f.render();
      assertTrue(f.gate.blocked()); assertArrayEquals(values(2, 0), f.frame);
      assertFalse(f.stage(data(4, 2000), 363, false)); assertTrue(f.gate.blocked());
      f.stage(data(4, 2000), 364, true); f.advance(4); assertFalse(f.gate.accept(2)); f.cancel();
      assertTrue(f.gate.blocked()); f.stage(data(4, 2000), 364, true); f.advance(256);
      assertFalse(f.accept(NEXT)); f.render(); assertArrayEquals(values(2, 0), f.frame);
      assertTrue(f.accept(NEWER)); f.render(); assertArrayEquals(values(4, 2000), f.frame); assertSame(NEWER, f.metadata);
      assertEquals(4, f.upload.resident().texture()); assertEquals(List.of(1, 3, 2), f.memory.deletes);
    }
  }

  @Test
  void failedSetupBeforeOrAfterCpuAssignmentCannotReleasePresentation() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var f = new Fixture()) {
      Throwable failure = error ? new AssertionError("setup") : new IllegalStateException("setup");
      f.stage(data(3, 1000), 140, true); f.advance(108);
      assertSame(failure, assertThrows(Throwable.class, () -> { if (after) f.metadata = NEXT; raise(failure); }));
      f.cancel(); f.render(); assertTrue(f.gate.blocked()); assertArrayEquals(values(2, 0), f.frame);
      assertEquals(2, f.upload.statistics().version()); assertEquals(2, f.upload.resident().texture());
      f.stage(data(4, 2000), 364, true); f.advance(256); assertTrue(f.accept(NEWER)); f.render();
      assertArrayEquals(values(4, 2000), f.frame); assertSame(NEWER, f.metadata); assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void retirementFailureStillEstablishesTheMatchingDeferredDisplayReceipt() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) try (var f = new Fixture()) {
      Throwable failure = error ? new AssertionError("retirement") : new IllegalStateException("retirement");
      f.stage(data(3, 1000), 140, true); f.memory.retirement = failure; f.memory.releaseFirst = after;
      assertSame(failure, assertThrows(Throwable.class, () -> f.advance(108))); f.render();
      assertTrue(f.gate.blocked()); assertSame(OLD, f.metadata); assertArrayEquals(values(2, 0), f.frame);
      assertTrue(f.accept(NEXT)); f.render(); assertArrayEquals(values(3, 1000), f.frame);
      assertEquals(after ? Set.of(2) : Set.of(1, 2), f.memory.textures.keySet()); assertEquals(List.of(1), f.memory.deletes);
    }
  }

  @Test
  void legacyCompletionAndClearOrCloseKeepTheirImmediateLifecycle() {
    var f = new Fixture();
    try {
      f.stage(data(3, 1000), 140, false); f.advance(108); f.render();
      assertFalse(f.gate.blocked()); assertArrayEquals(values(3, 1000), f.frame);
      f.stage(data(4, 2000), 364, true); f.advance(256); assertTrue(f.gate.blocked()); f.clear();
      assertFalse(f.gate.blocked()); assertNull(f.upload.resident()); assertFalse(f.accept(NEWER));
      f.stage(data(2, 0), 32, true); f.advance(32); assertTrue(f.gate.blocked()); f.close();
      assertFalse(f.gate.blocked()); assertNull(f.upload.resident()); assertTrue(f.memory.textures.isEmpty());
    } finally { f.close(); }
  }
}
