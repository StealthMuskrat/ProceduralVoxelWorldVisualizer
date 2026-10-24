package io.voxelcreator.example;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.voxelcreator.core.*;
import io.voxelcreator.model.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Generates structural Draft 2020-12 definitions directly from the canonical authoring model.
 * Semantic validation remains mandatory.
 */
public final class ContractGenerator {
  private static final ObjectMapper JSON = ProjectIO.JSON;
  private final Map<String, ObjectNode> definitions = new TreeMap<>();

  static ObjectNode obj() {
    return JSON.createObjectNode();
  }

  private JsonNode type(Type type) {
    if (type instanceof ParameterizedType p) {
      Class<?> raw = (Class<?>) p.getRawType();
      if (List.class.isAssignableFrom(raw)) {
        ObjectNode n = obj().put("type", "array");
        n.set("items", type(p.getActualTypeArguments()[0]));
        return n;
      }
      if (Map.class.isAssignableFrom(raw)) {
        ObjectNode n = obj().put("type", "object");
        n.set("additionalProperties", type(p.getActualTypeArguments()[1]));
        return n;
      }
    }
    Class<?> c = (Class<?>) type;
    if (c == String.class) return obj().put("type", "string");
    if (c == boolean.class) return obj().put("type", "boolean");
    if (c == int.class || c == Integer.class || c == long.class) {
      ObjectNode n = obj();
      n.set(
          "type",
          c == Integer.class
              ? JSON.valueToTree(List.of("integer", "null"))
              : TextNode.valueOf("integer"));
      n.put("minimum", c == long.class ? Long.MIN_VALUE : Integer.MIN_VALUE);
      n.put("maximum", c == long.class ? Long.MAX_VALUE : Integer.MAX_VALUE);
      return n;
    }
    if (c.isArray()) {
      ObjectNode n = obj().put("type", "array");
      n.set("items", type(c.getComponentType()));
      return n;
    }
    if (c == Float.class || c == Double.class || c == double.class || c == float.class)
      return obj().put("type", "number");
    if (c == java.util.UUID.class) {
      ObjectNode n = obj().put("format", "uuid");
      n.set("type", JSON.valueToTree(List.of("string", "null")));
      return n;
    }
    if (c == JsonNode.class) return obj();
    String name = c.getSimpleName();
    if (!definitions.containsKey(name)) {
      var def = obj();
      definitions.put(name, def);
      def.put("type", "object");
      def.put("additionalProperties", false);
      var props = obj();
      var required = JSON.createArrayNode();
      for (var f : c.getFields())
        if (!Modifier.isStatic(f.getModifiers())) {
          props.set(f.getName(), type(f.getGenericType()));
          if (f.getType() != java.util.UUID.class
              && f.getType() != Integer.class
              && f.getType() != JsonNode.class) required.add(f.getName());
        }
      if (props.has("uuid")) {
        props.set("uuid", obj().put("type", "string").put("format", "uuid"));
        required.add("uuid");
      }
      def.set("properties", props);
      def.set("required", required);
    }
    return obj().put("$ref", "#/$defs/" + name);
  }

