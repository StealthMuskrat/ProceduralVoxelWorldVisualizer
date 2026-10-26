package io.voxelcreator.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectData.Item;
import io.voxelcreator.model.ProjectIO;
import java.util.*;

/** Canonical typed controls for the sampled calculus and distance-shape operations. */
public final class ProcessingExtensions {
  private ProcessingExtensions() {}

  static final class InvalidControl extends IllegalArgumentException {
    final String key;

    InvalidControl(String key, String message) {
      super(message);
      this.key = key;
    }
  }

  public record Control(
      String key,
      String label,
      Object defaultValue,
      double minimum,
      double maximum,
      boolean integer,
      List<String> options,
      boolean threeDimensional) {
    public Control {
      options = List.copyOf(options);
    }

    public JsonNode value(Item item) {
      var value = item.parameters.getOrDefault(key, ProjectIO.JSON.valueToTree(defaultValue));
      if (defaultValue instanceof Boolean) {
        if (!value.isBoolean()) throw new InvalidControl(key, key + ": expected Boolean");
      } else if (!options.isEmpty()) {
        if (!value.isTextual() || !options.contains(value.textValue()))
          throw new InvalidControl(key, key + ": choose " + options);
      } else if (!value.isNumber()
          || !Double.isFinite(value.doubleValue())
          || value.doubleValue() < minimum
          || value.doubleValue() > maximum
          || integer && value.doubleValue() != StrictMath.rint(value.doubleValue()))
        throw new InvalidControl(
            key,
            key
                + ": expected "
                + (integer ? "integer" : "finite number")
                + " in ["
                + minimum
                + ", "
                + maximum
                + "]");
      return value.deepCopy();
    }
  }

  public record Detail(
      List<Integer> dimensions,
      String result2D,
      String result3D,
      List<Control> controls,
      String description) {
    public Detail {
      dimensions = List.copyOf(dimensions);
      controls = List.copyOf(controls);
    }
  }

  public static final Set<String> CALCULUS =
      Set.of("GRADIENT", "LAPLACIAN", "MIN_FILTER", "MAX_FILTER", "MEDIAN_FILTER");
  public static final Set<String> SHAPES =
      Set.of("PLANE", "CYLINDER", "CAPSULE", "TORUS", "ROUNDED_BOX");
  public static final Set<String> COMBINATIONS =
      Set.of("SMOOTH_INTERSECTION", "SMOOTH_SUBTRACTION");
  public static final Map<String, Detail> DETAILS;

