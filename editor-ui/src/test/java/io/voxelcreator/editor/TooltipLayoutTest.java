package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TooltipLayoutTest {
  @Test
  void defaultFontUsesThirtySixGlyphHeightsAndTwelveLines() {
    var budget = TooltipLayout.budget(new DialogBounds.Rect(-1920, -100, 1920, 1080), 13, 8, 8);
    assertEquals(new TooltipLayout.Budget(468, 156), budget);
  }

  @Test
  void narrowWorkAreasReserveTheSameInsetsAndPaddingAsActualFitting() {
    var work = new DialogBounds.Rect(-300, -200, 200, 140);
    var budget = TooltipLayout.budget(work, 81.25f, 8, 8);
    assertEquals(new TooltipLayout.Budget(164, 104), budget);
    var fit =
        PopupBounds.fit(
            work,
            new DialogBounds.Rect(-105, -65, 1, 1),
            budget.width() + 20,
            budget.height() + 20);
    assertTrue(DialogBounds.contains(work, fit));
  }

  @Test
  void fontMetricScalingIsProportionalUntilTheCurrentWorkAreaLimitsIt() {
    var area = new DialogBounds.Rect(0, 0, 20000, 20000);
    var a = TooltipLayout.budget(area, 13, 8, 8);
    var b = TooltipLayout.budget(area, 32.5f, 8, 8);
    assertEquals(a.width() * 2.5f, b.width());
    assertEquals(a.height() * 2.5f, b.height());
    var huge =
        TooltipLayout.budget(
            new DialogBounds.Rect(0, 0, 1, 1), Float.MAX_VALUE, Float.MAX_VALUE, 0);
    assertEquals(1, huge.width());
    assertEquals(1, huge.height());
  }

  @Test
  void nonfiniteNegativeOrZeroFontMetricsAreRejected() {
    var area = new DialogBounds.Rect(0, 0, 640, 480);
    for (float value : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY})
      assertThrows(IllegalArgumentException.class, () -> TooltipLayout.budget(area, value, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> TooltipLayout.budget(area, 13, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> TooltipLayout.budget(area, 13, 0, Float.NaN));
  }
}
