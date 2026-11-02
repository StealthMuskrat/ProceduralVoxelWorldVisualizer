package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.TypeInterval;
import io.voxelcreator.model.WorldSemantics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deterministic private defaults; never edits or reorders existing authored intervals. */
final class WorldIntervalDefaults {
  record Proposal(TypeInterval interval, String message) {
    boolean available() {
      return interval != null;
    }
  }

  private WorldIntervalDefaults() {}

  static Proposal suggest(List<TypeInterval> authored, UUID type) {
    if (type == null)
      return unavailable("Create a Voxel Type in Voxel Manager before adding an interval.");
    if (authored.size() >= 256) return unavailable("The 256 type-interval limit has been reached.");
    var sorted = new ArrayList<>(authored);
    for (var r : sorted)
      if (r == null
          || !Double.isFinite(r.min)
          || !Double.isFinite(r.max)
          || r.min > r.max
          || r.min == r.max && !(r.includeMin && r.includeMax)) return invalid();
    sorted.sort(WorldSemantics::compareTypeIntervals);
    for (int i = 1; i < sorted.size(); i++) {
      var a = sorted.get(i - 1);
      var b = sorted.get(i);
      if (a.max > b.min || a.max == b.min && a.includeMax && b.includeMin) return invalid();
    }
    if (sorted.isEmpty()) return proposal(0, 1, true, false, type);
    var last = sorted.getLast();
    if (last.max < Double.MAX_VALUE) {
      double end = nextEnd(last.max);
      boolean includeMin = !last.includeMax;
      // Adjacent doubles need an included endpoint to select any representable field value.
      boolean includeMax = !includeMin && Math.nextUp(last.max) == end;
      return proposal(last.max, end, includeMin, includeMax, type);
    }
    if (!last.includeMax) return proposal(Double.MAX_VALUE, Double.MAX_VALUE, true, true, type);
    // Upper space is exhausted: use the first available internal gap, then the lower prefix.
    for (int i = 1; i < sorted.size(); i++) {
      var a = sorted.get(i - 1);
      var b = sorted.get(i);
      if (a.max == b.min) {
        if (!a.includeMax && !b.includeMin) return proposal(a.max, a.max, true, true, type);
      } else if (a.max < b.min) {
        double end = Math.min(nextEnd(a.max), b.min);
        boolean includeMin = !a.includeMax;
        boolean includeMax = end == b.min ? !b.includeMin : false;
        if (!hasPoint(a.max, end, includeMin, includeMax) && end < b.min) includeMax = true;
        if (hasPoint(a.max, end, includeMin, includeMax))
          return proposal(a.max, end, includeMin, includeMax, type);
      }
    }
    var first = sorted.getFirst();
    if (first.min > -Double.MAX_VALUE) {
      double start = first.min - 1;
      if (!Double.isFinite(start) || start >= first.min) start = Math.nextDown(first.min);
      return proposal(start, first.min, true, !first.includeMin, type);
    }
    if (!first.includeMin) return proposal(-Double.MAX_VALUE, -Double.MAX_VALUE, true, true, type);
    return unavailable("No unused finite field value remains. Edit or remove an interval first.");
  }

  private static double nextEnd(double start) {
    double end = start + 1;
    return Double.isFinite(end) && end > start ? end : Math.nextUp(start);
  }

  private static boolean hasPoint(double min, double max, boolean includeMin, boolean includeMax) {
    double first = includeMin ? min : Math.nextUp(min);
    return Double.isFinite(first) && (first < max || first == max && includeMax);
  }

  private static Proposal proposal(
      double min, double max, boolean includeMin, boolean includeMax, UUID type) {
    var value = new TypeInterval();
    value.min = min;
    value.max = max;
    value.includeMin = includeMin;
    value.includeMax = includeMax;
    value.voxelTypeUuid = type;
    return new Proposal(
        value,
        "New interval: "
            + (includeMin ? "[" : "(")
            + ScalarDisplay.exact(min)
            + ", "
            + ScalarDisplay.exact(max)
            + (includeMax ? "]" : ")")
            + ". Existing ranges stay unchanged.");
  }

  private static Proposal invalid() {
    return unavailable(
        "Correct the existing finite, non-overlapping interval ranges before adding another.");
  }

  private static Proposal unavailable(String message) {
    return new Proposal(null, message);
  }
}
