package io.voxelcreator.model;

import com.fasterxml.jackson.databind.*;
import io.voxelcreator.model.ProjectData.*;
import java.lang.reflect.Modifier;
import java.util.*;

/** UUID preflight follows declared model types; map keys remain user data. */
final class ProjectUuidShapes {
  private static final ClassValue<Map<String, JavaType>> FIELDS =
      new ClassValue<>() {
        protected Map<String, JavaType> computeValue(Class<?> type) {
          var fields = new HashMap<String, JavaType>();
          for (var field : type.getFields())
            if (!Modifier.isStatic(field.getModifiers()))
              fields.put(field.getName(), ProjectIO.JSON.constructType(field.getGenericType()));
          return Map.copyOf(fields);
        }
      };

  private ProjectUuidShapes() {}

  static String invalid(JsonNode tree) {
    return invalid(tree, ProjectIO.JSON.constructType(ProjectData.class), "");
  }

  static String invalidSettings(JsonNode tree) {
    return invalid(tree, ProjectIO.JSON.constructType(Settings.class), "");
  }

  private static String invalid(JsonNode node, JavaType type, String path) {
    if (node == null || node.isNull()) return null;
    Class<?> raw = type.getRawClass();
    if (raw == UUID.class) return UuidText.valid(node) ? null : path;
    if (JsonNode.class.isAssignableFrom(raw)) return null;
    if (type.isCollectionLikeType() || type.isArrayType()) {
      if (!node.isArray()) return null; // Binding reports non-UUID shape errors separately.
      var content = type.getContentType();
      for (int i = 0; i < node.size(); i++) {
        String child = path + "/" + i;
        if (content.getRawClass() == UUID.class && node.get(i).isNull()) return child;
        String issue = invalid(node.get(i), content, child);
        if (issue != null) return issue;
      }
      return null;
    }
    if (type.isMapLikeType()) {
      if (!node.isObject()) return null;
      var fields = node.fields();
      while (fields.hasNext()) {
        var field = fields.next();
        String issue =
            invalid(field.getValue(), type.getContentType(), child(path, field.getKey()));
        if (issue != null) return issue;
      }
      return null;
    }
    if (!node.isObject()
        || raw != ProjectData.class && raw.getEnclosingClass() != ProjectData.class) return null;
    var declared = FIELDS.get(raw);
    var fields = node.fields();
    while (fields.hasNext()) {
      var field = fields.next();
      String key = field.getKey(), next = child(path, key);
      var value = field.getValue();
      JavaType expected = declared.get(key);
      if (expected == null) continue; // Unknown fields still reject during model binding.
      String issue;
      if (raw == Operation.class && key.equals("overrides"))
        issue = invalid(value, ProjectIO.JSON.constructType(Settings.class), next);
      else if (raw == Item.class && key.equals("parameters")) issue = parameters(value, next);
      else issue = invalid(value, expected, next);
      if (issue != null) return issue;
    }
    return null;
  }

  private static String parameters(JsonNode parameters, String path) {
    if (!parameters.isObject()) return null;
    if (parameters.has("noiseGeneratorUuid")
        && !UuidText.valid(parameters.get("noiseGeneratorUuid")))
      return child(path, "noiseGeneratorUuid");
    return invalid(
        parameters.get("curve"), ProjectIO.JSON.constructType(Curve.class), child(path, "curve"));
  }

  private static String child(String path, String key) {
    return path + "/" + key.replace("~", "~0").replace("/", "~1");
  }
}
