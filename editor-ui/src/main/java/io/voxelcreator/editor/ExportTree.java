package io.voxelcreator.editor;

import java.util.*;

/** Cached iterative export hierarchy over immutable catalog metadata; no candidate construction. */
final class ExportTree {
  record Row(
      ResourceTreeIndex.Node node, int depth, boolean open, boolean context, boolean forced) {}

  private static final List<String> ROOTS =
      List.of("noise2D", "noise3D", "world", "voxel", "group", "set");
  private final ExportRows matches = new ExportRows();
  private final Map<UUID, Boolean> expansion = new HashMap<>();
  private ResourceTreeIndex catalog;
  private List<ResourceTreeIndex.Row> preorder = List.of();
  private List<ResourceTreeIndex.Node> input;
  private List<Row> rows = List.of();
  private boolean lastReveal, lastDescendants, lastAncestors;
  private List<ResourceTreeIndex.Node> includedInput, includedMapped;
  private long version, shownVersion = -1, projections, builds;

  List<Row> choices(
      ResourceTreeIndex source, String text, boolean selectedOnly, Set<UUID> selected) {
    bind(source);
    var exact = matches.rows(source, text, selectedOnly, selected);
    boolean reveal = selectedOnly || !text.strip().isEmpty();
    return project(exact, reveal, !selectedOnly && !text.strip().isEmpty(), true);
  }

  List<Row> included(ResourceTreeIndex source, List<ResourceTreeIndex.Node> included) {
    bind(source);
    Objects.requireNonNull(included);
    if (includedInput != included) {
      var mapped = new ArrayList<ResourceTreeIndex.Node>();
      for (var node : included) {
        var actual = source.get(node.uuid());
        if (actual == null || !actual.equals(node))
          throw new IllegalArgumentException("Included metadata differs from its source catalog");
        mapped.add(actual);
      }
      includedMapped = List.copyOf(mapped);
      includedInput = included;
    }
    return project(includedMapped, false, false, false);
  }

  void open(UUID id, boolean open) {
    if (catalog == null || catalog.get(id) == null || !catalog.get(id).container())
      throw new IllegalArgumentException("Choose an export container");
    boolean previous = expansion.getOrDefault(id, catalog.get(id).kind().equals("layer"));
    expansion.put(id, open);
    if (previous != open) version++;
  }

  private void bind(ResourceTreeIndex source) {
    Objects.requireNonNull(source);
    if (catalog == source) return;
    var allOpen = new HashMap<UUID, Boolean>();
    for (var node : source.all()) if (node.container()) allOpen.put(node.uuid(), true);
    var ordered = new ArrayList<ResourceTreeIndex.Row>();
    for (String root : ROOTS) ordered.addAll(source.project(root, allOpen, ""));
    if (ordered.size() != source.size())
      throw new IllegalArgumentException("Incomplete export hierarchy");
    catalog = source;
    preorder = List.copyOf(ordered);
    expansion.keySet().removeIf(id -> source.get(id) == null || !source.get(id).container());
    input = includedInput = includedMapped = null;
    shownVersion = -1;
    builds++;
  }

  private List<Row> project(
      List<ResourceTreeIndex.Node> exact, boolean reveal, boolean descendants, boolean ancestors) {
    if (input == exact
        && shownVersion == version
        && lastReveal == reveal
        && lastDescendants == descendants
        && lastAncestors == ancestors) return rows;
    var matching = new HashSet<UUID>();
    for (var node : exact) {
      if (catalog.get(node.uuid()) != node)
        throw new IllegalArgumentException("Export rows belong to another catalog");
      matching.add(node.uuid());
    }
    var eligible = new HashSet<>(matching);
    if (descendants) {
      var inherited = new HashSet<UUID>();
      for (var row : preorder) {
        var node = row.node();
        if (matching.contains(node.uuid()) || inherited.contains(node.parent())) {
          eligible.add(node.uuid());
          if (node.container()) inherited.add(node.uuid());
        }
      }
    }
    // Reverse preorder closes ancestry in one pass, including a deep chain.
    if (ancestors)
      for (int i = preorder.size() - 1; i >= 0; i--) {
        var node = preorder.get(i).node();
        if (eligible.contains(node.uuid()) && node.parent() != null) eligible.add(node.parent());
      }
    var result = new ArrayList<Row>();
    int hiddenDepth = Integer.MAX_VALUE;
    for (var item : preorder) {
      if (item.depth() <= hiddenDepth) hiddenDepth = Integer.MAX_VALUE;
      if (item.depth() > hiddenDepth || !eligible.contains(item.node().uuid())) continue;
      var node = item.node();
      boolean open =
          node.container()
              && (reveal || expansion.getOrDefault(node.uuid(), node.kind().equals("layer")));
      result.add(
          new Row(
              node,
              item.depth(),
              open,
              !matching.contains(node.uuid()),
              node.container() && reveal));
      if (node.container() && !open) hiddenDepth = item.depth();
    }
    rows = List.copyOf(result);
    input = exact;
    shownVersion = version;
    lastReveal = reveal;
    lastDescendants = descendants;
    lastAncestors = ancestors;
    projections++;
    return rows;
  }

  long projections() {
    return projections;
  }

  long builds() {
    return builds;
  }

  void clear() {
    catalog = null;
    preorder = List.of();
    input = includedInput = includedMapped = null;
    rows = List.of();
    expansion.clear();
    matches.clear();
    version = 0;
    shownVersion = -1;
  }
}
