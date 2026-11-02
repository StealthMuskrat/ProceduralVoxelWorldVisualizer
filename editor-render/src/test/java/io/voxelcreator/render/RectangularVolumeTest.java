package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RectangularVolumeTest {
  private static final GridExtent SHAPE = new GridExtent(3, 5, 7);

  private static double affine(double x, double y, double z) {
    return x + 10 * y + 100 * z;
  }

  private static VolumeData data(GridExtent shape, double origin) {
    return VolumeData.sample(shape, origin, origin, origin, RectangularVolumeTest::affine);
  }

  @Test
  void samplesEveryCellCenterInOpenGlOrderAndClampsEachAxisIndependently() {
    var count = new AtomicInteger();
    var data =
        VolumeData.sample(
            SHAPE,
            0,
            0,
            0,
            (x, y, z) -> {
              count.incrementAndGet();
              return affine(x, y, z);
            });
    assertEquals(105, count.get());
    assertEquals(SHAPE, data.shape());
    for (int z = 0; z < 7; z++)
      for (int y = 0; y < 5; y++)
        for (int x = 0; x < 3; x++) {
          assertEquals(affine(x + .5, y + .5, z + .5), data.value(x, y, z));
          assertEquals(
              (float) affine(x + .5, y + .5, z + .5), data.copySamples()[(z * 5 + y) * 3 + x]);
        }
    assertEquals(affine(1.25, 2.75, 3.125), data.interpolate(1.25, 2.75, 3.125), 1e-12);
    assertEquals(affine(2.5, 4.5, 6.5), data.interpolate(100, 100, 100));
    assertThrows(IndexOutOfBoundsException.class, () -> data.value(0, 5, 0));
    assertThrows(IndexOutOfBoundsException.class, () -> data.value(0, 0, 7));
  }

  @Test
  void constructorsOwnTheirTexelsAndValidateAllDimensionsAndCount() {
    var values = new float[105];
    values[104] = 17;
    var data = new VolumeData(SHAPE, values);
    values[104] = 99;
    assertEquals(17, data.value(2, 4, 6));
    var copy = data.copySamples();
    copy[104] = 123;
    assertEquals(17, data.value(2, 4, 6));
    assertThrows(IllegalArgumentException.class, () -> new VolumeData(SHAPE, new float[104]));
    for (var shape :
        List.of(new GridExtent(1, 5, 7), new GridExtent(3, 1, 7), new GridExtent(3, 5, 1)))
      assertThrows(
          IllegalArgumentException.class, () -> new VolumeData(shape, new float[shape.cells()]));
  }

  @Test
  void coarseRectanglesExposeIndependentPhysicalSpansAndCubeEntryPointsMatch() {
    var data =
        VolumeData.sampleGrid(
            SHAPE, 2, 0, 0, 0, RectangularVolumeTest::affine, new CancellationToken());
    assertEquals(6, data.extentX());
    assertEquals(10, data.extentY());
    assertEquals(14, data.extentZ());
    assertEquals(affine(1, 1, 1), data.value(0, 0, 0));
    assertEquals(affine(5, 9, 13), data.value(2, 4, 6));
    assertEquals(affine(2, 3, 4), data.interpolate(2, 3, 4), 1e-12);
    var a = VolumeData.sample(5, -1.25, 2.5, -7.75, RectangularVolumeTest::affine);
    var b = VolumeData.sample(GridExtent.cube(5), -1.25, 2.5, -7.75, RectangularVolumeTest::affine);
    assertEquals(a.encoding(), b.encoding());
    assertArrayEquals(a.copySamples(), b.copySamples());
  }

  static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, GridExtent> shapes = new HashMap<>();
    final Map<Integer, float[]> values = new HashMap<>();
    int next;

    public int allocate(int side) {
      return allocate(GridExtent.cube(side));
    }

    public int allocate(GridExtent shape) {
      int id = ++next;
      shapes.put(id, shape);
      values.put(id, new float[shape.cells()]);
      return id;
    }

    int at(int id, int x, int y, int z) {
      var s = shapes.get(id);
      return (z * s.height() + y) * s.width() + x;
    }

    public void write(int id, int x, int y, int z, int w, int h, int d, FloatBuffer source) {
      assertEquals(w * h * d, source.remaining());
      for (int dz = 0; dz < d; dz++)
        for (int dy = 0; dy < h; dy++)
          for (int dx = 0; dx < w; dx++)
            values.get(id)[at(id, x + dx, y + dy, z + dz)] = source.get();
    }

    public boolean supportsCopies() {
      return true;
    }

    public void copy(
        int from, int sx, int sy, int sz, int to, int dx, int dy, int dz, int w, int h) {
      for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++)
          values.get(to)[at(to, dx + x, dy + y, dz)] =
              values.get(from)[at(from, sx + x, sy + y, sz)];
    }

    public void delete(int id) {
      assertNotNull(values.remove(id));
      assertNotNull(shapes.remove(id));
    }
  }

  @Test
  void partialRowsRespectArbitraryByteBudgetsAndPublishOnlyTheCompleteBox() {
    for (int budget : new int[] {4, 7, 28, 65539}) {
      var backend = new Memory();
      var data = data(SHAPE, 0);
      try (var upload = new VolumeTextureUpload(backend)) {
        assertTrue(upload.stage(data, 420));
        assertNull(upload.resident());
        assertEquals(0, upload.advance(3));
        int written = 0;
        while (upload.statistics().pending()) {
          int bytes = upload.advance(budget);
          assertTrue(bytes <= budget && bytes % 4 == 0);
          written += bytes;
          if (upload.statistics().pending()) assertNull(upload.resident());
        }
        assertEquals(420, written);
        assertEquals(420, upload.resident().bytes());
        assertEquals(SHAPE, upload.resident().shape());
        assertArrayEquals(data.copySamples(), backend.values.get(upload.resident().texture()));
      }
      assertTrue(backend.values.isEmpty());
    }
  }

  @Test
  void exactGpuReservationAndCancellationKeepThePreviousBox() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(SHAPE, 0), 420);
      upload.advance(1000);
      var active = upload.resident();
      var next = data(new GridExtent(4, 3, 6), 1);
      assertFalse(upload.stage(next, 707));
      assertSame(active, upload.resident());
      assertEquals(1, memory.next);
      assertTrue(upload.stage(next, 708));
      upload.advance(20);
      assertEquals(288, upload.statistics().pendingBytes());
      assertEquals(268, upload.statistics().remainingBytes());
      upload.cancel();
      assertSame(active, upload.resident());
      assertEquals(1, memory.values.size());
    }
  }

  @Test
  void overlapUsesOldAndNewDimensionsOnAllAxesAndWritesEachDestinationOnce() {
    for (int shift : new int[] {-1, 1}) {
      var memory = new Memory();
      var next = data(new GridExtent(4, 3, 6), shift);
      try (var upload = new VolumeTextureUpload(memory)) {
        upload.stage(data(SHAPE, 0), 420, new VolumeTextureUpload.Region("affine", 0, 0, 0));
        upload.advance(1000);
        assertTrue(
            upload.stage(next, 708, new VolumeTextureUpload.Region("affine", shift, shift, shift)));
        while (upload.statistics().pending()) upload.advance(28);
        long copied = shift > 0 ? 144 : 120;
        assertEquals(copied, upload.statistics().copiedBytes());
        assertEquals(708, upload.statistics().uploadedBytes());
        assertEquals(708 - copied, upload.statistics().hostBytes());
        assertArrayEquals(next.copySamples(), memory.values.get(upload.resident().texture()));
      }
    }
  }

  @Test
  void regionOverflowIsValidatedPerAxisBeforeReplacingAResident() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(SHAPE, 0), 420);
      upload.advance(1000);
      var prior = upload.resident();
      assertThrows(
          IllegalArgumentException.class,
          () ->
              upload.stage(
                  data(SHAPE, 0),
                  840,
                  new VolumeTextureUpload.Region("affine", 0, 0, Integer.MAX_VALUE - 5)));
      assertSame(prior, upload.resident());
      assertTrue(
          upload.stage(
              data(SHAPE, 0),
              840,
              new VolumeTextureUpload.Region(
                  "affine", Integer.MAX_VALUE - 2, Integer.MAX_VALUE - 4, Integer.MAX_VALUE - 6)));
    }
  }

  @Test
  void legacyCubeOnlyBackendsRejectBoxesExplicitlyAndRetainTheActiveTexture() {
    var memory = new VolumeTextureUploadTest.Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(GridExtent.cube(2), 0), 32);
      upload.advance(100);
      var active = upload.resident();
      assertThrows(UnsupportedOperationException.class, () -> upload.stage(data(SHAPE, 0), 452));
      assertSame(active, upload.resident());
      assertEquals(1, memory.textures.size());
    }
  }

  @Test
  void directionalLightUsesTheActualExitFaceOfEveryAxis() {
    var volume = VolumeData.sample(SHAPE, 0, 0, 0, (x, y, z) -> 1);
    var transfer =
        VolumeTransfer.of(volume.encoding(), new VolumeTransfer.Settings(0, 1, .2, 0, 256));
    for (double[] p : new double[][] {{2.99, 2, 3}, {.5, 4.99, .5}, {.5, .5, 6.99}}) {
      double length =
          Math.min(
              (3 - p[0]) / VolumeLight.LX,
              Math.min((5 - p[1]) / VolumeLight.LY, (7 - p[2]) / VolumeLight.LZ));
      assertEquals(
          Math.exp(-.2f * length),
          VolumeLight.transmittance(
              volume, transfer, p[0], p[1], p[2], new VolumeLight.Settings(true, 16)),
          1e-12);
    }
    assertThrows(
        IllegalArgumentException.class,
        () ->
            VolumeLight.transmittance(
                volume, transfer, 1, 5.01, 1, new VolumeLight.Settings(true, 16)));
  }
}
