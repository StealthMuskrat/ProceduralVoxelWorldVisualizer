package io.voxelcreator.core;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;

/** Shared dimensional warp controls; no runtime dependency on editor creation preferences. */
public final class WarpRegistry {
  private WarpRegistry() {}

  public static final List<String> MODES = List.of("INDEPENDENT", "PROGRESSIVE", "SEQUENTIAL");
  public static final List<String> SEEDS = List.of("SAME", "INCREMENT");
  public static final List<String> COMPONENTS =
      List.of("displacementX", "displacementY", "displacementZ");
  public static final String SEMANTICS =
      "Displace real sampling coordinates. INDEPENDENT octaves sample the original position;"
          + " PROGRESSIVE octaves sample the accumulated position; SEQUENTIAL additionally updates"
          + " axes in the selected order. Numeric warpX/Y/Z bindings are stage values;"
          + " displacementX/Y/Z are resampled fields. No normalization or output clamp.";

  public record Control(
      String key,
      String label,
      Object defaultValue,
      double minimum,
      double maximum,
      boolean integer,
      List<String> options,
      boolean numericOptions,
      double presetMinimum,
      double presetMaximum,
      boolean threeDimensional) {
    public Control {
      options = List.copyOf(options);
    }

    public JsonNode value(Item item) {
      var value = item.parameters.getOrDefault(key, ProjectIO.JSON.valueToTree(defaultValue));
      if (!options.isEmpty() && !numericOptions) {
        if (!value.isTextual() || !options.contains(value.textValue()))
          throw new ProcessingExtensions.InvalidControl(key, key + ": choose " + options);
      } else if (!value.isNumber()
          || !Double.isFinite(value.doubleValue())
          || value.doubleValue() < minimum
          || value.doubleValue() > maximum
          || integer && value.doubleValue() != StrictMath.rint(value.doubleValue()))
        throw new ProcessingExtensions.InvalidControl(
            key, key + ": invalid finite " + (integer ? "integer" : "number") + " range");
      return value.deepCopy();
    }
  }

  private static Control number(
      String key,
      String label,
      double value,
      double min,
      double max,
      boolean integer,
      double presetMin,
      double presetMax) {
    return new Control(
        key, label, value, min, max, integer, List.of(), false, presetMin, presetMax, false);
  }

  private static Control choice(String key, String label, String value, List<String> choices) {
    return new Control(key, label, value, 0, 0, false, choices, false, 0, 0, false);
  }

  public static List<Control> controls(int dimension) {
    if (dimension != 2 && dimension != 3) throw new IllegalArgumentException("Warp dimension");
    return dimension == 2 ? BuiltIns.TWO : BuiltIns.THREE;
  }

  private static final class BuiltIns {
    static final List<Control> TWO = buildControls(2), THREE = buildControls(3);
  }

  private static List<Control> buildControls(int dimension) {
    if (dimension != 2 && dimension != 3) throw new IllegalArgumentException("Warp dimension");
    var result = new ArrayList<Control>();
    result.add(choice("warpAlgorithm", "Warp algorithm", "OPEN_SIMPLEX_2", NoiseAlgorithms.KEYS));
    result.add(choice("warpMode", "Warp mode", "INDEPENDENT", MODES));
    result.add(
        choice(
            "warpOrder",
            "Sequential axis order",
            dimension == 2 ? "XZ" : "XYZ",
            orders(dimension)));
    result.add(
        number("amplitude", "Amplitude", 8, -Double.MAX_VALUE, Double.MAX_VALUE, false, -1e6, 1e6));
    result.add(number("frequency", "Frequency", .01, Double.MIN_VALUE, 1e6, false, 1e-9, 1e6));
    result.add(
        number(
            "seedOffset",
            "Seed offset (legacy IEEE bits)",
            0,
            -Double.MAX_VALUE,
            Double.MAX_VALUE,
            false,
            Integer.MIN_VALUE,
            Integer.MAX_VALUE));
    result.add(number("warpOctaves", "Warp octaves", 1, 1, 16, true, 1, 16));
    result.add(
        number(
            "warpLacunarity",
            "Warp lacunarity",
            2,
            Double.MIN_VALUE,
            Double.MAX_VALUE,
            false,
            1e-9,
            8));
    result.add(
        number("warpGain", "Warp gain", .5, -Double.MAX_VALUE, Double.MAX_VALUE, false, -8, 8));
    result.add(choice("octaveSeeds", "Algorithm octave seeds", "SAME", SEEDS));
    var known = new LinkedHashMap<String, NoiseAlgorithmRegistry.Parameter>();
    for (var key : NoiseAlgorithms.KEYS)
      for (var p : NoiseAlgorithmRegistry.parameters(key, dimension)) known.putIfAbsent(p.key(), p);
    for (var p : known.values())
      if (!p.key().equals("frequency"))
        result.add(
            new Control(
                "source." + p.key(),
                "Source " + p.label(),
                p.defaultValue(),
                p.minimum(),
                p.maximum(),
                p.integer(),
                p.choices(),
                !p.choices().isEmpty(),
                p.presetMinimum(),
                p.presetMaximum(),
                p.threeDimensional()));
    return List.copyOf(result);
  }

