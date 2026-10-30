package io.voxelcreator.editor;

import java.util.*;

/** One immutable observation projection and an exact selected-row review. */
final class TraceObservationSelection {
  record Review(TraceSnapshot snapshot, int step, int row, TraceSnapshot.Row value) {}

  private TraceSnapshot owner;
  private int step = -1, selected = -1;
  private String query;
  private List<Integer> rows = List.of();
  private long builds;

  List<Integer> observe(TraceSnapshot snapshot, int step, String raw) {
    String normalized = Objects.requireNonNullElse(raw, "").strip().toLowerCase(Locale.ROOT);
    if (owner != snapshot || this.step != step || !normalized.equals(query)) {
      var source = snapshot.rows(step);
      var filtered = new ArrayList<Integer>();
      for (int i = 0; i < source.size(); i++) {
        var row = source.get(i);
        if (normalized.isEmpty()
            || (row.label() + " " + row.value()).toLowerCase(Locale.ROOT).contains(normalized))
          filtered.add(i);
      }
      owner = snapshot;
      this.step = step;
      query = normalized;
      rows = List.copyOf(filtered);
      selected = rows.isEmpty() ? -1 : rows.getFirst();
      builds++;
    }
    return rows;
  }

  void select(int projectionIndex) {
    if (projectionIndex < 0 || projectionIndex >= rows.size())
      throw new IllegalArgumentException("Unknown trace observation");
    selected = rows.get(projectionIndex);
  }

  int index() {
    return selected;
  }

  Review review() {
    return owner == null || selected < 0
        ? null
        : new Review(owner, step, selected, owner.rows(step).get(selected));
  }

  boolean current(Review review) {
    return owner != null
        && review != null
        && owner == review.snapshot()
        && step == review.step()
        && selected == review.row()
        && owner.rows(step).get(selected) == review.value();
  }

  void clear() {
    owner = null;
    step = selected = -1;
    query = null;
    rows = List.of();
  }

  long builds() {
    return builds;
  }
}
