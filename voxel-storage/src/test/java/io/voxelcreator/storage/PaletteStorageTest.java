package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PaletteStorageTest {
  private static final ChunkPos POS = new ChunkPos(-2, 3, -4);

  @Test
  void everyLeafWidthCrossesWordsWithoutLosingLargeRuntimeIds() {
    for (int count : new int[] {2, 3, 5, 9, 17, 33, 64}) {
      var chunk = VoxelChunk.generate(POS, 4, (x, y, z) -> value((y * 4 + z) * 4 + x, count));
      for (int i = 0; i < 64; i++)
        assertEquals(value(i, count), chunk.getVoxelTypeId(i % 4, i / 16, i / 4 % 4));
      var stats = chunk.storageStatistics();
      assertEquals(count, stats.paletteEntries());
      assertEquals(count == 64 ? 1 : 0, stats.denseLeaves());
      assertEquals(count == 64 ? 0 : 1, stats.packedLeaves());
      long[] occupied = {0};
      chunk.forEachOccupied(
          (x, y, z, t) -> {
            assertEquals(value((y * 4 + z) * 4 + x, count), t);
            occupied[0]++;
          });
      assertEquals(occupied[0], stats.occupiedVoxels());
    }
  }

  private static int value(int index, int count) {
    return index % count == 0 ? 0 : Integer.MAX_VALUE - index % count;
  }

  @Test
  void localPalettesKeepOneBitLeavesInChunksWithManyTypes() {
    var chunk =
        VoxelChunk.generate(
            POS,
            32,
            (x, y, z) -> (x & 1) == 0 ? 0 : 1 + (x / 4 + (z / 4) * 8 + (y / 4) * 64) * 10000);
    var s = chunk.storageStatistics();
    assertEquals(513, s.paletteEntries());
    assertEquals(512, s.packedLeaves());
    assertEquals(1024, s.packedWords());
    assertEquals(16384, s.occupiedVoxels());
    assertTrue(chunk.estimatedBytes() < 20000);
    assertEquals(5110001, chunk.getVoxelTypeId(31, 31, 31));
    int[] palette = chunk.paletteRuntimeIds();
    Arrays.fill(palette, -1);
    assertEquals(5110001, chunk.getVoxelTypeId(31, 31, 31));
  }

  @Test
  void primitivePaletteGrowthPreservesEveryDistinctHighRuntimeId() {
    var c = VoxelChunk.generate(POS, 32, (x, y, z) -> Integer.MAX_VALUE - ((y * 32 + z) * 32 + x));
    assertEquals(32769, c.storageStatistics().paletteEntries());
    assertEquals(512, c.storageStatistics().denseLeaves());
    assertEquals(32768, c.storageStatistics().occupiedVoxels());
    for (int i = 0; i < 32768; i++)
      assertEquals(Integer.MAX_VALUE - i, c.getVoxelTypeId(i % 32, i / 1024, i / 32 % 32));
    assertTrue(c.estimatedBytes() < 128 + 9L * 32768);
  }

  @Test
  void randomizedInclusiveFillsMatchDenseOracleAndPreserveSnapshots() {
    int size = 16;
    int[] oracle = new int[size * size * size];
    var chunk = VoxelChunk.uniform(POS, size, 0);
    var random = new Random(0x5721);
    for (int step = 0; step < 100; step++) {
      int ax = random.nextInt(size), ay = random.nextInt(size), az = random.nextInt(size);
      int bx = random.nextInt(size), by = random.nextInt(size), bz = random.nextInt(size);
      int minX = Math.min(ax, bx), minY = Math.min(ay, by), minZ = Math.min(az, bz);
      int maxX = Math.max(ax, bx),
          maxY = Math.max(ay, by),
          maxZ = Math.max(az, bz),
          type = random.nextInt(5);
      var old = chunk;
      int[] before = oracle.clone();
      chunk = chunk.withFilledBox(minX, minY, minZ, maxX, maxY, maxZ, type);
      for (int y = minY; y <= maxY; y++)
        for (int z = minZ; z <= maxZ; z++)
          for (int x = minX; x <= maxX; x++) oracle[(y * size + z) * size + x] = type;
      for (int i = 0; i < oracle.length; i++) {
        assertEquals(oracle[i], chunk.getVoxelTypeId(i % size, i / (size * size), i / size % size));
        assertEquals(before[i], old.getVoxelTypeId(i % size, i / (size * size), i / size % size));
      }
      assertEquals(
          Arrays.stream(oracle).filter(v -> v != 0).count(),
          chunk.storageStatistics().occupiedVoxels());
      assertEquals(POS, chunk.position());
    }
  }

  @Test
  void sparse256BulkFillClearAndCoalescenceNeverExpandDenseRoot() {
    var empty = VoxelChunk.uniform(POS, 256, 0);
    var single = empty.withVoxel(255, 255, 255, Integer.MAX_VALUE);
    assertTrue(single.estimatedBytes() < 1000);
    assertEquals(Integer.MAX_VALUE, single.getVoxelTypeId(255, 255, 255));
    var clear = single.withVoxel(255, 255, 255, 0);
    assertTrue(clear.isEmpty());
    assertEquals(1, clear.paletteRuntimeIds().length);
    assertEquals(0, clear.storageStatistics().arenaInts());
    var half = empty.withFilledBox(128, 0, 0, 255, 255, 255, 72);
    assertEquals(4L * 128 * 128 * 128, half.storageStatistics().occupiedVoxels());
    assertTrue(half.estimatedBytes() < 200);
    var all = half.withFilledBox(0, 0, 0, 127, 255, 255, 72);
    assertEquals("UNIFORM", all.representation());
    assertTrue(all.estimatedBytes() < 100);
    assertArrayEquals(new int[] {0, 72}, all.paletteRuntimeIds());
    assertTrue(all.withFilledBox(0, 0, 0, 255, 255, 255, 0).isEmpty());
    assertTrue(empty.isEmpty());
    assertEquals(Integer.MAX_VALUE, single.getVoxelTypeId(255, 255, 255));
  }

  @Test
  void allRootCheckerboardsRemainExactAndCompact() {
    for (int size : new int[] {2, 4, 8, 16, 32, 64, 128, 256}) {
      var c = VoxelChunk.generate(POS, size, (x, y, z) -> (x + y + z) & 1);
      assertEquals((size - 1) & 1, c.getVoxelTypeId(size - 1, size - 1, size - 1));
      assertEquals((long) size * size * size / 2, c.storageStatistics().occupiedVoxels());
      assertTrue(c.estimatedBytes() < size * size * size + 200);
      assertEquals(
          1, c.withFilledBox(0, 0, 0, size - 1, size - 1, size - 1, 1).getVoxelTypeId(0, 0, 0));
    }
  }

  @Test
  void invalidBoxesTypesAndSamplerFailureLeavePublishedSnapshotIntact() {
    var c = VoxelChunk.uniform(POS, 8, 5);
    assertThrows(IndexOutOfBoundsException.class, () -> c.withVoxel(8, 0, 0, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> c.withVoxel(-1, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> c.withFilledBox(4, 0, 0, 3, 0, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> c.withVoxel(0, 0, 0, -1));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelChunk.generate(POS, 8, (x, y, z) -> -1));
    assertThrows(
        ArithmeticException.class,
        () ->
            VoxelChunk.generate(
                POS,
                8,
                (x, y, z) -> {
                  throw new ArithmeticException("failure");
                }));
    try {
      Thread.currentThread().interrupt();
      assertThrows(CancellationException.class, () -> c.withVoxel(7, 7, 7, 0));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals(5, c.getVoxelTypeId(7, 7, 7));
    assertSame(c, c.withFilledBox(0, 0, 0, 7, 7, 7, 5));
  }

  @Test
  void concurrentReadersAndIndependentEditsHavePrivateStorage() throws Exception {
    var c = VoxelChunk.generate(POS, 16, (x, y, z) -> (x + y + z) % 3);
    try (var workers = Executors.newFixedThreadPool(4)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int t = 0; t < 12; t++) {
        final int type = t + 100;
        tasks.add(
            () -> {
              var edit = c.withFilledBox(0, 0, 0, 7, 7, 7, type);
              for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                  for (int x = 0; x < 16; x++) {
                    assertEquals((x + y + z) % 3, c.getVoxelTypeId(x, y, z));
                    assertEquals(
                        x < 8 && y < 8 && z < 8 ? type : (x + y + z) % 3,
                        edit.getVoxelTypeId(x, y, z));
                  }
              return null;
            });
      }
      for (var f : workers.invokeAll(tasks)) f.get();
    }
  }
}
