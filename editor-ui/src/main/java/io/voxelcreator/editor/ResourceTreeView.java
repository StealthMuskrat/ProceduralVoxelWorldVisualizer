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

  /** Open only the target's ancestors; resolve the entire route before changing expansion. */
  public String reveal(UUID id) {
    var node = index.get(id);
    if (node == null) return null;
    String root = node.root().equals("operation") ? "world" : node.root();
    if (!io.voxelcreator.model.ResourceGraph.ORGANIZATIONAL_ROOTS.contains(root)) return null;
    var ancestors = new ArrayList<UUID>();
    var seen = new HashSet<UUID>();
    seen.add(id);
    while (node.parent() != null) {
      var parent = index.get(node.parent());
      if (parent == null
          || !parent.container()
          || !seen.add(parent.uuid())
          || !parent.root().equals(root)
          || node.root().equals("operation") != parent.kind().equals("layer")) return null;
      ancestors.add(parent.uuid());
      node = parent;
    }
    boolean changed = false;
    for (UUID ancestor : ancestors)
      if (!Boolean.TRUE.equals(expansion.put(ancestor, true))) changed = true;
    if (changed) rows.clear();
    return root;
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
