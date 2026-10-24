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
    return List.copyOf(v.errors);
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
        if (!Set.of("HEIGHT_BELOW", "HEIGHT_ABOVE", "HEIGHT_BOUNDARY", "SELECTION", "DENSITY")
            .contains(op.interpretation))
          error("INVALID_INTERPRETATION", q + "/interpretation", "Unsupported interpretation");
        try {
          var s = SettingsResolver.resolve(layer, op);
          settings(s, q + "/overrides");
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
    for (var list : List.of(n.coordinateTransforms, n.processing)) {
      for (int i = 0; i < list.size(); i++) {
        var item = list.get(i);
        String q = path + (list == n.processing ? "/processing/" : "/coordinateTransforms/") + i;
        uuid(item.uuid, q + "/uuid");
        id(item.id, itemNames, q + "/id");
        if (!TYPES.contains(item.resultType))
          error("INVALID_TYPE", q + "/resultType", "Unsupported type");
        for (var e : item.inputs.entrySet())
          binding(e.getValue(), params, earlier, item.enabled, q + "/inputs/" + e.getKey());
        earlier.put(item.uuid, item);
      }
    }
    binding(n.outputBinding, params, earlier, true, path + "/outputBinding");
    if (!"Scalar".equals(n.outputBinding.type))
      error("INVALID_OUTPUT", path + "/outputBinding", "Noise output must be Scalar");
    for (var pin : n.pinnedControls)
      if (!params.contains(pin.targetUuid) && !earlier.containsKey(pin.targetUuid))
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

  private void binding(
      Binding b, Set<UUID> params, Map<UUID, Item> earlier, boolean enabled, String path) {
    if (b == null) {
      error("MISSING_INPUT", path, "Binding required");
      return;
    }
    switch (b.sourceKind) {
      case "Constant" -> valueType(b.type, b.value, path);
      case "Shared Parameter" -> reference(b.parameterUuid, params, path);
      case "Source Result" -> {
        if (!"Scalar".equals(b.type)) error("TYPE_ERROR", path, "Source is Scalar");
      }
      case "Earlier Result" -> {
        var item = earlier.get(b.itemUuid);
        if (item == null) error("INVALID_ORDER", path, "Earlier result must precede this item");
        else {
          if (enabled && !item.enabled)
            error("DISABLED_DEPENDENCY", path, "Enabled result depends on disabled item");
          if (!b.type.equals(item.resultType)) error("TYPE_ERROR", path, "Result type mismatch");
        }
      }
      case "Noise Generator Reference" -> {
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
      for (var item : list)
        for (var b : item.inputs.values())
          if (b.noiseGeneratorUuid != null && noises.containsKey(b.noiseGeneratorUuid))
            cycle(noises.get(b.noiseGeneratorUuid), path, done);
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
    if (s.action.equals("SET")) reference(s.voxelTypeUuid, types, path + "/voxelTypeUuid");
    if (!Set.of("FLOOR", "CEIL", "ROUND").contains(s.rounding))
      error("INVALID_ROUNDING", path, "Unknown rounding");
    predicate(s.voxelStatePredicate, path + "/voxelStatePredicate", 0);
    gate(s.fieldGates, path + "/fieldGates", 0);
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
        for (var c : g.children) gate(c, path + "/children", depth + 1);
      }
      case "COMPARE", "INTERVAL" -> {
        finite(g.threshold, path);
        finite(g.max, path);
        if (g.noiseGeneratorUuid != null) reference(g.noiseGeneratorUuid, noises.keySet(), path);
        if (!Set.of("GE", "GT", "LE", "LT", "EQ", "NE").contains(g.comparison))
          error("INVALID_COMPARISON", path, "Unknown comparison");
      }
      case "ALWAYS" -> {}
      default -> error("INVALID_GATE", path, "Unknown field gate");
    }
  }
}
