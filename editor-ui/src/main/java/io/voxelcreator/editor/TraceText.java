package io.voxelcreator.editor;

/** Bounded native text pages with lossless UTF-16 boundaries. */
final class TraceText {
  static final int PAGE_SIZE = 4096;

  static int count(String text) {
    return text.isEmpty() ? 1 : 1 + (text.length() - 1) / PAGE_SIZE;
  }

  static String page(String text, int page) {
    if (page < 0 || page >= count(text))
      throw new IllegalArgumentException("Page outside trace text");
    return text.substring(
        boundary(text, (long) page * PAGE_SIZE), boundary(text, (long) (page + 1) * PAGE_SIZE));
  }

  private static int boundary(String text, long desired) {
    int at = (int) Math.min(text.length(), desired);
    if (at > 0
        && at < text.length()
        && Character.isLowSurrogate(text.charAt(at))
        && Character.isHighSurrogate(text.charAt(at - 1))) at--;
    return at;
  }
}
