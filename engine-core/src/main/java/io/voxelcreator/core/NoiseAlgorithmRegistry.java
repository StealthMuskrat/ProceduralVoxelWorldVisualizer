package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Algorithm;
import java.util.*;

/**
 * Stable source signatures. Preset ranges constrain future creation; engine ranges govern saved
 * data.
 */
public final class NoiseAlgorithmRegistry {
  private NoiseAlgorithmRegistry() {}

  public record Parameter(
      String key,
      String label,
      double defaultValue,
      double minimum,
      double maximum,
      boolean integer,
      List<String> choices,
      double presetMinimum,
      double presetMaximum,
      boolean threeDimensional,
      String visibility,
      String help) {
    public Parameter {
      choices = List.copyOf(choices);
    }

    public void validate(double value) {
      if (!Double.isFinite(value)
          || value < minimum
          || value > maximum
          || integer && value != StrictMath.rint(value))
        throw new Problem(
            key,
            "Expected "
                + (integer ? "integer" : "finite number")
                + " in ["
                + minimum
                + ", "
                + maximum
                + "] for "
                + key);
    }

    public boolean visible(Algorithm algorithm) {
      double fractal = algorithm.parameters.getOrDefault("fractal", 0.);
      return visibility.equals("ALWAYS")
          || visibility.equals("FRACTAL") && fractal != 0
          || visibility.equals("PING_PONG") && fractal == 3;
    }
  }

  public record Spec(
      String key,
      String family,
      List<Integer> dimensions,
      List<Parameter> parameters,
      String semantics) {
    public Spec {
      dimensions = List.copyOf(dimensions);
      parameters = List.copyOf(parameters);
    }
  }

  public static final class Problem extends IllegalArgumentException {
    private final String parameter;

    public Problem(String parameter, String message) {
      super(message);
      this.parameter = parameter;
    }

    public String parameter() {
      return parameter;
    }
  }

  private static final double FMAX = Float.MAX_VALUE;
  public static final List<String> FRACTALS = List.of("NONE", "FBM", "RIDGED", "PING_PONG");
  public static final List<String> DISTANCES =
      List.of("EUCLIDEAN", "EUCLIDEAN_SQUARED", "MANHATTAN", "HYBRID");
  public static final List<String> RETURNS =
      List.of(
          "CELL_VALUE",
          "DISTANCE",
          "DISTANCE_2",
          "DISTANCE_2_ADD",
          "DISTANCE_2_SUBTRACT",
          "DISTANCE_2_MULTIPLY",
          "DISTANCE_2_DIVIDE");
  public static final Map<String, Spec> ITEMS;
  public static final List<String> KEYS;
  private static final Map<String, Parameter> KNOWN;

  static {
    var all = new LinkedHashMap<String, Parameter>();
    var frequency =
        parameter(
            "frequency",
            "Frequency",
            .01,
            Double.MIN_VALUE,
            1e6,
            false,
            List.of(),
            1e-9,
            1e6,
            false,
            "ALWAYS",
            "Positive frequency; FastNoiseLite receives its float narrowing.");
    var fractal =
        parameter(
            "fractal",
            "Fractal mode",
            0,
            0,
            3,
            true,
            FRACTALS,
            0,
            3,
            false,
            "ALWAYS",
            "Stable numeric index: NONE, FBM, RIDGED, PING_PONG.");
    var octaves =
        parameter(
            "octaves",
            "Octaves",
            3,
            1,
            16,
            true,
            List.of(),
            1,
            16,
            false,
            "FRACTAL",
            "Enabled source fractal octave count.");
    var lacunarity =
        parameter(
            "lacunarity",
            "Lacunarity",
            2,
            -FMAX,
            FMAX,
            false,
            List.of(),
            1,
            8,
            false,
            "FRACTAL",
            "Signed finite float-compatible frequency multiplier; no implicit clamp.");
    var gain =
        parameter(
            "gain",
            "Gain",
            .5,
            -FMAX,
            FMAX,
            false,
            List.of(),
            0,
            1,
            false,
            "FRACTAL",
            "Signed finite float-compatible amplitude multiplier; no implicit clamp.");
    var ping =
        parameter(
            "pingPongStrength",
            "Ping-pong strength",
            2,
            -FMAX,
            FMAX,
            false,
            List.of(),
            0,
            8,
            false,
            "PING_PONG",
            "Used by source ping-pong fractals only.");
    var distance =
        parameter(
            "cellularDistance",
            "Distance metric",
            1,
            0,
            3,
            true,
            DISTANCES,
            0,
            3,
            false,
            "ALWAYS",
            "Stable FastNoiseLite distance index.");
    var returns =
        parameter(
            "cellularReturn",
            "Return mode",
            1,
            0,
            6,
            true,
            RETURNS,
            0,
            6,
            false,
            "ALWAYS",
            "Stable FastNoiseLite return index; output is not implicitly normalized.");
    var jitter =
        parameter(
            "jitter",
            "Cellular jitter",
            1,
            -FMAX,
            FMAX,
            false,
            List.of(),
            0,
            1,
            false,
            "ALWAYS",
            "Signed finite float-compatible jitter; preset range is 0..1.");
    var value =
        parameter(
            "value",
            "Constant value",
            0,
            -Double.MAX_VALUE,
            Double.MAX_VALUE,
            false,
            List.of(),
            -1e6,
            1e6,
            false,
            "ALWAYS",
            "Raw signed double.");
    var radius =
        parameter(
            "radius",
            "Radius",
            16,
            0,
            Double.MAX_VALUE,
            false,
            List.of(),
            0,
            1e6,
            false,
            "ALWAYS",
            "Nonnegative sphere/circle radius; negative means inside the resulting SDF.");
    var extents = new ArrayList<Parameter>();
    for (String axis : List.of("X", "Y", "Z"))
      extents.add(
          parameter(
              "extent" + axis,
              "Half extent " + axis,
              16,
              0,
              Double.MAX_VALUE,
              false,
              List.of(),
              0,
              1e6,
              axis.equals("Y"),
              "ALWAYS",
              "Nonnegative outer half extent in definition coordinates."));
    for (var item :
        List.of(
            frequency,
            fractal,
            octaves,
            lacunarity,
            gain,
            ping,
            distance,
            returns,
            jitter,
            value,
            radius)) all.put(item.key, item);
    for (var item : extents) all.put(item.key, item);
    KNOWN = Collections.unmodifiableMap(all);
    var m = new LinkedHashMap<String, Spec>();
    for (String key : List.of("OPEN_SIMPLEX_2", "PERLIN", "VALUE", "VALUE_CUBIC", "CELLULAR")) {
      var controls =
          new ArrayList<Parameter>(List.of(frequency, fractal, octaves, lacunarity, gain, ping));
      if (key.equals("CELLULAR")) controls.addAll(List.of(distance, returns, jitter));
      m.put(
          key,
          new Spec(
              key,
              "FastNoiseLite",
              List.of(2, 3),
              controls,
              "FastNoiseLite 1.1.1; marked coordinates use double, internal noise output is float"
                  + " widened to double; seed folds high/low 32 bits."));
    }
    m.put(
        "WHITE_HASH",
        new Spec(
            "WHITE_HASH",
            "Coordinate hash",
            List.of(2, 3),
            List.of(frequency),
            "SplitMix64 over scaled coordinate double bits; raw [-1,1). No source fractal"
                + " controls."));
    m.put(
        "CONSTANT",
        new Spec(
            "CONSTANT",
            "Analytic",
            List.of(2, 3),
            List.of(value),
            "Raw constant double, including signed zero."));
    m.put(
        "GRADIENT_Y",
        new Spec(
            "GRADIENT_Y",
            "Analytic",
            List.of(2, 3),
            List.of(),
            "Identity Z in 2D, identity Y in 3D; raw unbounded double."));
    m.put(
        "SPHERE_SDF",
        new Spec(
            "SPHERE_SDF",
            "Signed distance",
            List.of(2, 3),
            List.of(radius),
            "Euclidean length minus radius; negative inside. Existing arithmetic order is"
                + " preserved."));
    m.put(
        "BOX_SDF",
        new Spec(
            "BOX_SDF",
            "Signed distance",
            List.of(2, 3),
            extents,
            "Axis-aligned exact box SDF with half extents; negative inside; Y ignored in 2D."));
    ITEMS = Collections.unmodifiableMap(m);
    KEYS = List.copyOf(m.keySet());
  }