  static {
    var m = new LinkedHashMap<String, Detail>();
    var spacing = number("spacing", "Sample spacing", 1, Double.MIN_VALUE, Double.MAX_VALUE);
    put(
        m,
        "GRADIENT",
        false,
        "Vector2",
        "Vector3",
        List.of(spacing),
        "Centered first derivatives in X/Z (2D) or X/Y/Z (3D); unnormalized value vector.");
    put(
        m,
        "LAPLACIAN",
        false,
        "Scalar",
        "Scalar",
        List.of(spacing),
        "Sum of centered second derivatives on active axes; no diagonal samples.");
    for (String key : List.of("MIN_FILTER", "MAX_FILTER", "MEDIAN_FILTER"))
      put(
          m,
          key,
          false,
          "Scalar",
          "Scalar",
          List.of(
              spacing,
              new Control("radius", "Neighborhood radius", 1, 0, 4, true, List.of(), false),
              number("strength", "Filter strength", 1, 0, 1)),
          "Odd square/cube neighborhood in Y/Z/X order, radius 0..4; blend with the center; raw"
              + " signed values.");
    put(
        m,
        "PLANE",
        false,
        "Scalar",
        "Scalar",
        List.of(
            axis("x"),
            number("offset", "Plane offset", 0, -Double.MAX_VALUE, Double.MAX_VALUE),
            new Control(
                "direction",
                "Positive normal",
                "POSITIVE",
                0,
                0,
                false,
                List.of("POSITIVE", "NEGATIVE"),
                false)),
        "Axis-aligned signed distance: direction * (position[axis] - offset); negative is inside.");
    var capsule = new ArrayList<Control>();
    for (String endpoint : List.of("start", "end"))
      for (String axis : List.of("X", "Y", "Z"))
        capsule.add(
            coordinate(
                endpoint + axis,
                endpoint + " " + axis,
                axis.equals("X") ? endpoint.equals("start") ? -16 : 16 : 0,
                axis.equals("Y")));
    capsule.add(number("radius", "Radius", 4, 0, Double.MAX_VALUE));
    put(
        m,
        "CAPSULE",
        false,
        "Scalar",
        "Scalar",
        capsule,
        "Distance to the closed endpoint segment minus radius; coincident endpoints form a"
            + " sphere/circle.");
    var cylinder = centers();
    cylinder.add(axis("y"));
    cylinder.add(number("radius", "Radius", 16, 0, Double.MAX_VALUE));
    cylinder.add(number("halfHeight", "Half height", 16, 0, Double.MAX_VALUE));
    put(
        m,
        "CYLINDER",
        true,
        "Scalar",
        "Scalar",
        cylinder,
        "Exact capped cylinder signed distance about the selected axis; negative is inside.");
    var torus = centers();
    torus.add(axis("y"));
    torus.add(number("majorRadius", "Major radius", 16, 0, Double.MAX_VALUE));
    torus.add(number("minorRadius", "Minor radius", 4, 0, Double.MAX_VALUE));
    put(
        m,
        "TORUS",
        true,
        "Scalar",
        "Scalar",
        torus,
        "Ring torus signed distance about the selected axis; minor radius cannot exceed major"
            + " radius.");
    var box = centers();
    for (String axis : List.of("X", "Y", "Z"))
      box.add(
          new Control(
              "extent" + axis,
              "Outer half extent " + axis,
              16,
              0,
              Double.MAX_VALUE,
              false,
              List.of(),
              axis.equals("Y")));
    box.add(number("roundRadius", "Corner radius", 2, 0, Double.MAX_VALUE));
    put(
        m,
        "ROUNDED_BOX",
        false,
        "Scalar",
        "Scalar",
        box,
        "Exact rounded box with fixed outer half extents; corner radius cannot exceed any active"
            + " extent.");
    for (String key : List.of("SMOOTH_INTERSECTION", "SMOOTH_SUBTRACTION"))
      put(
          m,
          key,
          false,
          "Scalar",
          "Scalar",
          List.of(number("smoothness", "Smoothness", 1, Double.MIN_VALUE, Double.MAX_VALUE)),
          "Polynomial smooth maximum: intersection=max(a,b), subtraction=max(a,-b); raw signed"
              + " values.");
    put(
        m,
        "REMAP",
        false,
        "Scalar",
        "Scalar",
        List.of(
            number("inputMin", "Input minimum", -1, -Double.MAX_VALUE, Double.MAX_VALUE),
            number("inputMax", "Input maximum", 1, -Double.MAX_VALUE, Double.MAX_VALUE),
            number("outputMin", "Output minimum", 0, -Double.MAX_VALUE, Double.MAX_VALUE),
            number("outputMax", "Output maximum", 1, -Double.MAX_VALUE, Double.MAX_VALUE),
            bool("clamp", "Clamp output", false)),
        "Affine mapping with distinct input endpoints; descending ranges are supported. Optional"
            + " output clamp uses sorted output bounds.");
    put(
        m,
        "CLAMP",
        false,
        "Scalar",
        "Scalar",
        List.of(
            number("min", "Minimum", 0, -Double.MAX_VALUE, Double.MAX_VALUE),
            number("max", "Maximum", 1, -Double.MAX_VALUE, Double.MAX_VALUE)),
        "Inclusive clamp with ordered minimum and maximum; no automatic endpoint swapping.");
    put(
        m,
        "TERRACE",
        false,
        "Scalar",
        "Scalar",
        List.of(
            new Control(
                "levels", "Levels per unit", 8, 1, Double.MAX_VALUE, true, List.of(), false),
            number("transition", "Transition fraction", 0, 0, 1)),
        "Uniform steps floor(value * levels) / levels, including signed values. Smooth transition"
            + " occupies the final fraction of each interval; exact knots start the next interval."
            + " No output clamp.");
    var falloff = centers();
    falloff.add(0, choice("domain", "Distance domain", "COORDINATES", "COORDINATES", "INPUT"));
    falloff.add(
        1,
        choice(
            "metric",
            "Distance metric",
            "EUCLIDEAN",
            "EUCLIDEAN",
            "CHEBYSHEV",
            "MANHATTAN",
            "AXIS"));
    falloff.add(2, axis("x"));
    falloff.add(number("extent", "Extent", 64, Double.MIN_VALUE, Double.MAX_VALUE));
    falloff.add(
        choice(
            "shape", "Falloff shape", "LINEAR", "LINEAR", "SMOOTHSTEP", "SMOOTHERSTEP", "COSINE"));
    falloff.add(choice("outside", "Outside extent", "CLAMP", "CLAMP", "EXTEND"));
    put(
        m,
        "FALLOFF",
        false,
        "Scalar",
        "Scalar",
        falloff,
        "Multiply the signed input by 1 - shape(distance / extent). Coordinates use transformed"
            + " space or an explicit typed position. INPUT uses abs(input - centerX). CLAMP holds"
            + " distance ratio in [0,1]; EXTEND extrapolates the selected shape. No output"
            + " normalization.");
    put(
        m,
        "BLEND",
        false,
        "Scalar",
        "Scalar",
        List.of(
            choice(
                "mode",
                "Combination mode",
                "LERP",
                "LERP",
                "ADD",
                "SUBTRACT",
                "MULTIPLY",
                "DIVIDE",
                "MIN",
                "MAX"),
            number("weight", "Blend weight", .5, -Double.MAX_VALUE, Double.MAX_VALUE),
            choice("weightPolicy", "Weight policy", "RAW", "RAW", "CLAMP"),
            choice("outputPolicy", "Output policy", "RAW", "RAW", "CLAMP_01")),
        "LERP blends a toward b. Other modes blend a toward the named combination of a and b."
            + " Optional Scalar weight binding overrides the numeric weight. RAW extrapolates and"
            + " preserves signed values; explicit policies clamp weight or output. Zero/one weights"
            + " select endpoints without evaluating unused branches.");
    DETAILS = Collections.unmodifiableMap(m);
  }

