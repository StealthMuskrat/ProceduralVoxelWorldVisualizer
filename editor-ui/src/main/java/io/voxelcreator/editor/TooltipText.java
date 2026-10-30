package io.voxelcreator.editor;

import java.util.*;
import java.util.function.Function;

/** Bounded visible preview only. Complete source strings stay with their original owners. */
final class TooltipText {
  static final int MAX_CHARACTERS = 4096;

  record Size(float width, float height) {
    Size {
      if (!Float.isFinite(width) || !Float.isFinite(height) || width < 0 || height < 0)
        throw new IllegalArgumentException("Invalid tooltip text measurement");
    }
  }

  record Preview(String text, boolean truncated, Size size) {}

  static Preview fit(String source, float width, float height, Function<String, Size> measure) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(measure);
    if (!Float.isFinite(width) || !Float.isFinite(height) || width <= 0 || height <= 0)
      throw new IllegalArgumentException("Invalid tooltip text budget");
    var ends = new ArrayList<Integer>();
    ends.add(0);
    int end = 0;
    while (end < source.length()) {
      int next = end + Character.charCount(source.codePointAt(end));
      if (next > MAX_CHARACTERS) break;
      ends.add(next);
      end = next;
    }
    if (end == source.length()) {
      var size = Objects.requireNonNull(measure.apply(source));
      if (fits(size, width, height)) return new Preview(source, false, size);
    }
    var suffix = Objects.requireNonNull(measure.apply("..."));
    if (!fits(suffix, width, height)) return new Preview("", !source.isEmpty(), new Size(0, 0));
    int low = 0, high = ends.size() - 1, accepted = 0;
    Size acceptedSize = suffix;
    while (low <= high) {
      int middle = low + (high - low) / 2;
      var size =
          Objects.requireNonNull(measure.apply(source.substring(0, ends.get(middle)) + "..."));
      if (fits(size, width, height)) {
        accepted = middle;
        acceptedSize = size;
        low = middle + 1;
      } else high = middle - 1;
    }
    return new Preview(source.substring(0, ends.get(accepted)) + "...", true, acceptedSize);
  }

  private static boolean fits(Size size, float width, float height) {
    return size.width() <= width && size.height() <= height;
  }

  private TooltipText() {}
}
