package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ResourceReviewLayoutTest {
  @Test
  void contentUsesRemainingSpaceWithoutAnUnconditionalPixelMinimum() {
    assertEquals(240, ResourceReviewLayout.content(240));
    assertEquals(12.75f, ResourceReviewLayout.content(12.75f));
    assertEquals(1, ResourceReviewLayout.content(-90));
    assertEquals(1, ResourceReviewLayout.content(0));
    assertEquals(Float.MAX_VALUE, ResourceReviewLayout.content(Float.MAX_VALUE));
  }

  @Test
  void importRowsAccountForThreeTextLinesOneControlAndFourGapsBeforeRounding() {
    assertEquals(74, ResourceReviewLayout.importStride(13, 19, 4));
    assertEquals(62, ResourceReviewLayout.importStride(13.25f, 19.5f, .5f));
    assertEquals(111, ResourceReviewLayout.importStride(19.5f, 28.5f, 6));
    assertEquals(1, ResourceReviewLayout.importStride(.1f, .1f, 0));
  }

  @Test
  void checkboxRowsUseFramePlusGapAndFiniteExtremeSumsDoNotOverflow() {
    assertEquals(23, ResourceReviewLayout.membershipStride(19, 4));
    assertEquals(21, ResourceReviewLayout.membershipStride(19.5f, .75f));
    assertEquals(29, ResourceReviewLayout.membershipStride(28.5f, 0));
    assertEquals(
        Float.MAX_VALUE, ResourceReviewLayout.membershipStride(Float.MAX_VALUE, Float.MAX_VALUE));
    assertEquals(
        Float.MAX_VALUE,
        ResourceReviewLayout.importStride(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE));
  }

  @Test
  void invalidNativeGeometryCannotProduceAnUnboundedOrNegativeClipperStride() {
    for (float invalid :
        new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
      assertThrows(IllegalArgumentException.class, () -> ResourceReviewLayout.content(invalid));
      assertThrows(
          IllegalArgumentException.class, () -> ResourceReviewLayout.importStride(invalid, 19, 4));
      assertThrows(
          IllegalArgumentException.class, () -> ResourceReviewLayout.membershipStride(19, invalid));
    }
    assertThrows(IllegalArgumentException.class, () -> ResourceReviewLayout.importStride(0, 19, 4));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceReviewLayout.importStride(13, -1, 4));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceReviewLayout.membershipStride(19, -1));
  }
}
