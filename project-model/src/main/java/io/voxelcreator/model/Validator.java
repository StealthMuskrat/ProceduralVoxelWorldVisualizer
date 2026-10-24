package io.voxelcreator.model;

import io.voxelcreator.model.ProjectData.*;
import java.text.Normalizer;
import java.util.*;

public final class Validator {
  private final List<Diagnostic> errors = new ArrayList<>();
  private final Set<UUID> identities = new HashSet<>();
  private final Map<UUID, Noise> noises = new HashMap<>();
  private final Map<UUID, Folder> folders = new HashMap<>();
  private final Set<UUID> types = new HashSet<>(), sets = new HashSet<>();

  public static List<Diagnostic> validate(ProjectData p) {
    var v = new Validator();
    try {
      v.project(p);
    } catch (RuntimeException e) {
      v.error(
          "INVALID_STRUCTURE",
          "/",
          e.getMessage() == null ? "Null or malformed value" : e.getMessage());
    }
    return DiagnosticLocations.enrich(p, v.errors);
  }

  private void error(String code, String path, String message) {
    errors.add(Diagnostic.error(code, path, message));
  }

  private void uuid(UUID u, String path) {
    if (u == null) error("MISSING_UUID", path, "UUID required");
    else if (!identities.add(u)) error("DUPLICATE_UUID", path, "Duplicate identity " + u);
  }

  private void id(String id, Set<String> ids, String path) {
    if (id == null
        || id.isEmpty()
        || !id.equals(id.strip())
        || id.codePoints().anyMatch(Character::isISOControl)
        || !Normalizer.isNormalized(id, Normalizer.Form.NFC))
      error("INVALID_ID", path, "ID must be nonempty NFC without edge whitespace or controls");
    else if (!ids.add(id)) error("DUPLICATE_ID", path, "Duplicate ID " + id);
  }

  private void resources(List<? extends Identity> list, String path) {
    var ids = new HashSet<String>();
    for (int i = 0; i < list.size(); i++) {
      var r = list.get(i);
      uuid(r.uuid, path + "/" + i + "/uuid");
      id(r.id, ids, path + "/" + i + "/id");
    }
  }

  private void reference(UUID u, Collection<UUID> available, String path) {
    if (u == null || !available.contains(u))
      error("INVALID_REFERENCE", path, "Unresolved UUID " + u);
  }

  private void finite(double d, String path) {
    if (!Double.isFinite(d)) error("NONFINITE", path, "Finite number required");
  }

