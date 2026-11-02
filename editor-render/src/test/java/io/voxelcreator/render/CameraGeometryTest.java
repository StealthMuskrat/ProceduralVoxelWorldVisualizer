package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class CameraGeometryTest {
  private static Camera camera() {
    var c = new Camera();
    c.focus.set(1, 2, 3);
    c.yaw = c.pitch = 0;
    c.distance = 20;
    return c;
  }

  @Test
  void levelPanUsesScreenHorizontalAndVerticalRatherThanTheGroundPlane() {
    var c = camera();
    c.pan(20, -10);
    assertEquals(.4, c.focus.x, 1e-14);
    assertEquals(1.7, c.focus.y, 1e-14);
    assertEquals(3, c.focus.z);
  }

  @Test
  void pitchedPanIsOrthogonalToViewAndTranslatesEyeAndFocusTogether() {
    for (double yaw : new double[] {0, Math.PI / 2, -1.1})
      for (double pitch : new double[] {-1.5, -.7, 0, .6, 1.5}) {
        var c = camera();
        c.yaw = yaw;
        c.pitch = pitch;
        var focus = new Vector3d(c.focus);
        var eye = c.eye();
        var backward = new Vector3d(eye).sub(focus).normalize();
        c.pan(7, -11);
        var delta = new Vector3d(c.focus).sub(focus);
        assertEquals(0, delta.dot(backward), 1e-14);
        assertEquals(Math.hypot(7, 11) * 20 * .0015, delta.length(), 1e-14);
        assertEquals(0, c.eye().sub(eye).distance(delta), 1e-14);
        assertEquals(yaw, c.yaw);
        assertEquals(pitch, c.pitch);
        assertEquals(20, c.distance);
      }
  }

  @Test
  void panSensitivityUsesDistanceAndOppositeDragsRestoreThePose() {
    for (double distance : new double[] {.25, 20, 100000}) {
      var c = camera();
      c.yaw = -.9;
      c.pitch = 1.2;
      c.distance = distance;
      var focus = new Vector3d(c.focus);
      c.pan(13, -17);
      assertEquals(Math.hypot(13, 17) * distance * .0015, c.focus.distance(focus), 1e-11);
      c.pan(-13, 17);
      assertEquals(0, c.focus.distance(focus), 1e-11);
    }
  }

  @Test
  void centerRayPointsAtPivotAndStartsOnTheNearPlane() {
    var c = camera();
    var ray = c.ray(0, 0, 1, new Vector3d());
    assertEquals(new Vector3d(0, 0, -1), ray.direction());
    assertEquals(1, ray.origin().x);
    assertEquals(2, ray.origin().y);
    assertEquals(23 - (double) .05f, ray.origin().z);
    assertEquals(1, ray.direction().length(), 1e-15);
  }

  @Test
  void offAxisRayUsesAspectAndCameraUpAndStaysOnTheNearPlane() {
    var c = camera();
    c.yaw = Math.PI / 2;
    c.pitch = Math.PI / 6;
    double tangent = Math.tan((float) Math.toRadians(50) / 2.);
    var ray = c.ray(.5f, -.25f, 2, new Vector3d());
    var forward = new Vector3d(-Math.sqrt(3) / 2, -.5, 0);
    var offset =
        new Vector3d(forward).add(.125 * tangent, -Math.sqrt(3) * .125 * tangent, -tangent);
    assertEquals(0, new Vector3d(offset).normalize().distance(ray.direction()), 1e-15);
    assertEquals(0, c.eye().fma(.05f, offset).distance(ray.origin()), 1e-14);
    assertEquals(.05f, ray.origin().sub(c.eye(), new Vector3d()).dot(forward), 1e-14);
  }

  @Test
  void renderOriginCannotChangeTheWorldRayIncludingSignedCoordinateExtremes() {
    for (double offset : new double[] {0, 2_000_000_000, -2_000_000_000}) {
      var c = camera();
      c.focus.set(offset + .5, 64.5, -offset + .5);
      c.yaw = -.8;
      c.pitch = .6;
      var first = c.ray(.375f, -.625f, 1.75f, new Vector3d(offset, 64, -offset));
      for (var origin : new Vector3d[] {new Vector3d(), new Vector3d(1e12, -1e12, 1e12)}) {
        var ray = c.ray(.375f, -.625f, 1.75f, origin);
        assertEquals(first.origin(), ray.origin());
        assertEquals(first.direction(), ray.direction());
      }
    }
  }

  @Test
  void freeFlyAndOrbitUseIdenticalRayGeometryAtTheSameEyeAndOrientation() {
    var c = camera();
    c.yaw = .7;
    c.pitch = -.8;
    var orbit = c.ray(-.5f, .25f, 1.5f, new Vector3d());
    c.enterFly();
    var fly = c.ray(-.5f, .25f, 1.5f, new Vector3d());
    assertEquals(orbit, fly);
    c.teleport(2_000_000_000.5, -17.25, -2_000_000_000.5);
    assertEquals(fly.direction(), c.ray(-.5f, .25f, 1.5f, new Vector3d()).direction());
  }

  @Test
  void analyticalRaysProjectBackToTheRenderedScreenWithoutFarPlaneInversion() {
    for (double yaw : new double[] {0, .7, -1.1})
      for (double pitch : new double[] {-.9, 0, 1.2}) {
        var c = camera();
        c.focus.set(1.25, -2.75, 3.5);
        c.yaw = yaw;
        c.pitch = pitch;
        var origin = new Vector3d(-100, 10, 200);
        for (float nx : new float[] {-.75f, 0, .5f})
          for (float ny : new float[] {-.5f, 0, .75f}) {
            var ray = c.ray(nx, ny, 1.5f, origin);
            var point = new Vector3d(ray.origin()).fma(100, ray.direction()).sub(origin);
            var projected =
                new Vector3f((float) point.x, (float) point.y, (float) point.z)
                    .mulProject(c.matrix(1.5f, origin));
            assertEquals(nx, projected.x, 2e-5);
            assertEquals(ny, projected.y, 2e-5);
          }
      }
  }

  @Test
  void nearPlaneSkipsClippedCellsAndLargeSignedRaysKeepExactVoxelSelection() {
    var c = camera();
    c.flying = true;
    c.position.set(.5, .5, 1.02);
    var ray = c.ray(0, 0, 1, new Vector3d());
    assertEquals(.97, ray.origin().z, 1e-8);
    assertTrue(VoxelRaycast.firstOccupied(ray, 10, (x, y, z) -> z == 1 ? 1 : 0).isEmpty());
    for (int offset : new int[] {0, 2_000_000_000, -2_000_000_000}) {
      c.position.set(offset + .5, .5, (double) offset + 10.5);
      var hit =
          VoxelRaycast.firstOccupied(
                  c.ray(0, 0, 1, new Vector3d()),
                  20,
                  (x, y, z) -> x == offset && y == 0 && z == offset ? 1 : 0)
              .orElseThrow();
      assertEquals(new io.voxelcreator.storage.BlockPos(offset, 0, offset), hit);
    }
  }
}
