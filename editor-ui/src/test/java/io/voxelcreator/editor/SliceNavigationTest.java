package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.SlicePlane;
import org.junit.jupiter.api.Test;

class SliceNavigationTest {
  @Test
  void everyPlaneAndExtentKeepsTheWheelAnchorAtTheSameWorldCoordinate() {
    for (var plane : SlicePlane.values())
      for (int side : new int[] {3, 8, 96, 256})
        for (double x : new double[] {0, .25, .5, 1})
          for (double y : new double[] {0, .75, 1}) {
            var before = new SliceNavigation(plane, -13, -7.25, 12.5, .75, side);
            var after = before.zoom(x, y, 2);
            assertEquals(
                before.u() + (x * side - side / 2.) * before.step(),
                after.u() + (x * side - side / 2.) * after.step(),
                1e-10);
            assertEquals(
                before.v() + (y * side - side / 2.) * before.step(),
                after.v() + (y * side - side / 2.) * after.step(),
                1e-10);
            assertEquals(plane, after.plane());
            assertEquals(-13, after.slice());
            assertEquals(side, after.side());
          }
  }

  @Test
  void panUsesTheCapturedExtentAndInverseGesturesRecoverThePose() {
    var before = new SliceNavigation(SlicePlane.Z, 9, 10, 20, .5, 8);
    var after = before.pan(.25, -.125);
    assertEquals(9, after.u());
    assertEquals(20.5, after.v());
    var restored = after.pan(-.25, .125).zoom(.2, .8, 2).zoom(.2, .8, -2);
    assertEquals(before.u(), restored.u(), 1e-12);
    assertEquals(before.v(), restored.v(), 1e-12);
    assertEquals(before.step(), restored.step(), 1e-14);
  }

  @Test
  void zeroAndSaturatedGesturesDoNotCreateAnotherNavigationRequest() {
    var before = new SliceNavigation(SlicePlane.X, 0, 0, 0, 1, 7);
    assertSame(before, before.pan(0, -0.));
    assertSame(before, before.zoom(.1, .9, 0));
    var near = before.zoom(.1, .9, Double.MAX_VALUE);
    var far = before.zoom(.1, .9, -Double.MAX_VALUE);
    assertEquals(.0001, near.step());
    assertEquals(10000, far.step());
    assertSame(near, near.zoom(.1, .9, 1));
    assertSame(far, far.zoom(.1, .9, -1));
  }

  @Test
  void invalidAndOverflowingGesturesCannotMutateTheOriginalPose() {
    var before = new SliceNavigation(SlicePlane.Y, 7, Double.MAX_VALUE, 0, 1, 8);
    assertThrows(IllegalArgumentException.class, () -> before.pan(-Double.MAX_VALUE, 0));
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> before.pan(bad, 0));
      assertThrows(IllegalArgumentException.class, () -> before.zoom(.5, .5, bad));
    }
    assertThrows(IllegalArgumentException.class, () -> before.zoom(-.1, .5, 1));
    assertThrows(
        IllegalArgumentException.class, () -> new SliceNavigation(SlicePlane.X, 0, 0, 0, 0, 8));
    assertEquals(Double.MAX_VALUE, before.u());
    assertEquals(0, before.v());
  }
}