  private static Parameter parameter(
      String key,
      String label,
      double value,
      double min,
      double max,
      boolean integer,
      List<String> choices,
      double pmin,
      double pmax,
      boolean y,
      String visibility,
      String help) {
    return new Parameter(
        key, label, value, min, max, integer, choices, pmin, pmax, y, visibility, help);
  }

  public static Spec get(String key) {
    var spec = ITEMS.get(key);
    if (spec == null) throw new Problem("key", "Unknown algorithm " + key);
    return spec;
  }

  public static List<Parameter> parameters(String key, int dimension) {
    var spec = get(key);
    if (!spec.dimensions.contains(dimension))
      throw new Problem("dimension", "Algorithm dimension must be 2 or 3");
    return spec.parameters.stream().filter(p -> dimension == 3 || !p.threeDimensional).toList();
  }

  public static Parameter parameter(String key) {
    var value = KNOWN.get(key);
    if (value == null) throw new Problem(key, "Unknown source parameter " + key);
    return value;
  }

  public static void validate(Algorithm algorithm, int dimension) {
    if (algorithm == null || algorithm.parameters == null)
      throw new Problem("parameters", "Algorithm parameters are required");
    var supported = parameters(algorithm.key, dimension);
    for (var entry : algorithm.parameters.entrySet()) {
      var known = KNOWN.get(entry.getKey());
      if (known == null)
        throw new Problem(entry.getKey(), "Unsupported algorithm parameter " + entry.getKey());
      var value = entry.getValue();
      if (value == null
          || !Double.isFinite(value)
          || known.integer && value != StrictMath.rint(value))
        throw new Problem(
            entry.getKey(),
            "Finite "
                + (known.integer ? "integer " : "")
                + "algorithm parameter required: "
                + entry.getKey());
    }
    // Historical Algorithm defaults contain frequency even on analytic sources.
    if (algorithm.parameters.containsKey("frequency"))
      KNOWN.get("frequency").validate(algorithm.parameters.get("frequency"));
    if (get(algorithm.key).family.equals("FastNoiseLite"))
      for (String key : List.of("cellularDistance", "cellularReturn"))
        if (algorithm.parameters.containsKey(key))
          KNOWN.get(key).validate(algorithm.parameters.get(key));
    for (var control : supported)
      control.validate(algorithm.parameters.getOrDefault(control.key, control.defaultValue));
  }

  public static Map<String, Object> registry() {
    return Map.of(
        "registryVersion",
        2,
        "engineVersion",
        "0.1.0",
        "algorithms",
        KEYS,
        "specifications",
        ITEMS,
        "inactiveParameterPolicy",
        "Known finite typed inactive parameters are retained and ignored. Frequency retains its"
            + " historical global validity check; FastNoiseLite cellular indices retain their"
            + " historical bounds on all upstream families. Unknown keys reject.",
        "normalization",
        "Raw signed values; source fractals follow FastNoiseLite bounding. No implicit 0..1"
            + " mapping.");
  }
}
