package io.voxelcreator.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred public coordinate contracts with independent signed division and count oracles. */
class CoordinateBoundaryTest {
  private static final int[] ROOTS = {2, 4, 8, 16, 32, 64, 128, 256};
  private static final int[] INVALID = {Integer.MIN_VALUE, -256, -2, -1, 0, 1, 3, 6, 12, 127, 129, 255, 257, 512, 1024, Integer.MAX_VALUE};
  private record Axis(int chunk, int local) {}

  private Axis axis(int coordinate, int size) {
    var divisor = BigInteger.valueOf(size); var qr = BigInteger.valueOf(coordinate).divideAndRemainder(divisor);
    if (qr[1].signum() < 0) { qr[0] = qr[0].subtract(BigInteger.ONE); qr[1] = qr[1].add(divisor); }
    return new Axis(qr[0].intValueExact(), qr[1].intValueExact());
  }
  private int[] coordinates(int size) {
    return new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -size - 1, -size, -size + 1, -1, 0, 1,
        size - 1, size, size + 1, 255, 256, 257, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
  }
  private BigInteger count(RegionBounds bounds, int size) {
    var min = bounds.min(); var max = bounds.max();
    return BigInteger.valueOf((long) axis(max.x(), size).chunk - axis(min.x(), size).chunk + 1)
        .multiply(BigInteger.valueOf((long) axis(max.y(), size).chunk - axis(min.y(), size).chunk + 1))
        .multiply(BigInteger.valueOf((long) axis(max.z(), size).chunk - axis(min.z(), size).chunk + 1));
  }

  @Test
  void everyRootMapsNegativeBoundariesAndSignedExtremesWithExactReconstruction() {
    for (int size : ROOTS) {
      var values = coordinates(size);
      for (int i = 0; i < values.length; i++) {
        var p = new BlockPos(values[i], values[(i + 5) % values.length], values[(i + 11) % values.length]);
        var x = axis(p.x(), size); var y = axis(p.y(), size); var z = axis(p.z(), size);
        assertEquals(new ChunkPos(x.chunk, y.chunk, z.chunk), ChunkPos.fromBlock(p, size));
        for (int coordinate : new int[] {p.x(), p.y(), p.z()}) {
          var expected = axis(coordinate, size);
          assertTrue(expected.local >= 0 && expected.local < size);
          assertEquals(expected.local, Math.floorMod(coordinate, size));
          assertEquals(coordinate, (long) expected.chunk * size + expected.local);
          assertTrue((long) expected.chunk * size >= Integer.MIN_VALUE);
          assertTrue((long) expected.chunk * size + size - 1 <= Integer.MAX_VALUE);
        }
      }
    }
  }

  @Test
  void invalidRootsRejectConsistentlyBeforeSamplingOrMapping() {
    var point = new BlockPos(-1, 0, 1); var pos = new ChunkPos(-1, 0, 1); var region = new RegionBounds(point, point);
    var callbacks = new AtomicInteger();
    for (int size : INVALID) {
      assertThrows(IllegalArgumentException.class, () -> ChunkPos.fromBlock(point, size));
      assertThrows(IllegalArgumentException.class, () -> region.chunkCount(size));
      assertThrows(IllegalArgumentException.class, () -> VoxelChunk.uniform(pos, size, 0));
      assertThrows(IllegalArgumentException.class, () -> VoxelChunk.generate(pos, size, (x, y, z) -> callbacks.incrementAndGet()));
      var bytes = new ByteArrayInputStream(new byte[] {0});
      assertThrows(IllegalArgumentException.class, () -> VoxelChunk.readTree(pos, size, new DataInputStream(bytes), value -> { callbacks.incrementAndGet(); return value; }));
      assertEquals(1, bytes.available()); assertEquals(0, callbacks.get());
    }
  }

  @Test
  void rootValidationPrecedesNullCoordinatesAndValidNullsKeepTheirType() {
    for (int size : INVALID) assertThrows(IllegalArgumentException.class, () -> ChunkPos.fromBlock(null, size));
    for (int size : ROOTS) assertThrows(NullPointerException.class, () -> ChunkPos.fromBlock(null, size));
    assertEquals(new ChunkPos(-1, -1, -1), ChunkPos.fromBlock(new BlockPos(-1, -1, -1), 256));
  }

  @Test
  void inclusiveRegionCountsAndClippingAgreeAtAndBeyondSignedLongCapacity() {
    for (int size : ROOTS) {
      int end = 65536 * size - 1;
      var regions = List.of(
          new RegionBounds(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE)),
          new RegionBounds(new BlockPos(-size, -size, -size), new BlockPos(size - 1, size - 1, size - 1)),
          new RegionBounds(new BlockPos(-1, -1, -1), new BlockPos(0, 0, 0)),
          new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, 0, 0)),
          new RegionBounds(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE), new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)),
          new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE, end, end)),
          new RegionBounds(new BlockPos(Integer.MIN_VALUE, 0, 0), new BlockPos(Integer.MAX_VALUE - size, end, end)));
      for (var region : regions) {
        var expected = count(region, size);
        if (expected.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)
          assertThrows(IllegalArgumentException.class, () -> region.chunkCount(size));
        else assertEquals(expected.longValueExact(), region.chunkCount(size));
      }
      var original = regions.get(1);
      var middle = original.clipY(-1, 0).orElseThrow();
      assertEquals(new RegionBounds(new BlockPos(-size, -1, -size), new BlockPos(size - 1, 0, size - 1)), middle);
      assertEquals(8, middle.chunkCount(size));
      assertEquals(4, original.clipY(-size, -size).orElseThrow().chunkCount(size));
      assertTrue(original.clipY(size, size + 1).isEmpty());
      assertEquals(-size, original.min().y()); assertEquals(size - 1, original.max().y());
    }
  }
}
