package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ShortcutRowLayoutTest {
  private ShortcutRowLayout.Plan plan(float width) {
    return ShortcutRowLayout.prepare(width, 200, 60, 17, 23, 8);
  }

  @Test
  void wideRowUsesOneInputActionLineAndTwoTextLines() {
    var p = plan(600);
    assertTrue(p.inline());
    assertEquals(396, p.inputWidth());
    assertEquals(60, p.buttonWidth());
    assertEquals(3, p.actionColumns());
    assertEquals(1, p.actionRows());
    assertEquals(57, p.rowHeight());
  }

  @Test
  void exactInlineThresholdKeepsTheMeasuredMinimumInput() {
    assertTrue(plan(404).inline());
    assertEquals(200, plan(404).inputWidth());
    assertFalse(plan(403).inline());
    assertEquals(80, plan(403).rowHeight());
  }

  @Test
  void threeColumnActionRowFollowsTheFullWidthInput() {
    var p = plan(240);
    assertFalse(p.inline());
    assertEquals(3, p.actionColumns());
    assertEquals(1, p.actionRows());
    assertEquals(240, p.inputWidth());
    assertEquals(224.0 / 3, p.buttonWidth(), .001);
    assertEquals(80, p.rowHeight());
  }

  @Test
  void twoColumnsBudgetTwoActionRows() {
    var p = plan(160);
    assertEquals(2, p.actionColumns());
    assertEquals(2, p.actionRows());
    assertEquals(76, p.buttonWidth());
    assertEquals(103, p.rowHeight());
  }

  @Test
  void oneColumnRetainsFullWidthHitsAndThreeActionRows() {
    var p = plan(120);
    assertEquals(1, p.actionColumns());
    assertEquals(3, p.actionRows());
    assertEquals(120, p.buttonWidth());
    assertEquals(126, p.rowHeight());
  }

  @Test
  void fontMetricsDetermineTheClippedRowStride() {
    var p = ShortcutRowLayout.prepare(300, 600, 300, 84, 90, 8);
    assertFalse(p.inline());
    assertEquals(1, p.actionColumns());
    assertEquals(528, p.rowHeight());
  }

  @Test
  void fractionalLineMetricsReserveWholePixelStrides() {
    assertEquals(78, ShortcutRowLayout.prepare(600, 200, 60, 23.5f, 29.5f, 8).rowHeight());
    assertEquals(168, ShortcutRowLayout.prepare(120, 200, 60, 23.5f, 29.5f, 8).rowHeight());
  }

  @Test
  void listViewportFitsTheVisibleBodyEvenWhenHeadingsExhaustRemainingHeight() {
    assertEquals(390, ShortcutRowLayout.viewport(500, 600, 23));
    assertEquals(31, ShortcutRowLayout.viewport(100, 600, 23));
    assertEquals(23, ShortcutRowLayout.viewport(-100, 600, 23));
    assertEquals(6.5, ShortcutRowLayout.viewport(-100, 10, 23));
  }

  @Test
  void finiteExtremeMetricsKeepPositiveFiniteBudgets() {
    var p =
        ShortcutRowLayout.prepare(
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE,
            Float.MAX_VALUE);
    assertFalse(p.inline());
    assertEquals(1, p.actionColumns());
    assertEquals(Float.MAX_VALUE, p.rowHeight());
    assertEquals(Float.MAX_VALUE, p.buttonWidth());
    assertTrue(
        Float.isFinite(
            ShortcutRowLayout.viewport(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)));
    assertEquals(1, plan(-100).inputWidth());
  }

  @Test
  void invalidMetricsRejectBeforeNativeSubmission() {
    for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> plan(value));
      assertThrows(
          IllegalArgumentException.class, () -> ShortcutRowLayout.viewport(value, 600, 23));
    }
    assertThrows(
        IllegalArgumentException.class, () -> ShortcutRowLayout.prepare(600, 0, 60, 17, 23, 8));
    assertThrows(
        IllegalArgumentException.class, () -> ShortcutRowLayout.prepare(600, 200, 0, 17, 23, 8));
    assertThrows(
        IllegalArgumentException.class, () -> ShortcutRowLayout.prepare(600, 200, 60, 0, 23, 8));
    assertThrows(
        IllegalArgumentException.class, () -> ShortcutRowLayout.prepare(600, 200, 60, 17, 0, 8));
    assertThrows(
        IllegalArgumentException.class, () -> ShortcutRowLayout.prepare(600, 200, 60, 17, 23, -1));
    assertThrows(IllegalArgumentException.class, () -> ShortcutRowLayout.viewport(300, 0, 23));
    assertThrows(IllegalArgumentException.class, () -> ShortcutRowLayout.viewport(300, 600, -1));
  }
}
