package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CancellationToken;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class RectangularMeshTest {
  @Test
  void shapeCountsAndLimitsUseAllAxesWithoutCubingTheLargest() {
    var extent = new GridExtent(3, 5, 7);
    assertEquals(105, extent.cells());
    assertEquals(192, extent.corners());
    assertEquals(7, extent.maximum());
    assertEquals(16_777_216, GridExtent.cube(256).cells());
    for (int bad : new int[] {0, -1, 257, Integer.MAX_VALUE}) {
      assertThrows(IllegalArgumentException.class, () -> new GridExtent(bad, 3, 5));
      assertThrows(IllegalArgumentException.class, () -> new GridExtent(3, bad, 5));
      assertThrows(IllegalArgumentException.class, () -> new GridExtent(3, 5, bad));
    }
  }

  @Test
  void solidCuboidsHaveSixCorrectlyWoundFacesAndAnalyticSurfaceArea() {
    for (var e :
        new GridExtent[] {
          new GridExtent(3, 5, 7), new GridExtent(1, 9, 2), new GridExtent(7, 3, 5)
        }) {
      var mesh =
          MeshBuilder.greedy(
              e,
              (x, y, z) -> {
                assertTrue(
                    x >= 0 && x < e.width() && y >= 0 && y < e.height() && z >= 0 && z < e.depth());
                return 1;
              });
      assertEquals(36, mesh.vertexCount());
      assertEquals(
          2. * (e.width() * e.height() + e.height() * e.depth() + e.width() * e.depth()),
          area(mesh),
          1e-9);
      var vertices = mesh.vertices();
      for (int i = 0; i < vertices.length; i += 7) {
        assertTrue(vertices[i] >= 0 && vertices[i] <= e.width());
        assertTrue(vertices[i + 1] >= 0 && vertices[i + 1] <= e.height());
        assertTrue(vertices[i + 2] >= 0 && vertices[i + 2] <= e.depth());
      }
    }
  }

  @Test
  void pointsVisitEachCellOnceInExistingYzxOrderAtExactCenters() {
    var e = new GridExtent(3, 5, 7);
    var visits = new AtomicInteger();
    var mesh =
        MeshBuilder.points(
            e,
            (x, y, z) -> {
              visits.incrementAndGet();
              return 2;
            });
    assertTrue(mesh.points());
    assertEquals(105, visits.get());
    assertEquals(105, mesh.vertexCount());
    for (int y = 0; y < 5; y++)
      for (int z = 0; z < 7; z++)
        for (int x = 0; x < 3; x++) {
          int i = ((y * 7 + z) * 3 + x) * 7;
          assertEquals(x + .5f, mesh.vertices()[i]);
          assertEquals(y + .5f, mesh.vertices()[i + 1]);
          assertEquals(z + .5f, mesh.vertices()[i + 2]);
          assertEquals(2, mesh.vertices()[i + 6]);
        }
  }

  @Test
  void cubeOverloadsKeepExactLegacyVertexOrderAndMaterials() {
    MeshBuilder.Cells cells = (x, y, z) -> (x + 3 * y + z) % 3 == 0 ? 1 : 0;
    assertArrayEquals(
        MeshBuilder.greedy(5, cells).vertices(),
        MeshBuilder.greedy(GridExtent.cube(5), cells).vertices());
    assertArrayEquals(
        MeshBuilder.points(5, cells).vertices(),
        MeshBuilder.points(GridExtent.cube(5), cells).vertices());
    assertArrayEquals(
        IsosurfaceMesh.build(5, (x, y, z) -> x - 2.25, 0, new CancellationToken(), 1L << 20)
            .vertices(),
        IsosurfaceMesh.build(
                GridExtent.cube(5), (x, y, z) -> x - 2.25, 0, new CancellationToken(), 1L << 20)
            .vertices());
  }

  @Test
  void rectangularIsosurfaceSamplesEveryCornerAndPreservesAnExactPlane() {
    var extent = new GridExtent(3, 5, 7);
    var count = new AtomicInteger();
    var mesh =
        IsosurfaceMesh.build(
            extent,
            (x, y, z) -> {
              count.incrementAndGet();
              return x - 1.25;
            },
            0,
            new CancellationToken(),
            IsosurfaceMesh.maximumMeshBytes(extent));
    assertEquals(192, count.get());
    assertEquals(35, area(mesh), 1e-9);
    assertTrue(mesh.vertexCount() > 0);
    for (int i = 0; i < mesh.vertices().length; i += 7) {
      assertEquals(1.25f, mesh.vertices()[i]);
      assertEquals(-1, mesh.vertices()[i + 3], 1e-6);
    }
    assertEquals(1008L * 105, IsosurfaceMesh.maximumMeshBytes(extent));
    assertEquals(3 * 1008L * 105 + 8 * 192 + 65536, IsosurfaceMesh.workingBytes(extent));
  }

  @Test
  void snappedBoxUsesEachAxisAndRejectsOnlyItsActualOverflow() {
    var box =
        VoxelRaycast.Bounds.snappedBox(new Vector3d(-1.25, 2.5, -3.25), new GridExtent(3, 5, 7));
    assertEquals(new io.voxelcreator.storage.BlockPos(-2, 2, -4), box.min());
    assertEquals(new io.voxelcreator.storage.BlockPos(0, 6, 2), box.max());
    assertTrue(box.contains(0, 6, 2));
    assertFalse(box.contains(1, 6, 2));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            VoxelRaycast.Bounds.box(
                new Vector3d(Integer.MAX_VALUE - 1., 0, 0), new GridExtent(3, 1, 1)));
    assertDoesNotThrow(
        () ->
            VoxelRaycast.Bounds.box(
                new Vector3d(Integer.MAX_VALUE - 1., 0, 0), new GridExtent(2, 7, 5)));
  }

  static double area(Mesh mesh) {
    double sum = 0;
    var v = mesh.vertices();
    for (int i = 0; i < v.length; i += 21) {
      var a = new Vector3d(v[i + 7] - v[i], v[i + 8] - v[i + 1], v[i + 9] - v[i + 2]);
      var b = new Vector3d(v[i + 14] - v[i], v[i + 15] - v[i + 1], v[i + 16] - v[i + 2]);
      var normal = a.cross(b);
      assertTrue(normal.dot(new Vector3d(v[i + 3], v[i + 4], v[i + 5])) > 0);
      sum += normal.length() / 2;
    }
    return sum;
  }
}
