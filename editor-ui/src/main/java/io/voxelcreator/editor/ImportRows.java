package io.voxelcreator.editor;

import io.voxelcreator.model.ResourceImport;
import java.util.*;

/** Immutable cached presentation of one exact import plan; never builds or changes a candidate. */
final class ImportRows {
  static final List<String> KINDS =
      List.of("All", "noise2D", "noise3D", "folder", "layer", "voxel", "set", "group");
  static final List<String> RESOLUTIONS = List.of("All", "COPY", "REUSE", "SKIP");

  record Filter(String query, String kind, String resolution) {
    Filter {
      query = Objects.requireNonNull(query).strip().toLowerCase(Locale.ROOT);
      if (!KINDS.contains(kind) || !RESOLUTIONS.contains(resolution))
        throw new IllegalArgumentException("Unknown import filter");
    }
  }

  record Counts(int copy, int reuse, int skip) {}

  record View(List<ResourceImport.Row> rows, Counts counts, int total) {
    View {
      rows = List.copyOf(rows);
    }
  }

  private ResourceImport.Plan plan;
  private Filter filter;
  private Counts counts;
  private View view;
  private long evaluations;

  View view(ResourceImport.Plan current, Filter next) {
    Objects.requireNonNull(current);
    Objects.requireNonNull(next);
    if (plan == current && next.equals(filter)) return view;
    if (plan != current) {
      int copies = 0, reuses = 0, skips = 0;
      for (var row : current.rows()) {
        if (row.resolution().startsWith("COPY")) copies++;
        else if (row.resolution().startsWith("REUSE")) reuses++;
        else skips++;
      }
      counts = new Counts(copies, reuses, skips);
    }
    var rows = current.rows().stream().filter(row -> matches(row, next)).toList();
    view = new View(rows, counts, current.rows().size());
    plan = current;
    filter = next;
    evaluations++;
    return view;
  }

  private static boolean matches(ResourceImport.Row row, Filter filter) {
    if (!filter.kind().equals("All") && !filter.kind().equals(row.kind())) return false;
    if (!filter.resolution().equals("All") && !row.resolution().startsWith(filter.resolution()))
      return false;
    return (row.kind()
            + " "
            + row.id()
            + " "
            + row.uuid()
            + " "
            + row.resolution()
            + " "
            + Objects.toString(row.targetId(), "")
            + " "
            + Objects.toString(row.targetUuid(), "")
            + " "
            + Objects.toString(row.existing(), ""))
        .toLowerCase(Locale.ROOT)
        .contains(filter.query());
  }

  long evaluations() {
    return evaluations;
  }

  void clear() {
    plan = null;
    filter = null;
    counts = null;
    view = null;
  }
}
