package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferred independent control/preview budget and short-window surface bounds. */
class ViewportPanelLayoutTest {
  @Test
  void scrollingControlsReservePreviewSpaceRegardlessOfContentOrFontSize() {
    for (float height : new float[] {10, 100, 320, 640, 900})
      for (float font : new float[] {6.5f, 13, 32.5f, 81.25f})
        for (float content : new float[] {0, 20, 500, 100000}) {
          var p = ViewportPanelLayout.split(height, content, font, 8, 4);
          assertTrue(p.controls() >= 1 && p.preview() >= 1);
          assertEquals(height, p.controls() + p.preview() + p.gap(), .001);
          assertTrue(p.controls() <= (height - p.gap()) * .6 + .001);
          assertTrue(p.preview() >= (height - p.gap()) * .4 - .001);
        }
    var shortBody = ViewportPanelLayout.split(800, 80, 13, 8, 4);
    var longBody = ViewportPanelLayout.split(800, 8000, 13, 8, 4);
    assertTrue(shortBody.controls() < longBody.controls());
    assertTrue(shortBody.preview() > longBody.preview());
  }

  @Test
  void histogramAndStatusBudgetsNeverConsumeThePositiveImage() {
    var exact = ViewportPanelLayout.surface(300, 13, 4, true, true);
    assertEquals(70.5, exact.histogram(), .001);
    assertEquals(208.5, exact.image(), .001);
    assertEquals(13, exact.status());
    for (float height : new float[] {1, 2, 10, 100, 300, 10000})
      for (float font : new float[] {6.5f, 13, 81.25f})
        for (boolean histogram : new boolean[] {false, true})
          for (boolean status : new boolean[] {false, true}) {
            var p = ViewportPanelLayout.surface(height, font, 4, histogram, status);
            assertTrue(p.image() >= 1 && p.histogram() >= 0 && p.status() >= 0);
            assertEquals(
                height,
                p.image() + p.histogram() + p.histogramGap() + p.status() + p.statusGap(),
                .002);
            if (!histogram) assertEquals(0, p.histogram());
            if (!status) assertEquals(0, p.status());
          }
  }

  @Test
  void malformedMetricsRejectAndExhaustedAreasStayFinite() {
    assertThrows(
        IllegalArgumentException.class, () -> ViewportPanelLayout.split(Float.NaN, 0, 13, 8, 4));
    assertThrows(
        IllegalArgumentException.class, () -> ViewportPanelLayout.split(300, -1, 13, 8, 4));
    assertThrows(
        IllegalArgumentException.class,
        () -> ViewportPanelLayout.surface(300, Float.POSITIVE_INFINITY, 4, true, true));
    assertThrows(
        IllegalArgumentException.class, () -> ViewportPanelLayout.surface(300, 13, -1, true, true));
    var tiny = ViewportPanelLayout.split(-5, 0, 13, 8, 4);
    assertEquals(1, tiny.controls());
    assertEquals(1, tiny.preview());
    assertEquals(0, tiny.gap());
    var large = ViewportPanelLayout.split(Float.MAX_VALUE, Float.MAX_VALUE, 81.25f, 8, 4);
    assertTrue(Float.isFinite(large.controls()) && Float.isFinite(large.preview()));
  }
}
