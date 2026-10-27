package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.Camera;
import org.junit.jupiter.api.Test;

class OrbitGestureTest {
  private static Camera camera() {
    var c = new Camera();
    c.focus.set(1, 2, 3);
    c.yaw = c.pitch = 0;
    c.distance = 20;
    return c;
  }

  @Test
  void orbitUsesTotalDisplacementAndIsIndependentOfIntermediateFrames() {
    var a = camera();
    var b = camera();
    var one = new OrbitGesture();
    var many = new OrbitGesture();
    one.begin(a, 0, false, 100, 100, 0);
    many.begin(b, 0, false, 100, 100, 0);
    many.move(b, 300, 400);
    many.move(b, -200, -900);
    one.move(a, 125, 90);
    many.move(b, 125, 90);
    assertEquals(-.2, a.yaw, 1e-14);
    assertEquals(-.08, a.pitch, 1e-14);
    assertEquals(a.yaw, b.yaw);
    assertEquals(a.pitch, b.pitch);
    assertEquals(a.focus, b.focus);
  }

  @Test
  void rightPanAndReleaseUseOriginalOrientationAndTheFinalPointerDelta() {
    var c = camera();
    c.yaw = Math.PI / 2;
    var g = new OrbitGesture();
    g.begin(c, 1, false, 10, 20, 0);
    g.move(c, 20, 30);
    assertEquals(OrbitGesture.Click.NONE, g.release(c, 30, 10, .1, true));
    assertEquals(1.3, c.focus.x, 1e-14);
    assertEquals(2, c.focus.y);
    assertEquals(3.6, c.focus.z, 1e-14);
    assertFalse(g.active());
  }

  @Test
  void middleDragLocksItsWorldPlaneAtPressTime() {
    var plane = camera();
    var vertical = camera();
    var a = new OrbitGesture();
    var b = new OrbitGesture();
    a.begin(plane, 2, false, 0, 0, 0);
    b.begin(vertical, 2, true, 0, 0, 0);
    a.move(plane, 25, -50);
    b.move(vertical, 25, -50);
    assertEquals(0, plane.focus.x);
    assertEquals(2, plane.focus.y);
    assertEquals(5, plane.focus.z);
    assertEquals(1, vertical.focus.x);
    assertEquals(4, vertical.focus.y);
    assertEquals(3, vertical.focus.z);
  }

  @Test
  void shortClicksHaveInclusiveMovementAndTimeBoundsAndRequireReleaseInside() {
    for (int button : new int[] {0, 1}) {
      var c = camera();
      var g = new OrbitGesture();
      g.begin(c, button, false, 0, 0, 0);
      assertEquals(
          button == 0 ? OrbitGesture.Click.PICK : OrbitGesture.Click.CONTEXT,
          g.release(c, 3, 0, .35, true));
      g.begin(c, button, false, 0, 0, 0);
      assertEquals(OrbitGesture.Click.NONE, g.release(c, 0, 0, Math.nextUp(.35), true));
      g.begin(c, button, false, 0, 0, 0);
      assertEquals(OrbitGesture.Click.NONE, g.release(c, 0, 0, .1, false));
    }
  }

  @Test
  void dragClassificationIsStickyAfterReturningToItsPressPoint() {
    for (int button : new int[] {0, 1}) {
      var c = camera();
      var g = new OrbitGesture();
      g.begin(c, button, false, 0, 0, 0);
      g.move(c, 4, 0);
      assertEquals(OrbitGesture.Click.NONE, g.release(c, 0, 0, .1, true));
      assertEquals(0, c.yaw);
      assertEquals(1, c.focus.x);
    }
  }

  @Test
  void escapeRestoresTheOriginalPoseAndHeldMovementCannotResume() {
    var c = camera();
    var g = new OrbitGesture();
    g.begin(c, 0, false, 0, 0, 0);
    g.move(c, 80, 40);
    g.escape(c);
    g.move(c, 200, 300);
    assertEquals(0, c.yaw);
    assertEquals(0, c.pitch);
    assertEquals(OrbitGesture.Click.NONE, g.release(c, 0, 0, .1, true));
  }

  @Test
  void externalNavigationAndResetKeepTheLatestPoseAndCancelReleaseActions() {
    var c = camera();
    var g = new OrbitGesture();
    g.begin(c, 1, false, 0, 0, 0);
    g.move(c, 20, 10);
    c.teleport(40, 50, 60);
    g.move(c, 100, 100);
    assertFalse(g.active());
    assertEquals(40, c.focus.x);
    assertEquals(50, c.focus.y);
    assertEquals(60, c.focus.z);
    g.begin(c, 1, false, 0, 0, 0);
    g.move(c, 20, 10);
    var retained = new org.joml.Vector3d(c.focus);
    g.reset();
    assertEquals(OrbitGesture.Click.NONE, g.release(c, 0, 0, .1, true));
    assertEquals(retained, c.focus);
  }

  @Test
  void controlLeftAndFlyingCamerasCannotStartAnOrbitGesture() {
    var c = camera();
    var g = new OrbitGesture();
    g.begin(c, 0, true, 0, 0, 0);
    assertFalse(g.active());
    c.enterFly();
    for (int button = 0; button < 3; button++) {
      g.begin(c, button, false, 0, 0, 0);
      assertFalse(g.active());
    }
  }
}
