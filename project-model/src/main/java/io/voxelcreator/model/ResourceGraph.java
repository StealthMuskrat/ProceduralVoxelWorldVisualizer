package io.voxelcreator.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Shared identity/reference operations for tree edits, clipboard and portable packages. */
public final class ResourceGraph {
  public record Entry(String kind, String root, UUID parent, Identity resource) {
    public UUID uuid() {
      return resource.uuid;
    }

    public String scope() {
      return root + ":" + parent;
    }
  }

  public static List<Entry> entries(ProjectData p) {
    var result = new ArrayList<Entry>();
    for (var f : p.folders) result.add(new Entry("folder", f.rootKind, f.parentFolderUuid, f));
    for (var n : p.noise2D) result.add(new Entry("noise2D", "noise2D", n.parentFolderUuid, n));
    for (var n : p.noise3D) result.add(new Entry("noise3D", "noise3D", n.parentFolderUuid, n));
    for (var t : p.voxelTypes) result.add(new Entry("voxel", "voxel", null, t));
    for (var t : p.voxelGroups) result.add(new Entry("group", "group", null, t));
    for (var t : p.voxelSets) result.add(new Entry("set", "set", null, t));
    for (var l : p.worldGenerator.layers) {
      result.add(new Entry("layer", "world", l.parentFolderUuid, l));
      for (var op : l.operations) result.add(new Entry("operation", "operation", l.uuid, op));
    }
    return List.copyOf(result);
  }

  public static Entry find(ProjectData p, UUID id) {
    return entries(p).stream().filter(e -> e.uuid().equals(id)).findFirst().orElse(null);
  }

  private static boolean referenceKey(String key) {
    return key.endsWith("Uuid")
        || key.endsWith("Uuids")
        || key.equals("reference")
        || key.equals("childOrder");
  }

  public static Set<UUID> references(Object resource) {
    var refs = new LinkedHashSet<UUID>();
    scan(ProjectIO.JSON.valueToTree(resource), false, refs);
    return Set.copyOf(refs);
  }

  private static void scan(JsonNode n, boolean reference, Set<UUID> refs) {
    if (n.isObject()) {
      n.fields()
          .forEachRemaining(
              e -> {
                if (!e.getKey().equals("uuid") && !e.getKey().equals("childOrder"))
                  scan(e.getValue(), referenceKey(e.getKey()), refs);
              });
    } else if (n.isArray()) n.forEach(c -> scan(c, reference, refs));
    else if (reference && n.isTextual())
      try {
        refs.add(UUID.fromString(n.textValue()));
      } catch (IllegalArgumentException ignored) {
      }
  }

  public static List<Entry> dependents(ProjectData p, Set<UUID> deleted) {
    return entries(p).stream()
        .filter(
            e ->
                !deleted.contains(e.uuid())
                    && references(e.resource).stream().anyMatch(deleted::contains))
        .toList();
  }

  public static Set<UUID> subtree(ProjectData p, Collection<UUID> selected) {
    var children = new HashMap<UUID, List<UUID>>();
    for (var entry : entries(p))
      if (entry.parent() != null)
        children.computeIfAbsent(entry.parent(), key -> new ArrayList<>()).add(entry.uuid());
    var result = new LinkedHashSet<>(selected);
    var queue = new ArrayDeque<UUID>(selected);
    while (!queue.isEmpty())
      for (UUID child : children.getOrDefault(queue.removeFirst(), List.of()))
        if (result.add(child)) queue.addLast(child);
    return result;
  }

