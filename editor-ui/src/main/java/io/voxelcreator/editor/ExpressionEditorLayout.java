package io.voxelcreator.editor;

/** Measured expanded-editor viewports; document text and staging stay with the existing session. */
final class ExpressionEditorLayout {
  static float editor(float bodyHeight, float lineHeight) {
    return portion(bodyHeight, lineHeight, .5, 8);
  }

  static float feedback(float bodyHeight, float lineHeight) {
    return portion(bodyHeight, lineHeight, .3, 4);
  }

  private static float portion(float height, float line, double share, int lines) {
    if (!Float.isFinite(height) || height <= 0 || !Float.isFinite(line) || line <= 0)
      throw new IllegalArgumentException("Invalid expanded expression metrics");
    return (float) Math.max(1, Math.min(share * height, lines * (double) line));
  }

  private ExpressionEditorLayout() {}
}
