package io.voxelcreator.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Resolves validation paths against the validated candidate, before array order can change. */
public final class DiagnosticLocations {
  private DiagnosticLocations() {}

  private static final Set<String> RESOURCES =
      Set.of(
          "noise2D",
          "noise3D",
          "folders",
          "voxelTypes",
          "voxelGroups",
          "voxelSets",
          "layers",
          "operations");
  private static final Set<String> CONTROLS =
      Set.of("processing", "coordinateTransforms", "sharedParameters", "points");

  /**
   * Captures non-null diagnostic rows before reading the candidate. Missing context is inferred
   * only when pointer-resolved identities agree with every supplied resource/control identity.
   */
  public static List<Diagnostic> enrich(ProjectData project, List<Diagnostic> diagnostics) {
    var owned = List.copyOf(diagnostics);
    if (owned.isEmpty()) return owned;
    try {
      JsonNode root = ProjectIO.JSON.valueToTree(project);
      return owned.stream().map(d -> enrich(root, d)).toList();
    } catch (RuntimeException malformed) {
      return owned;
    }
  }

  private static Diagnostic enrich(JsonNode root, Diagnostic d) {
    String path = Objects.requireNonNullElse(d.jsonPointer(), "");
    String[] tokens = path.split("/", -1);
    JsonNode node = root;
    UUID owner = null, control = null;
    String collection = "", parameter = d.parameterKey();
    for (int i = 1; i < tokens.length; i++) {
      String token = tokens[i].replace("~1", "/").replace("~0", "~");
      if (i == 1
          && (token.equals("noise") || token.equals("membership"))
          && i + 1 < tokens.length) {
        UUID id = uuid(tokens[++i]);
        node =
            find(
                root,
                token.equals("noise")
                    ? List.of("noise2D", "noise3D")
                    : List.of("voxelGroups", "voxelSets"),
                id);
        owner = node == null ? null : id;
        collection = "";
      } else if (node != null && node.isArray()) {
        JsonNode next = null;
        try {
          int index = Integer.parseInt(token);
          if (index >= 0 && Integer.toString(index).equals(token)) next = node.get(index);
        } catch (NumberFormatException ignored) {
          UUID id = uuid(token);
          if (id != null)
            for (var child : node)
              if (id.equals(uuid(child.path("uuid").asText()))) {
                next = child;
                break;
              }
        }
        node = next;
        if (node != null) {
          UUID id = uuid(node.path("uuid").asText());
          if (RESOURCES.contains(collection)) {
            owner = id;
            control = null;
          } else if (CONTROLS.contains(collection) && id != null) control = id;
        }
      } else if (node != null && node.isObject()) {
        if ((token.equals("parameters") || token.equals("inputs")) && i + 1 < tokens.length)
          parameter = tokens[i + 1].replace("~1", "/").replace("~0", "~");
        collection = token;
        node = node.get(token);
      } else break;
    }
    if (parameter == null && tokens.length > 1) {
      String last = tokens[tokens.length - 1];
      if (!last.isBlank() && uuid(last) == null && !last.matches("[0-9]+"))
        parameter = last.replace("~1", "/").replace("~0", "~");
    }
    // An explicit identity is authority, not permission to combine it with a different pointer
    // target. Only fill missing context when every supplied identity agrees with that target.
    boolean matching =
        (d.sourceUuid() == null || d.sourceUuid().equals(owner))
            && (d.itemUuid() == null || d.itemUuid().equals(control));
    return new Diagnostic(
        d.code(),
        d.severity(),
        d.message(),
        d.jsonPointer(),
        d.sourceUuid() != null ? d.sourceUuid() : matching ? owner : null,
        d.itemUuid() != null ? d.itemUuid() : matching ? control : null,
        d.parameterKey() != null ? d.parameterKey() : matching ? parameter : null);
  }

  private static JsonNode find(JsonNode root, List<String> collections, UUID uuid) {
    if (uuid == null || root == null) return null;
    for (String key : collections)
      for (var node : root.path(key))
        if (uuid.equals(uuid(node.path("uuid").asText()))) return node;
    return null;
  }

  private static UUID uuid(String value) {
    try {
      var parsed = UUID.fromString(value);
      return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
