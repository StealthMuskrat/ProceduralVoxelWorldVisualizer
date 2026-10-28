package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import io.voxelcreator.storage.ChunkPos;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SurfaceLodTest {
  private Mesh mesh(int n, int level, MeshBuilder.Scalar field) {
    return SurfaceLodMesh.build(
        n, level, field, 0, new CancellationToken(), SurfaceLodMesh.maximumMeshBytes(n, level));
  }

  @Test
  void levelZeroIsByteIdenticalAndCoarsePlanesKeepAreaNormalsAndReduceGeometry() {
    MeshBuilder.Scalar field = (x, y, z) -> z - 13.257;
    var fine = IsosurfaceMesh.build(32, field, 0, new CancellationToken(), 64L << 20);
    assertArrayEquals(fine.vertices(), mesh(32, 0, field).vertices());
    boolean reduced = false;
    for (int level = 1; level <= 5; level++) {
      var surface = mesh(32, level, field);
      assertTrue(surface.vertexCount() <= fine.vertexCount());
      reduced |= surface.vertexCount() < fine.vertexCount();
      assertEquals(32 * 32, area(surface), .001);
      for (int i = 0; i < surface.vertices().length; i += 7) {
        assertEquals(13.257, surface.vertices()[i + 2], .000002);
        assertEquals(0, surface.vertices()[i + 3], .000001);
        assertEquals(0, surface.vertices()[i + 4], .000001);
        assertEquals(-1, surface.vertices()[i + 5], .000001);
        assertEquals(128, surface.vertices()[i + 6]);
      }
    }
    assertTrue(reduced);
  }

  @Test
  void everyMixedLevelFaceHasIdenticalCanonicalCrossingSegmentsAcrossSignedChunks() {
    int n = 8;
    for (int axis = 0; axis < 3; axis++) {
      int normal = axis;
      MeshBuilder.Scalar field = (x, y, z) -> .17 * x + .31 * y + .67 * z - 2.137;
      MeshBuilder.Scalar curved =
          (x, y, z) -> Math.sin(.71 * x) + Math.cos(.39 * y) + .23 * z - .417;
      for (var f : List.of(field, curved))
        for (int a = 0; a <= 3; a++)
          for (int b = 0; b <= 3; b++) {
            MeshBuilder.Scalar left =
                (x, y, z) ->
                    f.sample(
                        x - (normal == 0 ? n : 0),
                        y - (normal == 1 ? n : 0),
                        z - (normal == 2 ? n : 0));
            var l = faceSegments(mesh(n, a, left), axis, n);
            var r = faceSegments(mesh(n, b, f), axis, 0);
            assertFalse(l.isEmpty());
            assertEquals(l, r, "axis=" + axis + " levels=" + a + "," + b);
          }
    }
  }

  @Test
  void packedMixedFacesKeepTheirUnitSegmentsAndPrecisionAtTheRootBoundary() {
    for (int n : new int[] {8, 32, 256}) {
      MeshBuilder.Scalar field = (x, y, z) -> y + .37 * z - n * .473;
      // Large roots use maximal coarsening here; full-grid root-256 extraction would be needless.
      int max = Integer.numberOfTrailingZeros(n);
      var a = PackedMesh.surface(mesh(n, max, field), n);
      var b = PackedMesh.surface(mesh(n, Math.max(1, max - 1), field), n);
      assertEquals(packedFace(a, 0, n * 128), packedFace(b, 0, 0));
      assertFalse(packedFace(a, 0, n * 128).isEmpty());
      assertEquals(128, a.unitsPerBlock());
      var data = a.readOnly();
      for (int at = 0; at < a.byteSize(); at += 16) {
        assertTrue(Short.toUnsignedInt(data.getShort(at)) <= n * 128);
        assertTrue(Short.toUnsignedInt(data.getShort(at + 2)) <= n * 128);
        assertTrue(Short.toUnsignedInt(data.getShort(at + 4)) <= n * 128);
        assertEquals(255, Byte.toUnsignedInt(data.get(at + 9)));
        assertEquals(128, data.getInt(at + 12));
      }
    }
  }

  @Test
  void closedCoarseSphereHasTwoIncidentTrianglesPerEdgeAndOutwardNormals() {
    for (int level = 0; level <= 3; level++) {
      var sphere =
          mesh(
              8,
              level,
              (x, y, z) ->
                  2.731 * 2.731
                      - (x - 4.13) * (x - 4.13)
                      - (y - 3.91) * (y - 3.91)
                      - (z - 4.07) * (z - 4.07));
      assertTrue(sphere.vertexCount() > 0);
      Map<String, Integer> edges = new HashMap<>();
      var v = sphere.vertices();
      for (int at = 0; at < v.length; at += 21) {
        double x = (v[at] + v[at + 7] + v[at + 14]) / 3.0 - 4.13,
            y = (v[at + 1] + v[at + 8] + v[at + 15]) / 3.0 - 3.91,
            z = (v[at + 2] + v[at + 9] + v[at + 16]) / 3.0 - 4.07;
        assertTrue(x * v[at + 3] + y * v[at + 4] + z * v[at + 5] > 0);
        for (int a = 0; a < 3; a++) {
          String p = point(v, at + a * 7), q = point(v, at + ((a + 1) % 3) * 7);
          edges.merge(p.compareTo(q) < 0 ? p + "/" + q : q + "/" + p, 1, Integer::sum);
        }
      }
      assertTrue(edges.values().stream().allMatch(c -> c == 2));
    }
  }

  @Test
  void remeshingNeverSamplesSourceAgainAndConcurrentLevelsOwnTheirOutput() throws Exception {
    var calls = new AtomicInteger();
    var chunk =
        SceneChunk.isosurface(
            new ChunkPos(-1, 0, 0),
            16,
            new CancellationToken(),
            (x, y, z) -> {
              calls.incrementAndGet();
              return y + .23 * z - .17 * x - 4.137;
            },
            0);
    int canonical = calls.get();
    try (var workers = Executors.newFixedThreadPool(3)) {
      var results = new ArrayList<Future<PackedMesh>>();
      for (int level = 0; level <= 4; level++) {
        int requested = level;
        results.add(workers.submit(() -> chunk.surfaceMesh(requested, new CancellationToken())));
      }
      for (int level = 0; level <= 4; level++)
        assertEquals(
            chunk.surfaceMesh(level, new CancellationToken()).readOnly(),
            results.get(level).get().readOnly());
    }
    assertEquals(17 * 17 * 17, canonical);
    assertEquals(canonical, calls.get());
    assertEquals(
        chunk.surfaceMesh(new CancellationToken()).readOnly(),
        chunk.surfaceMesh(0, new CancellationToken()).readOnly());
  }

  @Test
  void finiteExtremeAndTinySamplesKeepTheSamePlaneAndComplementOrientation() {
    for (int level = 1; level <= 3; level++)
      for (double scale : new double[] {1, 1e-300, 1e300}) {
        var positive = mesh(8, level, (x, y, z) -> (z - 3.257) * scale);
        var negative = mesh(8, level, (x, y, z) -> (3.257 - z) * scale);
        assertEquals(64, area(positive), .0001);
        assertEquals(64, area(negative), .0001);
        for (int i = 0; i < positive.vertices().length; i += 7)
          assertEquals(-1, positive.vertices()[i + 5], .000001);
        for (int i = 0; i < negative.vertices().length; i += 7)
          assertEquals(1, negative.vertices()[i + 5], .000001);
      }
    var extreme = mesh(8, 3, (x, y, z) -> z < 4 ? -Double.MAX_VALUE : Double.MAX_VALUE);
    assertTrue(extreme.vertexCount() > 0);
    for (float f : extreme.vertices()) assertTrue(Float.isFinite(f));
  }

  @Test
  void meshByteLimitsCancellationAndInvalidNodesNeverReturnPartialGeometry() {
    MeshBuilder.Scalar field = (x, y, z) -> z - 3.257;
    var complete = mesh(8, 2, field);
    long bytes = 4L * complete.vertices().length;
    assertArrayEquals(
        complete.vertices(),
        SurfaceLodMesh.build(8, 2, field, 0, new CancellationToken(), bytes).vertices());
    assertThrows(
        IllegalStateException.class,
        () -> SurfaceLodMesh.build(8, 2, field, 0, new CancellationToken(), bytes - 1));
    var stopped = new CancellationToken();
    stopped.cancel();
    assertThrows(
        CancellationException.class, () -> SurfaceLodMesh.build(8, 2, field, 0, stopped, bytes));
    var checkpoints = new AtomicInteger();
    var mid = new CancellationToken(() -> checkpoints.incrementAndGet() > 100);
    assertThrows(
        CancellationException.class, () -> SurfaceLodMesh.build(8, 2, field, 0, mid, bytes));
    assertThrows(
        IllegalArgumentException.class,
        () -> mesh(8, 1, (x, y, z) -> x == 4 && y == 4 && z == 4 ? Double.NaN : 1));
    for (int level = 0; level <= 3; level++)
      for (double constant : new double[] {-1, 0, 1})
        assertEquals(0, mesh(8, level, (x, y, z) -> constant).vertexCount());
  }

  @Test
  void worstCaseBoundsDecreaseAfterLevelOneAndLegacyAdmissionStaysCompatible() {
    for (int n : new int[] {2, 4, 8, 32, 256}) {
      long previous = SurfaceLodMesh.maximumMeshBytes(n, 0);
      assertEquals(IsosurfaceMesh.maximumMeshBytes(n), previous);
      assertEquals(SceneChunk.surfaceWorkingBytes(n), SceneChunk.surfaceWorkingBytes(n, 0));
      for (int level = 1; level <= Integer.numberOfTrailingZeros(n); level++) {
        long current = SurfaceLodMesh.maximumMeshBytes(n, level);
        assertTrue(current <= previous);
        assertEquals(2016L * n * n * n / (1 << level), current);
        assertTrue(SceneChunk.surfaceWorkingBytes(n, level) >= current);
        previous = current;
      }
    }
    assertEquals(
        IsosurfaceMesh.workingBytes(3) + 2 * 576L * 27 + 65536, SceneChunk.surfaceWorkingBytes(3));
    for (int n : new int[] {0, 1, 3, 257})
      assertThrows(IllegalArgumentException.class, () -> SurfaceLodMesh.maximumMeshBytes(n, 1));
    assertThrows(IllegalArgumentException.class, () -> SurfaceLodMesh.maximumMeshBytes(8, 4));
    assertThrows(IllegalArgumentException.class, () -> SurfaceLodMesh.maximumMeshBytes(8, -1));
    var packed = PackedMesh.surface(mesh(8, 1, (x, y, z) -> z - 3.257), 8);
    assertEquals(0, new ChunkScene.Upload(1, new ChunkPos(0, 0, 0), 1, packed).level());
    assertThrows(
        IllegalArgumentException.class,
        () -> new ChunkScene.Upload(1, new ChunkPos(0, 0, 0), 1, packed, null, 9));
  }

  private static double area(Mesh mesh) {
    double sum = 0;
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 21) {
      double ax = v[i + 7] - (double) v[i],
          ay = v[i + 8] - (double) v[i + 1],
          az = v[i + 9] - (double) v[i + 2];
      double bx = v[i + 14] - (double) v[i],
          by = v[i + 15] - (double) v[i + 1],
          bz = v[i + 16] - (double) v[i + 2];
      sum += Math.hypot(Math.hypot(ay * bz - az * by, az * bx - ax * bz), ax * by - ay * bx) / 2;
    }
    return sum;
  }

  private static String point(float[] v, int at) {
    return Float.floatToIntBits(v[at])
        + ","
        + Float.floatToIntBits(v[at + 1])
        + ","
        + Float.floatToIntBits(v[at + 2]);
  }

  private static Set<String> faceSegments(Mesh mesh, int axis, int coordinate) {
    Set<String> result = new TreeSet<>();
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 21)
      for (int a = 0; a < 3; a++) {
        int p = i + a * 7, q = i + ((a + 1) % 3) * 7;
        if (v[p + axis] != coordinate || v[q + axis] != coordinate) continue;
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        String l = Float.floatToIntBits(v[p + u]) + "," + Float.floatToIntBits(v[p + w]);
        String r = Float.floatToIntBits(v[q + u]) + "," + Float.floatToIntBits(v[q + w]);
        if (!l.equals(r)) result.add(l.compareTo(r) < 0 ? l + "/" + r : r + "/" + l);
      }
    return result;
  }

  private static Set<String> packedFace(PackedMesh mesh, int axis, int coordinate) {
    Set<String> result = new TreeSet<>();
    ByteBuffer b = mesh.readOnly();
    for (int at = 0; at < mesh.byteSize(); at += 48)
      for (int a = 0; a < 3; a++) {
        int p = at + a * 16, q = at + ((a + 1) % 3) * 16;
        if (Short.toUnsignedInt(b.getShort(p + axis * 2)) != coordinate
            || Short.toUnsignedInt(b.getShort(q + axis * 2)) != coordinate) continue;
        int u = (axis + 1) % 3, w = (axis + 2) % 3;
        String l =
            Short.toUnsignedInt(b.getShort(p + u * 2))
                + ","
                + Short.toUnsignedInt(b.getShort(p + w * 2));
        String r =
            Short.toUnsignedInt(b.getShort(q + u * 2))
                + ","
                + Short.toUnsignedInt(b.getShort(q + w * 2));
        if (!l.equals(r)) result.add(l.compareTo(r) < 0 ? l + "/" + r : r + "/" + l);
      }
    return result;
  }
}
