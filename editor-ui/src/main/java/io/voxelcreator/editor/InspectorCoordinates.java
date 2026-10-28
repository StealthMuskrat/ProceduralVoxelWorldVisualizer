package io.voxelcreator.editor;

import io.voxelcreator.model.BookmarkTransactions.*;
import io.voxelcreator.storage.BlockPos;
import java.util.*;

/** Raw Inspector XYZ has no fallback parsed cell; each edit advances publication ownership. */
final class InspectorCoordinates {
  private CoordinateText input = new CoordinateText(Tab.WORLD, new Coordinates(0, 0, 0));
  private long version;
  private boolean checked;
  private BlockPos parsed;
  private String problem = "";

  List<String> axes() {
    return input.axes();
  }

  String text(String axis) {
    return input.text(axis);
  }

  long version() {
    return version;
  }

  void edit(String axis, String value) {
    var previous = input.text(axis);
    input.edit(axis, value);
    if (!previous.equals(value)) {
      version++;
      checked = false;
    }
  }

  void set(BlockPos point) {
    Objects.requireNonNull(point);
    input = new CoordinateText(Tab.WORLD, new Coordinates(point.x(), point.y(), point.z()));
    version++;
    checked = false;
  }

  private void check() {
    if (checked) return;
    try {
      var value = input.parse();
      parsed = new BlockPos(value.x(), value.y(), value.z());
      problem = "";
    } catch (IllegalArgumentException invalid) {
      parsed = null;
      problem = invalid.getMessage();
    }
    checked = true;
  }

  String problem() {
    check();
    return problem;
  }

  BlockPos coordinate() {
    check();
    if (parsed == null) throw new IllegalArgumentException(problem);
    return parsed;
  }

  BlockPos coordinate(Map<String, String> displayed) {
    if (!displayed.keySet().equals(Set.of("X", "Y", "Z")))
      throw new IllegalStateException("All three Inspector coordinate fields are required");
    for (var axis : axes())
      if (!Objects.equals(text(axis), displayed.get(axis)))
        throw new IllegalStateException(
            "Displayed "
                + axis
                + " differs from the Inspector input; correct it before continuing");
    return coordinate();
  }

  boolean differs(BlockPos point) {
    check();
    return parsed == null || !parsed.equals(point);
  }
}
