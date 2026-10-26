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
    g.type(ProjectData.Curve.class);
    ObjectNode defs = obj();
    g.definitions.forEach(defs::set);
    ((ObjectNode) defs.path("ProjectData").path("properties"))
        .set("formatVersion", obj().put("const", 2));
    schema.set("$defs", defs);
    for (var tab : BookmarkTransactions.Tab.values()) {
      String type = tab.threeDimensional() ? "Bookmark3D" : "Bookmark2D";
      var refinement = obj();
      var properties = obj();
      properties.set(
          "y",
          tab.threeDimensional()
              ? obj()
                  .put("type", "integer")
                  .put("minimum", Integer.MIN_VALUE)
                  .put("maximum", Integer.MAX_VALUE)
              : obj().putNull("const"));
      refinement.set("properties", properties);
      if (tab.threeDimensional()) refinement.set("required", JSON.valueToTree(List.of("y")));
      var specialized = obj();
      specialized.set(
          "allOf", JSON.valueToTree(List.of(obj().put("$ref", "#/$defs/Bookmark"), refinement)));
      defs.set(type, specialized);
      ((ObjectNode) defs.path("Bookmarks").path("properties").path(tab.key()))
          .set("items", obj().put("$ref", "#/$defs/" + type));
    }

    writeJSON(
        root.resolve("registries/resource-hierarchy.json"), ResourceGraph.hierarchyRegistry());
    writeJSON(root.resolve("registries/bookmark-navigation.json"), BookmarkTransactions.registry());
    writeJSON(root.resolve("registries/noise-authoring.json"), NoiseAuthoring.registry());
    writeJSON(
        root.resolve("registries/trace-navigation.json"),
        Map.of(
            "registryVersion",
            1,
            "noiseDefaultEvents",
            NoiseTrace.DEFAULT_LIMIT,
            "noiseMaximumEvents",
            NoiseTrace.MAX_LIMIT,
            "worldPrimaryEventBudget",
            1024,
            "phases",
            List.of("SOURCE", "TRANSFORM", "PROCESSING", "FOLDED", "OUTPUT"),
            "logicalSettingKeys",
            SettingsResolver.PROPERTIES,
            "navigationIdentity",
            "project/resource/item UUIDs and logical property; historical nested array indices are"
                + " not replayed"));
    ((ObjectNode) defs.path("Item").path("properties").path("parameters"))
        .set("properties", obj().set("curve", obj().put("$ref", "#/$defs/Curve")));
    ((ArrayNode) defs.path("Curve").path("required")).removeAll();
    for (String field : List.of("uuid", "min", "max", "outsideDomain", "points"))
      ((ArrayNode) defs.path("Curve").path("required")).add(field);
    ((ObjectNode) defs.path("Curve").path("properties").path("outsideDomain"))
        .set("enum", JSON.valueToTree(List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR")));
    ((ObjectNode) defs.path("Point").path("properties").path("handleMode"))
        .set("enum", JSON.valueToTree(List.of("AUTO", "ALIGNED", "FREE")));
    ((ObjectNode) defs.path("Point").path("properties").path("segmentInterpolation"))
        .set("enum", JSON.valueToTree(List.of("STEP", "LINEAR", "SMOOTH", "BEZIER")));

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
                    "Noise Generator Reference",
                    "Source Field",
                    "Earlier Field Prefix",
                    "Noise Generator Field")));
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
    var rootOrder = obj().put("type", "object").put("additionalProperties", false);
    var rootProperties = obj();
    for (String rootKind : List.of("noise2D", "noise3D", "world")) {
      var ids = obj().put("type", "array").put("uniqueItems", true);
      ids.set("items", obj().put("type", "string").put("format", "uuid"));
      rootProperties.set(rootKind, ids);
    }
    rootOrder.set("properties", rootProperties);
    ((ObjectNode) defs.path("Project").path("properties")).set("rootOrder", rootOrder);
    var projectRequired = (ArrayNode) defs.path("Project").path("required");
    for (int i = projectRequired.size() - 1; i >= 0; i--)
      if (projectRequired.get(i).asText().equals("rootOrder")) projectRequired.remove(i);
    // New format-2 fields have documented defaults, so historical format-2 documents remain valid.
    for (String name : List.of("Settings", "Gate")) {
      var required = (ArrayNode) defs.path(name).path("required");
      Set<String> optional =
          name.equals("Settings")
              ? Set.of(
                  "axisX",
                  "axisY",
                  "axisZ",
                  "samplingTransforms",
                  "typeSource",
                  "typeIntervals",
                  "probabilityDriver",
                  "strengthDriver",
                  "parameterDrivers")
              : Set.of("includeMin", "includeMax", "probability", "seedOffset");
      for (int i = required.size() - 1; i >= 0; i--)
        if (optional.contains(required.get(i).asText())) required.remove(i);
    }
    var settingsProps = (ObjectNode) defs.path("Settings").path("properties");
    for (String axis : List.of("axisX", "axisY", "axisZ"))
      ((ObjectNode) settingsProps.path(axis)).set("enum", JSON.valueToTree(WorldSemantics.AXES));
    ((ObjectNode) settingsProps.path("typeSource"))
        .set("enum", JSON.valueToTree(WorldSemantics.TYPE_SOURCES));
    ((ObjectNode) settingsProps.path("typeIntervals")).put("maxItems", 256);
    ((ObjectNode) settingsProps.path("samplingTransforms")).put("maxItems", 64);
    ((ObjectNode) settingsProps.path("parameterDrivers")).put("maxItems", 32);
    ((ObjectNode) defs.path("NumericDriver").path("properties").path("mapping"))
        .set("enum", JSON.valueToTree(WorldSemantics.MAPPINGS));
    ((ObjectNode) defs.path("NumericDriver").path("properties").path("destination"))
        .set("enum", JSON.valueToTree(WorldSemantics.PARAMETER_TARGETS));
    ((ObjectNode) defs.path("NumericDriver").path("properties").path("combination"))
        .set("enum", JSON.valueToTree(WorldSemantics.COMBINATIONS));
    ((ObjectNode) defs.path("SamplingTransform").path("properties").path("kind"))
        .set("enum", JSON.valueToTree(WorldSemantics.TRANSFORMS));
    ((ObjectNode) defs.path("SamplingTransform").path("properties").path("rotationOrder"))
        .set("enum", JSON.valueToTree(WorldSemantics.ROTATION_ORDERS));
    ((ObjectNode) defs.path("FieldDriver").path("properties").path("mapping"))
        .set("enum", JSON.valueToTree(WorldSemantics.MAPPINGS));
    for (String key : List.of("outputMin", "outputMax"))
      ((ObjectNode) defs.path("FieldDriver").path("properties").path(key))
          .put("minimum", 0)
          .put("maximum", 1);
    ((ObjectNode) defs.path("Gate").path("properties").path("kind"))
        .set("enum", JSON.valueToTree(WorldSemantics.GATES));
    ((ObjectNode) defs.path("Gate").path("properties").path("probability"))
        .put("minimum", 0)
        .put("maximum", 1);
    ((ObjectNode) defs.path("Operation").path("properties").path("interpretation"))
        .set("enum", JSON.valueToTree(WorldSemantics.INTERPRETATIONS));
    extensionSchema(defs);
    sourceAlgorithmSchema(defs);
    warpSchema(defs);
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
        if (editor[0].equals("UserConfig")) {
          Class<?> shortcuts = Class.forName("io.voxelcreator.editor.Shortcuts");
          @SuppressWarnings("unchecked")
          Map<String, String> bindings =
              (Map<String, String>) shortcuts.getMethod("defaults").invoke(null);
          ObjectNode chordMap = (ObjectNode) ed.path("Input").path("properties").path("shortcuts");
          chordMap.put("additionalProperties", false).put("maxProperties", bindings.size());
          ObjectNode properties = obj();
          for (String id : bindings.keySet())
            properties.set(id, obj().put("type", "string").put("maxLength", 80));
          chordMap.set("properties", properties);
          writeJSON(
              root.resolve("registries/keyboard-shortcuts.json"),
              shortcuts.getMethod("registry").invoke(null));
          var themeRegistry =
              JSON.valueToTree(
                  Class.forName("io.voxelcreator.editor.ThemeRegistry")
                      .getMethod("registry")
                      .invoke(null));
          ObjectNode appearance = (ObjectNode) ed.path("Appearance");
          ObjectNode appearanceProperties = (ObjectNode) appearance.path("properties");
          var themes = obj().put("type", "string");
          themes.set("enum", themeRegistry.path("startingAppearances"));
          appearanceProperties.set("theme", themes);
          var rgba = obj().put("type", "array").put("minItems", 4).put("maxItems", 4);
          rgba.set("items", obj().put("type", "number").put("minimum", 0).put("maximum", 1));
          var colorMap = obj().put("type", "object").put("additionalProperties", false);
          var colorProperties = obj();
          for (var color : themeRegistry.path("colors"))
            colorProperties.set(color.path("key").asText(), rgba.deepCopy());
          colorMap.set("properties", colorProperties);
          appearanceProperties.set("colors", colorMap);
          var statusMap = obj().put("type", "object").put("additionalProperties", false);
          var statusProperties = obj();
          for (var role : themeRegistry.path("statusRoles"))
            statusProperties.set(role.asText(), rgba.deepCopy());
          statusMap.set("properties", statusProperties);
          appearanceProperties.set("statusColors", statusMap);
          var appearanceRequired = (ArrayNode) appearance.path("required");
          for (int i = appearanceRequired.size() - 1; i >= 0; i--)
            if (appearanceRequired.get(i).asText().equals("statusColors"))
              appearanceRequired.remove(i);
          writeJSON(root.resolve("registries/theme-palette.json"), themeRegistry);
        }
        if (editor[0].equals("AppMetadata")) {
          var defaults = JSON.valueToTree(model.getMethod("defaults").invoke(null));
          var registry =
              JSON.valueToTree(
                  Class.forName("io.voxelcreator.editor.CreationRegistry")
                      .getMethod("registry")
                      .invoke(null));
          ObjectNode control = (ObjectNode) ed.path("Control");
          ((ArrayNode) control.path("required")).removeAll();
          ((ObjectNode) control.path("properties").path("label"))
              .put("maxLength", 160)
              .put("minLength", 1);
          ((ObjectNode) control.path("properties").path("tooltip")).put("maxLength", 1024);
          ((ObjectNode) control.path("properties").path("description")).put("maxLength", 8192);
          ((ObjectNode) control.path("properties").path("group")).put("maxLength", 80);
          ((ObjectNode) control.path("properties").path("order"))
              .put("minimum", -10000)
              .put("maximum", 10000);
          ObjectNode meta = (ObjectNode) ed.path("AppMetadata");
          ((ArrayNode) meta.path("required")).removeAll();
          ((ArrayNode) meta.path("required")).add("formatVersion");
          ObjectNode controlMap = (ObjectNode) meta.path("properties").path("controls");
          controlMap
              .put("additionalProperties", false)
              .put("maxProperties", defaults.path("controls").size());
          ObjectNode controlProperties = obj();
          for (var entries = defaults.path("controls").fields(); entries.hasNext(); ) {
            var entry = entries.next();
            ObjectNode optionProperties = obj();
            for (var option : registry.path("controls").path(entry.getKey()).path("options"))
              optionProperties.set(
                  option.asText(),
                  obj().put("type", "string").put("minLength", 1).put("maxLength", 160));
            var optionMap = obj().put("type", "object").put("additionalProperties", false);
            optionMap.set("properties", optionProperties);
            var refinement = obj();
            refinement.set("properties", obj().set("options", optionMap));
            controlProperties.set(
                entry.getKey(),
                obj()
                    .set(
                        "allOf",
                        JSON.valueToTree(
                            List.of(obj().put("$ref", "#/$defs/Control"), refinement))));
          }
          controlMap.set("properties", controlProperties);
          ObjectNode creationMap = (ObjectNode) meta.path("properties").path("creationDefaults");
          creationMap
              .put("additionalProperties", false)
              .put("maxProperties", registry.path("controls").size());
          ObjectNode properties = obj();
          for (var entries = registry.path("controls").fields(); entries.hasNext(); ) {
            var entry = entries.next();
            var spec = entry.getValue();
            String kind = spec.path("kind").asText();
            ObjectNode value = obj();
            switch (kind) {
              case "BOOLEAN" -> value.put("type", "boolean");
              case "TEXT" -> value.put("type", "string").put("maxLength", 8192);
              case "CHOICE" -> value.set("enum", spec.path("options"));
              case "LONG" ->
                  value
                      .put("type", "integer")
                      .put("minimum", Long.MIN_VALUE)
                      .put("maximum", Long.MAX_VALUE);
              case "VECTOR2", "VECTOR3" -> {
                int count = kind.equals("VECTOR2") ? 2 : 3;
                value.put("type", "array").put("minItems", count).put("maxItems", count);
                value.set(
                    "items",
                    obj()
                        .put("type", "number")
                        .put("minimum", spec.path("minimum").asDouble())
                        .put("maximum", spec.path("maximum").asDouble()));
              }
              default ->
                  value
                      .put("type", kind.equals("INTEGER") ? "integer" : "number")
                      .put("minimum", spec.path("minimum").asDouble())
                      .put("maximum", spec.path("maximum").asDouble());
            }
            properties.set(entry.getKey(), value);
          }
          creationMap.set("properties", properties);
          writeJSON(root.resolve("registries/editor-creation-controls.json"), registry);
        }
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
        root.resolve("registries/algorithm-registry.json"), NoiseAlgorithmRegistry.registry());
    writeJSON(root.resolve("registries/noise-processing-registry.json"), processingRegistry());
    writeJSON(root.resolve("registries/warp-registry.json"), WarpRegistry.registry());
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
        Map.of(
            "registryVersion",
            2,
            "functions",
            funcs,
            "evaluator",
            "immutable lazy register plan; successful-value sharing; indexed primitive bindings",
            "limits",
            Map.of(
                "sourceCharacters",
                16384,
                "tokens",
                4096,
                "parsedNodes",
                4096,
                "parserDepth",
                64,
                "instructions",
                32768),
            "floatingPointPolicy",
            "no reassociation or unsafe algebraic identities; invalid constants remain at executed"
                + " source spans"));
    var worldRegistry = new LinkedHashMap<String, Object>();
    worldRegistry.put("registryVersion", 3);
    worldRegistry.put("actions", List.of("SET", "CLEAR", "NO_CHANGE"));
    worldRegistry.put("interpretations", WorldSemantics.INTERPRETATIONS);
    worldRegistry.put("inheritableProperties", SettingsResolver.PROPERTIES);
    worldRegistry.put("sampleAxes", WorldSemantics.AXES);
    worldRegistry.put("samplingTransforms", WorldSemantics.TRANSFORMS);
    worldRegistry.put("rotationOrders", WorldSemantics.ROTATION_ORDERS);
    worldRegistry.put("typeSources", WorldSemantics.TYPE_SOURCES);
    worldRegistry.put("driverMappings", WorldSemantics.MAPPINGS);
    worldRegistry.put("fieldGates", WorldSemantics.GATES);
    worldRegistry.put("parameterTargets", WorldSemantics.PARAMETER_TARGETS);
    worldRegistry.put("parameterCombinations", WorldSemantics.COMBINATIONS);
    worldRegistry.put(
        "parameterPolicy",
        "at most 32 ordered rows; active destinations only; primary or explicit dimensional field;"
            + " finite signed mapped endpoints; REPLACE/ADD/MULTIPLY current value before"
            + " interpretation; disabled or irrelevant rows are not sampled");
    worldRegistry.put(
        "samplingOrder",
        "signed axes, component scale and offset, enabled transforms in stored order, resource"
            + " definition; 2D fields use transformed X/Z");
    worldRegistry.put(
        "driverComposition",
        "probability * strength * mapped probability driver * mapped strength driver; disabled"
            + " factors are 1");
    worldRegistry.put(
        "intervalPolicy",
        "finite nonempty intervals; explicit inclusive endpoints; overlaps rejected; no match is"
            + " NO_CHANGE");
    worldRegistry.put(
        "rotationPolicy",
        "right-handed degrees; each listed axis rotates current coordinates about that fixed"
            + " sampling-space axis; pivot applies before/after each transform");
    writeJSON(root.resolve("registries/world-operation-registry.json"), worldRegistry);
    var curve = new ProjectData.Curve();
    curve.uuid = UUID.fromString("00000000-0000-4000-8000-000000000030");
    curve.points.getFirst().uuid = UUID.fromString("00000000-0000-4000-8000-000000000031");
    curve.points.getLast().uuid = UUID.fromString("00000000-0000-4000-8000-000000000032");
    writeJSON(root.resolve("fixtures/curve.json"), curve);
    ProjectData empty = new ProjectData();
    empty.project.uuid = UUID.fromString("00000000-0000-4000-8000-000000000010");
    empty.worldGenerator.uuid = UUID.fromString("00000000-0000-4000-8000-000000000011");
    new ProjectIO().save(root.resolve("fixtures/valid-empty.json"), empty);
    new ProjectIO().save(root.resolve("fixtures/terrain.json"), Example.demo());
    new ProjectIO().save(root.resolve("fixtures/sampled-fields.json"), Example.sampledFieldsDemo());
    new ProjectIO().save(root.resolve("fixtures/world-use.json"), Example.worldUseDemo());
    new ProjectIO()
        .save(root.resolve("fixtures/parameter-drivers.json"), Example.parameterDriversDemo());
    new ProjectIO()
        .save(
            root.resolve("fixtures/compiled-expressions.json"), Example.compiledExpressionsDemo());
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

  private static void sourceAlgorithmSchema(ObjectNode defs) {
    ObjectNode map = (ObjectNode) defs.path("Algorithm").path("properties").path("parameters");
    map.put("additionalProperties", false);
    var known = obj();
    for (var spec : NoiseAlgorithmRegistry.ITEMS.values())
      for (var control : spec.parameters()) {
        var type = obj().put("type", control.integer() ? "integer" : "number");
        if (control.key().equals("frequency"))
          type.put("minimum", control.minimum()).put("maximum", control.maximum());
        known.set(control.key(), type);
      }
    map.set("properties", known);
    var rules = JSON.createArrayNode();
    for (var spec : NoiseAlgorithmRegistry.ITEMS.values()) {
      var controls = obj();
      for (var control : spec.parameters()) {
        if (control.threeDimensional()) continue;
        controls.set(
            control.key(),
            obj()
                .put("type", control.integer() ? "integer" : "number")
                .put("minimum", control.minimum())
                .put("maximum", control.maximum()));
      }
      var condition = obj();
      condition.set("properties", obj().set("key", obj().put("const", spec.key())));
      condition.set("required", JSON.valueToTree(List.of("key")));
      var rule = obj();
      rule.set("if", condition);
      rule.set(
          "then",
          obj().set("properties", obj().set("parameters", obj().set("properties", controls))));
      rules.add(rule);
    }
    ((ObjectNode) defs.path("Algorithm")).set("allOf", rules);
    var activeY = obj();
    activeY.set("properties", obj().set("extentY", obj().put("type", "number").put("minimum", 0)));
    var box = obj();
    box.set("if", obj().set("properties", obj().set("key", obj().put("const", "BOX_SDF"))));
    box.set("then", obj().set("properties", obj().set("parameters", activeY)));
    var dimension = obj();
    dimension.set("if", obj().set("properties", obj().set("dimension", obj().put("const", 3))));
    dimension.set("then", obj().set("properties", obj().set("algorithm", box)));
    ((ObjectNode) defs.path("Noise")).set("allOf", JSON.createArrayNode().add(dimension));
  }

  public static Map<String, Object> processingRegistry() {
    var result = new LinkedHashMap<String, Object>();
    result.put("registryVersion", 5);
    result.put("operations", ProcessingRegistry.ITEMS);
    result.put("sampledFieldOperations", new TreeSet<>(ProcessingRegistry.SAMPLED));
    result.put(
        "sampledFieldSources",
        List.of("Source Field", "Earlier Field Prefix", "Noise Generator Field"));
    result.put("extendedOperations", ProcessingExtensions.DETAILS);
    result.put("sampleWorkLimit", NoiseCompiler.MAX_SAMPLE_WORK);
    result.put("warpRegistry", "warp-registry.json");
    return Collections.unmodifiableMap(result);
  }

  private static void extensionSchema(ObjectNode defs) {
    var rules = JSON.createArrayNode();
    for (var entry : ProcessingExtensions.DETAILS.entrySet()) {
      var properties = obj();
      for (var control : entry.getValue().controls()) {
        var value = obj();
        if (control.defaultValue() instanceof Boolean) value.put("type", "boolean");
        else if (!control.options().isEmpty())
          value.set("enum", JSON.valueToTree(control.options()));
        else {
          value.put("type", control.integer() ? "integer" : "number");
          value.put("minimum", control.minimum()).put("maximum", control.maximum());
        }
        properties.set(control.key(), value);
      }
      var thenProperties = obj();
      thenProperties.set("parameters", obj().set("properties", properties));
      thenProperties.set(
          "resultType",
          obj()
              .set(
                  "enum",
                  JSON.valueToTree(
                      new LinkedHashSet<>(
                          List.of(entry.getValue().result2D(), entry.getValue().result3D())))));
      var inputs = obj();
      if (ProcessingExtensions.CALCULUS.contains(entry.getKey())) {
        inputs.set("field", obj().put("$ref", "#/$defs/Binding"));
        inputs.set("coordinates", obj().put("$ref", "#/$defs/Binding"));
      } else if (ProcessingExtensions.SHAPES.contains(entry.getKey()))
        inputs.set("position", obj().put("$ref", "#/$defs/Binding"));
      else {
        for (String key : ProcessingRegistry.ITEMS.get(entry.getKey()).inputs())
          inputs.set(key, obj().put("$ref", "#/$defs/Binding"));
        if (entry.getKey().equals("BLEND"))
          inputs.set("weight", obj().put("$ref", "#/$defs/Binding"));
        if (entry.getKey().equals("FALLOFF"))
          inputs.set("position", obj().put("$ref", "#/$defs/Binding"));
      }
      var inputMap = obj().put("additionalProperties", false);
      inputMap.set("properties", inputs);
      thenProperties.set("inputs", inputMap);
      var condition = obj();
      var conditionProperties = obj();
      conditionProperties.set("kind", obj().put("const", entry.getKey()));
      conditionProperties.set("enabled", obj().put("const", true));
      condition.set("properties", conditionProperties);
      condition.set("required", JSON.valueToTree(List.of("kind", "enabled")));
      var rule = obj();
      rule.set("if", condition);
      rule.set("then", obj().set("properties", thenProperties));
      rules.add(rule);
    }
    defs.withObject("/Item").set("allOf", rules);
  }

  private static ObjectNode fields(Object... entries) {
    var result = obj();
    for (int i = 0; i < entries.length; i += 2)
      result.set((String) entries[i], (JsonNode) entries[i + 1]);
    return result;
  }

  private static void warpSchema(ObjectNode defs) {
    var controls = obj();
    for (var control : WarpRegistry.controls(3)) {
      var value = obj();
      if (!control.options().isEmpty() && !control.numericOptions())
        value.set(
            "enum",
            JSON.valueToTree(
                control.key().equals("warpOrder")
                    ? List.of("XZ", "ZX", "XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX")
                    : control.options()));
      else {
        value.put("type", control.integer() ? "integer" : "number");
        if (!control.key().startsWith("source."))
          value.put("minimum", control.minimum()).put("maximum", control.maximum());
      }
      controls.set(control.key(), value);
    }
    controls.put("source.frequency", false);
    var algorithmRules = JSON.createArrayNode();
    for (var key : NoiseAlgorithms.KEYS) {
      var sourceControls = obj();
      for (var c : NoiseAlgorithmRegistry.parameters(key, 3))
        if (!c.key().equals("frequency") && !c.threeDimensional())
          sourceControls.set(
              "source." + c.key(),
              obj()
                  .put("type", c.integer() ? "integer" : "number")
                  .put("minimum", c.minimum())
                  .put("maximum", c.maximum()));
      var condition = fields("properties", fields("warpAlgorithm", obj().put("const", key)));
      if (!key.equals("OPEN_SIMPLEX_2"))
        condition.set("required", JSON.valueToTree(List.of("warpAlgorithm")));
      algorithmRules.add(fields("if", condition, "then", fields("properties", sourceControls)));
    }
    var parameterSchema = fields("properties", controls, "allOf", algorithmRules);
    for (var kind : List.of("DOMAIN_WARP", "WARP_SAMPLE")) {
      var inputs = obj();
      var names =
          new ArrayList<String>(List.of("coordinates", "amplitude", "warpX", "warpY", "warpZ"));
      if (kind.equals("WARP_SAMPLE")) names.add("field");
      else
        names.addAll(
            List.of(
                "x", "y", "z", "angle", "angleX", "angleY", "angleZ", "pivotX", "pivotY",
                "pivotZ"));
      for (var name : names) inputs.set(name, obj().put("$ref", "#/$defs/Binding"));
      for (var name : WarpRegistry.COMPONENTS) {
        var sources =
            kind.equals("DOMAIN_WARP")
                ? List.of("Noise Generator Field")
                : List.of("Source Field", "Earlier Field Prefix", "Noise Generator Field");
        var shape =
            fields(
                "properties",
                fields(
                    "sourceKind",
                    fields("enum", JSON.valueToTree(sources)),
                    "type",
                    obj().put("const", "Scalar")));
        inputs.set(
            name,
            fields(
                "allOf", JSON.valueToTree(List.of(obj().put("$ref", "#/$defs/Binding"), shape))));
      }
      var inputMap = fields("properties", inputs);
      inputMap.put("additionalProperties", false);
      var then = fields("properties", fields("parameters", parameterSchema, "inputs", inputMap));
      var condition =
          fields(
              "properties",
              fields("kind", obj().put("const", kind), "enabled", obj().put("const", true)),
              "required",
              JSON.valueToTree(List.of("kind", "enabled")));
      ((ArrayNode) defs.path("Item").path("allOf")).add(fields("if", condition, "then", then));
    }
    for (int dimension : List.of(2, 3)) {
      var parameterProperties =
          fields("warpOrder", fields("enum", JSON.valueToTree(WarpRegistry.orders(dimension))));
      var boxCondition =
          fields(
              "properties",
              fields("warpAlgorithm", obj().put("const", "BOX_SDF")),
              "required",
              JSON.valueToTree(List.of("warpAlgorithm")));
      var conditional =
          fields(
              "if",
              boxCondition,
              "then",
              fields("properties", fields("source.extentY", obj().put("minimum", 0))));
      var params = fields("properties", parameterProperties);
      if (dimension == 3) params.set("allOf", JSON.createArrayNode().add(conditional));
      var kind = fields("enum", JSON.valueToTree(List.of("DOMAIN_WARP", "WARP_SAMPLE")));
      var itemCondition =
          fields(
              "properties",
              fields("kind", kind, "enabled", obj().put("const", true)),
              "required",
              JSON.valueToTree(List.of("kind", "enabled")));
      var itemRefinement =
          fields("if", itemCondition, "then", fields("properties", fields("parameters", params)));
      var arrays = obj();
      for (var name : List.of("coordinateTransforms", "processing"))
        arrays.set(name, fields("items", itemRefinement));
      var dimensionCondition =
          fields("properties", fields("dimension", obj().put("const", dimension)));
      ((ArrayNode) defs.path("Noise").path("allOf"))
          .add(fields("if", dimensionCondition, "then", fields("properties", arrays)));
    }
  }

  static void writeJSON(Path path, Object value) throws Exception {
    Files.writeString(path, JSON.writeValueAsString(value) + "\n");
  }
}
