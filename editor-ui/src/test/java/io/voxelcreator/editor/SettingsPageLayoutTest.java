package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SettingsPageLayoutTest {
  @Test
  void multiplePersistentRowsReserveTheirOwnFramesAndGaps() {
    assertEquals(254, SettingsPageLayout.body(300, 19, 4, 2));
    assertEquals(40, SettingsPageLayout.body(300, 81.25f, 5.4166665f, 3), .001);
    assertEquals(1, SettingsPageLayout.body(100, 81.25f, 4, 2));
    assertEquals(SettingsPageLayout.body(300, 19, 4), SettingsPageLayout.body(300, 19, 4, 1));
  }

  @Test
  void actionRowCountsAndExtremeMeasurementsRejectOrRemainFiniteBeforeNativeUse() {
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.body(300, 19, 4, 0));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.body(300, 19, 4, 5));
    assertEquals(1, SettingsPageLayout.body(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, 4));
    assertThrows(IllegalArgumentException.class, () -> new SettingsPageChrome());
    assertThrows(IllegalArgumentException.class, () -> new SettingsPageChrome(4, 0));
    assertThrows(IllegalArgumentException.class, () -> new SettingsPageChrome(17));
    assertThrows(IllegalArgumentException.class, () -> new SettingsPageChrome(1, 1, 1, 1, 1));
  }

  @Test
  void multilineEditorsUseFiveMeasuredRowsWithinTheVisibleBodyBudget() {
    assertEquals(100, SettingsPageLayout.multiline(1000, 20));
    assertEquals(40, SettingsPageLayout.multiline(100, 20));
    assertEquals(102.5, SettingsPageLayout.multiline(400, 20.5f));
    assertEquals(1, SettingsPageLayout.multiline(1, 80));
  }

  @Test
  void multilineExtremeMetricsStayFinite() {
    float value = SettingsPageLayout.multiline(Float.MAX_VALUE, Float.MAX_VALUE);
    assertTrue(Float.isFinite(value) && value > 0 && value < Float.MAX_VALUE);
  }

  @Test
  void multilineEditorsRejectInvalidBodyAndFontMetrics() {
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.multiline(0, 20));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.multiline(400, 0));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.multiline(Float.NaN, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> SettingsPageLayout.multiline(400, Float.POSITIVE_INFINITY));
  }

  @Test
  void scaleTargetListUsesTwoToSixMeasuredRows() {
    assertEquals(120, SettingsPageLayout.choices(1000, 20));
    assertEquals(56, SettingsPageLayout.choices(200, 20), .001);
    assertEquals(40, SettingsPageLayout.choices(0, 20));
    assertEquals(40, SettingsPageLayout.choices(-100, 20));
    assertEquals(160, SettingsPageLayout.choices(200, 80));
  }

  @Test
  void scaleTargetListBoundsRemainFiniteAtExtremeFontMetrics() {
    assertEquals(Float.MAX_VALUE, SettingsPageLayout.choices(Float.MAX_VALUE, Float.MAX_VALUE));
    assertTrue(SettingsPageLayout.choices(Float.MAX_VALUE, Float.MIN_NORMAL) > 0);
  }

  @Test
  void scaleTargetListRejectsInvalidMetrics() {
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.choices(Float.NaN, 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> SettingsPageLayout.choices(300, Float.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.choices(300, 0));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.choices(300, -1));
  }

  @Test
  void footerBudgetTracksMeasuredFontHeight() {
    assertEquals(277, SettingsPageLayout.body(300, 19, 4));
    assertEquals(258, SettingsPageLayout.body(300, 38, 4));
    assertEquals(214.75, SettingsPageLayout.body(300, 81.25f, 4));
  }

  @Test
  void exhaustedBodyHasPositiveScrollableContentWithoutInventingExtraClientSpace() {
    assertEquals(1, SettingsPageLayout.body(20, 81.25f, 4));
    assertEquals(1, SettingsPageLayout.body(-100, 19, 4));
    var panes = SettingsPageLayout.panes(300, -100, 180, 20, 8, 4);
    assertEquals(24.32, panes.resultsHeight(), .001);
    assertEquals(51.68, panes.detailHeight(), .001);
  }

  @Test
  void equalActionHitsAndGapsFillTheAvailableRow() {
    assertEquals(154, SettingsPageLayout.actionWidth(640, 4, 8));
    assertEquals(316, SettingsPageLayout.actionWidth(640, 2, 8));
    assertEquals(640, SettingsPageLayout.actionWidth(640, 1, 8));
    assertEquals(1, SettingsPageLayout.actionWidth(1, 4, 8));
  }

  @Test
  void widePanesShareOneHeightWithDistinctHorizontalAndVerticalGaps() {
    var plan = SettingsPageLayout.panes(1000, 500, 180, 20, 8, 4);
    assertTrue(plan.columns());
    assertEquals(357.12, plan.resultsWidth(), .001);
    assertEquals(634.88, plan.detailWidth(), .001);
    assertEquals(500, plan.resultsHeight());
    assertEquals(500, plan.detailHeight());
  }

  @Test
  void exactColumnBoundaryRetainsBothMinimumPaneWidths() {
    var plan = SettingsPageLayout.panes(368, 500, 180, 20, 8, 4);
    assertTrue(plan.columns());
    assertEquals(180, plan.resultsWidth());
    assertEquals(180, plan.detailWidth());
    assertFalse(SettingsPageLayout.panes(367, 500, 180, 20, 8, 4).columns());
  }

  @Test
  void narrowPanesStackAndLimitTheListToFourTextRows() {
    var plan = SettingsPageLayout.panes(300, 500, 180, 20, 8, 4);
    assertFalse(plan.columns());
    assertEquals(300, plan.resultsWidth());
    assertEquals(300, plan.detailWidth());
    assertEquals(80, plan.resultsHeight());
    assertEquals(416, plan.detailHeight());
  }

  @Test
  void scaledStackKeepsAnEditableDetailViewportWhenItsHeadingOverflows() {
    var plan = SettingsPageLayout.panes(300, 100, 720, 80, 8, 4);
    assertFalse(plan.columns());
    assertEquals(101.12, plan.resultsHeight(), .001);
    assertEquals(214.88, plan.detailHeight(), .001);
    assertEquals(320, plan.resultsHeight() + plan.detailHeight() + 4, .001);
  }

  @Test
  void sliderAndExactTextStackBelowTheMeasuredPairBoundary() {
    var wide = SettingsPageLayout.pair(500, 100, 8);
    assertTrue(wide.inline());
    assertEquals(255.84, wide.sliderWidth(), .001);
    assertEquals(236.16, wide.inputWidth(), .001);
    var exact = SettingsPageLayout.pair(208, 100, 8);
    assertTrue(exact.inline());
    assertEquals(100, exact.sliderWidth());
    assertEquals(100, exact.inputWidth());
    var narrow = SettingsPageLayout.pair(207, 100, 8);
    assertFalse(narrow.inline());
    assertEquals(207, narrow.sliderWidth());
    assertEquals(207, narrow.inputWidth());
  }

  @Test
  void finiteExtremeMetricsDoNotOverflowIntermediateArithmetic() {
    var plan =
        SettingsPageLayout.panes(
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE);
    assertFalse(plan.columns());
    assertTrue(Float.isFinite(plan.resultsHeight()) && plan.resultsHeight() >= 1);
    assertTrue(Float.isFinite(plan.detailHeight()) && plan.detailHeight() >= 1);
    var pair = SettingsPageLayout.pair(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
    assertFalse(pair.inline());
    assertEquals(Float.MAX_VALUE, pair.inputWidth());
    assertEquals(1, SettingsPageLayout.actionWidth(Float.MAX_VALUE, 16, Float.MAX_VALUE));
  }

  @Test
  void invalidMeasurementsRejectBeforeNativeSubmission() {
    for (float invalid :
        new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.body(invalid, 19, 4));
      assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.pair(300, invalid, 8));
      assertThrows(
          IllegalArgumentException.class,
          () -> SettingsPageLayout.panes(300, 200, 100, 20, invalid, 4));
    }
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.body(300, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.body(300, 19, -1));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.actionWidth(300, 0, 4));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.actionWidth(300, 17, 4));
    assertThrows(IllegalArgumentException.class, () -> SettingsPageLayout.pair(300, 0, 4));
    assertThrows(
        IllegalArgumentException.class, () -> SettingsPageLayout.panes(300, 200, 100, 0, 8, 4));
    assertThrows(
        IllegalArgumentException.class, () -> SettingsPageLayout.panes(300, 200, 100, 20, 8, -1));
  }
}
