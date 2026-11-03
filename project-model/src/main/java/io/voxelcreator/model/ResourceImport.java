package io.voxelcreator.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Previewable portable-resource merge. Existing definitions are never replaced. */
public final class ResourceImport {
  public enum Choice {
    COPY,
    REUSE,
    SKIP
  }

  public record Row(
      UUID uuid,
      String kind,
      String id,
      String resolution,
      boolean compatible,
      UUID existing,
      UUID targetUuid,
      String targetId) {
    Row(UUID uuid, String kind, String id, String resolution, boolean compatible, UUID existing) {
      this(uuid, kind, id, resolution, compatible, existing, null, null);
    }
  }

  public record Plan(ProjectData candidate, List<Row> rows, List<Diagnostic> diagnostics) {
    public Plan {
      candidate = ProjectIO.copy(candidate);
      rows = List.copyOf(rows);
      diagnostics = List.copyOf(diagnostics);
    }

    /** Returns an owned snapshot; callers cannot change the previewed transaction. */
    @Override
    public ProjectData candidate() {
      return ProjectIO.copy(candidate);
    }

    public boolean valid() {
      return diagnostics.isEmpty();
    }
  }

  public static Plan plan(
      ProjectData destination, ProjectData incoming, Map<UUID, Choice> choices) {
    return plan(destination, incoming, choices, ResourceTransactions.duplicateMapped(incoming));
  }

  private static Plan plan(
      ProjectData destination,
      ProjectData incoming,
      Map<UUID, Choice> choices,
      ResourceTransactions.Copy duplicated) {
    var rows = new ArrayList<Row>();
    var issues = new ArrayList<Diagnostic>();
    var source = ResourceGraph.entries(incoming);
    var existing = new Matches(ResourceGraph.entries(destination));
    var compatibility = new Compatibility(incoming, destination);
    var selectable = new HashSet<UUID>();
    for (var entry : source) if (!entry.kind().equals("operation")) selectable.add(entry.uuid());
    for (var choice : choices.entrySet())
      if (!selectable.contains(choice.getKey()) || choice.getValue() == null)
        issues.add(
            Diagnostic.error(
                "INVALID_IMPORT_CHOICE",
                "/resources/" + choice.getKey(),
                "Choose COPY, REUSE or SKIP for an incoming resource row"));
    var skipped = new LinkedHashSet<UUID>();
    var reused = new LinkedHashMap<UUID, UUID>();
    for (var entry : source) {
      if (entry.kind().equals("operation")) continue;
      Choice choice = choices.getOrDefault(entry.uuid(), Choice.COPY);
      if (choice == null) choice = Choice.COPY;
      var match = existing.match(entry);
      boolean compatible = match != null && compatibility.compatible(entry, match);
      if (choice == Choice.SKIP) skipped.add(entry.uuid());
      if (choice == Choice.REUSE && compatible) reused.put(entry.uuid(), match.uuid());
      rows.add(
          new Row(
              entry.uuid(),
              entry.kind(),
              entry.resource().id,
              choice.name(),
              compatible,
              match == null ? null : match.uuid()));
    }
    // One reverse adjacency graph closes structural children and procedural dependents.
    // The queue is bounded by the number of resources, including cyclic input candidates.
    var excludedBy = new HashMap<UUID, List<UUID>>();
    for (var entry : source) {
      if (entry.parent() != null)
        excludedBy.computeIfAbsent(entry.parent(), key -> new ArrayList<>()).add(entry.uuid());
      if (!reused.containsKey(entry.uuid()))
        for (UUID reference : dependencies(entry))
          excludedBy.computeIfAbsent(reference, key -> new ArrayList<>()).add(entry.uuid());
    }
    var queue = new ArrayDeque<UUID>(skipped);
    while (!queue.isEmpty())
      for (UUID dependent : excludedBy.getOrDefault(queue.removeFirst(), List.of()))
        if (skipped.add(dependent)) queue.addLast(dependent);
    for (var row : rows)
      if (!skipped.contains(row.uuid()) && row.resolution().equals("REUSE") && !row.compatible())
        issues.add(
            Diagnostic.error(
                "INCOMPATIBLE_REUSE",
                "/resources/" + row.uuid(),
                "Reuse requires an existing semantically identical resource of the same kind"));
    var payload = ProjectIO.copy(duplicated.project());
    Set<UUID> removed = new HashSet<>();
    skipped.forEach(id -> removed.add(duplicated.identities().get(id)));
    reused.keySet().forEach(id -> removed.add(duplicated.identities().get(id)));
    ResourceGraph.remove(payload, removed);
    var referenceMap = new HashMap<UUID, UUID>();
    reused.forEach(
        (from, to) -> {
          if (!skipped.contains(from)) referenceMap.put(duplicated.identities().get(from), to);
        });
    JsonNode tree = ProjectIO.JSON.valueToTree(payload);
    ResourceGraph.rewriteReferences(tree, referenceMap);
    payload = ProjectIO.JSON.convertValue(tree, ProjectData.class);
    var candidate = ProjectIO.copy(destination);
    ResourceGraph.append(candidate, payload);
    issues.addAll(Validator.validate(candidate));
    var targets = new HashMap<UUID, ResourceGraph.Entry>();
    ResourceGraph.entries(candidate).forEach(entry -> targets.putIfAbsent(entry.uuid(), entry));
    var finalRows = new ArrayList<Row>();
    for (var row : rows) {
      UUID targetId =
          skipped.contains(row.uuid)
              ? null
              : reused.getOrDefault(row.uuid, duplicated.identities().get(row.uuid));
      var target = targets.get(targetId);
      finalRows.add(
          new Row(
              row.uuid,
              row.kind,
              row.id,
              skipped.contains(row.uuid) ? "SKIP (including dependent closure)" : row.resolution,
              row.compatible,
              row.existing,
              targetId,
              target == null ? null : target.resource().id));
    }
    return new Plan(candidate, List.copyOf(finalRows), List.copyOf(issues));
  }

