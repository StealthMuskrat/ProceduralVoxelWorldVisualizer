package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.*;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;

/**
 * Immutable, versioned editor creation-control signatures. Never consulted by project evaluation.
 */
public final class CreationRegistry {
  private CreationRegistry() {}

  public enum Kind {
    NUMBER,
    INTEGER,
    LONG,
    BOOLEAN,
    CHOICE,
    VECTOR2,
    VECTOR3,
    TEXT
  }

  public record Spec(
      String id,
      String group,
      String label,
      Kind kind,
      JsonNode builtIn,
      double minimum,
      double maximum,
      List<String> options) {
    public Spec {
      builtIn = builtIn.deepCopy();
      options = List.copyOf(options);
    }

    @Override
    public JsonNode builtIn() {
      return builtIn.deepCopy();
    }

    public void validate(JsonNode value) {
      if (value == null || value.isNull())
        throw new IllegalArgumentException(id + ": value required");
      boolean valid =
          switch (kind) {
            case BOOLEAN -> value.isBoolean();
            case CHOICE -> value.isTextual() && options.contains(value.textValue());
            case TEXT -> value.isTextual() && value.textValue().length() <= 8192;
            case LONG -> value.isIntegralNumber() && value.canConvertToLong();
            case NUMBER, INTEGER ->
                value.isNumber()
                    && Double.isFinite(value.doubleValue())
                    && value.doubleValue() >= minimum
                    && value.doubleValue() <= maximum
                    && (kind != Kind.INTEGER
                        || value.doubleValue() == StrictMath.rint(value.doubleValue()));
            case VECTOR2, VECTOR3 -> {
              boolean ok = value.isArray() && value.size() == (kind == Kind.VECTOR2 ? 2 : 3);
              for (var item : value)
                ok &=
                    item.isNumber()
                        && Double.isFinite(item.doubleValue())
                        && item.doubleValue() >= minimum
                        && item.doubleValue() <= maximum;
              yield ok;
            }
          };
      if (!valid)
        throw new IllegalArgumentException(
            id
                + ": expected "
                + kind
                + (options.isEmpty()
                    ? " in [" + minimum + ", " + maximum + "]"
                    : " from " + options));
    }
  }

  public static final Map<String, Spec> CONTROLS;
  public static final List<String> TRANSFORMS =
      List.of("TRANSLATE", "SCALE", "ROTATE", "DOMAIN_WARP");

