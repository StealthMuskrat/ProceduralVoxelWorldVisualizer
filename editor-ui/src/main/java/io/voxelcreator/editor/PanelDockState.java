package io.voxelcreator.editor;

import java.util.OptionalInt;
import java.util.function.IntPredicate;

/** Store numeric dock identities, never native node pointers, across frame-boundary actions. */
final class PanelDockState {
  private int previous;
  private Integer pending;

  void observe(int dock) {
    if (dock != 0) previous = dock;
  }

  void undock() {
    pending = 0;
  }

  boolean canRedock(IntPredicate exists) {
    return previous != 0 && exists.test(previous);
  }

  boolean redock(IntPredicate exists) {
    if (!canRedock(exists)) return false;
    pending = previous;
    return true;
  }

  OptionalInt take(IntPredicate exists) {
    if (pending == null) return OptionalInt.empty();
    int target = pending;
    pending = null;
    return target == 0 || exists.test(target) ? OptionalInt.of(target) : OptionalInt.empty();
  }

  void reset() {
    previous = 0;
    pending = null;
  }
}
