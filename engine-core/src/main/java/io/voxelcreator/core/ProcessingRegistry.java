package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;

public final class ProcessingRegistry {
  public static final Set<String> SAMPLED =
      Set.of(
          "NOISE_REFERENCE",
          "FBM",
          "RIDGED",
          "BILLOW",
          "PING_PONG",
          "OFFSET_SAMPLE",
          "DERIVATIVE",
          "SMOOTH_FILTER",
          "WARP_SAMPLE",
          "GRADIENT",
          "LAPLACIAN",
          "MIN_FILTER",
          "MAX_FILTER",
          "MEDIAN_FILTER");

  public static boolean isFieldBinding(Binding b) {
    return b != null
        && Set.of("Source Field", "Earlier Field Prefix", "Noise Generator Field")
            .contains(b.sourceKind);
  }

  public record Spec(String category, String result, List<String> inputs) {}

  public static final Map<String, Spec> ITEMS;

  static {
    var m = new LinkedHashMap<String, Spec>();
    add(
        m,
        "Math",
        "Scalar",
        "a,b",
        "ADD",
        "SUBTRACT",
        "MULTIPLY",
        "DIVIDE",
        "POWER",
        "MODULO",
        "MIN",
        "MAX");
    add(
        m, "Math", "Scalar", "a", "ABS", "FLOOR", "CEIL", "ROUND", "SIN", "COS", "TAN", "SQRT",
        "LOG", "EXP", "SIGN");
    add(
        m,
        "Logic & Selection",
        "Boolean",
        "a,b",
        "LESS",
        "LESS_EQUAL",
        "GREATER",
        "GREATER_EQUAL",
        "EQUAL",
        "NOT_EQUAL",
        "AND",
        "OR");
    add(m, "Logic & Selection", "Boolean", "a", "NOT");
    add(m, "Logic & Selection", "Scalar", "condition,true,false", "SELECT");
    add(m, "Noise Generators", "Scalar", "a,b", "BLEND");
    add(m, "Remapping & Curves", "Scalar", "a", "REMAP", "CURVE", "TERRACE", "FALLOFF", "CLAMP");
    add(m, "Vectors", "Vector3", "x,y,z", "COMPOSE3");
    add(m, "Vectors", "Vector2", "x,z", "COMPOSE2");
    add(m, "Vectors", "Scalar", "a,b", "DOT");
    add(m, "Vectors", "Vector3", "a,b", "CROSS");
    add(m, "Vectors", "Scalar", "a", "LENGTH", "COMPONENT");
    add(m, "Vectors", "Vector3", "a", "NORMALIZE", "REPLICATE3");
    add(m, "Vectors", "Scalar", "a", "BOOLEAN_TO_SCALAR");
    add(m, "Vectors", "Boolean", "a", "SCALAR_TO_BOOLEAN");
    add(m, "Random & Utility", "Scalar", "", "CONSTANT", "COORDINATE_HASH", "COORDINATE");
    add(m, "Expression", "Scalar", "", "EXPRESSION");
    add(
        m,
        "Noise Generators",
        "Scalar",
        "",
        "NOISE_REFERENCE",
        "FBM",
        "RIDGED",
        "BILLOW",
        "PING_PONG");
    add(m, "Sampling & Filters", "Scalar", "", "OFFSET_SAMPLE", "DERIVATIVE", "SMOOTH_FILTER");
    add(m, "Shapes & Distance", "Scalar", "", "SPHERE", "BOX");
    add(
        m,
        "Shapes & Distance",
        "Scalar",
        "a,b",
        "UNION",
        "INTERSECTION",
        "SUBTRACTION",
        "SMOOTH_UNION");
    add(m, "Coordinates & Transforms", "Coordinates2D", "x,z", "COORDINATES2");
    add(m, "Coordinates & Transforms", "Coordinates3D", "x,y,z", "COORDINATES3");
    add(m, "Vectors", "Vector2", "a", "REPLICATE2", "PROJECT_VECTOR");
    add(m, "Vectors", "Vector3", "a,b", "EXPAND_VECTOR");
    add(m, "Vectors", "Vector3", "a", "COORDINATES_TO_VECTOR", "VECTOR_ROTATE");
    add(m, "Coordinates & Transforms", "Coordinates3D", "a", "VECTOR_TO_COORDINATES");
    add(m, "Coordinates & Transforms", "Coordinates2D", "a", "PROJECT_COORDINATES");
    add(m, "Coordinates & Transforms", "Coordinates3D", "a,b", "EXPAND_COORDINATES");
    add(m, "Coordinates & Transforms", "Scalar", "", "WARP_SAMPLE");
    add(m, "Sampling & Filters", "Vector3", "", "GRADIENT");
    add(
        m,
        "Sampling & Filters",
        "Scalar",
        "",
        "LAPLACIAN",
        "MIN_FILTER",
        "MAX_FILTER",
        "MEDIAN_FILTER");
    add(
        m,
        "Shapes & Distance",
        "Scalar",
        "",
        "PLANE",
        "CYLINDER",
        "CAPSULE",
        "TORUS",
        "ROUNDED_BOX");
    add(m, "Shapes & Distance", "Scalar", "a,b", "SMOOTH_INTERSECTION", "SMOOTH_SUBTRACTION");
    ITEMS = Collections.unmodifiableMap(m);
  }

