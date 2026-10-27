package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.render.SlicePlane;
import org.junit.jupiter.api.Test;

class SliceImageViewTest {
  @Test
  void acceptedPlaneOriginExtentAndSamplerDetermineHoverIndependentlyOfLaterRequests() {
    var x =
        new SliceImageView(
            SlicePlane.X, -3, -10.25, 9.5, .75, 8, (a, b, c) -> a + 10 * b + 100 * c);
    var point = x.at(.25, .75);
    assertEquals(new SliceImageView.Point(-3, -8.75, 14), point);
    assertEquals(1309.5, x.sample(.25, .75).raw());
    var pending = new SliceNavigation(SlicePlane.Z, 99, 20, 30, 5, 64);
    assertEquals(SlicePlane.Z, pending.plane());
    assertEquals(point, x.at(.25, .75));
    assertEquals(8, x.side());
    assertEquals(-3, x.slice());
  }

  @Test
  void allThreeMappingsUseTheirDeclaredAxesIncludingOddAndFractionalGeometry() {
    for (var plane : SlicePlane.values()) {
      var view = new SliceImageView(plane, 11, -3.75, -8.25, .5, 3, (x, y, z) -> 1);
      var expected =
          switch (plane) {
            case X -> new SliceImageView.Point(11, -3, -7.5);
            case Y -> new SliceImageView.Point(-3, 11, -7.5);
            case Z -> new SliceImageView.Point(-3, -7.5, 11);
          };
      assertEquals(expected, view.at(.5, .5));
    }
  }

  @Test
  void outOfImageNonfiniteCoordinatesAndNonfiniteSamplesRejectWithoutInventingValues() {
    var finite = new SliceImageView(SlicePlane.Y, 0, 0, 0, 1, 8, (x, y, z) -> 1);
    for (double bad : new double[] {-.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> finite.at(bad, .5));
    var overflow = new SliceImageView(SlicePlane.Z, 0, 0, 0, Double.MAX_VALUE, 8, (x, y, z) -> 1);
    assertThrows(IllegalArgumentException.class, () -> overflow.at(1, .5));
    var invalid = new SliceImageView(SlicePlane.X, 0, 0, 0, 1, 8, (x, y, z) -> Double.NaN);
    assertThrows(ArithmeticException.class, () -> invalid.sample(.5, .5));
    assertEquals(1, finite.sample(.5, .5).raw());
  }
}
