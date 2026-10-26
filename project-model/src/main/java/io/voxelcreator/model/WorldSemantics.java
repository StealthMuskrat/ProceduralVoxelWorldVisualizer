package io.voxelcreator.model;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Fixed world-use vocabulary shared by persistence, validation, UI and contract exports. */
public final class WorldSemantics {
  private WorldSemantics() {}

  public static final List<String> INTERPRETATIONS =
      List.of(
          "HEIGHT_BELOW",
          "HEIGHT_ABOVE",
          "HEIGHT_BOUNDARY",
          "SELECTION",
          "DENSITY",
          "TYPE_SELECTION",
          "ALL");
  public static final List<String> AXES = List.of("X", "Y", "Z", "-X", "-Y", "-Z");
  public static final List<String> TRANSFORMS = List.of("TRANSLATE", "SCALE", "ROTATE");
  public static final List<String> ROTATION_ORDERS =
      List.of("XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX");
  public static final List<String> TYPE_SOURCES = List.of("FIXED", "INTERVALS");
  public static final List<String> MAPPINGS = List.of("LINEAR", "SMOOTHSTEP");

  public enum Phase {
    SAMPLING,
    INTERPRETATION,
    ACCEPTANCE
  }

  public enum Parameter {
    THRESHOLD("threshold", Phase.INTERPRETATION, false, false),
    BASE_HEIGHT("baseHeight", Phase.INTERPRETATION, false, false),
    HEIGHT_SCALE("heightScale", Phase.INTERPRETATION, false, false),
    STRENGTH("strength", Phase.ACCEPTANCE, true, false),
    PROBABILITY("probability", Phase.ACCEPTANCE, true, false),
    SCALE_X("scaleX", Phase.SAMPLING, false, true),
    SCALE_Y("scaleY", Phase.SAMPLING, false, true),
    SCALE_Z("scaleZ", Phase.SAMPLING, false, true),
    OFFSET_X("offsetX", Phase.SAMPLING, false, true),
    OFFSET_Y("offsetY", Phase.SAMPLING, false, true),
    OFFSET_Z("offsetZ", Phase.SAMPLING, false, true);
    public final String key;
    public final Phase phase;
    public final boolean unitRange, explicitField;

    Parameter(String key, Phase phase, boolean unitRange, boolean explicitField) {
      this.key = key;
      this.phase = phase;
      this.unitRange = unitRange;
      this.explicitField = explicitField;
    }

    public double value(Settings s) {
      return switch (this) {
        case THRESHOLD -> s.threshold;
        case BASE_HEIGHT -> s.baseHeight;
        case HEIGHT_SCALE -> s.heightScale;
        case STRENGTH -> s.strength;
        case PROBABILITY -> s.probability;
        case SCALE_X -> s.scaleX;
        case SCALE_Y -> s.scaleY;
        case SCALE_Z -> s.scaleZ;
        case OFFSET_X -> s.offsetX;
        case OFFSET_Y -> s.offsetY;
        case OFFSET_Z -> s.offsetZ;
      };
    }
  }

  public static final List<Parameter> PARAMETERS = List.of(Parameter.values());
  public static final List<String> PARAMETER_TARGETS = PARAMETERS.stream().map(p -> p.key).toList();
  public static final List<String> COMBINATIONS = List.of("REPLACE", "ADD", "MULTIPLY");

  public static Parameter parameter(String key) {
    for (var p : PARAMETERS) if (p.key.equals(key)) return p;
    throw new IllegalArgumentException("Unknown parameter destination " + key);
  }

  public static Map<String, Object> parameterRegistry() {
    var result = new LinkedHashMap<String, Object>();
    for (var p : PARAMETERS)
      result.put(
          p.key,
          Map.of(
              "phase", p.phase.name(), "unitRange", p.unitRange, "explicitField", p.explicitField));
    return Collections.unmodifiableMap(result);
  }

  public static boolean parameterUsed(String interpretation, String destination) {
    var parameter = parameter(destination);
    if (parameter.phase != Phase.INTERPRETATION) return true;
    return destination.equals("threshold")
        ? interpretation.equals("DENSITY") || interpretation.equals("SELECTION")
        : interpretation.startsWith("HEIGHT_");
  }

  /** Arbitrary finite output endpoints; convex interpolation is clamped to its finite endpoints. */
  public static double mapNumeric(FieldDriver driver, double value) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite driver sample");
    if (value <= driver.inputMin) return driver.outputMin;
    if (value >= driver.inputMax) return driver.outputMax;
    double span = driver.inputMax - driver.inputMin;
    double t =
        Double.isFinite(span)
            ? (value - driver.inputMin) / span
            : (value * .5 - driver.inputMin * .5) / (driver.inputMax * .5 - driver.inputMin * .5);
    if (driver.mapping.equals("SMOOTHSTEP")) t = t * t * (3 - 2 * t);
    if (driver.outputMin == driver.outputMax) return driver.outputMin;
    double outputSpan = driver.outputMax - driver.outputMin;
    double result =
        Double.isFinite(outputSpan)
            ? driver.outputMin + outputSpan * t
            : driver.outputMin * (1 - t) + driver.outputMax * t;
    return Math.max(
        Math.min(driver.outputMin, driver.outputMax),
        Math.min(Math.max(driver.outputMin, driver.outputMax), result));
  }

  public static double combine(String combination, double current, double mapped) {
    return switch (combination) {
      case "REPLACE" -> mapped;
      case "ADD" -> current + mapped;
      case "MULTIPLY" -> current * mapped;
      default -> throw new IllegalArgumentException("Unknown parameter combination " + combination);
    };
  }

  public static final List<String> GATES =
      List.of("ALWAYS", "COMPARE", "INTERVAL", "PROBABILITY", "NOT", "AND", "OR");

  public static boolean contains(
      double value, double min, double max, boolean includeMin, boolean includeMax) {
    return (value > min || includeMin && value == min)
        && (value < max || includeMax && value == max);
  }

  public static int interval(List<TypeInterval> intervals, double value) {
    for (int i = 0; i < intervals.size(); i++) {
      var range = intervals.get(i);
      if (contains(value, range.min, range.max, range.includeMin, range.includeMax)) return i;
    }
    return -1;
  }

  /**
   * Endpoint clamp is explicit; scaled subtraction avoids overflow for opposite large endpoints.
   */
  public static double map(FieldDriver driver, double value) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite driver sample");
    double t;
    if (value <= driver.inputMin) t = 0;
    else if (value >= driver.inputMax) t = 1;
    else {
      double span = driver.inputMax - driver.inputMin;
      t =
          Double.isFinite(span)
              ? (value - driver.inputMin) / span
              : (value * .5 - driver.inputMin * .5) / (driver.inputMax * .5 - driver.inputMin * .5);
    }
    if (driver.mapping.equals("SMOOTHSTEP")) t = t * t * (3 - 2 * t);
    return driver.outputMin + (driver.outputMax - driver.outputMin) * t;
  }
}