  private static Set<UUID> dependencies(ResourceGraph.Entry entry) {
    if (entry.resource() instanceof ProjectData.Layer layer)
      return ResourceGraph.references(layer.defaults);
    return ResourceGraph.references(entry.resource());
  }

  /**
   * Private import draft with stable COPY identities. Choices never mutate either input, and
   * publication rejects a destination changed since this preview was opened.
   */
  public static final class Session {
    private final ProjectData destination, incoming;
    private final JsonNode base;
    private final ResourceTransactions.Copy copied;
    private final Set<UUID> selectable;
    private final Map<UUID, Choice> choices = new LinkedHashMap<>();
    private Plan cached;

    public Session(ProjectData destination, ProjectData incoming) {
      this.destination = ProjectIO.copy(destination);
      this.incoming = ProjectIO.copy(incoming);
      base = ProjectIO.JSON.valueToTree(this.destination);
      copied = ResourceTransactions.duplicateMapped(this.incoming);
      var ids = new HashSet<UUID>();
      for (var entry : ResourceGraph.entries(this.incoming))
        if (!entry.kind().equals("operation")) ids.add(entry.uuid());
      selectable = Set.copyOf(ids);
    }

    public Map<UUID, Choice> choices() {
      return Map.copyOf(choices);
    }

    public void choose(UUID resource, Choice choice) {
      if (!selectable.contains(resource) || choice == null)
        throw new IllegalArgumentException("Choose an incoming resource and a resolution");
      if (choices.getOrDefault(resource, Choice.COPY) == choice) return;
      if (choice == Choice.COPY) choices.remove(resource);
      else choices.put(resource, choice);
      cached = null;
    }

    public Plan preview() {
      if (cached == null) cached = plan(destination, incoming, choices, copied);
      return cached;
    }

    public boolean current(ProjectData project) {
      return base.equals(ProjectIO.JSON.valueToTree(project));
    }

    /** Call inside the owning document transaction; all checks precede publication. */
    public void applyTo(ProjectData project) {
      if (!current(project))
        throw new IllegalStateException("Import destination changed; refresh the preview");
      var plan = preview();
      if (!plan.valid())
        throw new IllegalStateException("Import preview contains unresolved errors");
      ResourceGraph.assign(project, plan.candidate());
    }
  }

