package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Computation identity independent of palette, bookmarks and folder presentation. */
public final class PreviewIdentity {
  private PreviewIdentity() {}

  public static String of(ProjectData p, int kind, UUID noise) {
    ObjectNode tree;
    if (kind == 2) {
      tree = ProjectIO.JSON.valueToTree(p);
      tree.remove("bookmarks");
      tree.remove("folders");
      for (JsonNode type : tree.path("voxelTypes")) ((ObjectNode) type).remove("color");
    } else {
      tree = ProjectIO.JSON.createObjectNode();
      tree.set("project", ProjectIO.JSON.valueToTree(p.project));
      var resources = new HashMap<UUID, Noise>();
      p.noise2D.forEach(n -> resources.put(n.uuid, n));
      p.noise3D.forEach(n -> resources.put(n.uuid, n));
      var closure = new HashSet<UUID>();
      var todo = new ArrayDeque<UUID>();
      if (noise != null) todo.add(noise);
      while (!todo.isEmpty()) {
        UUID id = todo.removeFirst();
        if (!closure.add(id)) continue;
        Noise n = resources.get(id);
        if (n != null)
          for (UUID reference : ResourceGraph.references(n))
            if (resources.containsKey(reference)) todo.add(reference);
      }
      ArrayNode definitions = tree.putArray("definitions");
      closure.stream()
          .sorted()
          .forEach(
              id -> {
                if (resources.containsKey(id))
                  definitions.add(ProjectIO.JSON.valueToTree(resources.get(id)));
              });
    }
    stripPresentation(tree);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(tree.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void stripPresentation(JsonNode n) {
    if (n instanceof ObjectNode object) {
      object.remove("parentFolderUuid");
      object.remove("rootOrder");
      object.remove("pinnedControls");
      if (object.has("points")) {
        object.remove("displayMin");
        object.remove("displayMax");
      }
      object.elements().forEachRemaining(PreviewIdentity::stripPresentation);
    } else if (n.isArray()) n.forEach(PreviewIdentity::stripPresentation);
  }
}
