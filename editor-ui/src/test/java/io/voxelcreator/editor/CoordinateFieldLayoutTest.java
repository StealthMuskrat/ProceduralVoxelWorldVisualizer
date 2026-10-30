package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred independent analytical axis-grid vectors and raw-input separation. */
class CoordinateFieldLayoutTest {
  @Test
  void threeColumnsShareSpaceAfterAllLabelsAndGaps() {
    var p = CoordinateFieldLayout.prepare(300, 70, 20, 8);
    assertEquals(3, p.columns());
    assertEquals(224 / 3f, p.inputWidth());
    assertFalse(p.stackedLabels());
  }

  @Test
  void twoColumnsWrapTheThirdAxisOntoAnotherRow() {
    assertEquals(
        new CoordinateFieldLayout.Plan(2, 76, false),
        CoordinateFieldLayout.prepare(200, 70, 20, 8));
  }

  @Test
  void oneColumnKeepsAnInlineLabelWhenTheFullIntegerCanFit() {
    assertEquals(
        new CoordinateFieldLayout.Plan(1, 130, false),
        CoordinateFieldLayout.prepare(150, 70, 20, 8));
  }

  @Test
  void narrowFieldsPutLabelsAboveTheEntireAvailableInputWidth() {
    assertEquals(
        new CoordinateFieldLayout.Plan(1, 80, true), CoordinateFieldLayout.prepare(80, 70, 20, 8));
  }

  @Test
  void exactThreeAndTwoColumnThresholdsDoNotWrapPrematurely() {
    assertEquals(
        new CoordinateFieldLayout.Plan(3, 70, false),
        CoordinateFieldLayout.prepare(286, 70, 20, 8));
    assertEquals(
        new CoordinateFieldLayout.Plan(2, 70, false),
        CoordinateFieldLayout.prepare(188, 70, 20, 8));
  }

  @Test
  void proportionalMetricScalingPreservesColumnsAndDoublesInputWidth() {
    var a = CoordinateFieldLayout.prepare(200, 70, 20, 8);
    var b = CoordinateFieldLayout.prepare(400, 140, 40, 16);
    assertEquals(a.columns(), b.columns());
    assertEquals(a.inputWidth() * 2, b.inputWidth());
    assertEquals(a.stackedLabels(), b.stackedLabels());
  }

  @Test
  void zeroSpacingAndLabelsUseTheWholeDividedWidth() {
    assertEquals(
        new CoordinateFieldLayout.Plan(3, 90, false), CoordinateFieldLayout.prepare(270, 70, 0, 0));
  }

  @Test
  void temporarilyNegativeRemainingWidthNeverProducesNegativeNativeWidths() {
    assertEquals(
        new CoordinateFieldLayout.Plan(1, 1, true), CoordinateFieldLayout.prepare(-20, 70, 20, 8));
  }

  @Test
  void invalidMetricsRejectAndExtremeFiniteMetricsRemainFinite() {
    for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(invalid, 70, 20, 8));
      assertThrows(
          IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(300, invalid, 20, 8));
      assertThrows(
          IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(300, 70, invalid, 8));
      assertThrows(
          IllegalArgumentException.class,
          () -> CoordinateFieldLayout.prepare(300, 70, 20, invalid));
    }
    assertThrows(
        IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(300, 0, 20, 8));
    assertThrows(
        IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(300, 70, -1, 8));
    assertThrows(
        IllegalArgumentException.class, () -> CoordinateFieldLayout.prepare(300, 70, 20, -1));
    var p =
        CoordinateFieldLayout.prepare(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    assertEquals(1, p.columns());
    assertTrue(Float.isFinite(p.inputWidth()));
  }

  @Test
  void reflowDoesNotParseRepairOrAdvanceRawCoordinateOwnership() {
    var raw = new InspectorCoordinates();
    raw.edit("X", "-");
    raw.edit("Y", "2147483647");
    raw.edit("Z", "-17");
    long version = raw.version();
    String problem = raw.problem();
    for (float width : new float[] {1, 80, 150, 200, 300, 1000})
      CoordinateFieldLayout.prepare(width, 70, 20, 8);
    assertEquals(version, raw.version());
    assertEquals(problem, raw.problem());
    assertEquals("-", raw.text("X"));
    assertEquals("2147483647", raw.text("Y"));
    assertEquals("-17", raw.text("Z"));
    assertThrows(IllegalArgumentException.class, raw::coordinate);
    raw.edit("X", "-2147483648");
    var position = raw.coordinate(Map.of("X", "-2147483648", "Y", "2147483647", "Z", "-17"));
    assertEquals(Integer.MIN_VALUE, position.x());
    assertEquals(Integer.MAX_VALUE, position.y());
    assertEquals(-17, position.z());
  }
}