  public static List<String> orders(int dimension) {
    return dimension == 2 ? List.of("XZ", "ZX") : List.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX");
  }

  public static boolean component(String key) {
    return COMPONENTS.contains(key);
  }

  public static List<Control> visibleControls(Item item, int dimension) {
    Algorithm algorithm;
    try {
      algorithm = algorithm(item, dimension);
    } catch (RuntimeException invalid) {
      return controls(dimension);
    }
    var supported = NoiseAlgorithmRegistry.parameters(algorithm.key, dimension);
    return controls(dimension).stream()
        .filter(
            c -> {
              if (c.key().equals("warpOrder"))
                return text(item, "warpMode", "INDEPENDENT").equals("SEQUENTIAL");
              if (c.key().equals("warpLacunarity")
                  || c.key().equals("warpGain")
                  || c.key().equals("octaveSeeds")) return numeric(item, "warpOctaves", 1) > 1;
              if (c.key().startsWith("source."))
                return supported.stream()
                    .anyMatch(p -> c.key().equals("source." + p.key()) && p.visible(algorithm));
              return true;
            })
        .toList();
  }

  public static Algorithm algorithm(Item item, int dimension) {
    var a = new Algorithm();
    a.key = text(item, "warpAlgorithm", "OPEN_SIMPLEX_2");
    a.parameters.clear();
    a.parameters.put("frequency", numeric(item, "frequency", .01));
    for (var e : item.parameters.entrySet())
      if (e.getKey().startsWith("source.")) {
        if (e.getKey().equals("source.frequency"))
          throw new ProcessingExtensions.InvalidControl(
              e.getKey(), "Use the shared frequency control");
        if (e.getValue() == null
            || !e.getValue().isNumber()
            || !Double.isFinite(e.getValue().doubleValue()))
          throw new ProcessingExtensions.InvalidControl(
              e.getKey(), "Finite source parameter required");
        a.parameters.put(e.getKey().substring(7), e.getValue().doubleValue());
      }
    try {
      NoiseAlgorithmRegistry.validate(a, dimension);
    } catch (NoiseAlgorithmRegistry.Problem failure) {
      throw new ProcessingExtensions.InvalidControl(
          failure.parameter().equals("key")
              ? "warpAlgorithm"
              : failure.parameter().equals("frequency")
                  ? "frequency"
                  : "source." + failure.parameter(),
          failure.getMessage());
    }
    return a;
  }

  static String text(Item item, String key, String fallback) {
    var n = item.parameters.get(key);
    if (n == null) return fallback;
    if (!n.isTextual())
      throw new ProcessingExtensions.InvalidControl(key, "Text choice required: " + key);
    return n.textValue();
  }

  static double numeric(Item item, String key, double fallback) {
    var n = item.parameters.get(key);
    if (n == null) return fallback;
    if (!n.isNumber() || !Double.isFinite(n.doubleValue()))
      throw new ProcessingExtensions.InvalidControl(key, "Finite number required: " + key);
    return n.doubleValue();
  }

  public static Map<String, Object> registry() {
    var m = new LinkedHashMap<String, Object>();
    m.put("registryVersion", 1);
    m.put("operations", List.of("DOMAIN_WARP", "WARP_SAMPLE"));
    m.put("dimensions", List.of(2, 3));
    m.put("controls2D", controls(2));
    m.put("controls3D", controls(3));
    m.put("components", COMPONENTS);
    m.put("coordinateStageSources", List.of("Noise Generator Field"));
    m.put(
        "processingSources",
        List.of("Source Field", "Earlier Field Prefix", "Noise Generator Field"));
    m.put("semantics", SEMANTICS);
    m.put(
        "seedPolicy",
        "mix(noiseSeed + rawDoubleBits(seedOffset)); SAME reuses that algorithm seed; INCREMENT"
            + " uses mix(baseSeed + octave) after octave zero. Referenced fields retain their own"
            + " canonical seeds.");
    return Collections.unmodifiableMap(m);
  }
}
