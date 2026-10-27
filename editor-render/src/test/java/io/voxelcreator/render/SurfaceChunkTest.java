package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.storage.ChunkPos;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SurfaceChunkTest {
  @Test
  void cornersAreSampledOnceInWorldCoordinatesAndRemeshingUsesOwnedData() {
    var calls = new AtomicInteger();
    var data =
        SceneChunk.isosurface(
            new ChunkPos(-1, 0, 2),
            4,
            new CancellationToken(),
            (x, y, z) -> {
              calls.incrementAndGet();
              return z - 9.257;
            },
            0);
    assertTrue(data.surface());
    assertEquals(125, calls.get());
    assertEquals(128 + 8L * 125, data.bytes());
    var a = data.surfaceMesh(new CancellationToken());
    var b = data.surfaceMesh(new CancellationToken());
    assertEquals(125, calls.get());
    assertEquals(a.readOnly(), b.readOnly());
    assertEquals(128, a.unitsPerBlock());
    assertTrue(a.vertexCount() > 0);
  }

  @Test
  void adjacentSignedChunksHaveTheSameQuantizedFaceVertices() {
    var a =
        SceneChunk.isosurface(
            new ChunkPos(-1, 0, 0),
            4,
            new CancellationToken(),
            (x, y, z) -> y + .37 * z + .23 * x - 2.1,
            0);
    var b =
        SceneChunk.isosurface(
            new ChunkPos(0, 0, 0),
            4,
            new CancellationToken(),
            (x, y, z) -> y + .37 * z + .23 * x - 2.1,
            0);
    var left = face(a.surfaceMesh(new CancellationToken()), 4 * 128);
    var right = face(b.surfaceMesh(new CancellationToken()), 0);
    assertFalse(left.isEmpty());
    assertEquals(left, right);
  }

  @Test
  void canceledAndInvalidSourcesNeverReturnPartialChunksOrMeshes() {
    var token = new CancellationToken();
    var calls = new AtomicInteger();
    assertThrows(
        CancellationException.class,
        () ->
            SceneChunk.isosurface(
                new ChunkPos(0, 0, 0),
                4,
                token,
                (x, y, z) -> {
                  calls.incrementAndGet();
                  token.cancel();
                  return x;
                },
                0));
    assertTrue(calls.get() <= 5);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SceneChunk.isosurface(
                new ChunkPos(0, 0, 0), 4, new CancellationToken(), (x, y, z) -> Double.NaN, 0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SceneChunk.isosurface(
                new ChunkPos(Integer.MAX_VALUE, 0, 0),
                4,
                new CancellationToken(),
                (x, y, z) -> 1,
                0));
    var data =
        SceneChunk.isosurface(
            new ChunkPos(0, 0, 0), 4, new CancellationToken(), (x, y, z) -> x - 1.25, 0);
    var canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(CancellationException.class, () -> data.surfaceMesh(canceled));
  }

  @Test
  void fullFieldAndEmptyFieldHaveNoInventedBoundaryCapsAndWorkingStorageIsBounded() {
    for (double value : new double[] {-1, 0, 1}) {
      var data =
          SceneChunk.isosurface(
              new ChunkPos(0, 0, 0), 4, new CancellationToken(), (x, y, z) -> value, 0);
      assertEquals(0, data.surfaceMesh(new CancellationToken()).vertexCount());
    }
    assertEquals(
        IsosurfaceMesh.workingBytes(4) + 2 * 576L * 64 + 65536, SceneChunk.surfaceWorkingBytes(4));
  }

  private static Set<String> face(PackedMesh mesh, int x) {
    Set<String> result = new TreeSet<>();
    ByteBuffer b = mesh.readOnly();
    for (int i = 0; i < mesh.byteSize(); i += 16)
      if (Short.toUnsignedInt(b.getShort(i)) == x)
        result.add(
            Short.toUnsignedInt(b.getShort(i + 2)) + "," + Short.toUnsignedInt(b.getShort(i + 4)));
    return result;
  }
}
