package io.voxelcreator.editor;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** One parser selection owned by the exact project, private draft, item and source buffer. */
final class ExpressionSelection {
  record Range(int start, int end) {}

  private Object project, draft, item;
  private String source;
  private Range range;

  boolean pending() {
    return range != null;
  }

  void request(Object project, Object draft, Object item, String source, int start, int end) {
    cancel();
    if (source.length() > ExpressionEditSession.MAX_CHARACTERS) return;
    this.project = Objects.requireNonNull(project);
    this.draft = Objects.requireNonNull(draft);
    this.item = Objects.requireNonNull(item);
    this.source = source;
    range = utf8Range(source, start, end);
  }

  Range take(Object project, Object draft, Object item, String actual) {
    Range result =
        this.project == project
                && this.draft == draft
                && this.item == item
                && Objects.equals(source, actual)
            ? range
            : null;
    cancel();
    return result;
  }

  void synchronize(Object project, Object draft, Object item, String currentSource) {
    if (range != null
        && (this.project != project
            || this.draft != draft
            || this.item != item
            || !Objects.equals(source, currentSource))) cancel();
  }

  void cancel() {
    project = draft = item = null;
    source = null;
    range = null;
  }

  /** Parser offsets are UTF-16; ImGui's public callback offsets are UTF-8 bytes. */
  static Range utf8Range(String source, int start, int end) {
    int a = Math.clamp(start, 0, source.length());
    int b = Math.clamp(end, a, source.length());
    boolean caret = a == b;
    if (splitsPair(source, a)) a--;
    if (caret) b = a;
    else if (splitsPair(source, b)) b++;
    return new Range(bytes(source, a), bytes(source, b));
  }

  private static boolean splitsPair(String source, int index) {
    return index > 0
        && index < source.length()
        && Character.isHighSurrogate(source.charAt(index - 1))
        && Character.isLowSurrogate(source.charAt(index));
  }

  private static int bytes(String source, int end) {
    return source.substring(0, end).getBytes(StandardCharsets.UTF_8).length;
  }
}
