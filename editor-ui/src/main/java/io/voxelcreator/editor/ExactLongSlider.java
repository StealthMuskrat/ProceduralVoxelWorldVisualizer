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

  static Range neighborhood(long value) {
    int minimum = value < Long.MIN_VALUE + 256 ? (int) (Long.MIN_VALUE - value) : -256;
    int maximum = value > Long.MAX_VALUE - 256 ? (int) (Long.MAX_VALUE - value) : 256;
    return new Range(value, minimum, maximum);
  }

  View prepare(int currentId, int currentFrame, String accepted, String displayed) {
    if (id != currentId || currentFrame != frame && currentFrame != frame + 1) {
      held = blocked = false;
      range = null;
    }
    id = currentId;
    frame = currentFrame;
    if (held && (!Objects.equals(expected, accepted) || !Objects.equals(expected, displayed)))
      blocked = true;
    long value = 0;
    boolean valid = Objects.equals(accepted, displayed);
    try {
      if (displayed == null || !displayed.matches("[+-]?[0-9]+")) throw new NumberFormatException();
      value = Long.parseLong(displayed);
    } catch (NumberFormatException invalid) {
      valid = false;
    }
    if (!held) range = neighborhood(value);
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
