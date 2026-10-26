package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class NoiseImageNavigationTest {
  @Test
  void cursorWorldPointIsPreservedForEveryAnchorAndWheelDirection() {
    for (double u : new double[] {0, .125, .5, .91, 1})
      for (double v : new double[] {0, .3, .5, 1})
        for (double wheel : new double[] {-4, -1, .25, 1, 4}) {
          var before = new NoiseImageNavigation(-17.25, 31.5, .75);
          var after = before.zoom(u, v, wheel);
          assertEquals(
              before.x() + (u * 256 - 128) * before.step(),
              after.x() + (u * 256 - 128) * after.step(),
              1e-10);
          assertEquals(
              before.z() + (v * 256 - 128) * before.step(),
              after.z() + (v * 256 - 128) * after.step(),
              1e-10);
        }
  }

  @Test
  void panUsesImageExtentAndZoomAtCenterLeavesCenterUnchanged() {
    var before = new NoiseImageNavigation(10, 20, .5);
    var after = before.pan(.25, -.125);
    assertEquals(-22, after.x());
    assertEquals(36, after.z());
    assertEquals(.5, after.step());
    var zoom = before.zoom(.5, .5, 1);
    assertEquals(10, zoom.x());
    assertEquals(20, zoom.z());
    assertEquals(.5 * Math.exp(-.15), zoom.step());
  }

  @Test
  void zeroAndClampedGesturesDoNotRequestWorkAndLargeWheelsSaturate() {
    var p = new NoiseImageNavigation(0, 0, 1);
    assertSame(p, p.pan(0, -0.));
    assertSame(p, p.zoom(.2, .8, 0));
    var near = p.zoom(.2, .8, Double.MAX_VALUE);
    var far = p.zoom(.2, .8, -Double.MAX_VALUE);
    assertEquals(NoiseImageNavigation.MIN_STEP, near.step());
    assertEquals(NoiseImageNavigation.MAX_STEP, far.step());
    assertSame(near, near.zoom(.2, .8, 1));
    assertSame(far, far.zoom(.2, .8, -1));
  }

  @Test
  void rejectedGestureLeavesOriginalNavigationUsable() {
    var p = new NoiseImageNavigation(Double.MAX_VALUE, 0, 1);
    assertThrows(IllegalArgumentException.class, () -> p.pan(-Double.MAX_VALUE, 0));
    assertEquals(Double.MAX_VALUE, p.x());
    assertEquals(0, p.z());
    assertEquals(-256, p.pan(0, 1).z());
    assertTrue(Double.isFinite(p.zoom(.5, .5, 1).x()));
    for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> p.pan(bad, 0));
      assertThrows(IllegalArgumentException.class, () -> p.zoom(.5, .5, bad));
    }
    assertThrows(IllegalArgumentException.class, () -> p.zoom(-.1, .5, 1));
    assertThrows(IllegalArgumentException.class, () -> new NoiseImageNavigation(0, 0, 0));
  }

  @Test
  void inverseZoomAndPanRecoverOrdinaryPoseWithinRounding() {
    var p = new NoiseImageNavigation(123.5, -42.25, 1.25);
    var r = p.zoom(.23, .87, 2).zoom(.23, .87, -2).pan(.1, -.2).pan(-.1, .2);
    assertEquals(p.x(), r.x(), 1e-11);
    assertEquals(p.z(), r.z(), 1e-11);
    assertEquals(p.step(), r.step(), 1e-14);
  }
}
