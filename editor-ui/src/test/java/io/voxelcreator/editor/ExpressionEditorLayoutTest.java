package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExpressionEditorLayoutTest {
  @Test
  void tallBodiesStopAtEightEditorLinesAndFourFeedbackLines() {
    assertEquals(160, ExpressionEditorLayout.editor(1000, 20));
    assertEquals(80, ExpressionEditorLayout.feedback(1000, 20));
  }

  @Test
  void shortBodiesShareAvailableSpaceWithoutFixedPixelMinimums() {
    assertEquals(100, ExpressionEditorLayout.editor(200, 20));
    assertEquals(60, ExpressionEditorLayout.feedback(200, 20));
    assertEquals(40, ExpressionEditorLayout.editor(80, 20));
    assertEquals(24, ExpressionEditorLayout.feedback(80, 20));
    assertEquals(1, ExpressionEditorLayout.editor(.1f, 20));
    assertEquals(1, ExpressionEditorLayout.feedback(.1f, 20));
  }

  @Test
  void proportionalNativeMetricsRetainTheSameLogicalPartition() {
    assertEquals(
        ExpressionEditorLayout.editor(400, 17) * 2.5f, ExpressionEditorLayout.editor(1000, 42.5f));
    assertEquals(
        ExpressionEditorLayout.feedback(400, 17) * 2.5f,
        ExpressionEditorLayout.feedback(1000, 42.5f));
  }

  @Test
  void finiteExtremeMetricsDoNotOverflowBeforeNativeSubmission() {
    float editor = ExpressionEditorLayout.editor(Float.MAX_VALUE, Float.MAX_VALUE);
    float feedback = ExpressionEditorLayout.feedback(Float.MAX_VALUE, Float.MAX_VALUE);
    assertTrue(Float.isFinite(editor) && editor > 0 && editor < Float.MAX_VALUE);
    assertTrue(Float.isFinite(feedback) && feedback > 0 && feedback < editor);
  }

  @Test
  void invalidViewportAndFontMetricsRejectWithoutCoercion() {
    for (float invalid : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
      assertThrows(
          IllegalArgumentException.class, () -> ExpressionEditorLayout.editor(invalid, 20));
      assertThrows(
          IllegalArgumentException.class, () -> ExpressionEditorLayout.feedback(200, invalid));
    }
  }
}
