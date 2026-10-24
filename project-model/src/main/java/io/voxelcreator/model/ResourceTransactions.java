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
    if (node.isObject()) {
      var o = (ObjectNode) node;
      var fields = new ArrayList<String>();
      node.fieldNames().forEachRemaining(fields::add);
      for (String key : fields) {
        var v = node.get(key);
        if (v.isTextual() && mapping.containsKey(v.asText())) o.put(key, mapping.get(v.asText()));
        else remap(v, mapping);
      }
    } else if (node.isArray()) {
      var a = (ArrayNode) node;
      for (int i = 0; i < a.size(); i++) {
        var v = a.get(i);
        if (v.isTextual() && mapping.containsKey(v.asText()))
          a.set(i, TextNode.valueOf(mapping.get(v.asText())));
        else remap(v, mapping);
      }
    }
  }

  /**
   * Import copies only authoring resources; project seed/bounds and bookmarks remain those of the
   * destination.
   */
  public static void mergeCopies(ProjectData into, ProjectData selection) {
    ProjectData copy = duplicateSelection(selection);
    merge(into.noise2D, copy.noise2D);
    merge(into.noise3D, copy.noise3D);
    merge(into.voxelTypes, copy.voxelTypes);
    merge(into.voxelGroups, copy.voxelGroups);
    merge(into.voxelSets, copy.voxelSets);
    merge(into.worldGenerator.layers, copy.worldGenerator.layers);
    for (var folder : copy.folders) {
      var existing =
          into.folders.stream()
              .filter(
                  f ->
                      Objects.equals(f.parentFolderUuid, folder.parentFolderUuid)
                          && f.rootKind.equals(folder.rootKind))
              .map(f -> f.id)
              .toList();
      folder.id = unique(folder.id, existing);
      into.folders.add(folder);
    }
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