  /** Per-plan conflict indexes preserve the old first-match and UUID-before-visible-ID rules. */
  private static final class Matches {
    private record Identity(String kind, String root, UUID uuid) {}

    private record Name(String kind, String root, String id) {}

    private final Map<Identity, ResourceGraph.Entry> identities = new HashMap<>();
    private final Map<Name, ResourceGraph.Entry> names = new HashMap<>();

    Matches(List<ResourceGraph.Entry> entries) {
      for (var entry : entries) {
        identities.putIfAbsent(new Identity(entry.kind(), entry.root(), entry.uuid()), entry);
        names.putIfAbsent(new Name(entry.kind(), entry.root(), entry.resource().id), entry);
      }
    }

    ResourceGraph.Entry match(ResourceGraph.Entry source) {
      var identity = identities.get(new Identity(source.kind(), source.root(), source.uuid()));
      return identity != null
          ? identity
          : names.get(new Name(source.kind(), source.root(), source.resource().id));
    }
  }

  public static boolean compatible(
      ResourceGraph.Entry a, ProjectData ap, ResourceGraph.Entry b, ProjectData bp) {
    return new Compatibility(ap, bp).compatible(a, b);
  }

  /** Per-preview graph: compare every reachable definition once, without recursive Java calls. */
  private static final class Compatibility {
    private record Pair(ResourceGraph.Entry source, ResourceGraph.Entry target) {}

    private static final class Comparison {
      final Pair pair;
      final List<Comparison> dependencies = new ArrayList<>(), dependents = new ArrayList<>();
      boolean inspected, same;
      Boolean result;

      Comparison(Pair pair) {
        this.pair = pair;
      }
    }

    private final Map<UUID, ResourceGraph.Entry> source = new HashMap<>(), target = new HashMap<>();
    private final Map<ResourceGraph.Entry, JsonNode> definitions = new HashMap<>();
    private final Map<Pair, Comparison> comparisons = new HashMap<>();

    Compatibility(ProjectData incoming, ProjectData destination) {
      ResourceGraph.entries(incoming).forEach(e -> source.put(e.uuid(), e));
      ResourceGraph.entries(destination).forEach(e -> target.put(e.uuid(), e));
    }

    private Comparison comparison(ResourceGraph.Entry a, ResourceGraph.Entry b) {
      return comparisons.computeIfAbsent(new Pair(a, b), Comparison::new);
    }

    boolean compatible(ResourceGraph.Entry a, ResourceGraph.Entry b) {
      var root = comparison(a, b);
      var visited = new LinkedHashSet<Comparison>();
      var queue = new ArrayDeque<Comparison>();
      queue.add(root);
      while (!queue.isEmpty()) {
        var current = queue.removeFirst();
        if (current.result != null || !visited.add(current)) continue;
        inspect(current);
        queue.addAll(current.dependencies);
      }
      // A mismatch invalidates all its already discovered ancestors, including cycles. A cycle
      // with identical definitions terminates here; the candidate validator still rejects it.
      for (var current : visited)
        if (!current.same
            || current.dependencies.stream().anyMatch(d -> Boolean.FALSE.equals(d.result)))
          queue.add(current);
      while (!queue.isEmpty()) {
        var failed = queue.removeFirst();
        if (Boolean.FALSE.equals(failed.result)) continue;
        failed.result = false;
        queue.addAll(failed.dependents);
      }
      for (var current : visited) if (current.result == null) current.result = true;
      return Boolean.TRUE.equals(root.result);
    }

