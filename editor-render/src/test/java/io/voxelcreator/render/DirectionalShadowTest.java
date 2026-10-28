package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class DirectionalShadowTest {
  @Test
  void depthBudgetLeavesGeometryCapacityAndLowersOnlyEffectiveResolution() {
    assertEquals(1 << 20, DirectionalShadow.bytes(512));
    assertEquals(4 << 20, DirectionalShadow.bytes(1024));
    assertEquals(16 << 20, DirectionalShadow.bytes(2048));
    assertEquals(0, DirectionalShadow.resolution(2048, (2 << 20) - 1));
    assertEquals(512, DirectionalShadow.resolution(2048, 2 << 20));
    assertEquals(1024, DirectionalShadow.resolution(2048, 8 << 20));
    assertEquals(2048, DirectionalShadow.resolution(2048, 32 << 20));
    assertEquals(4 << 20, DirectionalShadow.arenaBytes(8 << 20, true, 2048));
    assertEquals(8 << 20, DirectionalShadow.arenaBytes(8 << 20, false, 2048));
    for (int budget = 0; budget <= 256 << 20; budget += 65536) {
      int r = DirectionalShadow.resolution(2048, budget);
      assertTrue(DirectionalShadow.bytes(r) <= budget / 2);
      assertEquals(
          budget, DirectionalShadow.arenaBytes(budget, true, 2048) + DirectionalShadow.bytes(r));
    }
  }

  @Test
  void malformedResolutionAndBoundsRejectBeforeNativeAllocation() {
    for (int value : new int[] {-1, 1, 256, 513, 4096, Integer.MAX_VALUE})
      assertThrows(IllegalArgumentException.class, () -> DirectionalShadow.bytes(value));
    assertThrows(
        IllegalArgumentException.class, () -> DirectionalShadow.arenaBytes(-1, true, 1024));
    assertThrows(
        IllegalArgumentException.class, () -> new DirectionalShadow.Bounds(1, 0, 0, 0, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectionalShadow.Bounds(Double.NaN, 0, 0, 1, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectionalShadow.Bounds(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectionalShadow.Bounds(0, 0, 0, 10_000_001, 1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> new DirectionalShadow.Bounds(1e100, 0, 0, 1e100, 1, 1));
  }

  @Test
  void immutableGeometryBoundsIgnorePointsAndHandleEmptyOrNonFiniteStreams() {
    assertNull(DirectionalShadow.bounds(new Mesh(new float[0], false)));
    assertNull(DirectionalShadow.bounds(new Mesh(new float[21], true)));
    assertNull(
        DirectionalShadow.bounds(new Mesh(new float[] {Float.NaN, 0, 0, 0, 1, 0, 1}, false)));
    var mesh = MeshBuilder.greedy(256, (x, y, z) -> 1);
    var bounds = DirectionalShadow.bounds(mesh);
    assertEquals(new DirectionalShadow.Bounds(0, 0, 0, 256, 256, 256), bounds);
    assertEquals(
        new DirectionalShadow.Bounds(-2, -3, -4, 256, 256, 256),
        bounds.union(new DirectionalShadow.Bounds(-2, -3, -4, 0, 0, 0)));
  }

  @Test
  void allEightCasterCornersFitLightFrustumIncludingFlatAndSignedRegions() {
    for (var bounds :
        new DirectionalShadow.Bounds[] {
          new DirectionalShadow.Bounds(0, 0, 0, 256, 256, 256),
          new DirectionalShadow.Bounds(-64, 2, -32, 32, 2, 64),
          new DirectionalShadow.Bounds(-4_000_000, -100, -4_000_000, -3_999_968, -68, -3_999_968),
          new DirectionalShadow.Bounds(0, 0, 0, 0, 0, 0)
        })
      for (int resolution : new int[] {512, 1024, 2048}) {
        var m = DirectionalShadow.projection(bounds, resolution).matrix();
        for (double x : new double[] {bounds.minX(), bounds.maxX()})
          for (double y : new double[] {bounds.minY(), bounds.maxY()})
            for (double z : new double[] {bounds.minZ(), bounds.maxZ()}) {
              var p = m.transformProject(new Vector3f((float) x, (float) y, (float) z));
              assertTrue(Float.isFinite(p.x) && Float.isFinite(p.y) && Float.isFinite(p.z));
              assertTrue(
                  Math.abs(p.x) <= 1.01 && Math.abs(p.y) <= 1.01 && Math.abs(p.z) <= 1.01,
                  p.toString());
            }
      }
  }

  @Test
  void pointsAlongTheLightRayShareTexelsAndHigherCasterWinsDepth() {
    var plan =
        DirectionalShadow.projection(new DirectionalShadow.Bounds(-32, -32, -32, 64, 64, 64), 1024);
    var ground = plan.matrix().transformProject(new Vector3f(10, 1, 10));
    var caster = plan.matrix().transformProject(new Vector3f(12, 6, 11.5f));
    assertEquals(ground.x, caster.x, 1e-6);
    assertEquals(ground.y, caster.y, 1e-6);
    assertTrue(caster.z < ground.z);
    double rayLength = Math.sqrt(2 * 2 + 5 * 5 + 1.5 * 1.5);
    // The projection spans three bounding radii along depth: twice clip-space delta is linear.
    double radius = plan.worldUnitsPerTexel() * 1024 / 2;
    assertEquals(2 * rayLength / (3 * radius), ground.z - caster.z, 1e-6);
  }

  @Test
  void matricesAreOwnedAndRepeatedFitsAreDeterministic() {
    var b = new DirectionalShadow.Bounds(-16, -16, -16, 16, 16, 16);
    var plan = DirectionalShadow.projection(b, 512);
    var original = plan.matrix().get(new float[16]);
    plan.matrix().zero();
    assertArrayEquals(original, plan.matrix().get(new float[16]));
    assertArrayEquals(original, DirectionalShadow.projection(b, 512).matrix().get(new float[16]));
    var input = new Matrix4f();
    var owned = new DirectionalShadow.Projection(input, 0, 1, b);
    input.zero();
    assertEquals(new Matrix4f(), owned.matrix());
  }

  @Test
  void subTexelTravelSnapsWithoutChangingFittedScale() {
    var b = new DirectionalShadow.Bounds(-16, -16, -16, 16, 16, 16);
    var a = DirectionalShadow.projection(b, 1024);
    double d = a.worldUnitsPerTexel() * .1;
    var right = new Vector3d(.6, 0, -.8).mul(d);
    var moved =
        new DirectionalShadow.Bounds(
            -16 + right.x, -16, -16 + right.z, 16 + right.x, 16, 16 + right.z);
    var c = DirectionalShadow.projection(moved, 1024);
    assertEquals(a.worldUnitsPerTexel(), c.worldUnitsPerTexel(), 1e-12);
    assertArrayEquals(a.matrix().get(new float[16]), c.matrix().get(new float[16]), 1e-7f);
  }

  @Test
  void qualityChangesTexelFootprintAndBiasWithoutChangingLightRayOrdering() {
    var b = new DirectionalShadow.Bounds(-1, 0, -1, 1, 1, 1);
    var low = DirectionalShadow.projection(b, 512);
    var high = DirectionalShadow.projection(b, 2048);
    assertEquals(low.worldUnitsPerTexel() / 4, high.worldUnitsPerTexel());
    assertEquals(low.bias() / 4, high.bias());
    assertEquals(b, high.bounds());
  }
}
