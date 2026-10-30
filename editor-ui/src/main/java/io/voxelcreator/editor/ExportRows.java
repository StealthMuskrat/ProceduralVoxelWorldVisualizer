package io.voxelcreator.editor;

import java.util.*;

/** Catalog-only cached filtering; choosing rows never rebuilds a dependency payload here. */
final class ExportRows {
  private ResourceTreeIndex catalog;
  private String query;
  private Set<UUID> selected;
  private boolean selectedOnly;
  private List<ResourceTreeIndex.Node> all = List.of(), rows = List.of();
  private long evaluations;

  List<ResourceTreeIndex.Node> rows(
      ResourceTreeIndex source, String text, boolean only, Set<UUID> selection) {
    Objects.requireNonNull(source);
    Objects.requireNonNull(selection);
    String next = Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
    if (catalog == source
        && next.equals(query)
        && only == selectedOnly
        && (!only || selection.equals(selected))) return rows;
    if (catalog != source) all = source.all();
    rows =
        all.stream()
            .filter(
                n ->
                    (!only || selection.contains(n.uuid()))
                        && (n.kind() + " " + n.id() + " " + n.uuid())
                            .toLowerCase(Locale.ROOT)
                            .contains(next))
            .toList();
    catalog = source;
    query = next;
    selectedOnly = only;
    selected = only ? Set.copyOf(selection) : Set.of();
    evaluations++;
    return rows;
  }

  long evaluations() {
    return evaluations;
  }

  void clear() {
    catalog = null;
    query = null;
    selected = null;
    all = rows = List.of();
  }
}
