package io.voxelcreator.model;

import java.util.*;

/** Canonical serialized curve vocabulary shared by evaluation, schemas and authoring. */
public final class CurveModes {
  private CurveModes() {}

  public static final List<String> INTERPOLATIONS = List.of("STEP", "LINEAR", "SMOOTH", "BEZIER");
  public static final List<String> OUTSIDE = List.of("CLAMP", "EXTEND", "REPEAT", "MIRROR");
  public static final List<String> HANDLES = List.of("AUTO", "ALIGNED", "FREE");
  private static final Map<String, String> HELP =
      Map.ofEntries(
          Map.entry("STEP", "Use the left point between knots; an exact knot uses its own value."),
          Map.entry("LINEAR", "Connect neighboring points with a straight line."),
          Map.entry("SMOOTH", "Use a shape-preserving monotone cubic between neighboring points."),
          Map.entry("BEZIER", "Use endpoint slopes and handles one third of the segment width."),
          Map.entry("CLAMP", "Outside the domain, use the nearest endpoint value."),
          Map.entry(
              "EXTEND", "Continue the first or last segment's endpoint slope outside the domain."),
          Map.entry(
              "REPEAT",
              "Repeat beyond the domain; the configured maximum keeps its exact endpoint value."),
          Map.entry("MIRROR", "Reflect the curve on alternating domain intervals."),
          Map.entry(
              "AUTO", "Follow the curve's monotone slopes; handles cannot be dragged directly."),
          Map.entry(
              "ALIGNED", "Keep incoming and outgoing slopes equal when either handle is edited."),
          Map.entry("FREE", "Edit incoming and outgoing slopes independently."));

  public static String help(String key) {
    return HELP.getOrDefault(key, "Unknown curve mode");
  }

  private static List<Map<String, String>> choices(List<String> keys) {
    return keys.stream().map(key -> Map.of("key", key, "help", help(key))).toList();
  }

  public static Map<String, Object> registry() {
    return Map.of(
        "formatVersion",
        1,
        "projectFormat",
        2,
        "interpolation",
        choices(INTERPOLATIONS),
        "outside",
        choices(OUTSIDE),
        "handles",
        choices(HANDLES),
        "knotPolicy",
        "Numeric equality, including signed zero; exact knots return stored values");
  }
}