  private void project(ProjectData p) {
    if (p.formatVersion != 2) error("UNSUPPORTED_VERSION", "/formatVersion", "Expected version 2");
    uuid(p.project.uuid, "/project/uuid");
    uuid(p.worldGenerator.uuid, "/worldGenerator/uuid");
    if (p.project.minY > p.project.maxY)
      error("INVALID_BOUNDS", "/project", "minY must be <= maxY");
    resources(p.voxelTypes, "/voxelTypes");
    resources(p.voxelGroups, "/voxelGroups");
    resources(p.voxelSets, "/voxelSets");
    for (int i = 0; i < p.voxelTypes.size(); i++) {
      var t = p.voxelTypes.get(i);
      types.add(t.uuid);
      if ("EMPTY".equals(t.id))
        error("RESERVED_ID", "/voxelTypes/" + i + "/id", "EMPTY is not a resource");
      var c = t.color;
      if (c.r < 0 || c.r > 255 || c.g < 0 || c.g > 255 || c.b < 0 || c.b > 255)
        error("INVALID_COLOR", "/voxelTypes/" + i + "/color", "RGB bytes required");
    }
    for (var set : p.voxelSets) sets.add(set.uuid);
    for (var list : List.of(p.voxelGroups, p.voxelSets))
      for (var group : list) {
        var members = new HashSet<UUID>();
        for (var u : group.voxelTypeUuids) {
          reference(u, types, "/membership/" + group.uuid);
          if (!members.add(u))
            error("DUPLICATE_MEMBER", "/membership/" + group.uuid, "Duplicate member");
        }
      }
    for (var n : p.noise2D) noises.put(n.uuid, n);
    for (var n : p.noise3D) noises.put(n.uuid, n);
    resources(p.noise2D, "/noise2D");
    resources(p.noise3D, "/noise3D");
    for (int dim = 2; dim <= 3; dim++) {
      var list = dim == 2 ? p.noise2D : p.noise3D;
      for (int i = 0; i < list.size(); i++) {
        var n = list.get(i);
        String path = "/noise" + dim + "D/" + i;
        if (n.dimension != dim)
          error("INVALID_DIMENSION", path + "/dimension", "Dimension does not match root");
        noise(n, path);
      }
    }
    var folderNames = new HashMap<String, Set<String>>();
    for (int i = 0; i < p.folders.size(); i++) {
      var f = p.folders.get(i);
      String path = "/folders/" + i;
      uuid(f.uuid, path + "/uuid");
      id(
          f.id,
          folderNames.computeIfAbsent(f.rootKind + f.parentFolderUuid, k -> new HashSet<>()),
          path + "/id");
      if (!Set.of("noise2D", "noise3D", "world").contains(f.rootKind))
        error("INVALID_ROOT", path, "Unknown root");
      folders.put(f.uuid, f);
    }
    for (var f : p.folders) {
      if (f.parentFolderUuid != null) {
        var parent = folders.get(f.parentFolderUuid);
        if (parent == null || !parent.rootKind.equals(f.rootKind))
          error("INVALID_REFERENCE", "/folders/" + f.uuid, "Missing or incompatible parent");
      }
      var children = new HashSet<UUID>();
      for (var e : ResourceGraph.entries(p))
        if (Objects.equals(e.parent(), f.uuid)) children.add(e.uuid());
      var ordered = new HashSet<UUID>();
      for (UUID child : f.childOrder)
        if (!children.contains(child) || !ordered.add(child))
          error(
              "INVALID_ORDER",
              "/folders/" + f.uuid + "/childOrder",
              "Order entries must identify distinct direct children");
      var visited = new HashSet<UUID>();
      Folder at = f;
      while (at != null) {
        if (!visited.add(at.uuid)) {
          error("FOLDER_CYCLE", "/folders/" + f.uuid, "Folder ancestry cycle");
          break;
        }
        at = folders.get(at.parentFolderUuid);
      }
    }
    for (var n : noises.values())
      if (n.parentFolderUuid != null) {
        var f = folders.get(n.parentFolderUuid);
        if (f == null || !f.rootKind.equals("noise" + n.dimension + "D"))
          error(
              "INVALID_REFERENCE", "/noise/" + n.uuid + "/parentFolderUuid", "Incompatible folder");
      }
    for (var n : noises.values()) cycle(n, new LinkedHashSet<>(), new HashSet<>());
    resources(p.worldGenerator.layers, "/worldGenerator/layers");
    int li = 0;
    for (var layer : p.worldGenerator.layers) {
      String path = "/worldGenerator/layers/" + li++;
      settings(layer.defaults, path + "/defaults");
      resources(layer.operations, path + "/operations");
      int oi = 0;
      for (var op : layer.operations) {
        String q = path + "/operations/" + oi++;
        reference(op.noiseGeneratorUuid, noises.keySet(), q + "/noiseGeneratorUuid");
        if (!WorldSemantics.INTERPRETATIONS.contains(op.interpretation))
          error("INVALID_INTERPRETATION", q + "/interpretation", "Unsupported interpretation");
        try {
          var s = SettingsResolver.resolve(layer, op);
          settings(s, q + "/overrides");
          if (op.interpretation.equals("TYPE_SELECTION") && !s.typeSource.equals("INTERVALS"))
            error(
                "INVALID_TYPE_SOURCE",
                q + "/interpretation",
                "TYPE_SELECTION requires interval type source");
          var n = noises.get(op.noiseGeneratorUuid);
          if (n != null && op.interpretation.startsWith("HEIGHT") && n.dimension != 2)
            error("INVALID_DIMENSION", q, "Height interpretation requires 2D field");
        } catch (RuntimeException e) {
          error("INVALID_OVERRIDE", q + "/overrides", e.getMessage());
        }
      }
    }
    for (var list : List.of(p.bookmarks.noise2D, p.bookmarks.noise3D, p.bookmarks.world))
      for (var b : list) {
        uuid(b.uuid, "/bookmarks/uuid");
        if (b.name == null || b.name.isBlank())
          error("INVALID_ID", "/bookmarks/name", "Name required");
        if (list == p.bookmarks.noise2D && b.y != null)
          error("INVALID_BOOKMARK", "/bookmarks/noise2D", "2D bookmark has no Y");
        if (list != p.bookmarks.noise2D && b.y == null)
          error("INVALID_BOOKMARK", "/bookmarks", "3D bookmark requires Y");
      }
  }

