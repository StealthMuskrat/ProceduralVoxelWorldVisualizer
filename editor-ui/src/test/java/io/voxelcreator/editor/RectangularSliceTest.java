package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.*;
import org.junit.jupiter.api.Test;

class RectangularSliceTest {
  @Test
  void eachPlaneProjectsTheCorrectTwoAxesAndCountsOnlySampledPixels() {
    var extent = new GridExtent(3, 5, 7);
    int[][] expected = {{5, 7}, {3, 7}, {3, 5}};
    for (var plane : SlicePlane.values()) {
      int[] e = expected[plane.ordinal()];
      assertEquals(e[0], plane.width(extent));
      assertEquals(e[1], plane.height(extent));
      assertEquals(e[0] * e[1], plane.imageExtent(extent).cells());
      assertEquals(1, plane.imageExtent(extent).height());
      var otherNormal =
          switch (plane) {
            case X -> new GridExtent(17, 5, 7);
            case Y -> new GridExtent(3, 17, 7);
            case Z -> new GridExtent(3, 5, 17);
          };
      assertEquals(plane.imageExtent(extent), plane.imageExtent(otherNormal));
    }
  }

  @Test
  void panUsesIndependentHorizontalAndVerticalWorldSpans() {
    var before = new SliceNavigation(SlicePlane.Y, -3, -7.25, 5.75, .75, 3, 7);
    var after = before.pan(.25, -.5);
    assertEquals(-7.8125, after.u());
    assertEquals(8.375, after.v());
    assertEquals(before, after.pan(-.25, .5));
    assertEquals(3, after.width());
    assertEquals(7, after.height());
  }

  @Test
  void everyRectangularZoomAnchorRemainsAtTheSameWorldPosition() {
    for (var plane : SlicePlane.values())
      for (double x : new double[] {0, .25, .5, 1})
        for (double y : new double[] {0, .75, 1}) {
          var before = new SliceNavigation(plane, 11, -7.25, 12.5, .75, 3, 7);
          var after = before.zoom(x, y, 2);
          assertEquals(
              before.u() + (x * 3 - 1.5) * before.step(),
              after.u() + (x * 3 - 1.5) * after.step(),
              1e-12);
          assertEquals(
              before.v() + (y * 7 - 3.5) * before.step(),
              after.v() + (y * 7 - 3.5) * after.step(),
              1e-12);
          assertEquals(3, after.width());
          assertEquals(7, after.height());
        }
  }

  @Test
  void acceptedRectangularImageRetainsItsGeometryAndSamplerDuringAnotherRequest() {
    var accepted =
        new SliceImageView(
            SlicePlane.Z, -3, -8.375, 10.625, .75, 3, 5, (x, y, z) -> x + 10 * y + 100 * z);
    var point = accepted.at(.25, .75);
    assertEquals(new SliceImageView.Point(-7.8125, 13.4375, -3), point);
    assertEquals(-173.4375, accepted.sample(.25, .75).raw());
    var requested = new SliceNavigation(SlicePlane.X, 9, 10, -20, 2, 4, 2);
    assertNotEquals(requested.plane(), accepted.plane());
    assertEquals(point, accepted.at(.25, .75));
    assertEquals(new SliceImageView.Point(-6.875, 13.625, -3), accepted.at(2. / 3, 4. / 5));
  }

  @Test
  void squareConstructorsPreserveTheirOriginalNavigationAndImageContracts() {
    var before = new SliceNavigation(SlicePlane.X, -3, 1, 2, .75, 8);
    assertEquals(new SliceNavigation(SlicePlane.X, -3, 1, 2, .75, 8, 8), before);
    var image = new SliceImageView(SlicePlane.X, -3, 1, 2, .75, 8, (x, y, z) -> x + y + z);
    assertEquals(8, image.width());
    assertEquals(8, image.height());
    assertEquals(8, image.side());
    assertEquals(new SliceImageView.Point(-3, 4, 5), image.at(.5, .5));
  }

  @Test
  void eitherDimensionParticipatesInValidationAndGestureIdentity() {
    var n = new SliceNavigation(SlicePlane.Y, 0, 0, 0, 1, 3, 7);
    assertNotEquals(n, new SliceNavigation(SlicePlane.Y, 0, 0, 0, 1, 3, 5));
    for (int bad : new int[] {-1, 0, 257}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> new SliceNavigation(SlicePlane.Y, 0, 0, 0, 1, bad, 7));
      assertThrows(
          IllegalArgumentException.class,
          () -> new SliceNavigation(SlicePlane.Y, 0, 0, 0, 1, 3, bad));
      assertThrows(
          IllegalArgumentException.class,
          () -> new SliceImageView(SlicePlane.Y, 0, 0, 0, 1, 3, bad, (x, y, z) -> 0));
    }
  }

  @Test
  void sliceAdmissionUsesThePlaneAreaAndImageWorkingBytes() {
    for (var plane : SlicePlane.values()) {
      var image = plane.imageExtent(new GridExtent(3, 5, 7));
      var admission = new PreviewAdmission();
      long pixels = image.cells(), bytes = pixels * 64;
      assertTrue(admission.check(1, image, "Slices", pixels, 7, bytes).allowed());
      assertEquals(bytes, admission.check(1, image, "Slices", pixels, 7, bytes).workingBytes());
      assertFalse(admission.check(1, image, "Slices", pixels - 1, 7, bytes).allowed());
      admission.changed();
      assertFalse(admission.check(1, image, "Slices", pixels, 7, bytes - 1).allowed());
    }
  }
}