  static void write(Path root) throws Exception {
    Files.createDirectories(root.resolve("schema"));
    Files.createDirectories(root.resolve("registries"));
    Files.createDirectories(root.resolve("fixtures"));
    var g = new ContractGenerator();
    var schema =
        obj()
            .put("$schema", "https://json-schema.org/draft/2020-12/schema")
            .put("$id", "urn:voxelcreator:project:2")
            .put("title", "Procedural Voxel Creator project format 2");
    schema.setAll((ObjectNode) g.type(ProjectData.class));
    ObjectNode defs = obj();
    g.definitions.forEach(defs::set);
    ((ObjectNode) defs.path("ProjectData").path("properties"))
        .set("formatVersion", obj().put("const", 2));
    schema.set("$defs", defs);
    for (String channel : List.of("r", "g", "b"))
      ((ObjectNode) defs.path("Color").path("properties").path(channel))
          .put("minimum", 0)
          .put("maximum", 255);
    ((ObjectNode) defs.path("Noise").path("properties").path("dimension"))
        .set("enum", JSON.valueToTree(List.of(2, 3)));
    ((ObjectNode) defs.path("Algorithm").path("properties").path("key"))
        .set("enum", JSON.valueToTree(NoiseAlgorithms.KEYS));
    ((ObjectNode) defs.path("Binding").path("properties").path("sourceKind"))
        .set(
            "enum",
            JSON.valueToTree(
                List.of(
                    "Constant",
                    "Shared Parameter",
                    "Ambient Value",
                    "Source Result",
                    "Earlier Result",
                    "Noise Generator Reference")));
    ((ObjectNode) defs.path("Item").path("properties").path("resultType"))
        .set(
            "enum",
            JSON.valueToTree(
                List.of(
                    "Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D")));
    ((ObjectNode) defs.path("Settings").path("properties").path("action"))
        .set("enum", JSON.valueToTree(List.of("SET", "CLEAR", "NO_CHANGE")));
    for (String key : List.of("strength", "probability"))
      ((ObjectNode) defs.path("Settings").path("properties").path(key))
          .put("minimum", 0)
          .put("maximum", 1);
    ((ObjectNode) defs.path("Binding").path("properties").path("ambientKey"))
        .set("type", JSON.valueToTree(List.of("string", "null")));
    ((ObjectNode) defs.path("Operation").path("properties"))
        .set("noiseGeneratorUuid", obj().put("type", "string").put("format", "uuid"));
    ((ArrayNode) defs.path("Operation").path("required")).add("noiseGeneratorUuid");
    writeJSON(root.resolve("schema/project.schema.json"), schema);
    for (String[] editor :
        List.of(
            new String[] {"UserConfig", "user-config.schema.json"},
            new String[] {"AppMetadata", "app-config.schema.json"})) {
      try {
        var eg = new ContractGenerator();
        Class<?> model = Class.forName("io.voxelcreator.editor." + editor[0]);
        ObjectNode es =
            obj()
                .put("$schema", "https://json-schema.org/draft/2020-12/schema")
                .put("$id", "urn:voxelcreator:" + editor[1] + ":1");
        es.setAll((ObjectNode) eg.type(model));
        ObjectNode ed = obj();
        eg.definitions.forEach(ed::set);
        ((ObjectNode) ed.path(editor[0]).path("properties"))
            .set("formatVersion", obj().put("const", 1));
        es.set("$defs", ed);
        writeJSON(root.resolve("schema/" + editor[1]), es);
        Object defaults =
            editor[0].equals("AppMetadata")
                ? model.getMethod("defaults").invoke(null)
                : model.getConstructor().newInstance();
        writeJSON(
            root.resolve("fixtures/" + editor[1].replace(".schema.json", "-defaults.json")),
            defaults);
      } catch (ClassNotFoundException ignored) {
        /* Independent clients do not load editor modules. */
      }
    }

    writeJSON(
        root.resolve("registries/algorithm-registry.json"),
        Map.of(
            "registryVersion",
            1,
            "engineVersion",
            "0.1.0",
            "algorithms",
            NoiseAlgorithms.KEYS,
            "sourceImplementation",
            "FastNoiseLite 1.1.1, double marked coordinates; float output widened to double"));
    writeJSON(
        root.resolve("registries/noise-processing-registry.json"),
        Map.of("registryVersion", 1, "operations", ProcessingRegistry.ITEMS));
    var funcs = new LinkedHashMap<String, Object>();
    Expression.FUNCTIONS.forEach(
        (name, fn) ->
            funcs.put(
                name,
                Map.of(
                    "arity",
                    fn.arity(),
                    "argumentTypes",
                    "Scalar",
                    "resultType",
                    "Scalar",
                    "invalidNumericPolicy",
                    "structured failure at executed boundary")));
    writeJSON(
        root.resolve("registries/expression-function-registry.json"),
        Map.of("registryVersion", 1, "functions", funcs));
    writeJSON(
        root.resolve("registries/world-operation-registry.json"),
        Map.of(
            "registryVersion",
            1,
            "actions",
            List.of("SET", "CLEAR", "NO_CHANGE"),
            "interpretations",
            List.of("HEIGHT_BELOW", "HEIGHT_ABOVE", "HEIGHT_BOUNDARY", "SELECTION", "DENSITY"),
            "inheritableProperties",
            SettingsResolver.PROPERTIES));
    ProjectData empty = new ProjectData();
    empty.project.uuid = UUID.fromString("00000000-0000-4000-8000-000000000010");
    empty.worldGenerator.uuid = UUID.fromString("00000000-0000-4000-8000-000000000011");
    new ProjectIO().save(root.resolve("fixtures/valid-empty.json"), empty);
    new ProjectIO().save(root.resolve("fixtures/terrain.json"), Example.demo());
    ObjectNode invalid = JSON.valueToTree(empty);
    invalid.put("formatVersion", 999);
    writeJSON(root.resolve("fixtures/invalid-format-version.json"), invalid);
    writeJSON(
        root.resolve("fixtures/expression-vectors.json"),
        Map.of(
            "formatVersion",
            2,
            "engineVersion",
            "0.1.0",
            "vectors",
            Map.of("2^3^2", 512, "true ? 7 : 1/0", 7, "mod(-1,3)", 2, "remap(0.5,0,1,10,20)", 15)));
  }

  static void writeJSON(Path path, Object value) throws Exception {
    Files.writeString(path, JSON.writeValueAsString(value) + "\n");
  }
}
