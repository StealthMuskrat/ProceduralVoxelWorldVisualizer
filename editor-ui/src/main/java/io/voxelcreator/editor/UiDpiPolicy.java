package io.voxelcreator.editor;

/** Pure runtime DPI factors; user preference multipliers remain unchanged. */
final class UiDpiPolicy {
  static final float MINIMUM = .5f, MAXIMUM = 8;

  record Frame(float mainContentScale, float framebufferScale, float userGlobalScale) {
    Frame {
      if (!valid(mainContentScale)
          || mainContentScale < MINIMUM
          || mainContentScale > MAXIMUM
          || !valid(framebufferScale)
          || framebufferScale < MINIMUM
          || framebufferScale > MAXIMUM)
        throw new IllegalArgumentException("Invalid normalized DPI sample");
      UiScalePreferences.validateScale(userGlobalScale);
    }

    float fontGlobalScale() {
      return userGlobalScale * mainContentScale / framebufferScale;
    }

    float viewportRatio(float contentScale) {
      return factor(contentScale, mainContentScale) / mainContentScale;
    }

    float effective(float window, float contentScale) {
      UiScalePreferences.validateScale(window);
      return fontGlobalScale() * window * viewportRatio(contentScale);
    }
  }

  private float content = 1, framebuffer = 1;

  Frame sample(float rawContent, float rawFramebuffer, float userGlobal) {
    UiScalePreferences.validateScale(userGlobal);
    content = factor(rawContent, content);
    framebuffer = factor(rawFramebuffer, framebuffer);
    return new Frame(content, framebuffer, userGlobal);
  }

  static float factor(float sample, float fallback) {
    return valid(sample) ? Math.max(MINIMUM, Math.min(MAXIMUM, sample)) : fallback;
  }

  static float inheritedFont(float parentFont, float parentContent, float targetContent) {
    if (!valid(parentFont) || !valid(parentContent) || !valid(targetContent))
      throw new IllegalArgumentException("Invalid inherited font metrics");
    double result = (double) parentFont * targetContent / parentContent;
    if (result > Float.MAX_VALUE || result < Float.MIN_VALUE)
      throw new IllegalArgumentException("Inherited font is outside native float bounds");
    return (float) result;
  }

  private static boolean valid(float value) {
    return Float.isFinite(value) && value > 0;
  }
}
