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

  @Test
  void treeIndentPreservesDepthButCapsItsMeasuredWidthShare() {
    assertEquals(0, ResourceReviewLayout.treeIndent(80, 0, 14));
    assertEquals(14, ResourceReviewLayout.treeIndent(80, 1, 14));
    assertEquals(20, ResourceReviewLayout.treeIndent(80, 30, 14));
    assertEquals(250, ResourceReviewLayout.treeIndent(1000, 100, 14));
    assertEquals(.5f, ResourceReviewLayout.treeIndent(13.25f, 1, .5f));
    assertEquals(14.4f, ResourceReviewLayout.treeDisclosure(80, 20));
    assertEquals(20, ResourceReviewLayout.treeDisclosure(1000, 20));
    assertEquals(1, ResourceReviewLayout.treeDisclosure(0, 20));
    assertTrue(
        Float.isFinite(
            ResourceReviewLayout.treeIndent(Float.MAX_VALUE, Integer.MAX_VALUE, Float.MAX_VALUE)));
  }

  @Test
  void invalidTreeMeasurementsRejectBeforeNativeSubmission() {
    assertThrows(
        IllegalArgumentException.class, () -> ResourceReviewLayout.treeIndent(100, -1, 10));
    assertThrows(IllegalArgumentException.class, () -> ResourceReviewLayout.treeIndent(100, 2, 0));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceReviewLayout.treeIndent(Float.NaN, 2, 10));
    assertThrows(IllegalArgumentException.class, () -> ResourceReviewLayout.treeDisclosure(100, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceReviewLayout.treeDisclosure(Float.POSITIVE_INFINITY, 20));
  }
}
