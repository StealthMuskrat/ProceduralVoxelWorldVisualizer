package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseAlgorithms.Field;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VolumeOverlapTest {
  private static final Field AFFINE = (x, y, z) -> x + 10 * y + 100 * z - 1000;

  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> data = new HashMap<>();
    final Map<Integer, BitSet> coverage = new HashMap<>();
    final Map<Integer, Integer> sides = new HashMap<>();
    int next, copies, failCopy = -1;
    long written;
    boolean capable = true;

    public int allocate(int side) {
      int id = ++next;
      data.put(id, new float[side * side * side]);
      coverage.put(id, new BitSet(side * side * side));
      sides.put(id, side);
      return id;
    }

    private void put(int id, int x, int y, int z, float value) {
      int n = sides.get(id);
      assertTrue(x >= 0 && y >= 0 && z >= 0 && x < n && y < n && z < n);
      int i = (z * n + y) * n + x;
      assertFalse(coverage.get(id).get(i), "destination written twice");
      coverage.get(id).set(i);
      data.get(id)[i] = value;
      written += 4;
    }

    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      assertEquals(w * h * d, values.remaining());
      assertTrue(values.remaining() * 4 <= 65536);
      for (int k = 0; k < d; k++)
        for (int j = 0; j < h; j++)
          for (int i = 0; i < w; i++) put(id, x + i, y + j, z + k, values.get());
    }

    public boolean supportsCopies() {
      return capable;
    }

    public void copy(
        int src, int sx, int sy, int sz, int dst, int dx, int dy, int dz, int w, int h) {
      if (copies == failCopy) throw new IllegalStateException("copy failed");
      copies++;
      assertNotEquals(src, dst);
      int n = sides.get(src);
      var pixels = data.get(src);
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
          assertTrue(coverage.get(src).get((sz * n + sy + y) * n + sx + x));
          put(dst, dx + x, dy + y, dz, pixels[(sz * n + sy + y) * n + sx + x]);
        }
    }

    public void delete(int id) {
      assertNotNull(data.remove(id), "double delete");
      coverage.remove(id);
      sides.remove(id);
    }
  }

  private VolumeData data(int n, VolumeTextureUpload.Region region, Field field) {
    return VolumeData.sample(n, region.x(), region.y(), region.z(), field);
  }

  private VolumeTextureUpload.Region region(int x, int y, int z) {
    return new VolumeTextureUpload.Region("affine", x, y, z);
  }

  private void finish(VolumeTextureUpload upload, Memory memory, Random random) {
    while (upload.statistics().pending()) {
      long before = memory.written;
      int budget = random.nextInt(70001), written = upload.advance(budget);
      assertEquals(written, memory.written - before);
      assertTrue(written <= budget && written % 4 == 0);
      assertEquals(written, upload.statistics().lastBytes());
      assertEquals(
          written, upload.statistics().lastHostBytes() + upload.statistics().lastCopiedBytes());
      var resident = upload.resident();
      if (resident != null)
        assertEquals(
            resident.size() * resident.size() * resident.size(),
            memory.coverage.get(resident.texture()).cardinality());
    }
  }

  private long overlap(int a, VolumeTextureUpload.Region r, int b, VolumeTextureUpload.Region s) {
    long result = 4;
    int[] p = {r.x(), r.y(), r.z()}, q = {s.x(), s.y(), s.z()};
    for (int axis = 0; axis < 3; axis++)
      result *=
          Math.max(
              0, Math.min((long) p[axis] + a, (long) q[axis] + b) - Math.max(p[axis], q[axis]));
    return result;
  }

  @Test
  void arbitrarySignedShiftsAndExtentChangesWriteEveryTexelOnceUnderRandomBudgets() {
    var random = new Random(42);
    for (int[] shape :
        new int[][] {
          {5, 5, 1, 0, 0},
          {5, 5, -1, 0, 0},
          {5, 5, 0, 2, 0},
          {5, 5, 0, 0, -2},
          {5, 5, 1, -2, 2},
          {5, 3, 1, 1, 1},
          {3, 5, -1, -1, -1},
          {17, 11, 4, -3, 2},
          {3, 3, 3, 0, 0}
        }) {
      var memory = new Memory();
      try (var upload = new VolumeTextureUpload(memory)) {
        var old = region(0, 0, 0);
        var next = region(shape[2], shape[3], shape[4]);
        var first = data(shape[0], old, AFFINE);
        var second = data(shape[1], next, AFFINE);
        upload.stage(first, 1L << 20, old);
        finish(upload, memory, random);
        var accepted = upload.resident();
        var prior = memory.data.get(accepted.texture()).clone();
        long beforeCopy = upload.statistics().copiedBytes(),
            beforeHost = upload.statistics().hostBytes();
        upload.stage(second, 1L << 20, next);
        finish(upload, memory, random);
        assertArrayEquals(prior, first.copySamples());
        assertArrayEquals(second.copySamples(), memory.data.get(upload.resident().texture()));
        long reused = overlap(shape[0], old, shape[1], next);
        assertEquals(reused, upload.statistics().copiedBytes() - beforeCopy);
        assertEquals(
            4L * shape[1] * shape[1] * shape[1] - reused,
            upload.statistics().hostBytes() - beforeHost);
        assertEquals(next, upload.resident().region());
        assertEquals(second.encoding(), upload.resident().encoding());
        assertFalse(memory.data.containsKey(accepted.texture()));
      }
      assertTrue(memory.data.isEmpty());
    }
  }

  @Test
  void differentSourceEncodingCapabilityAndUnidentifiedDataUseFullHostUpload() {
    for (int mode = 0; mode < 4; mode++) {
      var memory = new Memory();
      try (var upload = new VolumeTextureUpload(memory)) {
        Field field =
            mode == 1 ? (x, y, z) -> x < 3 ? x + 10 * y + 100 * z : 1e300 + x * 1e298 : AFFINE;
        var first = region(0, 0, 0);
        var second = region(2, 0, 0);
        upload.stage(data(3, first, field), 1000, first);
        finish(upload, memory, new Random(1));
        if (mode == 0) second = new VolumeTextureUpload.Region("other", 2, 0, 0);
        if (mode == 2) memory.capable = false;
        var next = data(3, second, field);
        long host = upload.statistics().hostBytes();
        upload.stage(next, 1000, mode == 3 ? null : second);
        finish(upload, memory, new Random(2));
        assertEquals(0, upload.statistics().copiedBytes());
        assertEquals(108, upload.statistics().hostBytes() - host);
        assertArrayEquals(next.copySamples(), memory.data.get(upload.resident().texture()));
      }
    }
  }

  @Test
  void aContainedWindowNeedsNoHostBytesAndOppositeDomainEndsNeverOverlap() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(5, region(0, 0, 0), AFFINE), 1000, region(0, 0, 0));
      upload.advance(1000);
      long host = upload.statistics().hostBytes();
      upload.stage(data(3, region(1, 1, 1), AFFINE), 1000, region(1, 1, 1));
      finish(upload, memory, new Random(2));
      assertEquals(host, upload.statistics().hostBytes());
      assertEquals(108, upload.statistics().copiedBytes());
      var low = region(Integer.MIN_VALUE, 0, 0);
      var high = region(Integer.MAX_VALUE - 2, 0, 0);
      upload.stage(data(3, low, AFFINE), 1000, low);
      finish(upload, memory, new Random(3));
      long copied = upload.statistics().copiedBytes();
      upload.stage(data(3, high, AFFINE), 1000, high);
      finish(upload, memory, new Random(4));
      assertEquals(copied, upload.statistics().copiedBytes());
    }
  }

  @Test
  void cancellationAndRejectedReplacementLeaveAcceptedDataAndPendingProgressIntact() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      var first = data(5, region(0, 0, 0), AFFINE);
      upload.stage(first, 1000, region(0, 0, 0));
      upload.advance(1000);
      var accepted = upload.resident();
      var prior = memory.data.get(accepted.texture()).clone();
      upload.stage(data(5, region(1, 1, 1), AFFINE), 1000, region(1, 1, 1));
      assertEquals(16, upload.advance(17));
      var progress = upload.statistics();
      assertFalse(upload.stage(data(5, region(2, 2, 2), AFFINE), 999, region(2, 2, 2)));
      assertEquals(progress, upload.statistics());
      assertSame(accepted, upload.resident());
      assertArrayEquals(prior, memory.data.get(accepted.texture()));
      upload.cancel();
      assertEquals(Set.of(accepted.texture()), memory.data.keySet());
      assertEquals(0, upload.statistics().remainingBytes());
      upload.stage(data(5, region(-1, -1, -1), AFFINE), 1000, region(-1, -1, -1));
      finish(upload, memory, new Random(9));
      assertArrayEquals(
          data(5, region(-1, -1, -1), AFFINE).copySamples(),
          memory.data.get(upload.resident().texture()));
    }
  }

  @Test
  void copyFailuresReclaimOnlyCandidateAndLegacyHostRetrySucceeds() {
    for (int failAfter : new int[] {0, 1}) {
      var memory = new Memory();
      try (var upload = new VolumeTextureUpload(memory)) {
        upload.stage(data(5, region(0, 0, 0), AFFINE), 1000, region(0, 0, 0));
        upload.advance(1000);
        var accepted = upload.resident();
        memory.failCopy = failAfter;
        var next = data(5, region(1, 1, 1), AFFINE);
        upload.stage(next, 1000, region(1, 1, 1));
        assertThrows(IllegalStateException.class, () -> upload.advance(1000));
        assertSame(accepted, upload.resident());
        assertFalse(upload.statistics().pending());
        assertEquals(failAfter * 64, upload.statistics().lastCopiedBytes());
        assertEquals(failAfter * 64, upload.statistics().lastBytes());
        assertEquals(Set.of(accepted.texture()), memory.data.keySet());
        upload.stage(next, 1000);
        finish(upload, memory, new Random(3));
        assertArrayEquals(next.copySamples(), memory.data.get(upload.resident().texture()));
      }
    }
  }

  @Test
  void regionValidationRejectsBeforeMutatingPendingGpuOwnership() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      var data = data(3, region(0, 0, 0), AFFINE);
      upload.stage(data, 1000, region(0, 0, 0));
      upload.advance(4);
      var before = upload.statistics();
      int ids = memory.next;
      assertThrows(
          IllegalArgumentException.class, () -> new VolumeTextureUpload.Region("", 0, 0, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> upload.stage(data, 1000, region(Integer.MAX_VALUE, 0, 0)));
      assertEquals(before, upload.statistics());
      assertEquals(ids, memory.next);
    }
  }

  @Test
  void boundedRectangularGatherValidatesBeforeWritesAndOwnsItsOutput() {
    var data = data(5, region(0, 0, 0), AFFINE);
    var original = data.copySamples();
    var buffer = FloatBuffer.allocate(8);
    data.copyRegion(1, 1, 1, 2, 2, 2, buffer);
    for (int z = 0; z < 2; z++)
      for (int y = 0; y < 2; y++)
        for (int x = 0; x < 2; x++)
          assertEquals(
              original[((z + 1) * 5 + y + 1) * 5 + x + 1], buffer.get((z * 2 + y) * 2 + x));
    buffer.put(0, 999);
    assertArrayEquals(original, data.copySamples());
    var shortBuffer = FloatBuffer.allocate(7);
    assertThrows(
        BufferOverflowException.class, () -> data.copyRegion(0, 0, 0, 2, 2, 2, shortBuffer));
    assertEquals(0, shortBuffer.position());
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> data.copyRegion(Integer.MAX_VALUE, 0, 0, 2, 2, 2, shortBuffer));
    assertEquals(0, shortBuffer.position());
  }

  @Test
  void legacyRecordConstructorsAndBackendsRetainTheirOriginalSemantics() {
    var data = data(3, region(0, 0, 0), AFFINE);
    var resident = new VolumeTextureUpload.Resident(1, 3, data.encoding());
    assertNull(resident.region());
    var stats = new VolumeTextureUpload.Statistics(108, 0, 0, 65536, 108, 16, 1, false);
    assertEquals(108, stats.hostBytes());
    assertEquals(16, stats.lastHostBytes());
    assertEquals(0, stats.copiedBytes());
    var backend =
        new VolumeTextureUpload.Backend() {
          public int allocate(int side) {
            return 1;
          }

          public void write(int t, int x, int y, int z, int w, int h, int d, FloatBuffer v) {}

          public void delete(int t) {}
        };
    assertFalse(backend.supportsCopies());
  }
}
