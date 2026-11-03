package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScalarInputPresentationTest {
  @Test
  void idleReadoutsAreCompactWhileTheNativeFormatAlwaysRetainsDoublePrecision() {
    var presentation = new ScalarInputPresentation();
    assertEquals("1", presentation.compact(7, Math.nextUp(1.0)));
    assertEquals("1.23457", presentation.compact(8, 1.2345678901234567));
    assertEquals("1e-300", presentation.compact(9, 1e-300));
    assertEquals("1e+100", presentation.compact(10, 1e100));
    assertEquals("%.17g", ScalarInputPresentation.EXACT_FORMAT);
  }

  @Test
  void repeatedPresentationDoesNotReformatStableFieldsAndSignedZeroChangesInvalidateIt() {
    var presentation = new ScalarInputPresentation();
    for (int frame = 0; frame < 100; frame++) assertEquals("0", presentation.compact(11, 0.0));
    assertEquals(1, presentation.formats());
    assertEquals("-0", presentation.compact(11, -0.0));
    assertEquals(2, presentation.formats());
    for (int frame = 0; frame < 100; frame++) assertEquals("-0", presentation.compact(11, -0.0));
    assertEquals(2, presentation.formats());
  }

  @Test
  void readoutsRemainBoundedWhenManyResourceAndOwnerIdsAreVisited() {
    var presentation = new ScalarInputPresentation();
    for (int id = 0; id < 3 * ScalarInputPresentation.MAX_FIELDS; id++) {
      assertEquals("1.23457", presentation.compact(id, 1.2345678901234567));
      assertTrue(presentation.size() <= ScalarInputPresentation.MAX_FIELDS);
    }
    assertEquals("1e-9", presentation.compact(0, 1e-9));
    assertEquals(ScalarInputPresentation.MAX_FIELDS, presentation.size());
    presentation.clear();
    assertEquals(0, presentation.size());
    assertEquals("-1e-9", presentation.compact(0, -1e-9));
  }

  @Test
  void coordinateReadoutsRetainWholeDigitsAndDoNotReuseAScalarCacheEntry() {
    var presentation = new ScalarInputPresentation();
    assertEquals("2e+9", presentation.compact(1, 2_000_000_000.125));
    assertEquals("2000000000.125", presentation.coordinate(1, 2_000_000_000.125));
    assertEquals("-2147483648", presentation.coordinate(1, Integer.MIN_VALUE));
    assertEquals("2147483647", presentation.coordinate(1, Integer.MAX_VALUE));
    assertEquals("2e+9", presentation.compact(1, 2_000_000_000.125));
  }

  @Test
  void activeNativeEditorAndExplicitIntegerOrInvalidRawPoliciesKeepTheirOwnText() {
    assertTrue(ScalarInputPresentation.compact(true, 17, 0));
    assertFalse(ScalarInputPresentation.compact(true, 17, 17));
    assertTrue(ScalarInputPresentation.compact(true, 18, 17));
    assertFalse(ScalarInputPresentation.compact(false, 17, 0));
    assertFalse(ScalarInputPresentation.compact(false, 17, 17));
  }

  @Test
  void visibleLabelsFollowNativeHiddenIdentityRulesWithoutFormattingLiteralContent() {
    assertEquals("", ScalarInputPresentation.visibleLabel("##scalar"));
    assertEquals("", ScalarInputPresentation.visibleLabel("###scalar"));
    assertEquals("100% 雪 😀", ScalarInputPresentation.visibleLabel("100% 雪 😀###owner"));
    assertEquals("%f # ", ScalarInputPresentation.visibleLabel("%f # ##hidden"));
    assertEquals("Scalar", ScalarInputPresentation.visibleLabel("Scalar"));
  }

  @Test
  void readoutInkUsesOnlyTheFrameInteriorEvenWhenTheNativeItemIncludesItsLabel() {
    var item = new DialogBounds.Rect(10, 20, 220, 24);
    assertEquals(
        new ScalarInputPresentation.Ink(15, 23, 110, 18),
        ScalarInputPresentation.ink(item, 120, 24, 5, 3));
    assertEquals(new DialogBounds.Rect(10, 20, 220, 24), item);
    var clipped = ScalarInputPresentation.ink(item, 400, 50, 5, 3);
    assertEquals(new ScalarInputPresentation.Ink(15, 23, 210, 18), clipped);
  }

  @Test
  void exhaustedPaddingSkipsInkWithoutGrowingANarrowFieldOrItsItem() {
    var item = new DialogBounds.Rect(10, 20, 4, 2);
    var ink = ScalarInputPresentation.ink(item, 4, 2, 5, 3);
    assertEquals(new ScalarInputPresentation.Ink(14, 22, 0, 0), ink);
    assertFalse(ink.visible());
  }

  @Test
  void fractionalAndComposedFontMetricsKeepTheReadoutInsideTheSameNativeFrame() {
    for (float scale : new float[] {.5f, 1, 1.25f, 2.5f, 6.25f}) {
      var item = new DialogBounds.Rect(16, 32, 300, 22.5f * scale);
      var ink = ScalarInputPresentation.ink(item, 180, item.height(), 4 * scale, 3 * scale);
      assertTrue(ink.visible());
      assertEquals(16 + 4 * scale, ink.x(), .001);
      assertEquals(32 + 3 * scale, ink.y(), .001);
      assertEquals(180 - 8 * scale, ink.width(), .001);
      assertEquals(16.5f * scale, ink.height(), .001);
      assertTrue(ink.x() + ink.width() <= item.x() + 180);
      assertTrue(ink.y() + ink.height() <= item.y() + item.height());
    }
  }

  @Test
  void nonfiniteAndInvalidGeometryIsRejectedBeforeAnyNativeInkIsSubmitted() {
    var item = new DialogBounds.Rect(0, 0, 100, 24);
    for (float bad :
        new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1}) {
      assertThrows(
          IllegalArgumentException.class, () -> ScalarInputPresentation.ink(item, bad, 24, 4, 3));
      assertThrows(
          IllegalArgumentException.class, () -> ScalarInputPresentation.ink(item, 100, bad, 4, 3));
      assertThrows(
          IllegalArgumentException.class, () -> ScalarInputPresentation.ink(item, 100, 24, bad, 3));
    }
    assertThrows(
        IllegalArgumentException.class, () -> ScalarInputPresentation.ink(item, 0, 24, 4, 3));
    assertThrows(
        IllegalArgumentException.class, () -> ScalarInputPresentation.ink(item, 100, 0, 4, 3));
  }
}
