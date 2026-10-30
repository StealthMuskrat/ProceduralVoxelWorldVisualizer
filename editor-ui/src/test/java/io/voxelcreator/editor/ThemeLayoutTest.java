package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ThemeLayoutTest {
  private ThemeLayout.Channels channels(float width) {
    return ThemeLayout.channels(width, 60, 10, 8, 17, 23);
  }

  @Test
  void widePanesShareTheMeasuredHeightAndHorizontalBudget() {
    var p = ThemeLayout.panes(1000, 500, 800, 200, 23, 8);
    assertTrue(p.columns());
    assertEquals(515.84, p.paletteWidth(), .001);
    assertEquals(476.16, p.contrastWidth(), .001);
    assertEquals(500, p.height());
  }

  @Test
  void exactPaneThresholdKeepsBothMinimumWidths() {
    var p = ThemeLayout.panes(408, 500, 800, 200, 23, 8);
    assertTrue(p.columns());
    assertEquals(200, p.paletteWidth());
    assertEquals(200, p.contrastWidth());
  }

  @Test
  void narrowModeGivesEitherSelectedPaneTheFullWidth() {
    var p = ThemeLayout.panes(407, 500, 800, 200, 23, 8);
    assertFalse(p.columns());
    assertEquals(407, p.paletteWidth());
    assertEquals(407, p.contrastWidth());
  }

  @Test
  void paneHeightCapsToVisibleBodyAndSurvivesExhaustedHeadings() {
    assertEquals(420, ThemeLayout.panes(1000, 1000, 600, 200, 23, 8).height());
    assertEquals(184, ThemeLayout.panes(1000, -100, 600, 200, 23, 8).height());
  }

  @Test
  void colorListViewportNeverExpandsToItsScrollableContent() {
    assertEquals(100, ThemeLayout.viewport(100, 400, 23));
    assertEquals(260, ThemeLayout.viewport(500, 400, 23));
    assertEquals(23, ThemeLayout.viewport(-100, 400, 23));
    assertEquals(14.95, ThemeLayout.viewport(-100, 23, 23), .001);
  }

  @Test
  void fourChannelsShareOneRowWithIndividualAxisLabels() {
    var p = channels(500);
    assertEquals(4, p.columns());
    assertEquals(1, p.rows());
    assertFalse(p.stackedLabels());
    assertEquals(101, p.fieldWidth());
    assertEquals(69, p.rowHeight());
  }

  @Test
  void twoColumnsBudgetTwoChannelRows() {
    var p = channels(250);
    assertEquals(2, p.columns());
    assertEquals(2, p.rows());
    assertEquals(103, p.fieldWidth());
    assertEquals(92, p.rowHeight());
  }

  @Test
  void oneColumnRetainsAnInlineAxisAndFullRemainingInputWidth() {
    var p = channels(120);
    assertEquals(1, p.columns());
    assertEquals(4, p.rows());
    assertFalse(p.stackedLabels());
    assertEquals(102, p.fieldWidth());
    assertEquals(138, p.rowHeight());
  }

  @Test
  void veryNarrowCellsStackAxisLabelsAboveInputs() {
    var p = channels(70);
    assertEquals(1, p.columns());
    assertTrue(p.stackedLabels());
    assertEquals(70, p.fieldWidth());
    assertEquals(206, p.rowHeight());
  }

  @Test
  void fractionalMetricsReserveWholePixelNativeStrides() {
    assertEquals(90, ThemeLayout.channels(500, 60, 10, 8, 23.5f, 29.5f).rowHeight());
    assertEquals(276, ThemeLayout.channels(70, 60, 10, 8, 23.5f, 29.5f).rowHeight());
  }

  @Test
  void finiteExtremesStayPositiveAndFinite() {
    var p =
        ThemeLayout.channels(
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE);
    assertEquals(1, p.columns());
    assertTrue(p.stackedLabels());
    assertEquals(Float.MAX_VALUE, p.fieldWidth());
    assertEquals(Float.MAX_VALUE, p.rowHeight());
    assertTrue(
        Float.isFinite(
            ThemeLayout.panes(
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE)
                .height()));
    assertEquals(1, channels(-100).fieldWidth());
  }

  @Test
  void invalidMeasurementsRejectBeforeNativeSubmission() {
    for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> channels(bad));
      assertThrows(
          IllegalArgumentException.class, () -> ThemeLayout.panes(1000, bad, 600, 200, 23, 8));
    }
    assertThrows(IllegalArgumentException.class, () -> ThemeLayout.channels(500, 0, 10, 8, 17, 23));
    assertThrows(
        IllegalArgumentException.class, () -> ThemeLayout.channels(500, 60, -1, 8, 17, 23));
    assertThrows(
        IllegalArgumentException.class, () -> ThemeLayout.channels(500, 60, 10, -1, 17, 23));
    assertThrows(IllegalArgumentException.class, () -> ThemeLayout.channels(500, 60, 10, 8, 0, 23));
    assertThrows(IllegalArgumentException.class, () -> ThemeLayout.panes(1000, 500, 0, 200, 23, 8));
    assertThrows(IllegalArgumentException.class, () -> ThemeLayout.viewport(100, 400, 0));
  }
}