  private void noise(Noise n, String path) {
    if (n.algorithm == null || n.algorithm.key == null)
      error("INVALID_ALGORITHM", path + "/algorithm", "Algorithm required");
    else n.algorithm.parameters.forEach((k, v) -> finite(v, path + "/algorithm/parameters/" + k));
    resources(n.sharedParameters, path + "/sharedParameters");
    var params = new HashSet<UUID>();
    for (var param : n.sharedParameters) {
      params.add(param.uuid);
      valueType(param.type, param.value, path + "/sharedParameters/" + param.uuid);
    }
    var itemNames = new HashSet<String>();
    var earlier = new HashMap<UUID, Item>();
    var allItems = new HashSet<UUID>();
    for (var list : List.of(n.coordinateTransforms, n.processing)) {
      earlier.clear();
      for (int i = 0; i < list.size(); i++) {
        var item = list.get(i);
        allItems.add(item.uuid);
        String q = path + (list == n.processing ? "/processing/" : "/coordinateTransforms/") + i;
        uuid(item.uuid, q + "/uuid");
        id(item.id, itemNames, q + "/id");
        if (!TYPES.contains(item.resultType))
          error("INVALID_TYPE", q + "/resultType", "Unsupported type");
        if (item.parameters.containsKey("curve"))
          curveIdentities(item.parameters.get("curve"), q + "/parameters/curve");
        if (item.parameters.containsKey("noiseGeneratorUuid")) {
          try {
            reference(
                UUID.fromString(item.parameters.get("noiseGeneratorUuid").asText()),
                noises.keySet(),
                q + "/parameters/noiseGeneratorUuid");
          } catch (RuntimeException e) {
            error(
                "INVALID_REFERENCE", q + "/parameters/noiseGeneratorUuid", "Malformed noise UUID");
          }
        }
        for (var e : item.inputs.entrySet()) {
          binding(e.getValue(), params, earlier, item.enabled, q + "/inputs/" + e.getKey());
          if (list == n.coordinateTransforms
              && e.getValue() != null
              && Set.of(
                      "Source Result",
                      "Source Field",
                      "Earlier Field Prefix",
                      "Noise Generator Field")
                  .contains(e.getValue().sourceKind))
            error(
                "INVALID_STAGE",
                q + "/inputs/" + e.getKey(),
                "Coordinate stage cannot use source or processing results");
        }
        earlier.put(item.uuid, item);
      }
    }
    binding(n.outputBinding, params, earlier, true, path + "/outputBinding");
    if (n.outputBinding != null
        && !Set.of("Source Result", "Earlier Result").contains(n.outputBinding.sourceKind))
      error(
          "INVALID_OUTPUT",
          path + "/outputBinding",
          "Select raw source or an enabled Scalar processing result");
    if (n.outputBinding != null && !"Scalar".equals(n.outputBinding.type))
      error("INVALID_OUTPUT", path + "/outputBinding", "Noise output must be Scalar");
    for (var pin : n.pinnedControls)
      if (!params.contains(pin.targetUuid) && !allItems.contains(pin.targetUuid))
        error("INVALID_REFERENCE", path + "/pinnedControls", "Unknown pinned control");
  }

  public static final Set<String> TYPES =
      Set.of("Scalar", "Boolean", "Vector2", "Vector3", "Coordinates2D", "Coordinates3D");

  private void valueType(String type, com.fasterxml.jackson.databind.JsonNode v, String path) {
    if (!TYPES.contains(type)) {
      error("INVALID_TYPE", path, "Unsupported type " + type);
      return;
    }
    if (v == null) {
      error("MISSING_INPUT", path, "Constant required");
      return;
    }
    if (type.equals("Scalar")) {
      if (!v.isNumber() || !Double.isFinite(v.doubleValue()))
        error("TYPE_ERROR", path, "Finite Scalar required");
    } else if (type.equals("Boolean")) {
      if (!v.isBoolean()) error("TYPE_ERROR", path, "Boolean required");
    } else {
      int size = type.endsWith("2") || type.endsWith("2D") ? 2 : 3;
      if (!v.isArray() || v.size() != size)
        error("TYPE_ERROR", path, "Expected " + size + " components");
      else
        for (var c : v)
          if (!c.isNumber() || !Double.isFinite(c.doubleValue()))
            error("TYPE_ERROR", path, "Finite components required");
    }
  }

