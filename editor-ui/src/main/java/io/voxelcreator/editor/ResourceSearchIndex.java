package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Immutable committed search metadata and precise UUID locations; no per-frame serialization. */
public final class ResourceSearchIndex {
  public static final List<String> KINDS =
      List.of(
          "All",
          "noise2D",
          "noise3D",
          "folder",
          "layer",
          "operation",
          "voxel",
          "group",
          "set",
          "transform",
          "processing",
          "parameter",
          "source control",
          "item parameter",
          "input binding",
          "output binding",
          "curve point");
  public static final List<String> ROOTS =
      List.of("All", "noise2D", "noise3D", "world", "voxel", "group", "set");

  public enum Mode {
    ANY_TEXT,
    ID,
    BREADCRUMB
  }

  public record Filter(
      String query,
      String kind,
      String state,
      String root,
      UUID directory,
      UUID reference,
      boolean containers,
      Mode mode) {
    public Filter {
      query = Objects.requireNonNull(query).strip().toLowerCase(Locale.ROOT);
      if (!KINDS.contains(kind)
          || !List.of("All", "Enabled", "Disabled").contains(state)
          || !ROOTS.contains(root)) throw new IllegalArgumentException("Unknown Find filter");
      Objects.requireNonNull(mode);
    }

    public static Filter all() {
      return new Filter("", "All", "All", "All", null, null, false, Mode.ANY_TEXT);
    }
  }

  public record Hit(
      UUID uuid,
      UUID owner,
      String kind,
      String label,
      boolean enabled,
      String text,
      Set<UUID> references,
      String root,
      String breadcrumb,
      EditorEvents.Target target,
      Set<UUID> directReferences,
      String key) {
    public Hit {
      references = Set.copyOf(references);
      directReferences = Set.copyOf(directReferences);
    }

    public UUID resource() {
      return owner == null ? uuid : owner;
    }
  }

  private final List<Hit> all;
  private final Map<UUID, UUID> parents;
  private final Map<UUID, String> paths;
  private final UUID project;
  private final Map<UUID, String> locations;

  public ResourceSearchIndex(ProjectData p) {
    project = p.project.uuid;
    var pointers = new HashMap<UUID, String>();
    locate(pointers, "/folders", p.folders);
    locate(pointers, "/noise2D", p.noise2D);
    locate(pointers, "/noise3D", p.noise3D);
    locate(pointers, "/voxelTypes", p.voxelTypes);
    locate(pointers, "/voxelGroups", p.voxelGroups);
    locate(pointers, "/voxelSets", p.voxelSets);
    locate(pointers, "/worldGenerator/layers", p.worldGenerator.layers);
    for (var layer : p.worldGenerator.layers)
      locate(pointers, pointers.get(layer.uuid) + "/operations", layer.operations);
    locations = Map.copyOf(pointers);
    var entries = ResourceGraph.entries(p);
    var resources = new LinkedHashMap<UUID, ResourceGraph.Entry>();
    var ancestry = new HashMap<UUID, UUID>();
    for (var entry : entries) {
      resources.put(entry.uuid(), entry);
      if (entry.parent() != null) ancestry.put(entry.uuid(), entry.parent());
    }
    parents = Map.copyOf(ancestry);
    var breadcrumbs = new HashMap<UUID, String>();
    for (var entry : entries) path(entry.uuid(), resources, breadcrumbs);
    paths = Map.copyOf(breadcrumbs);
    var hits = new ArrayList<Hit>();
    for (var entry : entries) {
      var resource = entry.resource();
      String root = entry.root().equals("operation") ? "world" : entry.root();
      boolean enabled = !(resource instanceof Layer l) || l.enabled;
      if (resource instanceof Operation op) {
        var parent = resources.get(entry.parent());
        enabled = op.enabled && parent != null && ((Layer) parent.resource()).enabled;
      }
      var tree = ProjectIO.JSON.valueToTree(resource);
      var direct = tree.deepCopy();
      if (direct instanceof ObjectNode object)
        object.remove(
            List.of(
                "coordinateTransforms",
                "processing",
                "sharedParameters",
                "outputBinding",
                "operations"));
      add(
          hits,
          resource.uuid,
          null,
          entry.kind(),
          resource.id,
          enabled,
          root,
          paths.get(entry.uuid()),
          null,
          null,
          locations.get(resource.uuid),
          direct,
          tree);
      if (resource instanceof Noise n) {
        add(
            hits,
            n.uuid,
            n.uuid,
            "source control",
            "Seed offset",
            true,
            root,
            paths.get(n.uuid) + " / Source / seedOffset",
            null,
            "seedOffset",
            locations.get(n.uuid) + "/seedOffset",
            ProjectIO.JSON.valueToTree(n.seedOffset),
            null);
        add(
            hits,
            n.uuid,
            n.uuid,
            "source control",
            "Algorithm",
            true,
            root,
            paths.get(n.uuid) + " / Source / algorithm",
            null,
            "algorithm",
            locations.get(n.uuid) + "/algorithm",
            ProjectIO.JSON.valueToTree(n.algorithm.key),
            null);
        for (var parameter : n.algorithm.parameters.entrySet())
          add(
              hits,
              n.uuid,
              n.uuid,
              "source control",
              parameter.getKey(),
              true,
              root,
              paths.get(n.uuid) + " / Source / " + parameter.getKey(),
              null,
              "algorithm/parameters/" + parameter.getKey(),
              locations.get(n.uuid) + "/algorithm/parameters/" + escape(parameter.getKey()),
              ProjectIO.JSON.valueToTree(parameter.getValue()),
              null);
        for (int parameterIndex = 0; parameterIndex < n.sharedParameters.size(); parameterIndex++) {
          var parameter = n.sharedParameters.get(parameterIndex);
          add(
              hits,
              parameter.uuid,
              n.uuid,
              "parameter",
              parameter.id,
              true,
              root,
              paths.get(n.uuid) + " / Parameters / " + parameter.id,
              parameter.uuid,
              null,
              locations.get(n.uuid) + "/sharedParameters/" + parameterIndex,
              ProjectIO.JSON.valueToTree(parameter),
              null);
        }
        items(hits, n, n.coordinateTransforms, "transform", root);
        items(hits, n, n.processing, "processing", root);
        add(
            hits,
            n.uuid,
            n.uuid,
            "output binding",
            "Output",
            true,
            root,
            paths.get(n.uuid) + " / Output",
            null,
            "outputBinding",
            locations.get(n.uuid) + "/outputBinding",
            ProjectIO.JSON.valueToTree(n.outputBinding),
            null);
      }
    }
    all = List.copyOf(hits);
  }

