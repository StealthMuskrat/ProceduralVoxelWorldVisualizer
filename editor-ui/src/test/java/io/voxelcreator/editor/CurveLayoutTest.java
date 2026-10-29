package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferred body/footer/plot budget definitions, independent of curve values and native calls. */
class CurveLayoutTest {
  @Test
  void bodyReservesTwoCompleteActionRows() {
    assertEquals(432, CurveLayout.body(480, 24));
  }

  @Test
  void anOverfullFooterDoesNotProduceANegativeChildSize() {
    assertEquals(1, CurveLayout.body(20, 30));
  }

  @Test
  void largerFontsReduceTheBodyBudgetByExactlyTwoRows() {
    assertEquals(60, CurveLayout.body(480, 24) - CurveLayout.body(480, 54));
  }

  @Test
  void narrowPlotsUseTheAvailableWidthInsteadOfAFixedMinimum() {
    assertEquals(new CurveLayout.Plot(80, 170), CurveLayout.plot(80, 200, 432));
    assertEquals(1, CurveLayout.plot(-10, -10, 100).width());
  }

  @Test
  void shortBodiesBoundThePlotToSixtyFivePercentOfTheVisibleBody() {
    assertEquals(new CurveLayout.Plot(300, 39), CurveLayout.plot(300, -200, 60));
  }

  @Test
  void spaciousBodiesStillKeepRoomForThePointInspector() {
    assertEquals(new CurveLayout.Plot(800, 650), CurveLayout.plot(800, 1000, 1000));
  }

  @Test
  void invalidSizesRejectWithoutChangingAnyAuthoringState() {
    for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> CurveLayout.body(invalid, 24));
      assertThrows(IllegalArgumentException.class, () -> CurveLayout.body(480, invalid));
      assertThrows(IllegalArgumentException.class, () -> CurveLayout.plot(invalid, 200, 400));
      assertThrows(IllegalArgumentException.class, () -> CurveLayout.plot(300, invalid, 400));
      assertThrows(IllegalArgumentException.class, () -> CurveLayout.plot(300, 200, invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> CurveLayout.body(480, 0));
    assertThrows(IllegalArgumentException.class, () -> CurveLayout.plot(300, 200, 0));
  }

  @Test
  void finiteExtremeInputsDoNotCreateInfiniteNativeSizes() {
    assertTrue(Float.isFinite(CurveLayout.body(Float.MAX_VALUE, Float.MAX_VALUE)));
    var plot = CurveLayout.plot(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    assertTrue(Float.isFinite(plot.width()));
    assertTrue(Float.isFinite(plot.height()));
  }
}