  private void curveIdentities(com.fasterxml.jackson.databind.JsonNode curve, String path) {
    if (curve == null || !curve.isObject()) {
      error("INVALID_CURVE", path, "Curve object required");
      return;
    }
    try {
      ProjectIO.JSON.convertValue(curve, Curve.class);
    } catch (IllegalArgumentException invalid) {
      error(
          "INVALID_CURVE",
          path,
          "Unknown fields or invalid serialized curve types: " + invalid.getMessage());
      return;
    }
    rawIdentity(curve.get("uuid"), path + "/uuid");
    var points = curve.get("points");
    if (points == null || !points.isArray()) {
      error("INVALID_CURVE", path + "/points", "Point array required");
      return;
    }
    for (int i = 0; i < points.size(); i++)
      rawIdentity(points.get(i).get("uuid"), path + "/points/" + i + "/uuid");
  }

  private void rawIdentity(com.fasterxml.jackson.databind.JsonNode node, String path) {
    try {
      if (node == null || !node.isTextual()) throw new IllegalArgumentException();
      uuid(UUID.fromString(node.textValue()), path);
    } catch (IllegalArgumentException invalid) {
      error("MISSING_UUID", path, "Explicit valid UUID required");
    }
  }

  private void binding(
      Binding b, Set<UUID> params, Map<UUID, Item> earlier, boolean enabled, String path) {
    if (b == null) {
      error("MISSING_INPUT", path, "Binding required");
      return;
    }
    finite(b.slice, path + "/slice");
    if (!Set.of("XZ", "XY", "YZ").contains(b.projection))
      error("INVALID_PROJECTION", path + "/projection", "Expected XZ, XY or YZ");
    if (Set.of("Source Field", "Earlier Field Prefix", "Noise Generator Field")
            .contains(b.sourceKind)
        && !b.type.equals("Scalar"))
      error("TYPE_ERROR", path, "Sampled fields must have Scalar results");
    switch (b.sourceKind) {
      case "Constant" -> valueType(b.type, b.value, path);
      case "Shared Parameter" -> reference(b.parameterUuid, params, path);
      case "Source Result", "Source Field" -> {
        if (!"Scalar".equals(b.type)) error("TYPE_ERROR", path, "Source is Scalar");
      }
      case "Earlier Result", "Earlier Field Prefix" -> {
        var item = earlier.get(b.itemUuid);
        if (item == null) error("INVALID_ORDER", path, "Earlier result must precede this item");
        else {
          if (enabled && !item.enabled)
            error("DISABLED_DEPENDENCY", path, "Enabled result depends on disabled item");
          if (!b.type.equals(item.resultType)) error("TYPE_ERROR", path, "Result type mismatch");
        }
      }
      case "Noise Generator Reference", "Noise Generator Field" -> {
        reference(b.noiseGeneratorUuid, noises.keySet(), path);
        if (!b.type.equals("Scalar")) error("TYPE_ERROR", path, "Noise output is Scalar");
      }
      case "Ambient Value" -> {
        if (!Set.of(
                "x",
                "y",
                "z",
                "worldMinY",
                "worldMaxY",
                "worldHeight",
                "normalizedY",
                "projectSeed",
                "noiseGeneratorSeed")
            .contains(b.ambientKey)) error("INVALID_AMBIENT", path, "Unknown ambient value");
      }
      default -> error("INVALID_BINDING", path, "Unsupported source kind " + b.sourceKind);
    }
  }

  private void cycle(Noise n, LinkedHashSet<UUID> path, Set<UUID> done) {
    if (done.contains(n.uuid)) return;
    if (!path.add(n.uuid)) {
      error("NOISE_CYCLE", "/noise/" + n.uuid, "Noise reference cycle " + path + " -> " + n.uuid);
      return;
    }
    for (var list : List.of(n.coordinateTransforms, n.processing))
      for (var item : list) {
        if (item.parameters.containsKey("noiseGeneratorUuid"))
          try {
            UUID ref = UUID.fromString(item.parameters.get("noiseGeneratorUuid").asText());
            if (noises.containsKey(ref)) cycle(noises.get(ref), path, done);
          } catch (IllegalArgumentException ignored) {
          }
        for (var b : item.inputs.values())
          if (b.noiseGeneratorUuid != null && noises.containsKey(b.noiseGeneratorUuid))
            cycle(noises.get(b.noiseGeneratorUuid), path, done);
      }
    path.remove(n.uuid);
    done.add(n.uuid);
  }

