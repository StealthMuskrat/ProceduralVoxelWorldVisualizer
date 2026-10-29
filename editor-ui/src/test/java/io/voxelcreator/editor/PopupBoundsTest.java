package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Deferred independent anchored-selector expectations. No native context or project mutation. */
class PopupBoundsTest {
  private final DialogBounds.Rect work = new DialogBounds.Rect(0, 0, 800, 600);

  @Test
  void prefersBelowAnInteriorTriggerWithAFourUnitGap() {
    assertEquals(
        new DialogBounds.Rect(100, 134, 420, 340),
        PopupBounds.fit(work, new DialogBounds.Rect(100, 100, 200, 30), 420, 340));
  }

  @Test
  void flipsAboveWhenTheBottomWouldCrossTheWorkMargin() {
    assertEquals(
        new DialogBounds.Rect(100, 156, 420, 340),
        PopupBounds.fit(work, new DialogBounds.Rect(100, 500, 200, 30), 420, 340));
  }

  @Test
  void alignsItsRightEdgeWhenTheTriggerIsAtTheRightSide() {
    assertEquals(
        new DialogBounds.Rect(372, 134, 420, 340),
        PopupBounds.fit(work, new DialogBounds.Rect(700, 100, 80, 30), 420, 340));
  }

  @Test
  void overlapsTheTriggerOnlyWhenNeitherVerticalSideFits() {
    assertEquals(
        new DialogBounds.Rect(100, 252, 420, 340),
        PopupBounds.fit(work, new DialogBounds.Rect(100, 270, 200, 30), 420, 340));
  }

  @Test
  void narrowViewportFitsBothDimensionsInsteadOfTruncatingTheListWidth() {
    assertEquals(
        new DialogBounds.Rect(8, 8, 344, 224),
        PopupBounds.fit(
            new DialogBounds.Rect(0, 0, 360, 240),
            new DialogBounds.Rect(20, 170, 300, 30),
            480,
            340));
  }

  @Test
  void negativeSecondaryOriginsRemainInTheHostingCoordinateSpace() {
    assertEquals(
        new DialogBounds.Rect(-700, -366, 420, 340),
        PopupBounds.fit(
            new DialogBounds.Rect(-1000, -500, 800, 600),
            new DialogBounds.Rect(-700, -400, 200, 30),
            420,
            340));
  }

  @Test
  void workMarginEqualityDoesNotCauseAnUnnecessaryFlip() {
    assertEquals(
        new DialogBounds.Rect(100, 252, 420, 340),
        PopupBounds.fit(work, new DialogBounds.Rect(100, 218, 200, 30), 420, 340));
  }

  @Test
  void outsideAnchorsAreClampedWithoutChangingTheRequestedSize() {
    var result = PopupBounds.fit(work, new DialogBounds.Rect(-2000, -2000, 20, 20), 420, 340);
    assertEquals(new DialogBounds.Rect(8, 8, 420, 340), result);
  }

  @Test
  void tinyAreasKeepPositiveFiniteDimensionsAndUninvertedMargins() {
    var pixel = new DialogBounds.Rect(0, 0, 1, 1);
    assertEquals(pixel, PopupBounds.fit(pixel, pixel, 480, 340));
    assertEquals(
        new DialogBounds.Rect(8, 8, 84, 64),
        PopupBounds.fit(new DialogBounds.Rect(0, 0, 100, 80), pixel, 480, 340));
  }

  @Test
  void translationOfWorkAndAnchorTranslatesTheSameResult() {
    var anchor = new DialogBounds.Rect(700, 500, 80, 30);
    var original = PopupBounds.fit(work, anchor, 420, 340);
    var moved =
        PopupBounds.fit(
            new DialogBounds.Rect(-1600, -900, 800, 600),
            new DialogBounds.Rect(-900, -400, 80, 30),
            420,
            340);
    assertEquals(original.x() - 1600, moved.x());
    assertEquals(original.y() - 900, moved.y());
    assertEquals(original.width(), moved.width());
    assertEquals(original.height(), moved.height());
  }

  @Test
  void invalidPreferredSizesRejectBeforeProducingGeometry() {
    for (float value : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> PopupBounds.fit(work, work, value, 340));
      assertThrows(IllegalArgumentException.class, () -> PopupBounds.fit(work, work, 480, value));
    }
    assertThrows(NullPointerException.class, () -> PopupBounds.fit(null, work, 480, 340));
    assertThrows(NullPointerException.class, () -> PopupBounds.fit(work, null, 480, 340));
  }

  @Test
  void extremeFiniteAnchorsDoNotOverflowEdgeArithmetic() {
    var result =
        PopupBounds.fit(
            work,
            new DialogBounds.Rect(
                Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE),
            420,
            340);
    assertEquals(new DialogBounds.Rect(372, 252, 420, 340), result);
    assertTrue(DialogBounds.contains(DialogBounds.inset(work), result));
  }
}
