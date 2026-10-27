package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IsosurfaceMeshTest {
  private Mesh mesh(int n, MeshBuilder.Scalar field, double threshold) {
    return IsosurfaceMesh.build(
        n, field, threshold, new CancellationToken(), IsosurfaceMesh.maximumMeshBytes(n));
  }

  @Test
  void affinePlaneHasAnalyticalAreaAndConsistentOutwardNormalsAndWinding() {
    var m = mesh(2, (x, y, z) -> x - .25, 0);
    assertEquals(4, area(m), 1e-7);
    var v = m.vertices();
    for (int i = 0; i < v.length; i += 7) {
      assertEquals(.25, v[i], 0);
      assertEquals(-1, v[i + 3], 0);
      assertEquals(0, v[i + 4], 0);
      assertEquals(0, v[i + 5], 0);
      assertEquals(128, v[i + 6]);
    }
    winding(m);
  }

  @Test
  void obliqueThresholdCornerPlaneHasTheIndependentUnitCubeTriangleArea() {
    var m = mesh(1, (x, y, z) -> x + y + z, 1);
    assertEquals(Math.sqrt(3) / 2, area(m), 1e-7);
    for (int i = 0; i < m.vertices().length; i += 7) {
      var v = m.vertices();
      assertEquals(1, v[i] + v[i + 1] + v[i + 2], 1e-7);
      for (int a = 3; a < 6; a++) assertEquals(-1 / Math.sqrt(3), v[i + a], 1e-6);
    }
    winding(m);
  }

  @Test
  void complementPreservesGeometryAndReversesEveryNormal() {
    var a = mesh(2, (x, y, z) -> x + .3 * y + .7 * z, .61);
    var b = mesh(2, (x, y, z) -> -x - .3 * y - .7 * z, -.61);
    assertEquals(points(a), points(b));
    assertEquals(area(a), area(b), 1e-7);
    for (int i = 0; i < a.vertices().length; i += 7) assertTrue(a.vertices()[i + 3] < 0);
    for (int i = 0; i < b.vertices().length; i += 7) assertTrue(b.vertices()[i + 3] > 0);
    winding(a);
    winding(b);
  }

  @Test
  void extremeFiniteDifferencesAndSubnormalsInterpolateWithoutOverflow() {
    for (double magnitude : new double[] {Double.MAX_VALUE, Double.MIN_NORMAL, Double.MIN_VALUE}) {
      var m = mesh(1, (x, y, z) -> x == 0 ? -magnitude : magnitude, 0);
      assertEquals(1, area(m), 1e-7);
      for (int i = 0; i < m.vertices().length; i += 7) {
        assertEquals(.5, m.vertices()[i], 0);
        for (int c = 0; c < 7; c++) assertTrue(Float.isFinite(m.vertices()[i + c]));
      }
    }
    var shifted =
        mesh(1, (x, y, z) -> x == 0 ? -Double.MAX_VALUE : Double.MAX_VALUE, Double.MAX_VALUE / 2);
    for (int i = 0; i < shifted.vertices().length; i += 7)
      assertEquals(.75, shifted.vertices()[i], 0);
  }

  @Test
  void adjacentNegativeAndPositiveOriginsHaveIdenticalSharedEdgeVertices() {
    MeshBuilder.Scalar f = (x, y, z) -> y + .37 * z + .23 * x - .8;
    var left = mesh(2, (x, y, z) -> f.sample(x - 2, y, z), 0);
    var right = mesh(2, f, 0);
    assertFalse(face(left, 2).isEmpty());
    assertEquals(face(left, 2), face(right, 0));
    winding(left);
    winding(right);
  }

  @Test
  void closedSphereHasTwoFacesPerEdgeAndOutwardUnitNormals() {
    var m =
        mesh(4, (x, y, z) -> 1.69 - (x - 2) * (x - 2) - (y - 2) * (y - 2) - (z - 2) * (z - 2), 0);
    var edges = new HashMap<String, Integer>();
    var v = m.vertices();
    for (int i = 0; i < v.length; i += 21)
      for (int j = 0; j < 3; j++) {
        int a = i + 7 * j, b = i + 7 * ((j + 1) % 3);
        var pa = point(v, a);
        var pb = point(v, b);
        String key = pa.compareTo(pb) < 0 ? pa + "|" + pb : pb + "|" + pa;
        edges.merge(key, 1, Integer::sum);
      }
    assertFalse(edges.isEmpty());
    assertTrue(edges.values().stream().allMatch(n -> n == 2));
    for (int i = 0; i < v.length; i += 7) {
      assertEquals(1, Math.hypot(Math.hypot(v[i + 3], v[i + 4]), v[i + 5]), 1e-6);
      assertTrue((v[i] - 2) * v[i + 3] + (v[i + 1] - 2) * v[i + 4] + (v[i + 2] - 2) * v[i + 5] > 0);
    }
    winding(m);
  }

  @Test
  void cornerSamplesAreEvaluatedExactlyOnceAndConstantFieldsHaveNoSyntheticCaps() {
    var count = new AtomicInteger();
    var empty =
        mesh(
            3,
            (x, y, z) -> {
              count.incrementAndGet();
              return 1;
            },
            0);
    assertEquals(64, count.get());
    assertEquals(0, empty.vertexCount());
    assertEquals(0, mesh(3, (x, y, z) -> -1, 0).vertexCount());
    assertEquals(0, mesh(3, (x, y, z) -> 0, 0).vertexCount());
  }

  @Test
  void invalidParametersAndNonfiniteSamplesRejectBeforeMeshPublication() {
    var calls = new AtomicInteger();
    MeshBuilder.Scalar field =
        (x, y, z) -> {
          calls.incrementAndGet();
          return 1;
        };
    for (int n : new int[] {0, -1, 257})
      assertThrows(IllegalArgumentException.class, () -> mesh(n, field, 0));
    for (double t : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> mesh(2, field, t));
    assertEquals(0, calls.get());
    for (double value :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
      var error =
          assertThrows(IllegalArgumentException.class, () -> mesh(2, (x, y, z) -> value, 0));
      assertTrue(error.getMessage().contains("0,0,0"));
    }
  }

  @Test
  void cancellationStopsSamplingAndExtractionWithoutMutatingPreviousMeshes() {
    var old = mesh(2, (x, y, z) -> x - .25, 0);
    var before = old.vertices().clone();
    var cancelled = new CancellationToken();
    cancelled.cancel();
    var calls = new AtomicInteger();
    assertThrows(
        CancellationException.class,
        () ->
            IsosurfaceMesh.build(
                8,
                (x, y, z) -> {
                  calls.incrementAndGet();
                  return x;
                },
                0,
                cancelled,
                100000));
    assertEquals(0, calls.get());
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          CancellationException.class,
          () ->
              mesh(
                  8,
                  (x, y, z) -> {
                    calls.incrementAndGet();
                    return x;
                  },
                  0));
      assertEquals(0, calls.get());
    } finally {
      Thread.interrupted();
    }
    final var sampling = new CancellationToken();
    assertThrows(
        CancellationException.class,
        () ->
            IsosurfaceMesh.build(
                8,
                (x, y, z) -> {
                  calls.incrementAndGet();
                  sampling.cancel();
                  return x;
                },
                0,
                sampling,
                100000));
    assertTrue(calls.get() <= 9);
    var samples = new AtomicInteger();
    var checks = new AtomicInteger();
    var extracting =
        new CancellationToken(() -> samples.get() == 729 && checks.incrementAndGet() >= 2);
    assertThrows(
        CancellationException.class,
        () ->
            IsosurfaceMesh.build(
                8,
                (x, y, z) -> {
                  samples.incrementAndGet();
                  return x - 3.25;
                },
                0,
                extracting,
                1000000));
    assertEquals(729, samples.get());
    assertArrayEquals(before, old.vertices());
  }

  @Test
  void outputBudgetHasAnExactInclusiveBoundaryAndWorkingBoundIncludesGridAndGrowth() {
    var m = mesh(2, (x, y, z) -> x - .25, 0);
    assertArrayEquals(
        m.vertices(),
        IsosurfaceMesh.build(2, (x, y, z) -> x - .25, 0, new CancellationToken(), m.bytes())
            .vertices());
    assertThrows(
        IllegalStateException.class,
        () ->
            IsosurfaceMesh.build(
                2, (x, y, z) -> x - .25, 0, new CancellationToken(), m.bytes() - 1));
    assertEquals(
        0, IsosurfaceMesh.build(2, (x, y, z) -> 1, 0, new CancellationToken(), 0).vertexCount());
    assertEquals(1008L * 8, IsosurfaceMesh.maximumMeshBytes(2));
    assertEquals(3 * 1008L * 8 + 8 * 27 + 65536, IsosurfaceMesh.workingBytes(2));
    assertEquals(
        3 * 1008L * 256 * 256 * 256 + 8 * 257L * 257 * 257 + 65536,
        IsosurfaceMesh.workingBytes(256));
  }

  private static double area(Mesh mesh) {
    double area = 0;
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 21) {
      double ux = v[i + 7] - (double) v[i],
          uy = v[i + 8] - (double) v[i + 1],
          uz = v[i + 9] - (double) v[i + 2];
      double vx = v[i + 14] - (double) v[i],
          vy = v[i + 15] - (double) v[i + 1],
          vz = v[i + 16] - (double) v[i + 2];
      area += Math.hypot(Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz), ux * vy - uy * vx) / 2;
    }
    return area;
  }

  private static void winding(Mesh mesh) {
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 21) {
      double ux = v[i + 7] - (double) v[i],
          uy = v[i + 8] - (double) v[i + 1],
          uz = v[i + 9] - (double) v[i + 2];
      double vx = v[i + 14] - (double) v[i],
          vy = v[i + 15] - (double) v[i + 1],
          vz = v[i + 16] - (double) v[i + 2];
      assertTrue(
          (uy * vz - uz * vy) * v[i + 3]
                  + (uz * vx - ux * vz) * v[i + 4]
                  + (ux * vy - uy * vx) * v[i + 5]
              > 0);
    }
  }

  private static String point(float[] v, int i) {
    return v[i] + "," + v[i + 1] + "," + v[i + 2];
  }

  private static Set<String> points(Mesh mesh) {
    var result = new TreeSet<String>();
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 7) result.add(point(v, i));
    return result;
  }

  private static Set<String> face(Mesh mesh, float x) {
    var result = new TreeSet<String>();
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 7) if (v[i] == x) result.add(v[i + 1] + "," + v[i + 2]);
    return result;
  }
}
