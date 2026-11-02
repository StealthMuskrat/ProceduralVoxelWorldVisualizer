package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScalarDisplayTest {
  @Test
  void normalReadoutsUseAtMostFiveFractionalPlacesAndTrimOnlyDisplayZeros() {
    assertEquals("1", ScalarDisplay.compact(1));
    assertEquals("-12.5", ScalarDisplay.compact(-12.5));
    assertEquals("1.23457", ScalarDisplay.compact(1.23456789));
    assertEquals("0.00001", ScalarDisplay.compact(1e-5));
    assertEquals("0", ScalarDisplay.compact(0));
    assertEquals("-0", ScalarDisplay.compact(-0.0));
  }

  @Test
  void everySmallNonzeroReadoutRetainsItsSignAndNonzeroMagnitude() {
    for (double magnitude :
        new double[] {Double.MIN_VALUE, Double.MIN_NORMAL, 1e-300, 1e-9, Math.nextDown(1e-5)})
      for (double sign : new double[] {-1, 1}) {
        double value = magnitude * sign;
        String shown = ScalarDisplay.compact(value);
        assertTrue(shown.contains("e"), shown);
        double parsed = Double.parseDouble(shown);
        assertTrue(Double.isFinite(parsed) && parsed != 0, shown);
        assertEquals(Math.copySign(1, value), Math.copySign(1, parsed));
      }
  }

  @Test
  void formatTransitionsAreExplicitAndLargeFiniteReadoutsStayBounded() {
    assertEquals("%.5e", ScalarDisplay.sliderFormat(Math.nextDown(1e-5)));
    assertEquals("%.5f", ScalarDisplay.sliderFormat(1e-5));
    assertEquals("%.5f", ScalarDisplay.sliderFormat(Math.nextDown(1e7)));
    assertEquals("%.5e", ScalarDisplay.sliderFormat(1e7));
    assertEquals("%.5f", ScalarDisplay.sliderFormat(-0.0));
    assertEquals("1e+7", ScalarDisplay.compact(1e7));
    for (double value : new double[] {Double.MAX_VALUE, -Double.MAX_VALUE, 1e100, -1e20}) {
      String shown = ScalarDisplay.compact(value);
      assertTrue(shown.length() <= 16, shown);
      assertTrue(Double.isFinite(Double.parseDouble(shown)), shown);
    }
  }

  @Test
  void worldCoordinatesKeepAllWholeDigitsAtBothSignedIntBoundaries() {
    assertEquals("-2147483648", ScalarDisplay.coordinate(Integer.MIN_VALUE));
    assertEquals("2147483647", ScalarDisplay.coordinate(Integer.MAX_VALUE));
    assertEquals("2000000000.125", ScalarDisplay.coordinate(2_000_000_000.125));
    assertEquals("-2000000000.125", ScalarDisplay.coordinate(-2_000_000_000.125));
    assertEquals("2000000001.125", ScalarDisplay.coordinate(2_000_000_001.125));
    assertEquals("-1e-9", ScalarDisplay.coordinate(-1e-9));
  }

  @Test
  void exactStringsRoundTripEveryRetainedBitIncludingSignedZeroAndAdjacentValues() {
    for (double value :
        new double[] {
          0,
          -0.0,
          Double.MIN_VALUE,
          -Double.MIN_VALUE,
          Double.MIN_NORMAL,
          Math.nextDown(1.0),
          1,
          Math.nextUp(1.0),
          Double.MAX_VALUE,
          -Double.MAX_VALUE,
          1.2345678901234567,
          -1e-300
        }) {
      long before = Double.doubleToRawLongBits(value);
      for (int i = 0; i < 100; i++) {
        ScalarDisplay.compact(value);
        ScalarDisplay.coordinate(value);
        ScalarDisplay.sliderFormat(value);
        assertEquals(before, Double.doubleToRawLongBits(value));
        assertEquals(
            before, Double.doubleToRawLongBits(Double.parseDouble(ScalarDisplay.exact(value))));
      }
    }
  }

  @Test
  void nonfiniteFeedbackRemainsExplicitRatherThanShowingAnOrdinaryZero() {
    assertEquals("NaN", ScalarDisplay.compact(Double.NaN));
    assertEquals("Infinity", ScalarDisplay.compact(Double.POSITIVE_INFINITY));
    assertEquals("-Infinity", ScalarDisplay.compact(Double.NEGATIVE_INFINITY));
    assertEquals("%.5f", ScalarDisplay.sliderFormat(Double.NaN));
  }
}
