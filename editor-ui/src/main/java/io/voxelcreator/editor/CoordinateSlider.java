package io.voxelcreator.editor;

import java.util.*;

/** Exact integer slider proposals; raw text remains authoritative and drawing never edits it. */
final class CoordinateSlider {
  record Range(int minimum, int maximum) {}

  record View(int value, Range range, boolean enabled) {}

  private Range heldRange;
  private String expected;
  private boolean held, blocked;
  private int id, frame = -2;
  private View view;

  static Range neighborhood(int value) {
    return new Range(
        (int) Math.max(Integer.MIN_VALUE, (long) value - 256),
        (int) Math.min(Integer.MAX_VALUE, (long) value + 256));
  }

  View prepare(int currentId, int currentFrame, String accepted, String displayed) {
    if (id != currentId || currentFrame != frame && currentFrame != frame + 1) {
      held = blocked = false;
      heldRange = null;
    }
    id = currentId;
    frame = currentFrame;
    if (held && (!Objects.equals(expected, accepted) || !Objects.equals(expected, displayed)))
      blocked = true;
    int value = 0;
    boolean valid = Objects.equals(accepted, displayed);
    try {
      value = CoordinateText.parseAxis("Coordinate", displayed);
    } catch (IllegalArgumentException invalid) {
      valid = false;
    }
    if (!held) heldRange = neighborhood(value);
    expected = accepted;
    view = new View(value, heldRange, valid && !blocked);
    return view;
  }

  Optional<String> finish(boolean active, boolean edited, double proposed) {
    if (view == null) throw new IllegalStateException("Prepare the coordinate slider first");
    Optional<String> result = Optional.empty();
    double integer = StrictMath.rint(proposed);
    if (view.enabled()
        && edited
        && Double.isFinite(integer)
        && integer >= view.range().minimum()
        && integer <= view.range().maximum()
        && integer != view.value()) {
      expected = Integer.toString((int) integer);
      result = Optional.of(expected);
    }
    held = active;
    if (!active) blocked = false;
    return result;
  }
}
