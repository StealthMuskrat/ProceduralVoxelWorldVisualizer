package io.voxelcreator.editor;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/**
 * Known top-level scale targets; older/extension map keys remain owned by their original writers.
 */
public final class UiScalePreferences {
  public static final float MINIMUM = .5f, MAXIMUM = 2.5f;
  public static final List<String> WINDOWS =
      List.of(
          "Resources",
          "Properties",
          "Visualizers",
          "Find",
          "Logs",
          "Validation",
          "Help",
          "Notifications",
          "Profiler",
          "Themes",
          "User Settings",
          "Project Settings",
          "Application Settings",
          "Debug & Profiling",
          "About",
          "Check for Updates",
          "Voxel Manager",
          "Voxel Inspector",
          "Curve Editor");

  public static Map<String, Object> registry() {
    var result = new LinkedHashMap<String, Object>();
    result.put("registryVersion", 2);
    result.put("minimum", MINIMUM);
    result.put("maximum", MAXIMUM);
    result.put("windows", WINDOWS);
    result.put("composition", "global scale multiplied by window multiplier");
    result.put(
        "inheritance", "absent window entry uses multiplier 1; unknown entries are retained");
    result.put("runtimeDpiComposition", "monitor content scale divided by framebuffer density");
    result.put("minimumContentScale", UiDpiPolicy.MINIMUM);
    result.put("maximumContentScale", UiDpiPolicy.MAXIMUM);
    result.put("fontRasterDensity", UiFontAtlas.RASTER_DENSITY);
    return Collections.unmodifiableMap(result);
  }

  private UiScalePreferences() {}

  public record Values(float global, Map<String, Float> overrides) {
    public Values {
      validateScale(global);
      Objects.requireNonNull(overrides);
      if (!WINDOWS.containsAll(overrides.keySet()))
        throw new IllegalArgumentException("Unknown owned window scale target");
      var copy = new LinkedHashMap<String, Float>();
      for (var name : WINDOWS)
        if (overrides.containsKey(name)) {
          float value = Objects.requireNonNull(overrides.get(name));
          validateScale(value);
          copy.put(name, value);
        }
      overrides = Collections.unmodifiableMap(copy);
    }

    void writeTo(UserConfig.Appearance appearance) {
      var merged = new LinkedHashMap<>(appearance.windowScales);
      WINDOWS.forEach(merged::remove);
      merged.putAll(overrides);
      appearance.globalScale = global;
      appearance.windowScales = merged;
    }
  }

  public static Values from(UserConfig user) {
    var appearance = Objects.requireNonNull(user.appearance);
    var owned = new LinkedHashMap<String, Float>();
    for (var name : WINDOWS)
      if (appearance.windowScales.containsKey(name))
        owned.put(name, appearance.windowScales.get(name));
    return new Values(appearance.globalScale, owned);
  }

  static void validateScale(float value) {
    if (!Float.isFinite(value) || value < MINIMUM || value > MAXIMUM)
      throw new IllegalArgumentException("Scale must be a finite value from 0.5 to 2.5");
  }

  static void validate(UserConfig.Appearance appearance) {
    validateScale(appearance.globalScale);
    Objects.requireNonNull(appearance.windowScales);
    appearance.windowScales.forEach(
        (name, value) -> {
          if (name == null || value == null)
            throw new IllegalArgumentException("Missing window scale name/value");
          validateScale(value);
        });
  }

  static float window(UserConfig user, String name) {
    if (!WINDOWS.contains(name)) throw new IllegalArgumentException("Unknown window scale target");
    if (user == null) return 1; // Standalone curve editor has no preference owner.
    float value = user.appearance.windowScales.getOrDefault(name, 1f);
    validateScale(value);
    return value;
  }

  static void validateStored(JsonNode tree) {
    var appearance = tree.path("appearance");
    if (appearance.has("globalScale")) number(appearance.get("globalScale"));
    if (appearance.has("windowScales")) {
      var map = appearance.get("windowScales");
      if (!map.isObject()) throw new IllegalArgumentException("Invalid window scale map");
      map.forEach(UiScalePreferences::number);
    }
  }

  private static void number(JsonNode value) {
    if (!value.isNumber()
        || !Double.isFinite(value.doubleValue())
        || value.doubleValue() < MINIMUM
        || value.doubleValue() > MAXIMUM)
      throw new IllegalArgumentException("Stored scale must be a number from 0.5 to 2.5");
  }
}