  private void settings(Settings s, String path) {
    for (var f : Settings.class.getFields())
      try {
        if (f.getType() == double.class) finite(f.getDouble(s), path + "/" + f.getName());
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    if (s.minY > s.maxY) error("INVALID_BOUNDS", path, "minY exceeds maxY");
    if (s.strength < 0 || s.strength > 1 || s.probability < 0 || s.probability > 1)
      error("INVALID_PROBABILITY", path, "Strength/probability must be in [0,1]");
    if (!Set.of("SET", "CLEAR", "NO_CHANGE").contains(s.action))
      error("INVALID_ACTION", path + "/action", "Unknown action");
    if (!WorldSemantics.TYPE_SOURCES.contains(s.typeSource))
      error("INVALID_TYPE_SOURCE", path + "/typeSource", "Unknown type source");
    if (s.action.equals("SET") && s.typeSource.equals("FIXED"))
      reference(s.voxelTypeUuid, types, path + "/voxelTypeUuid");
    if (s.typeSource.equals("INTERVALS") && s.typeIntervals.isEmpty())
      error("EMPTY_INTERVALS", path + "/typeIntervals", "Interval type source needs candidates");
    intervals(s.typeIntervals, path + "/typeIntervals");
    for (String axis : List.of(s.axisX, s.axisY, s.axisZ))
      if (!WorldSemantics.AXES.contains(axis))
        error("INVALID_AXIS", path, "Choose signed X, Y or Z");
    if (s.samplingTransforms.size() > 64)
      error("COMPLEXITY_LIMIT", path + "/samplingTransforms", "At most 64 sampling transforms");
    for (int i = 0; i < s.samplingTransforms.size(); i++) {
      var t = s.samplingTransforms.get(i);
      String q = path + "/samplingTransforms/" + i;
      if (!WorldSemantics.TRANSFORMS.contains(t.kind))
        error("INVALID_TRANSFORM", q, "Unknown sampling transform");
      if (!WorldSemantics.ROTATION_ORDERS.contains(t.rotationOrder))
        error("INVALID_ROTATION_ORDER", q, "Explicit XYZ permutation required");
      finite(t.x, q + "/x");
      finite(t.y, q + "/y");
      finite(t.z, q + "/z");
      finite(t.pivotX, q + "/pivotX");
      finite(t.pivotY, q + "/pivotY");
      finite(t.pivotZ, q + "/pivotZ");
    }
    driver(s.probabilityDriver, path + "/probabilityDriver");
    driver(s.strengthDriver, path + "/strengthDriver");
    if (s.parameterDrivers.size() > 32)
      error("COMPLEXITY_LIMIT", path + "/parameterDrivers", "At most 32 parameter drivers");
    for (int i = 0; i < s.parameterDrivers.size(); i++) {
      var d = s.parameterDrivers.get(i);
      String q = path + "/parameterDrivers/" + i;
      driver(d, q, false);
      if (!WorldSemantics.PARAMETER_TARGETS.contains(d.destination))
        error("INVALID_DRIVER_TARGET", q + "/destination", "Unsupported numeric destination");
      if (!WorldSemantics.COMBINATIONS.contains(d.combination))
        error("INVALID_COMBINATION", q + "/combination", "Unknown combination");
    }
    if (!Set.of("FLOOR", "CEIL", "ROUND").contains(s.rounding))
      error("INVALID_ROUNDING", path, "Unknown rounding");
    predicate(s.voxelStatePredicate, path + "/voxelStatePredicate", 0);
    gate(s.fieldGates, path + "/fieldGates", 0);
  }

  private void driver(FieldDriver d, String path) {
    driver(d, path, true);
  }

  private void driver(FieldDriver d, String path, boolean unitOutput) {
    if (unitOutput && d instanceof NumericDriver)
      error(
          "INVALID_DRIVER_SHAPE",
          path,
          "Probability/strength use FieldDriver; numeric rows belong in parameterDrivers");
    finite(d.inputMin, path + "/inputMin");
    finite(d.inputMax, path + "/inputMax");
    finite(d.outputMin, path + "/outputMin");
    finite(d.outputMax, path + "/outputMax");
    if (!(d.inputMin < d.inputMax))
      error("INVALID_DRIVER_RANGE", path, "Input minimum must be smaller than maximum");
    if (unitOutput && (d.outputMin < 0 || d.outputMin > 1 || d.outputMax < 0 || d.outputMax > 1))
      error("INVALID_PROBABILITY", path, "Driver output endpoints must be in [0,1]");
    if (!WorldSemantics.MAPPINGS.contains(d.mapping))
      error("INVALID_MAPPING", path, "Unknown driver mapping");
    if (d.noiseGeneratorUuid != null)
      reference(d.noiseGeneratorUuid, noises.keySet(), path + "/noiseGeneratorUuid");
  }

  private void intervals(List<TypeInterval> ranges, String path) {
    if (ranges.size() > 256) error("COMPLEXITY_LIMIT", path, "At most 256 type intervals");
    for (int i = 0; i < ranges.size(); i++) {
      var r = ranges.get(i);
      String q = path + "/" + i;
      finite(r.min, q + "/min");
      finite(r.max, q + "/max");
      if (r.min > r.max || r.min == r.max && !(r.includeMin && r.includeMax))
        error("EMPTY_INTERVAL", q, "Interval must contain at least one value");
      reference(r.voxelTypeUuid, types, q + "/voxelTypeUuid");
    }
    var order = new ArrayList<Integer>();
    for (int i = 0; i < ranges.size(); i++) order.add(i);
    order.sort(
        Comparator.<Integer>comparingDouble(i -> ranges.get(i).min)
            .thenComparingDouble(i -> ranges.get(i).max));
    for (int i = 1; i < order.size(); i++) {
      var a = ranges.get(order.get(i - 1));
      var b = ranges.get(order.get(i));
      if (a.max > b.min || a.max == b.min && a.includeMax && b.includeMin)
        error(
            "OVERLAPPING_INTERVAL",
            path + "/" + order.get(i),
            "Type intervals overlap, including endpoint inclusivity");
    }
  }

  private void predicate(Predicate p, String path, int depth) {
    if (depth > 32) {
      error("COMPLEXITY_LIMIT", path, "Predicate too deep");
      return;
    }
    switch (p.kind) {
      case "AND", "OR" -> {
        if (p.children.isEmpty()) error("EMPTY_GROUP", path, "Logical group needs children");
        for (var c : p.children) predicate(c, path + "/children", depth + 1);
      }
      case "TYPE_IS", "TYPE_IS_NOT" -> reference(p.reference, types, path);
      case "IN_SET", "NOT_IN_SET" -> reference(p.reference, sets, path);
      case "ANY", "EMPTY", "OCCUPIED" -> {}
      default -> error("INVALID_PREDICATE", path, "Unknown kind");
    }
  }

  private void gate(Gate g, String path, int depth) {
    if (depth > 32) {
      error("COMPLEXITY_LIMIT", path, "Gate too deep");
      return;
    }
    switch (g.kind) {
      case "AND", "OR" -> {
        if (g.children.isEmpty()) error("EMPTY_GROUP", path, "Gate needs children");
        for (int i = 0; i < g.children.size(); i++)
          gate(g.children.get(i), path + "/children/" + i, depth + 1);
      }
      case "NOT" -> {
        if (g.children.size() != 1) error("INVALID_GROUP", path, "NOT requires exactly one child");
        for (int i = 0; i < g.children.size(); i++)
          gate(g.children.get(i), path + "/children/" + i, depth + 1);
      }
      case "PROBABILITY" -> {
        finite(g.probability, path + "/probability");
        if (g.probability < 0 || g.probability > 1)
          error("INVALID_PROBABILITY", path, "Gate probability must be in [0,1]");
      }
      case "COMPARE", "INTERVAL" -> {
        finite(g.threshold, path);
        finite(g.max, path);
        if (g.kind.equals("INTERVAL")
            && (g.threshold > g.max || g.threshold == g.max && !(g.includeMin && g.includeMax)))
          error("EMPTY_INTERVAL", path, "Gate interval must contain at least one value");
        if (g.noiseGeneratorUuid != null) reference(g.noiseGeneratorUuid, noises.keySet(), path);
        if (!Set.of("GE", "GT", "LE", "LT", "EQ", "NE").contains(g.comparison))
          error("INVALID_COMPARISON", path, "Unknown comparison");
      }
      case "ALWAYS" -> {}
      default -> error("INVALID_GATE", path, "Unknown field gate");
    }
  }
}
