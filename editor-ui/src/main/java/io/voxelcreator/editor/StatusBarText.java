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
}