  static {
    var m = new LinkedHashMap<String, Spec>();
    for (int dimension : new int[] {2, 3}) {
      String root = "noise" + dimension + "D";
      String group = dimension + "D Noise";
      choice(
          m,
          root + ".algorithm",
          group,
          "New noise algorithm",
          "OPEN_SIMPLEX_2",
          NoiseAlgorithms.KEYS);
      add(
          m,
          root + ".seedOffset",
          group,
          "New noise seed offset",
          Kind.LONG,
          0L,
          Long.MIN_VALUE,
          Long.MAX_VALUE,
          List.of());
      for (String algorithm : NoiseAlgorithms.KEYS) {
        String prefix = root + ".algorithm." + algorithm + ".";
        String category = group + " / " + algorithm;
        for (var control : NoiseAlgorithmRegistry.parameters(algorithm, dimension))
          add(
              m,
              prefix + control.key(),
              category,
              control.label(),
              control.integer() ? Kind.INTEGER : Kind.NUMBER,
              control.defaultValue(),
              control.presetMinimum(),
              control.presetMaximum(),
              control.choices());
      }
    }
    for (var entry : ProcessingRegistry.ITEMS.entrySet()) {
      var item = ProcessingRegistry.create(entry.getKey(), "prototype");
      String prefix = "processing." + entry.getKey() + ".";
      String group = "Processing / " + entry.getKey();
      for (var input : item.inputs.entrySet())
        if (input.getValue().sourceKind.equals("Constant")) {
          var binding = input.getValue();
          Kind type =
              switch (binding.type) {
                case "Boolean" -> Kind.BOOLEAN;
                case "Vector2", "Coordinates2D" -> Kind.VECTOR2;
                case "Vector3", "Coordinates3D" -> Kind.VECTOR3;
                default -> Kind.NUMBER;
              };
          add(
              m,
              prefix + "input." + input.getKey(),
              group,
              "Literal input " + input.getKey(),
              type,
              binding.value,
              -1e6,
              1e6,
              List.of());
        }
    }
    for (var entry : ProcessingExtensions.DETAILS.entrySet())
      for (var control : entry.getValue().controls()) {
        String id = "processing." + entry.getKey() + ".parameter." + control.key();
        if (!control.options().isEmpty())
          choice(
              m,
              id,
              "Processing / " + entry.getKey(),
              control.label(),
              (String) control.defaultValue(),
              control.options());
        else
          add(
              m,
              id,
              "Processing / " + entry.getKey(),
              control.label(),
              control.integer() ? Kind.INTEGER : Kind.NUMBER,
              control.defaultValue(),
              control.minimum(),
              control.maximum(),
              List.of());
      }
    parameter(m, "CONSTANT", "value", 0, -1e6, 1e6);
    parameter(m, "REMAP", "inputMin", -1, -1e6, 1e6);
    parameter(m, "REMAP", "inputMax", 1, -1e6, 1e6);
    parameter(m, "REMAP", "outputMin", 0, -1e6, 1e6);
    parameter(m, "REMAP", "outputMax", 1, -1e6, 1e6);
    add(
        m,
        "processing.REMAP.parameter.clamp",
        "Processing / REMAP",
        "Clamp output",
        Kind.BOOLEAN,
        false,
        0,
        0,
        List.of());
    parameter(m, "CLAMP", "min", 0, -1e6, 1e6);
    parameter(m, "CLAMP", "max", 1, -1e6, 1e6);
    integer(
        m, "processing.TERRACE.parameter.levels", "Processing / TERRACE", "Levels", 8, 1, 65536);
    parameter(m, "TERRACE", "transition", 0, 0, 1);
    parameter(m, "FALLOFF", "extent", 64, 1e-9, 1e6);
    for (String op : List.of("FALLOFF", "SPHERE"))
      for (String axis : List.of("X", "Y", "Z")) parameter(m, op, "center" + axis, 0, -1e6, 1e6);
    parameter(m, "SPHERE", "radius", 16, 0, 1e6);
    for (String axis : List.of("X", "Y", "Z")) parameter(m, "BOX", "extent" + axis, 16, 0, 1e6);
    parameter(m, "SMOOTH_UNION", "smoothness", 1, 1e-9, 1e6);
    integer(
        m,
        "processing.COMPONENT.parameter.component",
        "Processing / COMPONENT",
        "Component index",
        0,
        0,
        2);
    parameter(m, "VECTOR_ROTATE", "angle", 0, -360000, 360000);
    choice(
        m,
        "processing.VECTOR_ROTATE.parameter.axis",
        "Processing / VECTOR_ROTATE",
        "Rotation axis",
        "x",
        List.of("x", "y", "z"));
    for (String op :
        List.of("PROJECT_VECTOR", "PROJECT_COORDINATES", "EXPAND_VECTOR", "EXPAND_COORDINATES"))
      choice(
          m,
          "processing." + op + ".parameter.axes",
          "Processing / " + op,
          "Retained axes",
          "XZ",
          List.of("XZ", "XY", "YZ"));
    add(
        m,
        "processing.EXPRESSION.parameter.expression",
        "Processing / EXPRESSION",
        "Expression",
        Kind.TEXT,
        "source",
        0,
        0,
        List.of());
    for (String op : List.of("FBM", "RIDGED", "BILLOW", "PING_PONG")) {
      integer(
          m, "processing." + op + ".parameter.octaves", "Processing / " + op, "Octaves", 4, 1, 16);
      parameter(m, op, "frequency", 1, 1e-9, 1e6);
      parameter(m, op, "lacunarity", 2, 1, 8);
      parameter(m, op, "gain", .5, 0, 1);
    }
    for (String op : List.of("DERIVATIVE", "SMOOTH_FILTER"))
      parameter(m, op, "spacing", 1, 1e-9, 1e6);
    integer(
        m,
        "processing.SMOOTH_FILTER.parameter.radius",
        "Processing / SMOOTH_FILTER",
        "Radius",
        1,
        0,
        4);
    parameter(m, "SMOOTH_FILTER", "strength", 1, 0, 1);
    choice(
        m,
        "processing.SMOOTH_FILTER.parameter.kernel",
        "Processing / SMOOTH_FILTER",
        "Kernel",
        "BOX",
        List.of("BOX", "GAUSSIAN"));
    parameter(m, "WARP_SAMPLE", "amplitude", 8, -1e6, 1e6);
    parameter(m, "WARP_SAMPLE", "frequency", .01, 1e-9, 1e6);
    integer(
        m,
        "processing.WARP_SAMPLE.parameter.seedOffset",
        "Processing / WARP_SAMPLE",
        "Seed offset",
        0,
        Integer.MIN_VALUE,
        Integer.MAX_VALUE);
    for (String axis : List.of("x", "y", "z")) parameter(m, "OFFSET_SAMPLE", axis, 0, -1e6, 1e6);
    for (int dimension : new int[] {2, 3})
      for (String transform : TRANSFORMS) {
        String prefix = "transform" + dimension + "D." + transform + ".";
        String group = dimension + "D Transform / " + transform;
        if (transform.equals("TRANSLATE") || transform.equals("SCALE"))
          for (String axis : dimension == 2 ? List.of("x", "z") : List.of("x", "y", "z"))
            number(
                m,
                prefix + axis,
                group,
                axis.toUpperCase(Locale.ROOT),
                transform.equals("SCALE") ? 1 : 0,
                -1e6,
                1e6);
        if (transform.equals("ROTATE")) {
          for (String angle :
              dimension == 2 ? List.of("angle") : List.of("angleX", "angleY", "angleZ"))
            number(m, prefix + angle, group, angle, 0, -360000, 360000);
          if (dimension == 3)
            choice(
                m,
                prefix + "rotationOrder",
                group,
                "Rotation order",
                "XYZ",
                List.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX"));
        }
        if (transform.equals("ROTATE") || transform.equals("SCALE"))
          for (String axis : dimension == 2 ? List.of("X", "Z") : List.of("X", "Y", "Z"))
            number(m, prefix + "pivot" + axis, group, "Pivot " + axis, 0, -1e6, 1e6);
        if (transform.equals("DOMAIN_WARP")) {
          number(m, prefix + "amplitude", group, "Amplitude", 8, -1e6, 1e6);
          number(m, prefix + "frequency", group, "Frequency", .01, 1e-9, 1e6);
          integer(
              m,
              prefix + "seedOffset",
              group,
              "Seed offset",
              0,
              Integer.MIN_VALUE,
              Integer.MAX_VALUE);
        }
      }
    for (String channel : List.of("r", "g", "b"))
      integer(
          m,
          "voxel.color." + channel,
          "Voxel Type",
          "Color " + channel.toUpperCase(Locale.ROOT),
          200,
          0,
          255);
    for (String key : List.of("min", "max", "startValue", "endValue", "displayMin", "displayMax"))
      number(
          m,
          "curve." + key,
          "Curve",
          key,
          key.equals("displayMin")
              ? -.2
              : key.equals("displayMax")
                  ? 1.2
                  : key.equals("max") || key.equals("endValue") ? 1 : 0,
          -1e6,
          1e6);
    choice(
        m,
        "curve.interpolation",
        "Curve",
        "Interpolation",
        "LINEAR",
        List.of("STEP", "LINEAR", "SMOOTH", "BEZIER"));
    choice(
        m,
        "curve.outsideDomain",
        "Curve",
        "Outside domain",
        "CLAMP",
        List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR"));
    CONTROLS = Collections.unmodifiableMap(m);
  }

  private static void parameter(
      Map<String, Spec> m, String op, String key, double def, double min, double max) {
    number(m, "processing." + op + ".parameter." + key, "Processing / " + op, key, def, min, max);
  }

  private static void number(
      Map<String, Spec> m,
      String id,
      String group,
      String label,
      double def,
      double min,
      double max) {
    add(m, id, group, label, Kind.NUMBER, def, min, max, List.of());
  }

  private static void integer(
      Map<String, Spec> m,
      String id,
      String group,
      String label,
      double def,
      double min,
      double max) {
    add(m, id, group, label, Kind.INTEGER, (long) def, min, max, List.of());
  }

  private static void choice(
      Map<String, Spec> m,
      String id,
      String group,
      String label,
      String def,
      List<String> choices) {
    add(m, id, group, label, Kind.CHOICE, def, 0, 0, choices);
  }

  private static void add(
      Map<String, Spec> m,
      String id,
      String group,
      String label,
      Kind type,
      Object def,
      double min,
      double max,
      List<String> options) {
    var value = def instanceof JsonNode node ? node : ProjectIO.JSON.valueToTree(def);
    var spec = new Spec(id, group, label, type, value, min, max, options);
    spec.validate(value);
    if (m.put(id, spec) != null)
      throw new IllegalStateException("Duplicate creation control " + id);
  }

  public static Spec get(String id) {
    var spec = CONTROLS.get(id);
    if (spec == null) throw new IllegalArgumentException("Unknown creation control " + id);
    return spec;
  }

  public static JsonNode value(AppMetadata metadata, String id) {
    var spec = get(id);
    var value = metadata.creationDefaults.get(id);
    return value == null ? spec.builtIn() : value.deepCopy();
  }

  public static Map<String, Object> registry() {
    var controls = new LinkedHashMap<String, JsonNode>();
    for (var entry : CONTROLS.entrySet()) {
      var node =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              ProjectIO.JSON.valueToTree(entry.getValue());
      if (entry.getValue().kind() == Kind.LONG)
        node.put("minimum", Long.MIN_VALUE).put("maximum", Long.MAX_VALUE);
      controls.put(entry.getKey(), node);
    }
    return Map.of(
        "registryVersion",
        1,
        "scope",
        "Editor-created items only; existing definitions, loading, copying, imports and engine"
            + " evaluation do not consult these defaults.",
        "controls",
        controls,
        "rangePolicy",
        "Immutable creation UI ranges; existing project/engine numeric domains are unchanged.");
  }
}
