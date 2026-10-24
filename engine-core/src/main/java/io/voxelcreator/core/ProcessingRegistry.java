package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;

public final class ProcessingRegistry {
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
    ITEMS = Collections.unmodifiableMap(m);
  }

  private static void add(Map<String, Spec> m, String c, String t, String inputs, String... keys) {
    for (String k : keys)
      m.put(k, new Spec(c, t, inputs.isEmpty() ? List.of() : List.of(inputs.split(","))));
  }

  public static Item create(String key, String id) {
    var s = ITEMS.get(key);
    if (s == null) throw new IllegalArgumentException(key);
    var item = new Item();
    item.kind = key;
    item.id = id;
    item.resultType = s.result;
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
      item.inputs.put(name, b);
    }
    if (key.equals("EXPRESSION"))
      item.parameters.put("expression", ProjectIO.JSON.valueToTree("source"));
    return item;
  }
}
