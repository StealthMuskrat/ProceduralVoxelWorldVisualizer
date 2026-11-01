package io.voxelcreator.editor;

/** Bounded, single-line code-point-safe presentation without changing the original event/status. */
final class StatusBarText {
  private StatusBarText() {}

  static String clip(String text, int limit) {
    if (limit <= 0) throw new IllegalArgumentException("Invalid status bound");
    if (text == null || text.isEmpty()) return "Ready";
    var out = new StringBuilder(Math.min(limit, text.length()));
    int offset = 0, count = 0;
    while (offset < text.length() && count++ < limit) {
      int code = text.codePointAt(offset);
      offset += Character.charCount(code);
      out.appendCodePoint(code == '\n' || code == '\r' || code == '\t' ? ' ' : code);
    }
    if (offset < text.length()) out.append('…');
    return out.toString();
  }

  /** Retained diagnostic cap includes the ellipsis and never splits a surrogate pair. */
  static String bounded(String text, int limit) {
    String visible = clip(text, limit);
    if (visible.length() <= limit) return visible;
    int end = limit - 1;
    if (end > 0 && Character.isHighSurrogate(visible.charAt(end - 1))) end--;
    return visible.substring(0, end) + "…";
  }
}
