package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;

class VolumeTextureUploadTest {
  static final class Memory implements VolumeTextureUpload.Backend {
    final Map<Integer, float[]> textures = new HashMap<>();
    final Map<Integer, Integer> sizes = new HashMap<>();
    int next, writes, maxWrite;
    boolean allocationFailure, writeFailure;

    public int allocate(int size) {
      if (allocationFailure) throw new IllegalStateException("allocation");
      int id = ++next;
      textures.put(id, new float[size * size * size]);
      sizes.put(id, size);
      return id;
    }

    public void write(int texture, int x, int y, int z, int w, int h, int d, FloatBuffer values) {
      if (writeFailure) throw new IllegalStateException("write");
      assertEquals(w * h * d, values.remaining());
      maxWrite = Math.max(maxWrite, values.remaining() * 4);
      writes++;
      int side = sizes.get(texture);
      for (int k = 0; k < d; k++)
        for (int j = 0; j < h; j++)
          for (int i = 0; i < w; i++)
            textures.get(texture)[((z + k) * side + y + j) * side + x + i] = values.get();
    }

    public void delete(int texture) {
      assertNotNull(textures.remove(texture), "double delete");
      sizes.remove(texture);
    }
  }

  private VolumeData data(int side) {
    float[] values = new float[side * side * side];
    for (int i = 0; i < values.length; i++) values[i] = i - 17.25f;
    return new VolumeData(side, values);
  }

  @Test
  void arbitraryBudgetsCoverEveryTexelExactlyAndPublishOnlyComplete() {
    for (int side : new int[] {2, 3, 17, 32, 65}) {
      var memory = new Memory();
      var data = data(side);
      try (var upload = new VolumeTextureUpload(memory)) {
        assertTrue(upload.stage(data, 4L * side * side * side));
        assertNull(upload.resident());
        assertEquals(0, upload.advance(3));
        var random = new Random(side);
        long bytes = 0;
        while (upload.statistics().pending()) {
          int budget = random.nextInt(70001);
          int written = upload.advance(budget);
          assertTrue(written <= budget && written % 4 == 0);
          bytes += written;
          if (upload.statistics().pending()) assertNull(upload.resident());
        }
        assertEquals(4L * side * side * side, bytes);
        assertEquals(1, upload.statistics().version());
        assertEquals(0, upload.statistics().cpuBytes());
        assertArrayEquals(data.copySamples(), memory.textures.get(upload.resident().texture()));
        assertTrue(memory.maxWrite <= 65536);
      }
      assertTrue(memory.textures.isEmpty());
    }
  }

  @Test
  void exactReservationIncludesBothResidentAndCandidateAndRejectsBeforeAllocation() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      assertTrue(upload.stage(data(2), 32));
      upload.advance(32);
      var accepted = upload.resident();
      assertFalse(upload.stage(data(3), 139));
      assertEquals(1, memory.next);
      assertSame(accepted, upload.resident());
      assertTrue(upload.stage(data(3), 140));
      upload.advance(20);
      assertEquals(32, upload.statistics().activeBytes());
      assertEquals(108, upload.statistics().pendingBytes());
      assertEquals(88, upload.statistics().remainingBytes());
      assertFalse(upload.stage(data(4), 287));
      assertEquals(2, memory.next);
      assertTrue(upload.statistics().pending());
      upload.advance(1000);
      assertEquals(108, upload.resident().bytes());
      assertFalse(memory.textures.containsKey(accepted.texture()));
    }
  }

  @Test
  void replacementAndCancellationRetainAcceptedTextureAndReclaimCandidates() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(2), 1000);
      upload.advance(1000);
      var accepted = upload.resident();
      upload.stage(data(3), 1000);
      upload.advance(5);
      upload.stage(data(4), 1000);
      assertEquals(Set.of(1, 3), memory.textures.keySet());
      assertSame(accepted, upload.resident());
      upload.cancel();
      upload.cancel();
      assertEquals(Set.of(1), memory.textures.keySet());
      assertEquals(0, upload.statistics().remainingBytes());
      assertEquals(0, upload.statistics().cpuBytes());
      assertEquals(0, upload.advance(1000));
    }
  }

  @Test
  void failedAllocationOrWriteDoesNotPublishOrReplaceAcceptedMetadata() {
    var memory = new Memory();
    try (var upload = new VolumeTextureUpload(memory)) {
      upload.stage(data(2), 1000);
      upload.advance(1000);
      var accepted = upload.resident();
      memory.allocationFailure = true;
      assertThrows(IllegalStateException.class, () -> upload.stage(data(3), 1000));
      memory.allocationFailure = false;
      upload.stage(data(3), 1000);
      upload.advance(4);
      memory.writeFailure = true;
      assertThrows(IllegalStateException.class, () -> upload.advance(100));
      assertSame(accepted, upload.resident());
      assertEquals(1, upload.statistics().version());
      assertFalse(upload.statistics().pending());
      assertEquals(Set.of(1), memory.textures.keySet());
      memory.writeFailure = false;
      upload.stage(data(3), 1000);
      upload.advance(1000);
      assertEquals(2, upload.statistics().version());
    }
  }

  @Test
  void closeDisposesBothAllocationsAndRejectsFutureWork() {
    var memory = new Memory();
    var upload = new VolumeTextureUpload(memory);
    upload.stage(data(2), 1000);
    upload.advance(32);
    upload.stage(data(3), 1000);
    upload.close();
    upload.close();
    assertTrue(memory.textures.isEmpty());
    assertThrows(IllegalStateException.class, () -> upload.advance(4));
    assertThrows(IllegalStateException.class, () -> upload.stage(data(2), 1000));
    assertNull(upload.resident());
  }

  @Test
  void invalidInputsAndBufferRangesDoNotChangeOwnedSamples() {
    var memory = new Memory();
    var data = data(2);
    var original = data.copySamples();
    try (var upload = new VolumeTextureUpload(memory)) {
      assertThrows(NullPointerException.class, () -> upload.stage(null, 0));
      assertThrows(IllegalArgumentException.class, () -> upload.stage(data, -1));
      assertThrows(IllegalArgumentException.class, () -> upload.advance(-1));
      var buffer = FloatBuffer.allocate(3);
      assertThrows(IndexOutOfBoundsException.class, () -> data.copySamples(7, buffer));
      assertEquals(0, buffer.position());
      data.copySamples(2, buffer);
      assertArrayEquals(Arrays.copyOfRange(original, 2, 5), buffer.array());
      buffer.put(0, 999);
      assertArrayEquals(original, data.copySamples());
    }
  }
}
