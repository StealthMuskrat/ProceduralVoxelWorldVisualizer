package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VolumeCoarseUploadTest {
  private static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> data = new HashMap<>();
    final Map<Integer, Integer> sizes = new HashMap<>();
    int next, copies;
    boolean copy = true;

    public int allocate(int n) {
      int id = ++next;
      data.put(id, new float[n * n * n]);
      sizes.put(id, n);
      return id;
    }

    public void delete(int id) {
      assertNotNull(data.remove(id));
      sizes.remove(id);
    }

    public boolean supportsCopies() {
      return copy;
    }

    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      int n = sizes.get(id);
      for (int k = 0; k < d; k++)
        for (int j = 0; j < h; j++)
          for (int i = 0; i < w; i++)
            data.get(id)[((z + k) * n + y + j) * n + x + i] = values.get();
    }

    public void copy(
        int src, int sx, int sy, int sz, int dst, int dx, int dy, int dz, int w, int h) {
      copies++;
      int n = sizes.get(src), m = sizes.get(dst);
      for (int j = 0; j < h; j++)
        for (int i = 0; i < w; i++)
          data.get(dst)[(dz * m + dy + j) * m + dx + i] =
              data.get(src)[(sz * n + sy + j) * n + sx + i];
    }
  }

  private VolumeData grid(int n, int step, int x, int y, int z) {
    return VolumeData.sampleGrid(
        n, step, x, y, z, (a, b, c) -> a + 10 * b + 100 * c, new CancellationToken());
  }

  private void finish(VolumeTextureUpload upload) {
    while (upload.statistics().pending()) assertTrue(upload.advance(37) <= 37);
  }

  @Test
  void compatibleCoarseCentersCopyHalfTheGridInTexelCoordinates() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      assertTrue(
          upload.stage(
              grid(4, 2, -8, -8, -8), 4096, new VolumeTextureUpload.Region("a", -8, -8, -8, 2)));
      finish(upload);
      var data = grid(4, 2, -4, -8, -8);
      assertTrue(upload.stage(data, 4096, new VolumeTextureUpload.Region("a", -4, -8, -8, 2)));
      finish(upload);
      assertArrayEquals(data.copySamples(), memory.data.get(upload.resident().texture()));
      assertEquals(128, upload.statistics().copiedBytes());
      assertEquals(8, upload.resident().extent());
      assertEquals(4, upload.resident().size());
    }
  }

  @Test
  void unequalSpacingAndMisalignedCentersAlwaysUseFreshHostValues() {
    for (int[] next : new int[][] {{4, 2, -7, -8, -8}, {4, 1, -8, -8, -8}, {2, 4, -8, -8, -8}}) {
      var memory = new Memory();
      try (var upload = new VolumeTextureUpload(memory)) {
        upload.stage(
            grid(4, 2, -8, -8, -8), 4096, new VolumeTextureUpload.Region("a", -8, -8, -8, 2));
        finish(upload);
        var data = grid(next[0], next[1], next[2], next[3], next[4]);
        upload.stage(
            data, 4096, new VolumeTextureUpload.Region("a", next[2], next[3], next[4], next[1]));
        finish(upload);
        assertEquals(0, memory.copies);
        assertArrayEquals(data.copySamples(), memory.data.get(upload.resident().texture()));
      }
    }
  }

  @Test
  void budgetCountsSampleStorageAndPublishesSpacingOnlyWhenComplete() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      var coarse = grid(4, 2, 0, 0, 0);
      upload.stage(coarse, 256);
      finish(upload);
      var resident = upload.resident();
      var fine = grid(8, 1, 0, 0, 0);
      assertFalse(upload.stage(fine, 256 + 2048 - 1));
      assertSame(resident, upload.resident());
      assertTrue(upload.stage(fine, 256 + 2048));
      upload.advance(4);
      assertSame(resident, upload.resident());
      assertEquals(2, upload.resident().sampleStep());
      upload.cancel();
      assertSame(resident, upload.resident());
      upload.stage(fine, 256 + 2048);
      finish(upload);
      assertEquals(1, upload.resident().sampleStep());
      assertEquals(8, upload.resident().extent());
    }
  }

  @Test
  void mismatchedRegionAndOverflowRejectWithoutReplacingTheResident() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      var data = grid(4, 2, 0, 0, 0);
      upload.stage(data, 4096);
      finish(upload);
      var resident = upload.resident();
      assertThrows(
          IllegalArgumentException.class,
          () -> upload.stage(data, 4096, new VolumeTextureUpload.Region("a", 0, 0, 0)));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              upload.stage(
                  data, 4096, new VolumeTextureUpload.Region("a", Integer.MAX_VALUE - 3, 0, 0, 2)));
      assertSame(resident, upload.resident());
      assertFalse(upload.statistics().pending());
    }
  }

  @Test
  void changedSourceAndCopylessBackendKeepCoarseMetadataWithHostFallback() {
    for (boolean capable : new boolean[] {false, true}) {
      var memory = new Memory();
      memory.copy = capable;
      try (var upload = new VolumeTextureUpload(memory)) {
        upload.stage(grid(4, 2, 0, 0, 0), 4096, new VolumeTextureUpload.Region("a", 0, 0, 0, 2));
        finish(upload);
        var data = grid(4, 2, 0, 0, 0);
        upload.stage(data, 4096, new VolumeTextureUpload.Region(capable ? "b" : "a", 0, 0, 0, 2));
        finish(upload);
        assertEquals(0, memory.copies);
        assertEquals(2, upload.resident().sampleStep());
        assertArrayEquals(data.copySamples(), memory.data.get(upload.resident().texture()));
      }
    }
  }
}
