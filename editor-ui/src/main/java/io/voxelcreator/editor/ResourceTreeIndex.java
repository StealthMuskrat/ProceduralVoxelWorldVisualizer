package io.voxelcreator.editor;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Immutable revision-scoped browser metadata. Building/projection never serializes a project. */
public final class ResourceTreeIndex {
  public record Scope(String root, UUID parent) {}

  public record Swatch(int r, int g, int b) {}

  public record Node(
      UUID uuid, String id, String kind, String root, UUID parent, boolean enabled, Swatch swatch) {
    public Scope scope() {
      return new Scope(root, parent);
    }

    public boolean container() {
      return kind.equals("folder") || kind.equals("layer");
    }
  }

  public record Row(Node node, int depth, boolean open) {}

  private final Map<UUID, Node> nodes;
  private final Map<Scope, List<Node>> children;
  private final Map<String, List<Node>> kinds;

  public ResourceTreeIndex(ProjectData project) {
    var all = new LinkedHashMap<UUID, Node>();
    var adjacency = new HashMap<Scope, List<Node>>();
    var byKind = new HashMap<String, List<Node>>();
    for (var entry : ResourceGraph.entries(project)) {
      var resource = entry.resource();
      boolean enabled =
          resource instanceof Layer l
              ? l.enabled
              : resource instanceof Operation op ? op.enabled : true;
      Swatch swatch =
          resource instanceof VoxelType t ? new Swatch(t.color.r, t.color.g, t.color.b) : null;
      var node =
          new Node(
              resource.uuid,
              resource.id,
              entry.kind(),
              entry.root(),
              entry.parent(),
              enabled,
              swatch);
      if (all.put(node.uuid(), node) != null)
        throw new IllegalArgumentException("Duplicate browser UUID");
      adjacency.computeIfAbsent(node.scope(), key -> new ArrayList<>()).add(node);
      byKind.computeIfAbsent(node.kind(), key -> new ArrayList<>()).add(node);
    }
    for (var folder : project.folders) {
      var list = adjacency.get(new Scope(folder.rootKind, folder.uuid));
      if (list == null) continue;
      var ranks = new HashMap<UUID, Integer>();
      for (int i = 0; i < folder.childOrder.size(); i++) ranks.put(folder.childOrder.get(i), i);
      list.sort(Comparator.comparingInt(n -> ranks.getOrDefault(n.uuid(), Integer.MAX_VALUE)));
    }
    var frozenChildren = new HashMap<Scope, List<Node>>();
    adjacency.forEach((key, value) -> frozenChildren.put(key, List.copyOf(value)));
    var frozenKinds = new HashMap<String, List<Node>>();
    byKind.forEach((key, value) -> frozenKinds.put(key, List.copyOf(value)));
    nodes = Collections.unmodifiableMap(all);
    children = Map.copyOf(frozenChildren);
    kinds = Map.copyOf(frozenKinds);
  }

  public int size() {
    return nodes.size();
  }

  public Node get(UUID id) {
    return nodes.get(id);
  }

  public List<Node> all() {
    return List.copyOf(nodes.values());
  }

  public List<Node> kind(String kind, String query) {
    String text = normalize(query);
    return kinds.getOrDefault(kind, List.of()).stream()
        .filter(n -> normalize(n.id()).contains(text))
        .toList();
  }

  public List<Node> noises(String query) {
    var result = new ArrayList<Node>();
    result.addAll(kind("noise2D", query));
    result.addAll(kind("noise3D", query));
    return List.copyOf(result);
  }

  private static String normalize(String text) {
    return Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
  }

  private List<Node> children(Node node) {
    return children.getOrDefault(
        new Scope(node.kind().equals("layer") ? "operation" : node.root(), node.uuid()), List.of());
  }

  private record Pending(Node node, int depth) {}

  private static void push(Deque<Pending> stack, List<Node> nodes, int depth) {
    for (int i = nodes.size() - 1; i >= 0; i--) stack.push(new Pending(nodes.get(i), depth));
  }

  public List<Row> project(String root, Map<UUID, Boolean> expansion, String query) {
    String text = normalize(query);
    var roots = children.getOrDefault(new Scope(root, null), List.of());
    var preorder = new ArrayList<Pending>();
    var stack = new ArrayDeque<Pending>();
    var seen = new HashSet<UUID>();
    push(stack, roots, 0);
    while (!stack.isEmpty()) {
      var item = stack.pop();
      if (!seen.add(item.node().uuid()))
        throw new IllegalArgumentException("Browser hierarchy cycle");
      preorder.add(item);
      if (item.node().container()) push(stack, children(item.node()), item.depth() + 1);
    }
    var included = new HashSet<UUID>();
    if (!text.isEmpty()) {
      var inherited = new HashSet<UUID>();
      for (var item : preorder) {
        var node = item.node();
        boolean match = normalize(node.id()).contains(text) || inherited.contains(node.parent());
        if (match) {
          included.add(node.uuid());
          if (node.container()) inherited.add(node.uuid());
        }
      }
      for (int i = preorder.size() - 1; i >= 0; i--) {
        var node = preorder.get(i).node();
        if (included.contains(node.uuid()) && node.parent() != null) included.add(node.parent());
      }
    }
    var result = new ArrayList<Row>();
    int hiddenDepth = Integer.MAX_VALUE;
    for (var item : preorder) {
      var node = item.node();
      if (item.depth() <= hiddenDepth) hiddenDepth = Integer.MAX_VALUE;
      if (item.depth() > hiddenDepth || !text.isEmpty() && !included.contains(node.uuid()))
        continue;
      boolean open =
          node.container()
              && (!text.isEmpty()
                  || expansion.getOrDefault(node.uuid(), node.kind().equals("layer")));
      result.add(new Row(node, item.depth(), open));
      if (node.container() && !open) hiddenDepth = item.depth();
    }
    return List.copyOf(result);
  }

  /** Only displayed siblings between the two displayed endpoints enter a Shift range. */
  public static List<UUID> range(List<Row> rows, UUID anchor, UUID target) {
    int a = -1, b = -1;
    for (int i = 0; i < rows.size(); i++) {
      if (rows.get(i).node().uuid().equals(anchor)) a = i;
      if (rows.get(i).node().uuid().equals(target)) b = i;
    }
    if (a < 0 || b < 0 || !rows.get(a).node().scope().equals(rows.get(b).node().scope()))
      return List.of();
    var scope = rows.get(a).node().scope();
    var result = new ArrayList<UUID>();
    for (int i = Math.min(a, b); i <= Math.max(a, b); i++)
      if (rows.get(i).node().scope().equals(scope)) result.add(rows.get(i).node().uuid());
    return List.copyOf(result);
  }

  /** Project replacement and committed revision changes invalidate; navigation/scrolling do not. */
  public static final class Cache {
    private ProjectData project;
    private long revision;
    private ResourceTreeIndex index;
    private long builds;

    public ResourceTreeIndex get(ProjectData current, long currentRevision) {
      if (current != project || currentRevision != revision || index == null) {
        index = new ResourceTreeIndex(current);
        project = current;
        revision = currentRevision;
        builds++;
      }
      return index;
    }

    public long builds() {
      return builds;
    }

    public void clear() {
      project = null;
      index = null;
    }
  }
}
