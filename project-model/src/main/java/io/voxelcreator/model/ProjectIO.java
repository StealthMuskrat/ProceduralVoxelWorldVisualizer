package io.voxelcreator.model;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Explicit-only, transactional JSON persistence. No migrations or autosaves. */
public final class ProjectIO {
  public static final ObjectMapper JSON =
      new ObjectMapper()
          .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
          .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
          .enable(SerializationFeature.INDENT_OUTPUT);

  public record LoadResult(ProjectData project, List<Diagnostic> diagnostics) {
    public boolean success() {
      return project != null && diagnostics.isEmpty();
    }
  }

  public LoadResult load(Path path) throws IOException {
    return parse(Files.readString(path));
  }

  public LoadResult parse(String text) {
    try {
      JsonNode tree = JSON.readTree(text);
      if (tree == null || !tree.isObject())
        return fail("INVALID_FORMAT", "/", "Expected a project object");
      for (String key :
          List.of(
              "formatVersion",
              "project",
              "voxelTypes",
              "voxelGroups",
              "voxelSets",
              "noise2D",
              "noise3D",
              "worldGenerator",
              "folders",
              "bookmarks"))
        if (!tree.hasNonNull(key))
          return fail("MISSING_FIELD", "/" + key, "Required field is missing");
      if (!tree.path("formatVersion").isIntegralNumber()
          || tree.path("formatVersion").intValue() != 2)
        return fail("UNSUPPORTED_VERSION", "/formatVersion", "Expected project format 2");
      String malformed = shape(tree);
      if (malformed != null)
        return fail(
            "INVALID_STRUCTURE",
            malformed,
            "Missing required field, incorrect shape, or malformed UUID");
      ProjectData data = JSON.treeToValue(tree, ProjectData.class);
      var issues = Validator.validate(data);
      return new LoadResult(issues.isEmpty() ? data : null, issues);
    } catch (JsonProcessingException e) {
      String path = "/";
      if (e instanceof JsonMappingException m)
        path =
            m.getPath().stream()
                .map(
                    r ->
                        r.getFieldName() != null
                            ? r.getFieldName()
                            : Integer.toString(r.getIndex()))
                .reduce("", (a, b) -> a + "/" + b);
      return fail("INVALID_JSON", path, e.getOriginalMessage());
    } catch (RuntimeException e) {
      return fail("INVALID_FORMAT", "/", String.valueOf(e.getMessage()));
    }
  }

  private static String shape(JsonNode tree) {
    for (String key : List.of("project", "worldGenerator", "bookmarks"))
      if (!tree.path(key).isObject()) return "/" + key;
    for (String key : List.of("uuid", "seed", "minY", "maxY"))
      if (!tree.path("project").hasNonNull(key)) return "/project/" + key;
    if (!tree.path("worldGenerator").hasNonNull("uuid")
        || !tree.path("worldGenerator").path("layers").isArray()) return "/worldGenerator";
    for (String key : List.of("noise2D", "noise3D", "world"))
      if (!tree.path("bookmarks").path(key).isArray()) return "/bookmarks/" + key;
    for (String key :
        List.of("voxelTypes", "voxelGroups", "voxelSets", "noise2D", "noise3D", "folders")) {
      if (!tree.path(key).isArray()) return "/" + key;
      String issue = resourceArray(tree.path(key), "/" + key);
      if (issue != null) return issue;
    }
    String issue =
        resourceArray(tree.path("worldGenerator").path("layers"), "/worldGenerator/layers");
    if (issue != null) return issue;
    return uuids(tree, "");
  }

  private static String resourceArray(JsonNode list, String path) {
    for (int i = 0; i < list.size(); i++) {
      JsonNode r = list.get(i);
      String p = path + "/" + i;
      if (!r.isObject() || !r.hasNonNull("uuid") || !r.path("id").isTextual()) return p;
      for (String key :
          List.of("processing", "coordinateTransforms", "sharedParameters", "operations"))
        if (r.has(key)) {
          if (!r.path(key).isArray()) return p + "/" + key;
          String issue = resourceArray(r.path(key), p + "/" + key);
          if (issue != null) return issue;
        }
    }
    return null;
  }

  private static String uuids(JsonNode node, String path) {
    if (node.isObject()) {
      var fields = node.fields();
      while (fields.hasNext()) {
        var e = fields.next();
        String key = e.getKey();
        JsonNode value = e.getValue();
        if ((key.equals("uuid") || key.endsWith("Uuid") || key.equals("reference"))
            && !value.isNull()
            && (!value.isTextual()
                || !value
                    .asText()
                    .matches(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
          return path + "/" + key;
        String issue = uuids(value, path + "/" + key);
        if (issue != null) return issue;
      }
    } else if (node.isArray())
      for (int i = 0; i < node.size(); i++) {
        String issue = uuids(node.get(i), path + "/" + i);
        if (issue != null) return issue;
      }
    return null;
  }

  private LoadResult fail(String c, String p, String m) {
    return new LoadResult(null, List.of(Diagnostic.error(c, p, m)));
  }

  public static ProjectData copy(ProjectData data) {
    return JSON.convertValue(data, ProjectData.class);
  }

  public void save(Path destination, ProjectData data) throws IOException {
    var issues = Validator.validate(data);
    if (!issues.isEmpty()) throw new IOException(issues.toString());
    Path target = destination.toAbsolutePath(), parent = target.getParent();
    Files.createDirectories(parent);
    Path temp = Files.createTempFile(parent, ".voxel-save-", ".tmp");
    try {
      Files.writeString(temp, JSON.writeValueAsString(data));
      try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(
            temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }
}
