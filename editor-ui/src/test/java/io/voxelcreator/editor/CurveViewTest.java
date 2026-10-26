package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CurveViewTest {
  private static CurveView view() {
    var v = new CurveView();
    v.synchronize(-2, 2, -3, 3);
    return v;
  }

  @Test
  void cursorAnchorRemainsFixedAndInverseWheelRestoresTheView() {
    var v = view();
    double amount = Math.log(2) / .12;
    assertTrue(v.wheel(.25, .75, amount));
    assertEquals(2, v.zoom(), 1e-14);
    assertEquals(-1.5, v.bounds().minX(), 1e-14);
    assertEquals(.5, v.bounds().maxX(), 1e-14);
    assertEquals(-.75, v.bounds().minY(), 1e-14);
    assertEquals(2.25, v.bounds().maxY(), 1e-14);
    assertEquals(-1, v.bounds().x(.25), 1e-14);
    assertEquals(1.5, v.bounds().y(.75), 1e-14);
    v.wheel(.25, .75, -amount);
    assertEquals(-2, v.bounds().minX(), 1e-14);
    assertEquals(3, v.bounds().maxY(), 1e-14);
  }

  @Test
  void panRollbackIsExactAndCaptureRetainsTheOriginalBaseline() {
    var v = view();
    var original = v.bounds();
    v.beginPan();
    v.pan(-.25, .5);
    assertEquals(new CurveView.Bounds(-3, 1, 0, 6), v.bounds());
    v.beginPan();
    assertFalse(v.wheel(.5, .5, 1));
    v.pan(.25, -.25);
    v.cancelPan();
    assertSame(original, v.bounds());
    assertEquals(1, v.zoom());
    assertFalse(v.pan(1, 1));
    v.beginPan();
    v.pan(.5, 0);
    v.endPan();
    assertEquals(new CurveView.Bounds(0, 4, -3, 3), v.bounds());
  }

  @Test
  void domainChangesResetNavigationButRepeatedSynchronizationDoesNot() {
    var v = view();
    v.wheel(.2, .6, 2);
    var zoomed = v.bounds();
    v.synchronize(-2, 2, -3, 3);
    assertSame(zoomed, v.bounds());
    v.beginPan();
    v.synchronize(0, 4, 0, 8);
    assertEquals(1, v.zoom());
    assertEquals(new CurveView.Bounds(0, 4, 0, 8), v.bounds());
    v.cancelPan();
    assertEquals(new CurveView.Bounds(0, 4, 0, 8), v.bounds());
  }

  @Test
  void invalidOrUnrepresentableNavigationNeverPoisonsTheLastView() {
    var v = view();
    var original = v.bounds();
    assertFalse(v.wheel(Double.NaN, .5, 1));
    assertFalse(v.wheel(.5, .5, Double.POSITIVE_INFINITY));
    v.beginPan();
    assertFalse(v.pan(Double.NaN, 0));
    v.cancelPan();
    assertSame(original, v.bounds());
    v.synchronize(-8e307, 8e307, -1, 1);
    var wide = v.bounds();
    assertFalse(v.wheel(.5, .5, -Math.log(2) / .12));
    assertSame(wide, v.bounds());
    v.beginPan();
    assertFalse(v.pan(1, 0));
    v.cancelPan();
    assertSame(wide, v.bounds());
    assertThrows(
        IllegalArgumentException.class, () -> v.synchronize(0, Double.POSITIVE_INFINITY, 0, 1));
    assertSame(wide, v.bounds());
    v.synchronize(1, Math.nextUp(1.), 0, 1);
    var tiny = v.bounds();
    assertFalse(v.wheel(.5, .5, 100));
    assertSame(tiny, v.bounds());
  }

  @Test
  void centerAndFitChangeOnlyViewCoordinates() {
    var v = view();
    var original = v.bounds();
    assertTrue(v.centerAt(3, 4));
    assertEquals(new CurveView.Bounds(1, 5, 1, 7), v.bounds());
    assertFalse(v.centerAt(Double.NaN, 4));
    assertEquals(1, v.zoom());
    v.fit();
    assertSame(original, v.bounds());
  }

  @Test
  void zoomClampsAreStableAndDoNotDriftAtTheLimit() {
    var v = view();
    assertTrue(v.wheel(.5, .5, 1e308));
    assertEquals(100, v.zoom());
    var maximum = v.bounds();
    for (int i = 0; i < 100; i++) assertFalse(v.wheel(.5, .5, 1));
    assertSame(maximum, v.bounds());
    assertTrue(v.wheel(.5, .5, -1e308));
    assertEquals(.1, v.zoom());
    var minimum = v.bounds();
    for (int i = 0; i < 100; i++) assertFalse(v.wheel(.5, .5, -1));
    assertSame(minimum, v.bounds());
  }
}
