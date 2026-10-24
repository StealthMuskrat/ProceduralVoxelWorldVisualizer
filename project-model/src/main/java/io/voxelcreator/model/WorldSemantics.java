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
  public static final List<String> PARAMETER_TARGETS =
      List.of("threshold", "baseHeight", "heightScale");
  public static final List<String> COMBINATIONS = List.of("REPLACE", "ADD", "MULTIPLY");

  public static boolean parameterUsed(String interpretation, String destination) {
    return destination.equals("threshold")
        ? interpretation.equals("DENSITY") || interpretation.equals("SELECTION")
        : (destination.equals("baseHeight") || destination.equals("heightScale"))
            && interpretation.startsWith("HEIGHT_");
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
