package io.voxelcreator.model;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
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

  private final ProjectSaveFiles files;

  public ProjectIO() {
    this(new ProjectSaveFiles() {});
  }

  ProjectIO(ProjectSaveFiles files) {
    this.files = Objects.requireNonNull(files);
  }

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
      JsonNode tree =
          JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
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
          || !tree.path("formatVersion").canConvertToInt()
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
                .reduce("", ProjectIO::child);
      if (path.isEmpty()) path = "/";
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
    for (String key : List.of("noise2D", "noise3D", "world")) {
      JsonNode bookmarks = tree.path("bookmarks").path(key);
      if (!bookmarks.isArray()) return "/bookmarks/" + key;
      for (int i = 0; i < bookmarks.size(); i++) {
        JsonNode bookmark = bookmarks.get(i);
        String path = "/bookmarks/" + key + "/" + i;
        if (!bookmark.isObject()) return path;
        if (!bookmark.hasNonNull("uuid")) return path + "/uuid";
        if (!bookmark.path("name").isTextual()) return path + "/name";
        for (String axis : List.of("x", "z"))
          if (!bookmark.path(axis).isIntegralNumber() || !bookmark.path(axis).canConvertToInt())
            return path + "/" + axis;
        if (key.equals("noise2D")) {
          if (bookmark.hasNonNull("y")) return path + "/y";
        } else if (!bookmark.path("y").isIntegralNumber() || !bookmark.path("y").canConvertToInt())
          return path + "/y";
      }
    }
    for (String key :
        List.of("voxelTypes", "voxelGroups", "voxelSets", "noise2D", "noise3D", "folders")) {
      if (!tree.path(key).isArray()) return "/" + key;
      String issue = resourceArray(tree.path(key), "/" + key);
      if (issue != null) return issue;
    }
    String issue =
        resourceArray(tree.path("worldGenerator").path("layers"), "/worldGenerator/layers");
    if (issue != null) return issue;
    return ProjectUuidShapes.invalid(tree);
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

  private static String child(String path, String key) {
    return path + "/" + key.replace("~", "~0").replace("/", "~1");
  }

  private LoadResult fail(String c, String p, String m) {
    return new LoadResult(null, List.of(Diagnostic.error(c, p, m)));
  }

  public static ProjectData copy(ProjectData data) {
    return JSON.convertValue(data, ProjectData.class);
  }

  /**
   * Validate and sync a temporary sibling; unsupported atomic replacement leaves the target intact.
   */
  public void save(Path destination, ProjectData data) throws IOException {
    saveAndGetCheckpoint(destination, data);
  }

  /** The exact serialized payload becomes the document's saved checkpoint after publication. */
  String saveAndGetCheckpoint(Path destination, ProjectData data) throws IOException {
    var issues = Validator.validate(data);
    if (!issues.isEmpty()) throw new IOException(issues.toString());
    String contents = JSON.writeValueAsString(data);
    Path target = Objects.requireNonNull(destination).toAbsolutePath(), parent = target.getParent();
    if (parent == null || target.getFileName() == null)
      throw new IOException("Choose a project file path");
    Files.createDirectories(parent);
    Path temp = files.temporary(parent);
    Throwable failure = null;
    boolean published = false;
    try {
      files.write(temp, contents);
      files.force(temp);
      try {
        files.replace(temp, target);
      } catch (AtomicMoveNotSupportedException unsupported) {
        throw new IOException(
            "This location does not support safe project replacement; choose another location",
            unsupported);
      }
      published = true;
    } catch (IOException | RuntimeException | Error rejected) {
      failure = rejected;
      throw rejected;
    } finally {
      // Successful replacement transferred the temporary file; no later I/O may turn it into a
      // failed save.
      if (!published) {
        try {
          files.delete(temp);
        } catch (IOException | RuntimeException | Error cleanup) {
          if (failure == null) throw cleanup;
          if (failure != cleanup) failure.addSuppressed(cleanup);
        }
      }
    }
    return contents;
  }
}
