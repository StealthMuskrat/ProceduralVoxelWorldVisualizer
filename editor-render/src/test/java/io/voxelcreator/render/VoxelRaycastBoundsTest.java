package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class VoxelRaycastBoundsTest {
  private static Camera.Ray ray(double x, double y, double z, double dx, double dy, double dz) {
    return new Camera.Ray(new Vector3d(x, y, z), new Vector3d(dx, dy, dz));
  }

  @Test
  void fixedSolidWorldPicksDisplayedCubeInsteadOfOccupiedCameraCell() {
    var box = VoxelRaycast.Bounds.cube(new Vector3d(-8, -8, -8), 16);
    var calls = new AtomicInteger();
    var cells =
        box.restrict(
            (x, y, z) -> {
              assertTrue(box.contains(x, y, z));
              calls.incrementAndGet();
              return 1;
            });
    assertEquals(
        new BlockPos(7, 0, 0),
        VoxelRaycast.firstOccupied(ray(20.5, .5, .5, -1, 0, 0), 40, cells).orElseThrow());
    assertEquals(
        new BlockPos(-8, 0, 0),
        VoxelRaycast.firstOccupied(ray(-20.5, .5, .5, 1, 0, 0), 40, cells).orElseThrow());
    assertEquals(2, calls.get());
  }

  @Test
  void missesNeverEvaluateOutOfRegionAndInsideStartsAtCurrentCell() {
    var box = VoxelRaycast.Bounds.cube(new Vector3d(-2, -2, -2), 4);
    var calls = new AtomicInteger();
    var cells =
        box.restrict(
            (x, y, z) -> {
              calls.incrementAndGet();
              return 1;
            });
    assertTrue(VoxelRaycast.firstOccupied(ray(5, 5, 5, 1, 0, 0), 20, cells).isEmpty());
    assertEquals(0, calls.get());
    assertEquals(
        new BlockPos(-1, -1, -1),
        VoxelRaycast.firstOccupied(ray(-.5, -.5, -.5, 1, 0, 0), 0, cells).orElseThrow());
  }

  @Test
  void finiteDirectionScaleDoesNotOverflowOrUnderflowNormalization() {
    for (double scale : new double[] {Double.MIN_VALUE, 1, Double.MAX_VALUE}) {
      assertEquals(
          new BlockPos(2, 2, 2),
          VoxelRaycast.firstOccupied(
                  ray(.5, .5, .5, scale, scale, scale),
                  4,
                  (x, y, z) -> x == 2 && y == 2 && z == 2 ? 1 : 0)
              .orElseThrow());
      assertTrue(
          VoxelRaycast.firstOccupied(ray(.5, .5, .5, scale, 0, 0), 1, (x, y, z) -> x == 2 ? 1 : 0)
              .isEmpty());
    }
  }

  @Test
  void originsOutsideIntegerCellsRejectBeforeQuerying() {
    for (double value :
        new double[] {
          (double) Integer.MAX_VALUE + 1, (double) Integer.MIN_VALUE - 1, 1e300, -1e300
        })
      assertThrows(
          IllegalArgumentException.class,
          () ->
              VoxelRaycast.firstOccupied(
                  ray(value, 0, 0, 1, 0, 0),
                  2,
                  (x, y, z) -> {
                    fail("Out-of-domain query");
                    return 0;
                  }));
  }

  @Test
  void traversalStopsAtIntegerBoundaryWithoutWrapping() {
    for (int edge : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
      var calls = new AtomicInteger();
      int direction = edge < 0 ? -1 : 1;
      assertTrue(
          VoxelRaycast.firstOccupied(
                  ray(edge + .5, .5, .5, direction, 0, 0),
                  10,
                  (x, y, z) -> {
                    assertEquals(edge, x);
                    calls.incrementAndGet();
                    return 0;
                  })
              .isEmpty());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void beforeAfterAndRemovalGhostPredicatesStayWithinDisplayedRegion() {
    var box = VoxelRaycast.Bounds.cube(new Vector3d(0, 0, 0), 4);
    var ray = ray(-5, .5, .5, 1, 0, 0);
    assertEquals(
        new BlockPos(0, 0, 0),
        VoxelRaycast.firstOccupied(ray, 20, box.restrict((x, y, z) -> x == 0 ? 2 : 0))
            .orElseThrow());
    assertEquals(
        new BlockPos(2, 0, 0),
        VoxelRaycast.firstOccupied(ray, 20, box.restrict((x, y, z) -> x == 2 ? 3 : 0))
            .orElseThrow());
    assertEquals(
        new BlockPos(0, 0, 0),
        VoxelRaycast.firstOccupied(ray, 20, box.restrict((x, y, z) -> x == 0 || x == 2 ? 1 : 0))
            .orElseThrow());
  }

  @Test
  void cubeBoundsValidateShapeAndInclusiveMaximumWithoutIntegerOverflow() {
    assertEquals(
        Integer.MAX_VALUE,
        VoxelRaycast.Bounds.cube(new Vector3d(Integer.MAX_VALUE - 15, 0, 0), 16).max().x());
    assertThrows(
        IllegalArgumentException.class,
        () -> VoxelRaycast.Bounds.cube(new Vector3d(Integer.MAX_VALUE, 0, 0), 2));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelRaycast.Bounds.cube(new Vector3d(.5, 0, 0), 4));
    assertThrows(
        IllegalArgumentException.class, () -> VoxelRaycast.Bounds.cube(new Vector3d(), 257));
    assertThrows(
        IllegalArgumentException.class,
        () -> new VoxelRaycast.Bounds(new BlockPos(1, 0, 0), new BlockPos(0, 0, 0)));
  }
}
