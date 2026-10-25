package io.voxelcreator.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/**
 * Session copy/portable import primitives. UUID remapping includes all copied internal identities
 * and references.
 */
public final class ResourceTransactions {
  public static String unique(String base, Collection<String> existing) {
    if (!existing.contains(base)) return base;
    String stem = base + "-copy";
    if (!existing.contains(stem)) return stem;
    for (int i = 2; ; i++) if (!existing.contains(stem + "-" + i)) return stem + "-" + i;
  }

  public static <T> T duplicate(Object object, Class<T> type) {
    JsonNode node = ProjectIO.JSON.valueToTree(object);
    Map<String, String> mapping = new HashMap<>();
    collectIds(node, mapping);
    remap(node, mapping);
    return ProjectIO.JSON.convertValue(node, type);
  }

  public record Copy(ProjectData project, Map<UUID, UUID> identities) {}

  public static Copy duplicateMapped(ProjectData selection) {
    JsonNode tree = ProjectIO.JSON.valueToTree(selection);
    Map<String, String> mapping = new LinkedHashMap<>();
    collectIds(tree, mapping);
    remap(tree, mapping);
    var ids = new LinkedHashMap<UUID, UUID>();
    mapping.forEach((a, b) -> ids.put(UUID.fromString(a), UUID.fromString(b)));
    return new Copy(ProjectIO.JSON.convertValue(tree, ProjectData.class), Map.copyOf(ids));
  }

  public static ProjectData duplicateSelection(ProjectData selection) {
    return duplicate(selection, ProjectData.class);
  }

  private static void collectIds(JsonNode node, Map<String, String> mapping) {
    if (node.isObject()) {
      var u = node.get("uuid");
      if (u != null && u.isTextual()) mapping.put(u.asText(), UUID.randomUUID().toString());
      node.elements().forEachRemaining(n -> collectIds(n, mapping));
    } else if (node.isArray()) node.forEach(n -> collectIds(n, mapping));
  }

  private static void remap(JsonNode node, Map<String, String> mapping) {
    remapOwnIds(node, mapping);
    var references = new HashMap<UUID, UUID>();
    mapping.forEach((from, to) -> references.put(UUID.fromString(from), UUID.fromString(to)));
    ResourceGraph.rewriteReferences(node, references);
  }

  private static void remapOwnIds(JsonNode node, Map<String, String> mapping) {
    if (node instanceof ObjectNode object) {
      if (object.hasNonNull("uuid")) {
        String value = mapping.get(object.get("uuid").asText());
        if (value != null) object.put("uuid", value);
      }
      object.elements().forEachRemaining(child -> remapOwnIds(child, mapping));
    } else if (node.isArray()) node.forEach(child -> remapOwnIds(child, mapping));
  }

  /**
   * Import copies only authoring resources; project seed/bounds and bookmarks remain those of the
   * destination.
   */
  public static void mergeCopies(ProjectData into, ProjectData selection) {
    ResourceGraph.append(into, duplicateSelection(selection));
  }

  private static <T extends Identity> void merge(List<T> to, List<T> from) {
    var ids = new ArrayList<>(to.stream().map(r -> r.id).toList());
    for (T r : from) {
      r.id = unique(r.id, ids);
      ids.add(r.id);
      to.add(r);
    }
  }
}
