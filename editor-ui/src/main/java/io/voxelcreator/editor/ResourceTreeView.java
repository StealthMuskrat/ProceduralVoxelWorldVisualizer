package io.voxelcreator.editor;

import java.util.*;

/** Session-only expansion/filter projections, reused until their actual inputs change. */
public final class ResourceTreeView {
  private ResourceTreeIndex index;
  private final Map<UUID, Boolean> expansion = new HashMap<>();
  private final Map<String, List<ResourceTreeIndex.Row>> rows = new HashMap<>();
  private String query = "";
  private long projections;

  public void bind(ResourceTreeIndex current) {
    if (index == current) return;
    index = Objects.requireNonNull(current);
    expansion.keySet().removeIf(id -> index.get(id) == null);
    rows.clear();
  }

  public void open(UUID id, boolean open) {
    var node = index.get(id);
    if (node == null || !node.container()) throw new IllegalArgumentException("Choose a container");
    if (!Objects.equals(expansion.put(id, open), open)) rows.clear();
  }

  public List<ResourceTreeIndex.Row> rows(String root, String filter) {
    String text = filter.strip().toLowerCase(Locale.ROOT);
    if (!text.equals(query)) {
      query = text;
      rows.clear();
    }
    return rows.computeIfAbsent(
        root,
        key -> {
          projections++;
          return index.project(root, expansion, text);
        });
  }

  public long projections() {
    return projections;
  }

  public void clear() {
    index = null;
    expansion.clear();
    rows.clear();
    query = "";
  }
}