    private void inspect(Comparison comparison) {
      if (comparison.inspected) return;
      comparison.inspected = true;
      var a = comparison.pair.source();
      var b = comparison.pair.target();
      if (!directlyCompatible(a, b)) return;
      var definition = definitions.computeIfAbsent(a, e -> canonical(e.resource()));
      if (!definition.equals(definitions.computeIfAbsent(b, e -> canonical(e.resource())))) return;
      comparison.same = true;
      // Local references have already become @paths, and organizational parent identities have
      // been removed. Only UUIDs retaining external procedural meaning are traversed here.
      for (UUID id : ResourceGraph.references(definition)) {
        var existing = target.get(id);
        if (existing == null) {
          comparison.same = false;
          continue;
        }
        var incoming = source.get(id);
        // A package may intentionally retain an external destination UUID without shipping its
        // definition. When it does ship that definition, identity alone cannot establish equality.
        if (incoming == null) continue;
        var dependency = comparison(incoming, existing);
        comparison.dependencies.add(dependency);
        dependency.dependents.add(comparison);
      }
    }
  }

  private static boolean directlyCompatible(ResourceGraph.Entry a, ResourceGraph.Entry b) {
    if (!a.kind().equals(b.kind()) || !a.root().equals(b.root())) return false;
    if (a.resource() instanceof ProjectData.VoxelType && !a.resource().id.equals(b.resource().id))
      return false;
    if (a.resource() instanceof ProjectData.Noise noise
        && !a.uuid().equals(b.uuid())
        && seedSensitive(noise)) return false;
    return !(a.resource() instanceof ProjectData.Layer layer
        && b.resource() instanceof ProjectData.Layer target
        && !layer.operations.stream()
            .map(op -> op.uuid)
            .toList()
            .equals(target.operations.stream().map(op -> op.uuid).toList()));
  }

  private static boolean seedSensitive(ProjectData.Noise n) {
    if (!Set.of("CONSTANT", "GRADIENT_Y", "SPHERE_SDF", "BOX_SDF").contains(n.algorithm.key))
      return true;
    for (var list : List.of(n.coordinateTransforms, n.processing))
      for (var item : list) {
        if (!item.enabled) continue;
        if (Set.of("DOMAIN_WARP", "WARP_SAMPLE", "COORDINATE_HASH").contains(item.kind))
          return true;
        if (item.parameters.containsKey("expression")
            && item.parameters
                .get("expression")
                .asText()
                .matches("(?s).*\\bnoiseGeneratorSeed\\b.*")) return true;
        for (var binding : item.inputs.values())
          if (binding.sourceKind.equals("Ambient Value")
              && "noiseGeneratorSeed".equals(binding.ambientKey)) return true;
      }
    return false;
  }

  private static JsonNode canonical(Object resource) {
    JsonNode tree = ProjectIO.JSON.valueToTree(resource);
    var identities = new HashMap<String, String>();
    collect(tree, "", identities);
    normalize(tree, identities);
    return tree;
  }

  private static void collect(JsonNode n, String path, Map<String, String> identities) {
    if (n.isObject()) {
      if (UuidText.valid(n.get("uuid"))) identities.put(n.get("uuid").textValue(), "@" + path);
      n.fields().forEachRemaining(e -> collect(e.getValue(), path + "/" + e.getKey(), identities));
    } else if (n.isArray())
      for (int i = 0; i < n.size(); i++) collect(n.get(i), path + "/" + i, identities);
  }

  private static void normalize(JsonNode n, Map<String, String> local) {
    if (n instanceof ObjectNode o) {
      // A user input named uuid contains a Binding, rather than declaring this map's identity.
      boolean identity = UuidText.valid(o.get("uuid"));
      if (identity) {
        o.remove("uuid");
        o.remove("id");
        o.remove("parentFolderUuid");
      }
      var keys = new ArrayList<String>();
      o.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        JsonNode value = o.get(key);
        if (value.isTextual() && (key.endsWith("Uuid") || key.equals("reference"))) {
          String text = value.asText();
          o.put(key, local.getOrDefault(text, text));
        } else if (key.endsWith("Uuids") && value instanceof ArrayNode array) {
          for (int i = 0; i < array.size(); i++) {
            String text = array.get(i).asText();
            array.set(i, TextNode.valueOf(local.getOrDefault(text, text)));
          }
        } else normalize(value, local);
      }
    } else if (n.isArray()) n.forEach(c -> normalize(c, local));
  }
}