  private static void add(Map<String, Spec> m, String c, String t, String inputs, String... keys) {
    for (String k : keys)
      m.put(k, new Spec(c, t, inputs.isEmpty() ? List.of() : List.of(inputs.split(","))));
  }

  /** Compatibility factory uses the three-dimensional default signature. */
  public static Item create(String key, String id) {
    return create(key, id, 3);
  }

  public static Item create(String key, String id, int dimension) {
    var s = ITEMS.get(key);
    if (s == null) throw new IllegalArgumentException(key);
    var item = new Item();
    item.kind = key;
    item.id = id;
    item.resultType = ProcessingExtensions.resultType(key, dimension);
    for (String name : s.inputs) {
      Binding b = name.equals("a") ? Binding.source() : Binding.constant(name.equals("b") ? 1 : 0);
      if (name.equals("condition")
          || key.equals("NOT")
          || key.equals("AND")
          || key.equals("OR")
          || key.equals("BOOLEAN_TO_SCALAR")) {
        b = Binding.constant(0);
        b.type = "Boolean";
        b.value = ProjectIO.JSON.valueToTree(false);
      }
      String vectorType =
          switch (key) {
            case "DOT",
                "CROSS",
                "LENGTH",
                "NORMALIZE",
                "COMPONENT",
                "PROJECT_VECTOR",
                "VECTOR_TO_COORDINATES",
                "VECTOR_ROTATE" ->
                "Vector3";
            case "EXPAND_VECTOR" -> "Vector2";
            case "COORDINATES_TO_VECTOR", "PROJECT_COORDINATES" -> "Coordinates3D";
            case "EXPAND_COORDINATES" -> "Coordinates2D";
            default -> null;
          };
      if (vectorType != null
          && (name.equals("a") || name.equals("b") && !key.startsWith("EXPAND"))) {
        b = Binding.constant(0);
        b.type = vectorType;
        b.value =
            ProjectIO.JSON.valueToTree(
                vectorType.endsWith("2") || vectorType.endsWith("2D")
                    ? new double[] {0, 0}
                    : new double[] {0, 0, 0});
      }
      item.inputs.put(name, b);
    }
    if (key.equals("CURVE")) item.parameters.put("curve", ProjectIO.JSON.valueToTree(new Curve()));
    if (SAMPLED.contains(key)) item.inputs.put("field", Binding.sourceField());
    if (key.equals("EXPRESSION"))
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("source"));
    return item;
  }
}