  public static ProjectData selection(
      ProjectData p, Collection<UUID> selected, boolean dependencies) {
    var included = new LinkedHashSet<>(subtree(p, selected));
    if (dependencies) {
      for (var layer : p.worldGenerator.layers)
        if (layer.operations.stream().anyMatch(o -> included.contains(o.uuid))) {
          for (UUID ref : references(layer.defaults)) if (find(p, ref) != null) included.add(ref);
          if (layer.parentFolderUuid != null) included.add(layer.parentFolderUuid);
        }
      boolean changed;
      do {
        changed = false;
        for (var e : entries(p))
          if (included.contains(e.uuid()))
            for (UUID ref : references(e.resource))
              if (find(p, ref) != null && included.add(ref)) changed = true;
      } while (changed);
    }
    var result = new ProjectData();
    for (var f : p.folders) if (included.contains(f.uuid)) result.folders.add(f);
    for (var n : p.noise2D) if (included.contains(n.uuid)) result.noise2D.add(n);
    for (var n : p.noise3D) if (included.contains(n.uuid)) result.noise3D.add(n);
    for (var t : p.voxelTypes) if (included.contains(t.uuid)) result.voxelTypes.add(t);
    for (var t : p.voxelGroups) if (included.contains(t.uuid)) result.voxelGroups.add(t);
    for (var t : p.voxelSets) if (included.contains(t.uuid)) result.voxelSets.add(t);
    for (var layer : p.worldGenerator.layers) {
      if (included.contains(layer.uuid)) result.worldGenerator.layers.add(layer);
      else if (layer.operations.stream().anyMatch(o -> included.contains(o.uuid))) {
        Layer carrier = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(layer), Layer.class);
        carrier.operations.removeIf(o -> !included.contains(o.uuid));
        result.worldGenerator.layers.add(carrier);
      }
    }
    result = ProjectIO.copy(result);
    reconcileOrders(result);
    for (var order : p.project.rootOrder.entrySet()) {
      var available = new HashSet<UUID>();
      children(result, order.getKey(), null).forEach(e -> available.add(e.uuid()));
      var filtered = order.getValue().stream().filter(available::contains).toList();
      if (!filtered.isEmpty())
        result.project.rootOrder.put(order.getKey(), new ArrayList<>(filtered));
    }
    return result;
  }

  public static void remove(ProjectData p, Set<UUID> ids) {
    p.noise2D.removeIf(n -> ids.contains(n.uuid));
    p.noise3D.removeIf(n -> ids.contains(n.uuid));
    p.folders.removeIf(n -> ids.contains(n.uuid));
    p.voxelTypes.removeIf(n -> ids.contains(n.uuid));
    p.voxelGroups.removeIf(n -> ids.contains(n.uuid));
    p.voxelSets.removeIf(n -> ids.contains(n.uuid));
    p.worldGenerator.layers.removeIf(n -> ids.contains(n.uuid));
    for (var l : p.worldGenerator.layers) l.operations.removeIf(o -> ids.contains(o.uuid));
    reconcileOrders(p);
  }

  public static void delete(ProjectData p, UUID id, UUID replacement) {
    Entry source = Objects.requireNonNull(find(p, id), "Resource no longer exists");
    Set<UUID> deleted = subtree(p, List.of(id));
    var deps = dependents(p, deleted);
    if (!deps.isEmpty()) {
      Entry target = find(p, replacement);
      if (target == null
          || deleted.contains(replacement)
          || !source.kind.equals(target.kind)
          || source.resource instanceof Folder)
        throw new IllegalArgumentException(
            "Choose an existing compatible replacement, or edit every dependent before deletion");
      JsonNode tree = ProjectIO.JSON.valueToTree(p);
      rewriteReferences(tree, Map.of(id, replacement));
      ProjectData edited = ProjectIO.JSON.convertValue(tree, ProjectData.class);
      assign(p, edited);
    }
    remove(p, deleted);
  }

  public static void rewriteReferences(JsonNode node, Map<UUID, UUID> mapping) {
    rewrite(node, mapping, false);
  }

  private static void rewrite(JsonNode node, Map<UUID, UUID> mapping, boolean reference) {
    if (node instanceof ObjectNode object) {
      var keys = new ArrayList<String>();
      object.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        JsonNode value = object.get(key);
        if (key.equals("rootOrder")
            && value.isObject()
            && object.path("uuid").isTextual()
            && object.path("seed").isIntegralNumber()
            && object.path("minY").isIntegralNumber()
            && object.path("maxY").isIntegralNumber()) {
          value.elements().forEachRemaining(order -> rewrite(order, mapping, true));
          continue;
        }
        boolean ref = referenceKey(key);
        if (ref && value.isTextual()) {
          try {
            UUID replacement = mapping.get(UUID.fromString(value.textValue()));
            if (replacement != null) object.put(key, replacement.toString());
          } catch (IllegalArgumentException ignored) {
          }
        } else rewrite(value, mapping, ref);
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        JsonNode value = array.get(i);
        if (reference && value.isTextual())
          try {
            UUID replacement = mapping.get(UUID.fromString(value.textValue()));
            if (replacement != null) array.set(i, TextNode.valueOf(replacement.toString()));
          } catch (IllegalArgumentException ignored) {
          }
        else rewrite(value, mapping, reference);
      }
      if (reference) {
        var seen = new HashSet<JsonNode>();
        for (int i = 0; i < array.size(); )
          if (!seen.add(array.get(i))) array.remove(i);
          else i++;
      }
    }
  }

  public static void rename(ProjectData p, UUID id, String name) {
    Entry e = Objects.requireNonNull(find(p, id));
    e.resource.id = name;
  }

  public static void move(ProjectData p, Collection<UUID> selected, String root, UUID parent) {
    var ids = new LinkedHashSet<>(selected);
    if (ids.isEmpty()) throw new IllegalArgumentException("Empty selection");
    var entries =
        ids.stream()
            .map(id -> Objects.requireNonNull(find(p, id), "Cut resource no longer exists"))
            .toList();
    String scope = entries.getFirst().scope();
    if (entries.stream().anyMatch(e -> !e.scope().equals(scope) || !e.root.equals(root)))
      throw new IllegalArgumentException("Move requires compatible siblings");
    UUID oldParent = entries.getFirst().parent();
    var movingOrder =
        children(p, root, oldParent).stream().map(Entry::uuid).filter(ids::contains).toList();
    validateParent(p, root, parent);
    if (parent != null) {
      Entry target = find(p, parent);
      if (root.equals("operation")) {
        if (target == null || !target.kind.equals("layer"))
          throw new IllegalArgumentException("Choose a layer");
      } else if (target == null
          || !(target.resource instanceof Folder f)
          || !f.rootKind.equals(root))
        throw new IllegalArgumentException("Choose a compatible folder");
      if (subtree(p, ids).contains(parent))
        throw new IllegalArgumentException("Cannot move a folder into itself or a descendant");
    }
    for (var e : entries) {
      if (e.resource instanceof Noise n) n.parentFolderUuid = parent;
      else if (e.resource instanceof Folder f) f.parentFolderUuid = parent;
      else if (e.resource instanceof Layer layer) layer.parentFolderUuid = parent;
      else if (e.resource instanceof Operation op) {
        if (parent == null) throw new IllegalArgumentException("Placements need a layer");
        Layer
            old =
                p.worldGenerator.layers.stream()
                    .filter(l -> l.uuid.equals(e.parent))
                    .findFirst()
                    .orElseThrow(),
            target =
                p.worldGenerator.layers.stream()
                    .filter(l -> l.uuid.equals(parent))
                    .findFirst()
                    .orElseThrow();
        old.operations.remove(op);
        op.id =
            ResourceTransactions.unique(op.id, target.operations.stream().map(o -> o.id).toList());
        target.operations.add(op);
      }
    }
    if (parent == null && !Set.of("noise2D", "noise3D", "world").contains(root))
      for (var entry : entries)
        if (!(entry.resource instanceof Folder)) {
          var list = list(p, entry);
          list.remove(entry.resource);
          list.add(entry.resource);
        }
    reconcileOrders(p);
    resolveNames(p, Set.copyOf(ids));
    if (ORGANIZATIONAL_ROOTS.contains(root) && !Objects.equals(oldParent, parent))
      appendOrder(p, root, parent, movingOrder);
  }

  @SuppressWarnings("unchecked")
  private static List<Identity> list(ProjectData p, Entry e) {
    return (List<Identity>)
        (List<?>)
            switch (e.kind) {
              case "noise2D" -> p.noise2D;
              case "noise3D" -> p.noise3D;
              case "folder" -> p.folders;
              case "voxel" -> p.voxelTypes;
              case "group" -> p.voxelGroups;
              case "set" -> p.voxelSets;
              case "layer" -> p.worldGenerator.layers;
              case "operation" ->
                  p.worldGenerator.layers.stream()
                      .filter(l -> l.uuid.equals(e.parent))
                      .findFirst()
                      .orElseThrow()
                      .operations;
              default -> throw new IllegalArgumentException("Unsupported resource kind");
            };
  }

  private static void legacyReorderBefore(ProjectData p, Collection<UUID> selected, UUID before) {
    Entry target = Objects.requireNonNull(find(p, before));
    if (selected.contains(before)) return;
    var source = selected.stream().map(id -> Objects.requireNonNull(find(p, id))).toList();
    if (target.parent != null && find(p, target.parent).resource instanceof Folder folder) {
      move(p, selected, target.root, target.parent);
      var order =
          new ArrayList<>(
              children(p, target.root, target.parent).stream().map(Entry::uuid).toList());
      var moving = order.stream().filter(selected::contains).toList();
      order.removeAll(moving);
      order.addAll(order.indexOf(before), moving);
      folder.childOrder = order;
      return;
    }
    if (source.stream().anyMatch(e -> !e.kind.equals(target.kind)))
      throw new IllegalArgumentException("Reordering requires resources of the same kind");
    move(p, selected, target.root, target.parent);
    var list = list(p, Objects.requireNonNull(find(p, before)));
    var moving = list.stream().filter(r -> selected.contains(r.uuid)).toList();
    list.removeAll(moving);
    int index = 0;
    while (index < list.size() && !list.get(index).uuid.equals(before)) index++;
    list.addAll(index, moving);
  }

  private static void legacyShift(ProjectData p, Collection<UUID> selected, int direction) {
    var orderedParents = new HashSet<UUID>();
    for (UUID id : selected) {
      var e = find(p, id);
      if (e != null
          && e.parent != null
          && find(p, e.parent).resource instanceof Folder folder
          && orderedParents.add(e.parent)) {
        var order =
            new ArrayList<>(children(p, e.root, e.parent).stream().map(Entry::uuid).toList());
        if (direction < 0) {
          for (int i = 1; i < order.size(); i++)
            if (selected.contains(order.get(i)) && !selected.contains(order.get(i - 1)))
              Collections.swap(order, i, i - 1);
        } else
          for (int i = order.size() - 2; i >= 0; i--)
            if (selected.contains(order.get(i)) && !selected.contains(order.get(i + 1)))
              Collections.swap(order, i, i + 1);
        folder.childOrder = order;
      }
    }
    var seen = new HashSet<String>();
    for (UUID id : selected) {
      if (find(p, id).parent != null && find(p, find(p, id).parent).resource instanceof Folder)
        continue;
      var entry = Objects.requireNonNull(find(p, id));
      String key = entry.kind + ":" + entry.scope();
      if (!seen.add(key)) continue;
      var list = list(p, entry);
      var positions = new ArrayList<Integer>();
      for (int i = 0; i < list.size(); i++) {
        Entry e = find(p, list.get(i).uuid);
        if (e.scope().equals(entry.scope())) positions.add(i);
      }
      if (direction < 0) {
        for (int i = 1; i < positions.size(); i++) {
          int at = positions.get(i), previous = positions.get(i - 1);
          if (selected.contains(list.get(at).uuid) && !selected.contains(list.get(previous).uuid))
            Collections.swap(list, at, previous);
        }
      } else
        for (int i = positions.size() - 2; i >= 0; i--) {
          int at = positions.get(i), next = positions.get(i + 1);
          if (selected.contains(list.get(at).uuid) && !selected.contains(list.get(next).uuid))
            Collections.swap(list, at, next);
        }
    }
  }

  public static final Set<String> ORGANIZATIONAL_ROOTS =
      Collections.unmodifiableSet(new LinkedHashSet<>(List.of("noise2D", "noise3D", "world")));

  public static Map<String, Object> hierarchyRegistry() {
    var registry = new LinkedHashMap<String, Object>();
    registry.put("registryVersion", 1);
    registry.put("projectFormatVersion", 2);
    registry.put("protectedRoots", List.copyOf(ORGANIZATIONAL_ROOTS));
    registry.put("rootOrderPath", "project.rootOrder");
    registry.put("folderOrderPath", "folders[].childOrder");
    registry.put("layerParentPath", "worldGenerator.layers[].parentFolderUuid");
    registry.put("executionOrderPath", "worldGenerator.layers");
    registry.put("treeOrderAffectsExecution", false);
    registry.put(
        "missingOrderPolicy",
        "listed direct children first; unlisted children retain legacy relative order");
    return Collections.unmodifiableMap(registry);
  }

  public static void validateParent(ProjectData p, String root, UUID parent) {
    if (!Set.of("noise2D", "noise3D", "world", "operation", "voxel", "group", "set").contains(root))
      throw new IllegalArgumentException("Unknown resource root");
    if (parent == null) {
      if (root.equals("operation")) throw new IllegalArgumentException("Placements need a layer");
      return;
    }
    var target = find(p, parent);
    if (root.equals("operation")) {
      if (target == null || !(target.resource() instanceof Layer))
        throw new IllegalArgumentException("Choose a layer");
    } else if (!ORGANIZATIONAL_ROOTS.contains(root)
        || target == null
        || !(target.resource() instanceof Folder f)
        || !f.rootKind.equals(root))
      throw new IllegalArgumentException("Choose a compatible folder");
  }

  private static List<UUID> explicitOrder(ProjectData p, String root, UUID parent) {
    if (parent == null) return p.project.rootOrder.getOrDefault(root, List.of());
    var entry = find(p, parent);
    return entry != null && entry.resource() instanceof Folder f ? f.childOrder : List.of();
  }

  private static void order(ProjectData p, String root, UUID parent, List<UUID> values) {
    if (parent == null) p.project.rootOrder.put(root, new ArrayList<>(values));
    else
      ((Folder) Objects.requireNonNull(find(p, parent)).resource()).childOrder =
          new ArrayList<>(values);
  }

  static void appendOrder(ProjectData p, String root, UUID parent, Collection<UUID> appended) {
    var ordered = new ArrayList<>(children(p, root, parent).stream().map(Entry::uuid).toList());
    if (!ordered.containsAll(appended))
      throw new IllegalArgumentException("Ordered entries must be direct children");
    ordered.removeAll(appended);
    ordered.addAll(new LinkedHashSet<>(appended));
    order(p, root, parent, ordered);
  }

  public static void reorderBefore(ProjectData p, Collection<UUID> selected, UUID before) {
    var target = Objects.requireNonNull(find(p, before), "Target no longer exists");
    if (!ORGANIZATIONAL_ROOTS.contains(target.root())) {
      legacyReorderBefore(p, selected, before);
      return;
    }
    if (selected.contains(before)) return;
    if (selected.isEmpty()) throw new IllegalArgumentException("Empty selection");
    var source = Objects.requireNonNull(find(p, selected.iterator().next()));
    var selectedIds = Set.copyOf(selected);
    var moving =
        children(p, source.root(), source.parent()).stream()
            .map(Entry::uuid)
            .filter(selectedIds::contains)
            .toList();
    move(p, selected, target.root(), target.parent());
    var ordered =
        new ArrayList<>(
            children(p, target.root(), target.parent()).stream().map(Entry::uuid).toList());
    ordered.removeAll(moving);
    ordered.addAll(ordered.indexOf(before), moving);
    order(p, target.root(), target.parent(), ordered);
  }

  public static void shift(ProjectData p, Collection<UUID> selected, int direction) {
    if (direction != -1 && direction != 1)
      throw new IllegalArgumentException("Direction must be -1 or 1");
    var scopes = new LinkedHashMap<String, Entry>();
    var legacy = new LinkedHashSet<UUID>();
    for (UUID id : selected) {
      var entry = Objects.requireNonNull(find(p, id), "Selected resource no longer exists");
      if (ORGANIZATIONAL_ROOTS.contains(entry.root())) scopes.putIfAbsent(entry.scope(), entry);
      else legacy.add(id);
    }
    var ids = Set.copyOf(selected);
    for (var scope : scopes.values()) {
      var ordered =
          new ArrayList<>(
              children(p, scope.root(), scope.parent()).stream().map(Entry::uuid).toList());
      shiftOrder(ordered, ids, direction);
      order(p, scope.root(), scope.parent(), ordered);
    }
    if (!legacy.isEmpty()) legacyShift(p, legacy, direction);
  }

  private static void shiftOrder(List<UUID> order, Set<UUID> selected, int direction) {
    if (direction < 0) {
      for (int i = 1; i < order.size(); i++)
        if (selected.contains(order.get(i)) && !selected.contains(order.get(i - 1)))
          Collections.swap(order, i, i - 1);
    } else
      for (int i = order.size() - 2; i >= 0; i--)
        if (selected.contains(order.get(i)) && !selected.contains(order.get(i + 1)))
          Collections.swap(order, i, i + 1);
  }

  /** Explicit execution edit; organizational folder moves and tree order never call this path. */
  public static void shiftExecution(ProjectData p, Collection<UUID> selected, int direction) {
    if (direction != -1 && direction != 1)
      throw new IllegalArgumentException("Direction must be -1 or 1");
    var layers = new LinkedHashMap<UUID, Layer>();
    p.worldGenerator.layers.forEach(layer -> layers.put(layer.uuid, layer));
    for (UUID id : selected)
      if (!layers.containsKey(id))
        throw new IllegalArgumentException("Execution order accepts layers only");
    var ids = new ArrayList<>(layers.keySet());
    shiftOrder(ids, Set.copyOf(selected), direction);
    p.worldGenerator.layers = new ArrayList<>(ids.stream().map(layers::get).toList());
  }

  public static List<Entry> children(ProjectData p, String root, UUID parent) {
    var children =
        new ArrayList<>(
            entries(p).stream()
                .filter(e -> e.root.equals(root) && Objects.equals(e.parent, parent))
                .toList());
    if (ORGANIZATIONAL_ROOTS.contains(root)) {
      var ranks = new HashMap<UUID, Integer>();
      var explicit = explicitOrder(p, root, parent);
      for (int i = 0; i < explicit.size(); i++) ranks.put(explicit.get(i), i);
      children.sort(Comparator.comparingInt(e -> ranks.getOrDefault(e.uuid(), Integer.MAX_VALUE)));
    }
    return List.copyOf(children);
  }

  private static void reconcileOrders(ProjectData p) {
    var entries = entries(p);
    var children = new HashMap<String, Set<UUID>>();
    for (var entry : entries)
      children.computeIfAbsent(entry.scope(), key -> new HashSet<>()).add(entry.uuid());
    for (var folder : p.folders)
      folder.childOrder.removeIf(
          id -> !children.getOrDefault(folder.rootKind + ":" + folder.uuid, Set.of()).contains(id));
    for (var order : p.project.rootOrder.entrySet())
      order
          .getValue()
          .removeIf(id -> !children.getOrDefault(order.getKey() + ":null", Set.of()).contains(id));
  }

  public static void append(ProjectData target, ProjectData from) {
    var existingOrders = new LinkedHashMap<String, List<UUID>>();
    var incomingOrders = new LinkedHashMap<String, List<UUID>>();
    for (String root : ORGANIZATIONAL_ROOTS) {
      existingOrders.put(root, children(target, root, null).stream().map(Entry::uuid).toList());
      incomingOrders.put(root, children(from, root, null).stream().map(Entry::uuid).toList());
    }
    var added = new LinkedHashSet<UUID>();
    entries(from).forEach(e -> added.add(e.uuid()));
    target.noise2D.addAll(from.noise2D);
    target.noise3D.addAll(from.noise3D);
    target.folders.addAll(from.folders);
    target.voxelTypes.addAll(from.voxelTypes);
    target.voxelSets.addAll(from.voxelSets);
    target.voxelGroups.addAll(from.voxelGroups);
    target.worldGenerator.layers.addAll(from.worldGenerator.layers);
    resolveNames(target, added);
    for (String root : ORGANIZATIONAL_ROOTS) {
      var merged = new ArrayList<>(existingOrders.get(root));
      merged.addAll(incomingOrders.get(root));
      if (!merged.isEmpty()) target.project.rootOrder.put(root, merged);
    }
    reconcileOrders(target);
  }

  public static void resolveNames(ProjectData p, Set<UUID> added) {
    var names = new HashMap<String, Set<String>>();
    for (var e : entries(p))
      if (!added.contains(e.uuid()))
        names.computeIfAbsent(namespace(e), k -> new HashSet<>()).add(e.resource.id);
    for (var e : entries(p))
      if (added.contains(e.uuid())) {
        Set<String> existing = names.computeIfAbsent(namespace(e), k -> new HashSet<>());
        e.resource.id = ResourceTransactions.unique(e.resource.id, existing);
        existing.add(e.resource.id);
      }
  }

  private static String namespace(Entry e) {
    return e.kind.equals("folder") ? e.scope() : e.kind.equals("operation") ? e.scope() : e.kind;
  }

  public static void assign(ProjectData destination, ProjectData source) {
    destination.noise2D = source.noise2D;
    destination.noise3D = source.noise3D;
    destination.folders = source.folders;
    destination.voxelTypes = source.voxelTypes;
    destination.voxelSets = source.voxelSets;
    destination.voxelGroups = source.voxelGroups;
    destination.worldGenerator = source.worldGenerator;
    destination.project.rootOrder = new LinkedHashMap<>();
    source.project.rootOrder.forEach(
        (root, values) -> destination.project.rootOrder.put(root, new ArrayList<>(values)));
  }
}