  private static Control bool(String key, String label, boolean value) {
    return new Control(key, label, value, 0, 0, false, List.of(), false);
  }

  private static Control choice(String key, String label, String value, String... options) {
    return new Control(key, label, value, 0, 0, false, List.of(options), false);
  }

  public static final Set<String> REMAPPING =
      Set.of("REMAP", "CLAMP", "TERRACE", "FALLOFF", "BLEND");

  /** Presentation only; inactive settings are retained and validated, never rewritten on draw. */
  public static boolean visible(Item item, Control control, int dimension) {
    if (dimension == 2 && control.threeDimensional()) return false;
    if (!item.kind.equals("FALLOFF")) return true;
    String domain =
        item.parameters.getOrDefault("domain", ProjectIO.JSON.valueToTree("COORDINATES")).asText();
    String metric =
        item.parameters.getOrDefault("metric", ProjectIO.JSON.valueToTree("EUCLIDEAN")).asText();
    if (domain.equals("INPUT"))
      return !Set.of("metric", "axis", "centerY", "centerZ").contains(control.key());
    return !control.key().equals("axis") || metric.equals("AXIS");
  }

  private static Control number(String key, String label, double value, double min, double max) {
    return new Control(key, label, value, min, max, false, List.of(), false);
  }

  private static Control coordinate(String key, String label, double value, boolean y) {
    return new Control(key, label, value, -Double.MAX_VALUE, Double.MAX_VALUE, false, List.of(), y);
  }

  private static Control axis(String value) {
    return new Control("axis", "Axis", value, 0, 0, false, List.of("x", "y", "z"), false);
  }

  private static ArrayList<Control> centers() {
    var result = new ArrayList<Control>();
    for (String axis : List.of("X", "Y", "Z"))
      result.add(coordinate("center" + axis, "Center " + axis, 0, axis.equals("Y")));
    return result;
  }

  private static void put(
      Map<String, Detail> m,
      String key,
      boolean only3,
      String r2,
      String r3,
      List<Control> controls,
      String description) {
    m.put(key, new Detail(only3 ? List.of(3) : List.of(2, 3), r2, r3, controls, description));
  }

  public static boolean supports(String key, int dimension) {
    if (dimension != 2 && dimension != 3) return false;
    var detail = DETAILS.get(key);
    return detail == null || detail.dimensions.contains(dimension);
  }

  public static String resultType(String key, int dimension) {
    if (!supports(key, dimension))
      throw new IllegalArgumentException(key + ": unsupported dimension " + dimension);
    var detail = DETAILS.get(key);
    return detail == null
        ? ProcessingRegistry.ITEMS.get(key).result()
        : dimension == 2 ? detail.result2D : detail.result3D;
  }

  static Map<String, JsonNode> controls(Item item, int dimension) {
    if (!supports(item.kind, dimension))
      throw new IllegalArgumentException(item.kind + ": requires 3D coordinates");
    var values = new LinkedHashMap<String, JsonNode>();
    for (var control : DETAILS.get(item.kind).controls)
      if (dimension == 3 || !control.threeDimensional) values.put(control.key, control.value(item));
    if (dimension == 2 && values.containsKey("axis") && values.get("axis").asText().equals("y"))
      throw new InvalidControl("axis", "axis: 2D fields use X/Z");
    return Map.copyOf(values);
  }
}
