package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

/** Deferred texture cleanup/failure ownership against an independent CPU memory backend. */
class VolumeTextureCleanupTest {
  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, GridExtent> shapes = new HashMap<>();
    final Map<Integer, Throwable> deletionFaults = new HashMap<>();
    final List<Integer> deletes = new ArrayList<>();
    int next, writes, copies, failWrite = -1, failCopy = -1;
    Throwable transferFault;
    boolean deleteAfter = true;
    IntConsumer onDelete;
    public int allocate(int side) { return allocate(GridExtent.cube(side)); }
    public int allocate(GridExtent shape) {
      int id = ++next; textures.put(id, new float[Math.toIntExact(shape.cells())]); shapes.put(id, shape); return id;
    }
    public boolean supportsCopies() { return true; }
    public void write(int texture, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      if (++writes == failWrite) fail(transferFault);
      assertEquals(w * h * d, values.remaining()); var shape = shapes.get(texture); var data = textures.get(texture);
      for (int dz = 0; dz < d; dz++) for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++)
        data[(((z + dz) * shape.height() + y + dy) * shape.width()) + x + dx] = values.get();
    }
    public void copy(int src, int sx, int sy, int sz, int dst, int dx, int dy, int dz, int w, int h) {
      if (++copies == failCopy) fail(transferFault);
      var from = shapes.get(src); var to = shapes.get(dst); assertNotEquals(src, dst);
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
        textures.get(dst)[((dz * to.height() + dy + y) * to.width()) + dx + x] =
            textures.get(src)[((sz * from.height() + sy + y) * from.width()) + sx + x];
    }
    public void delete(int id) {
      deletes.add(id); var fault = deletionFaults.get(id);
      if (!deleteAfter && fault != null) fail(fault);
      assertNotNull(textures.remove(id), "Duplicate deletion"); shapes.remove(id);
      var callback = onDelete; if (callback != null) callback.accept(id);
      if (fault != null) fail(fault);
    }
  }
  private static void fail(Throwable fault) {
    if (fault instanceof RuntimeException runtime) throw runtime;
    throw (Error) fault;
  }
  private static Throwable fault(boolean error, AtomicInteger reads) {
    return error ? new AssertionError() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    } : new IllegalStateException() {
      public String getMessage() { reads.incrementAndGet(); throw new AssertionError("message reader"); }
      public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); }
    };
  }
  private static float[] values(int side, int base) {
    var result = new float[side * side * side];
    for (int z = 0; z < side; z++) for (int y = 0; y < side; y++) for (int x = 0; x < side; x++)
      result[(z * side + y) * side + x] = base + x + 10 * y + 100 * z + .25f;
    return result;
  }
  private static VolumeData data(int side, int base) { return new VolumeData(side, values(side, base)); }
  private static void noCandidate(VolumeTextureUpload u) {
    assertFalse(u.statistics().pending()); assertEquals(0, u.statistics().pendingBytes()); assertEquals(0, u.statistics().remainingBytes());
  }
  private static void dispose(VolumeTextureUpload u, Memory m) {
    m.deletionFaults.clear(); m.onDelete = null; u.close(); m.textures.clear(); m.shapes.clear();
  }

  @Test
  void hostWriteFailureRetainsPrimaryCountersAndResidentWhenCandidateDeletionAlsoFails() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) {
      var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger();
      var primary = fault(error, reads); var cleanup = fault(!error, reads);
      try {
        u.stage(data(2, 0), 2L << 20); u.advance(32); var resident = u.resident();
        u.stage(data(65, 1000), 2L << 20); m.failWrite = m.writes + 2; m.transferFault = primary;
        m.deletionFaults.put(2, cleanup); m.deleteAfter = after;
        assertSame(primary, assertThrows(Throwable.class, () -> u.advance(100000)));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed()); assertEquals(0, cleanup.getSuppressed().length);
        assertSame(resident, u.resident()); noCandidate(u); assertEquals(1, u.statistics().version());
        assertEquals(50700, u.statistics().lastBytes()); assertEquals(0, u.statistics().lastCopiedBytes());
        assertEquals(50732, u.statistics().uploadedBytes()); assertArrayEquals(values(2, 0), m.textures.get(resident.texture()));
        assertEquals(List.of(2), m.deletes); assertEquals(after ? Set.of(1) : Set.of(1, 2), m.textures.keySet()); assertEquals(0, reads.get());
      } finally { dispose(u, m); }
    }
  }

  @Test
  void gpuCopyFailureRetainsSuccessfulCopyCountersAndTheOriginalCause() {
    for (boolean error : new boolean[] {false, true}) {
      var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger();
      var primary = fault(error, reads); var cleanup = fault(!error, reads);
      try {
        var region = new VolumeTextureUpload.Region("field", 0, 0, 0);
        u.stage(data(3, 0), 1000, region); u.advance(1000); var resident = u.resident();
        u.stage(data(3, 0), 1000, region); m.failCopy = 2; m.transferFault = primary; m.deletionFaults.put(2, cleanup);
        assertSame(primary, assertThrows(Throwable.class, () -> u.advance(1000)));
        assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed()); assertSame(resident, u.resident()); noCandidate(u);
        assertEquals(36, u.statistics().lastBytes()); assertEquals(36, u.statistics().lastCopiedBytes());
        assertEquals(36, u.statistics().copiedBytes()); assertEquals(144, u.statistics().uploadedBytes());
        assertArrayEquals(values(3, 0), m.textures.get(1)); assertEquals(List.of(2), m.deletes); assertEquals(0, reads.get());
      } finally { dispose(u, m); }
    }
  }

  @Test
  void identicalTransferAndCleanupFailuresNeverSelfSuppress() {
    var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger(); var primary = fault(true, reads);
    try {
      u.stage(data(2, 0), 1000); u.advance(32); var resident = u.resident(); u.stage(data(2, 10), 1000);
      m.failWrite = m.writes + 1; m.transferFault = primary; m.deletionFaults.put(2, primary);
      assertSame(primary, assertThrows(Throwable.class, () -> u.advance(32)));
      assertEquals(0, primary.getSuppressed().length); assertEquals(0, reads.get()); assertSame(resident, u.resident()); noCandidate(u);
    } finally { dispose(u, m); }
  }

  @Test
  void clearAndCloseAttemptBothCapturedHandlesAndDetachBeforeFailure() {
    for (boolean close : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true})
      for (boolean same : new boolean[] {false, true}) {
        var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger();
        var primary = fault(false, reads); var secondary = same ? primary : fault(true, reads);
        try {
          u.stage(data(2, 0), 1000); u.advance(32); u.stage(data(3, 10), 1000); u.advance(4);
          m.deletionFaults.put(2, primary); m.deletionFaults.put(1, secondary); m.deleteAfter = after;
          assertSame(primary, assertThrows(Throwable.class, () -> { if (close) u.close(); else u.clear(); }));
          assertEquals(List.of(2, 1), m.deletes); assertNull(u.resident()); noCandidate(u);
          assertEquals(0, u.statistics().activeBytes()); assertEquals(close ? 0 : VolumeTextureUpload.STAGING_BYTES, u.statistics().cpuBytes());
          assertEquals(0, u.statistics().lastBytes()); assertEquals(0, u.statistics().lastCopiedBytes());
          assertEquals(same ? 0 : 1, primary.getSuppressed().length); if (!same) assertSame(secondary, primary.getSuppressed()[0]);
          if (close) { u.close(); assertThrows(IllegalStateException.class, () -> u.stage(data(2, 0), 1000)); }
          else u.clear();
          assertEquals(List.of(2, 1), m.deletes); assertEquals(after ? Set.of() : Set.of(1, 2), m.textures.keySet()); assertEquals(0, reads.get());
        } finally { dispose(u, m); }
      }
  }

  @Test
  void clearCannotDeleteReplacementWorkCreatedByADeletionCallback() {
    var m = new Memory(); var u = new VolumeTextureUpload(m); var once = new AtomicBoolean();
    try {
      u.stage(data(2, 0), 1000); u.advance(32); u.stage(data(3, 10), 1000);
      m.onDelete = texture -> {
        if (once.compareAndSet(false, true)) { assertNull(u.resident()); noCandidate(u); assertTrue(u.stage(data(2, 1000), 1000)); }
      };
      u.clear(); assertEquals(List.of(2, 1), m.deletes); assertTrue(u.statistics().pending()); assertNull(u.resident());
      u.advance(32); assertEquals(3, u.resident().texture()); assertArrayEquals(values(2, 1000), m.textures.get(3));
      assertEquals(Set.of(3), m.textures.keySet()); assertEquals(2, u.statistics().version());
    } finally { dispose(u, m); }
  }

  @Test
  void retirementFailureAfterCopiesKeepsCommittedTextureAndCounters() {
    for (boolean error : new boolean[] {false, true}) for (boolean after : new boolean[] {false, true}) {
      var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger(); var failure = fault(error, reads);
      try {
        var region = new VolumeTextureUpload.Region("field", 0, 0, 0);
        u.stage(data(3, 0), 1000, region); u.advance(108); u.stage(data(3, 0), 1000, region);
        m.deletionFaults.put(1, failure); m.deleteAfter = after;
        assertSame(failure, assertThrows(Throwable.class, () -> u.advance(108)));
        assertEquals(2, u.resident().texture()); assertEquals(2, u.statistics().version()); noCandidate(u);
        assertEquals(108, u.statistics().lastBytes()); assertEquals(108, u.statistics().lastCopiedBytes());
        assertEquals(216, u.statistics().uploadedBytes()); assertEquals(108, u.statistics().copiedBytes());
        assertArrayEquals(values(3, 0), m.textures.get(2)); assertEquals(List.of(1), m.deletes);
        assertEquals(after ? Set.of(2) : Set.of(1, 2), m.textures.keySet()); assertEquals(0, reads.get());
      } finally { dispose(u, m); }
    }
  }

  @Test
  void failedRetirementCannotCancelASuccessorStagedByItsBackendCallback() {
    for (boolean error : new boolean[] {false, true}) {
      var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger(); var failure = fault(error, reads);
      try {
        u.stage(data(2, 0), 1000); u.advance(32); u.stage(data(3, 1000), 1000);
        m.deletionFaults.put(1, failure);
        m.onDelete = texture -> { if (texture == 1) assertTrue(u.stage(data(4, 2000), 1000)); };
        assertSame(failure, assertThrows(Throwable.class, () -> u.advance(108)));
        assertEquals(2, u.resident().texture()); assertEquals(2, u.statistics().version());
        assertTrue(u.statistics().pending()); assertEquals(256, u.statistics().pendingBytes());
        assertArrayEquals(values(3, 1000), m.textures.get(2)); assertEquals(List.of(1), m.deletes);
        assertEquals(Set.of(2, 3), m.textures.keySet()); u.advance(256);
        assertEquals(3, u.resident().texture()); assertEquals(3, u.statistics().version()); noCandidate(u);
        assertArrayEquals(values(4, 2000), m.textures.get(3)); assertEquals(List.of(1, 2), m.deletes); assertEquals(0, reads.get());
      } finally { dispose(u, m); }
    }
  }

  @Test
  void failedCandidateRetirementStopsReplacementAllocationButAllowsAnExplicitRetry() {
    var m = new Memory(); var u = new VolumeTextureUpload(m); var reads = new AtomicInteger(); var failure = fault(true, reads);
    try {
      u.stage(data(2, 0), 1000); u.advance(32); var resident = u.resident(); u.stage(data(3, 10), 1000); u.advance(4);
      m.deletionFaults.put(2, failure);
      assertSame(failure, assertThrows(Throwable.class, () -> u.stage(data(4, 1000), 1000)));
      assertEquals(2, m.next); assertSame(resident, u.resident()); noCandidate(u); assertArrayEquals(values(2, 0), m.textures.get(1));
      m.deletionFaults.clear(); assertTrue(u.stage(data(4, 1000), 1000)); u.advance(1000);
      assertEquals(3, u.resident().texture()); assertArrayEquals(values(4, 1000), m.textures.get(3)); assertEquals(0, reads.get());
    } finally { dispose(u, m); }
  }
}