  private static void locate(
      Map<UUID, String> locations, String root, List<? extends Identity> values) {
    for (int i = 0; i < values.size(); i++) locations.put(values.get(i).uuid, root + "/" + i);
  }

  private static String escape(String key) {
    return key.replace("~", "~0").replace("/", "~1");
  }

  private static String path(
      UUID id, Map<UUID, ResourceGraph.Entry> entries, Map<UUID, String> cache) {
    if (cache.containsKey(id)) return cache.get(id);
    var pending = new ArrayDeque<UUID>();
    var seen = new HashSet<UUID>();
    UUID cursor = id;
    while (cursor != null && !cache.containsKey(cursor)) {
      if (!seen.add(cursor)) throw new IllegalArgumentException("Search hierarchy cycle");
      var entry = entries.get(cursor);
      if (entry == null) break;
      pending.push(cursor);
      cursor = entry.parent();
    }
    String prefix = cursor == null ? "" : cache.getOrDefault(cursor, "");
    while (!pending.isEmpty()) {
      UUID next = pending.pop();
      var entry = entries.get(next);
      String root = entry.root().equals("operation") ? "world" : entry.root();
      prefix = (prefix.isEmpty() ? root : prefix) + " / " + entry.resource().id;
      if (prefix.length() > 4096) prefix = "…" + prefix.substring(prefix.length() - 4095);
      cache.put(next, prefix);
    }
    return cache.get(id);
  }

