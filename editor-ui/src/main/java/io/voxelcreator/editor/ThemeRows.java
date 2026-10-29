package io.voxelcreator.editor;

import java.util.*;

/** Cached immutable palette projection; stable keys survive filtering and clipping. */
final class ThemeRows {
  record Row(int color, String role, String label, String group) {
    String key() {
      return role == null ? "color:" + color : "status:" + role;
    }
  }

  static final List<String> GROUPS;
  private static final List<Row> ALL;

  static {
    var rows = new ArrayList<Row>();
    for (String role : ThemePalette.ROLES)
      rows.add(new Row(-1, role, role + " status", "Status messages"));
    for (var color : ThemeRegistry.COLORS)
      rows.add(new Row(color.key(), null, color.name(), color.group()));
    ALL = List.copyOf(rows);
    var groups = new ArrayList<String>();
    groups.add("All");
    ThemeRegistry.COLORS.stream().map(ThemeRegistry.Color::group).distinct().forEach(groups::add);
    groups.add("Status messages");
    GROUPS = List.copyOf(groups);
  }

  private String query, group;
  private List<Row> rows = List.of();
  private long queries;

  List<Row> get(String selected, String text) {
    if (!GROUPS.contains(selected)) throw new IllegalArgumentException("Unknown palette group");
    String normalized = text.strip().toLowerCase(Locale.ROOT);
    if (selected.equals(group) && normalized.equals(query)) return rows;
    rows =
        ALL.stream()
            .filter(
                row ->
                    (selected.equals("All") || selected.equals(row.group()))
                        && (row.label() + " " + row.group())
                            .toLowerCase(Locale.ROOT)
                            .contains(normalized))
            .toList();
    group = selected;
    query = normalized;
    queries++;
    return rows;
  }

  long queries() {
    return queries;
  }
}
