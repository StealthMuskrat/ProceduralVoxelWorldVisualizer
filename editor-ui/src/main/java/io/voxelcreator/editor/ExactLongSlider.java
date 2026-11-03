package io.voxelcreator.editor;

import java.util.*;

/** Small exact offsets around a signed-long anchor; the long itself never becomes a double. */
final class ExactLongSlider {
  record Range(long anchor, int minimumOffset, int maximumOffset) {}

  record View(long value, Range range, int offset, boolean enabled) {}

  private Range range;
  private String expected;
  private boolean held, blocked;
  private int id, frame = -2;
  private View view;
  private long minimum, maximum;
  private int radius;

  static Range neighborhood(long value) {
    return neighborhood(value, Long.MIN_VALUE, Long.MAX_VALUE, 256);
  }

  static Range neighborhood(long value, long minimum, long maximum, int radius) {
    if (minimum > maximum || value < minimum || value > maximum || radius < 1)
      throw new IllegalArgumentException("Integer slider bounds/radius");
    int low = value < Long.MIN_VALUE + (long) radius ? (int) (Long.MIN_VALUE - value) : -radius;
    int high = value > Long.MAX_VALUE - (long) radius ? (int) (Long.MAX_VALUE - value) : radius;
    if (Math.addExact(value, low) < minimum) low = Math.toIntExact(minimum - value);
    if (Math.addExact(value, high) > maximum) high = Math.toIntExact(maximum - value);
    return new Range(value, low, high);
  }

  View prepare(int currentId, int currentFrame, String accepted, String displayed) {
    return prepare(
        currentId, currentFrame, accepted, displayed, Long.MIN_VALUE, Long.MAX_VALUE, 256);
  }

  View prepare(
      int currentId,
      int currentFrame,
      String accepted,
      String displayed,
      long minimum,
      long maximum,
      int radius) {
    if (minimum > maximum || radius < 1)
      throw new IllegalArgumentException("Integer slider bounds/radius");
    if (id != currentId || currentFrame != frame && currentFrame != frame + 1) {
      held = blocked = false;
      range = null;
    }
    id = currentId;
    frame = currentFrame;
    if (held && (!Objects.equals(expected, accepted) || !Objects.equals(expected, displayed)))
      blocked = true;
    if (held && (this.minimum != minimum || this.maximum != maximum || this.radius != radius))
      blocked = true;
    this.minimum = minimum;
    this.maximum = maximum;
    this.radius = radius;
    long value = 0;
    boolean valid = Objects.equals(accepted, displayed);
    try {
      if (displayed == null || !displayed.matches("[+-]?[0-9]+")) throw new NumberFormatException();
      value = Long.parseLong(displayed);
      if (value < minimum || value > maximum) valid = false;
    } catch (NumberFormatException invalid) {
      valid = false;
    }
    if (!held) range = neighborhood(valid ? value : minimum, minimum, maximum, radius);
    expected = accepted;
    boolean enabled = valid && !blocked;
    int offset = enabled ? Math.toIntExact(Math.subtractExact(value, range.anchor())) : 0;
    view = new View(value, range, offset, enabled);
    return view;
  }

  Optional<String> finish(boolean active, boolean edited, double proposedOffset) {
    if (view == null) throw new IllegalStateException("Prepare the long slider first");
    Optional<String> result = Optional.empty();
    double integer = StrictMath.rint(proposedOffset);
    if (view.enabled()
        && edited
        && Double.isFinite(integer)
        && integer >= range.minimumOffset()
        && integer <= range.maximumOffset()) {
      long next = Math.addExact(range.anchor(), (long) integer);
      if (next != view.value()) {
        expected = Long.toString(next);
        result = Optional.of(expected);
      }
    }
    held = active;
    if (!active) blocked = false;
    return result;
  }
}
