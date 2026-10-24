package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.*;
import java.nio.*;
import java.util.*;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

public class StreamingContractTest {
  @Test
  void majorityTieBoundaryShellAndExactSnapshotsStayIndependent() {
    var p = new ChunkPos(0, 0, 0);
    var exact =
        VoxelChunk.generate(
            p,
            8,
            (x, y, z) ->
                x == 0 ? 9 : x >= 2 && x < 4 && y >= 2 && y < 4 && z == 2 ? (x == 2 ? 7 : 3) : 0);
    var lod = new DisplayLod(SceneChunk.exact(exact), 1);
    assertEquals(3, lod.get(3, 3, 3));
    assertEquals(0, exact.getVoxelTypeId(3, 3, 3));
    assertEquals(9, lod.get(0, 3, 3));
    assertEquals(0, lod.get(-1, 3, 3));
    var large = new DisplayLod(SceneChunk.exact(VoxelChunk.uniform(p, 256, 4)), 8);
    assertEquals(4, large.get(128, 128, 128));
    assertEquals(4, large.get(255, 128, 128));
  }

  @Test
  void packedBoundary256AndLargeTypeDoNotWrap() {
    assertThrows(IllegalArgumentException.class, () -> VoxelMesher.points(4, (x, y, z) -> 1, 0));
    int n = 256;
    var mesh =
        VoxelMesher.greedy(
            n, (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < n && y < n && z < n ? 70000 : 0);
    assertEquals(36, mesh.vertexCount());
    var data = mesh.readOnly();
    int max = 0;
    for (int i = 0; i < mesh.vertexCount(); i++) {
      int at = i * 16;
      max = Math.max(max, Short.toUnsignedInt(data.getShort(at)));
      max = Math.max(max, Short.toUnsignedInt(data.getShort(at + 2)));
      max = Math.max(max, Short.toUnsignedInt(data.getShort(at + 4)));
      assertEquals(70000, data.getInt(at + 12));
    }
    assertEquals(512, max);
    assertEquals(36 * 16, mesh.byteSize());
    assertThrows(ReadOnlyBufferException.class, () -> data.put(0, (byte) 1));
  }

  @Test
  void neighborSolidFacesAreHiddenAndAoReadsTheDiagonalHalo() {
    int n = 4;
    var alone =
        VoxelMesher.greedy(
            n, (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < n && y < n && z < n ? 1 : 0);
    var neighbor =
        VoxelMesher.greedy(
            n,
            (x, y, z) ->
                x >= 0 && y >= 0 && z >= 0 && x < 2 * n && y < n && z < n ? x < n ? 1 : 2 : 0);
    assertEquals(36, alone.vertexCount());
    assertEquals(30, neighbor.vertexCount());
    var ao =
        VoxelMesher.greedy(
            1, (x, y, z) -> x == 0 && y == 0 && z == 0 || x == 1 && y == 1 && z == 0 ? 1 : 0);
    boolean shaded = false;
    var bytes = ao.readOnly();
    for (int i = 0; i < ao.vertexCount(); i++)
      if (bytes.get(i * 16 + 6) == 127 && Byte.toUnsignedInt(bytes.get(i * 16 + 9)) < 255)
        shaded = true;
    assertTrue(shaded);
  }

  @Test
  void freeRangesCoalesceAndDoNotAliasLiveAllocations() {
    var arena = new ArenaAllocator(256);
    var a = arena.allocate(64);
    var b = arena.allocate(64);
    var c = arena.allocate(128);
    assertNull(arena.allocate(16));
    arena.release(b);
    arena.release(a);
    assertEquals(128, arena.largestFreeRange());
    var combined = arena.allocate(128);
    assertEquals(0, combined.offset());
    assertNotEquals(c.offset(), combined.offset());
    arena.release(c);
    arena.release(combined);
    assertEquals(0, arena.usedBytes());
    assertEquals(256, arena.largestFreeRange());
    assertThrows(IllegalArgumentException.class, () -> arena.release(a));
  }

  @Test
  void lodUsesNearestAabbDistanceAndHysteresis() {
    var p = new ChunkPos(0, 0, 0);
    assertEquals(0, LodSelector.distance(p, 32, new Vector3d(16, 16, 16)));
    assertEquals(32, LodSelector.distance(p, 32, new Vector3d(64, 16, 16)));
    assertEquals(0, LodSelector.choose(p, 32, new Vector3d(100, 16, 16), 0, 2, 1, 0));
    assertEquals(1, LodSelector.choose(p, 32, new Vector3d(110, 16, 16), 0, 2, 1, 0));
    assertEquals(1, LodSelector.choose(p, 32, new Vector3d(90, 16, 16), 1, 2, 1, 0));
    assertEquals(0, LodSelector.choose(p, 32, new Vector3d(80, 16, 16), 1, 2, 1, 0));
  }

  @Test
  void pressureHonorsPriorityManualResumeAndGradualRestoration() {
    var p = new RenderPressure();
    for (int level = 1; level <= 5; level++) {
      assertTrue(p.observe(level * 1_000_000_000L, .99, .99, 60, true));
      assertEquals(level, p.level());
      if (level == 1) assertEquals(2, p.radius(2));
      if (level == 2) assertEquals(1, p.radius(2));
      if (level == 3) assertFalse(p.preload());
      if (level == 4) assertFalse(p.acceptExpensive());
    }
    assertTrue(p.paused());
    p.observe(20_000_000_000L, .1, .1, 10, false);
    assertEquals(5, p.level());
    p.resume(21_000_000_000L);
    assertFalse(p.paused());
    assertEquals(3, p.level());
    p.observe(22_000_000_000L, .1, .1, 10, false);
    p.observe(28_000_000_000L, .1, .1, 10, false);
    assertEquals(2, p.level());
  }
}