  private void items(List<Hit> hits, Noise n, List<Item> items, String kind, String root) {
    for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
      var item = items.get(itemIndex);
      var tree = (ObjectNode) ProjectIO.JSON.valueToTree(item);
      var direct = tree.deepCopy();
      direct.remove(List.of("parameters", "inputs"));
      String base =
          paths.get(n.uuid)
              + " / "
              + (kind.equals("transform") ? "Coordinates" : "Processing")
              + " / "
              + item.id;
      String pointer =
          locations.get(n.uuid)
              + (kind.equals("transform") ? "/coordinateTransforms/" : "/processing/")
              + itemIndex;
      add(
          hits,
          item.uuid,
          n.uuid,
          kind,
          item.id,
          item.enabled,
          root,
          base,
          item.uuid,
          null,
          pointer,
          direct,
          tree);
      for (var input : item.inputs.entrySet())
        add(
            hits,
            item.uuid,
            n.uuid,
            "input binding",
            input.getKey(),
            item.enabled,
            root,
            base + " / Input / " + input.getKey(),
            item.uuid,
            "inputs/" + input.getKey(),
            pointer + "/inputs/" + escape(input.getKey()),
            ProjectIO.JSON.valueToTree(input.getValue()),
            null);
      for (var parameter : item.parameters.entrySet()) {
        String key = parameter.getKey();
        JsonNode value = parameter.getValue();
        if (key.equals("curve") && value.isObject()) {
          var metadata = value.deepCopy();
          ((ObjectNode) metadata).remove("points");
          add(
              hits,
              item.uuid,
              n.uuid,
              "item parameter",
              key,
              item.enabled,
              root,
              base + " / " + key,
              item.uuid,
              "parameters/" + key,
              pointer + "/parameters/curve",
              metadata,
              null);
          int pointIndex = 0;
          for (var point : value.path("points")) {
            UUID pointId = UUID.fromString(point.path("uuid").asText());
            String label = "(" + point.path("x").asText() + ", " + point.path("y").asText() + ")";
            add(
                hits,
                pointId,
                n.uuid,
                "curve point",
                label,
                item.enabled,
                root,
                base + " / Curve / " + label,
                pointId,
                "curve",
                pointer + "/parameters/curve/points/" + (pointIndex++),
                point,
                null);
          }
        } else
          add(
              hits,
              item.uuid,
              n.uuid,
              "item parameter",
              key,
              item.enabled,
              root,
              base + " / " + key,
              item.uuid,
              "parameters/" + key,
              pointer + "/parameters/" + escape(key),
              ProjectIO.JSON.createObjectNode().set(key, value),
              null);
      }
    }
  }

  private void add(
      List<Hit> hits,
      UUID id,
      UUID owner,
      String kind,
      String label,
      boolean enabled,
      String root,
      String breadcrumb,
      UUID control,
      String parameter,
      String pointer,
      JsonNode direct,
      JsonNode container) {
    String value = direct.toString();
    if (value.length() > 32768) value = value.substring(0, 32768);
    String text =
        (label + " " + kind + " " + id + " " + breadcrumb + " " + value).toLowerCase(Locale.ROOT);
    var directRefs = ResourceGraph.references(direct);
    hits.add(
        new Hit(
            id,
            owner,
            kind,
            label,
            enabled,
            text,
            container == null ? directRefs : ResourceGraph.references(container),
            root,
            breadcrumb,
            new EditorEvents.Target(
                project, owner == null ? id : owner, control, parameter, pointer, false),
            directRefs,
            id
                + ":"
                + kind
                + ":"
                + Objects.toString(parameter, "")
                    .chars()
                    .mapToObj(Integer::toHexString)
                    .collect(java.util.stream.Collectors.joining("-"))));
  }

  private boolean within(UUID resource, UUID directory, Map<UUID, Boolean> cache) {
    if (directory == null) return true;
    var pending = new ArrayList<UUID>();
    var seen = new HashSet<UUID>();
    UUID cursor = resource;
    boolean match = false;
    while (cursor != null) {
      if (cursor.equals(directory)) {
        match = true;
        break;
      }
      if (cache.containsKey(cursor)) {
        match = cache.get(cursor);
        break;
      }
      if (!seen.add(cursor)) break;
      pending.add(cursor);
      cursor = parents.get(cursor);
    }
    for (UUID id : pending) cache.put(id, match);
    return match;
  }

  public List<Hit> search(Filter filter) {
    var directoryCache = new HashMap<UUID, Boolean>();
    return all.stream()
        .filter(hit -> filter.kind().equals("All") || hit.kind().equals(filter.kind()))
        .filter(
            hit ->
                filter.state().equals("All") || hit.enabled() == filter.state().equals("Enabled"))
        .filter(hit -> filter.root().equals("All") || hit.root().equals(filter.root()))
        .filter(hit -> within(hit.resource(), filter.directory(), directoryCache))
        .filter(
            hit ->
                filter.reference() == null
                    || (filter.containers() ? hit.references() : hit.directReferences())
                        .contains(filter.reference()))
        .filter(
            hit ->
                (switch (filter.mode()) {
                      case ANY_TEXT -> hit.text();
                      case ID -> hit.label().toLowerCase(Locale.ROOT);
                      case BREADCRUMB -> hit.breadcrumb().toLowerCase(Locale.ROOT);
                    })
                    .contains(filter.query()))
        .toList();
  }

  public List<Hit> search(String query, String kind, String state, UUID reference) {
    return search(new Filter(query, kind, state, "All", null, reference, true, Mode.ANY_TEXT));
  }

  public int size() {
    return all.size();
  }
}
