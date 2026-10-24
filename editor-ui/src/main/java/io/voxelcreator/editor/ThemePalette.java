package io.voxelcreator.editor;

import imgui.flag.ImGuiCol;
import java.util.*;

/** Immutable theme-only preference value; arrays never cross its ownership boundary. */
public final class ThemePalette {
  public record Rgba(float r, float g, float b, float a) {
    public Rgba {
      for (float v : new float[] {r, g, b, a})
        if (!Float.isFinite(v) || v < 0 || v > 1)
          throw new IllegalArgumentException("Theme components must be finite in [0,1]");
    }

    public static Rgba of(float[] c) {
      if (c == null || c.length != 4)
        throw new IllegalArgumentException("Expected four RGBA components");
      return new Rgba(c[0], c[1], c[2], c[3]);
    }

    public float[] array() {
      return new float[] {r, g, b, a};
    }
  }

  public static final List<String> ROLES = List.of("Information", "Warning", "Error", "Success");
  private final String base;
  private final Map<Integer, Rgba> colors;
  private final Map<String, Rgba> statuses;

  public ThemePalette(String base, Map<Integer, Rgba> colors, Map<String, Rgba> statuses) {
    if (!List.of("Dark", "Light").contains(base))
      throw new IllegalArgumentException("Unknown starting appearance");
    Objects.requireNonNull(colors);
    Objects.requireNonNull(statuses);
    for (var e : colors.entrySet())
      if (e.getKey() == null
          || e.getKey() < 0
          || e.getKey() >= ImGuiCol.COUNT
          || e.getValue() == null) throw new IllegalArgumentException("Unknown theme color");
    for (var e : statuses.entrySet())
      if (!ROLES.contains(e.getKey()) || e.getValue() == null)
        throw new IllegalArgumentException("Unknown theme status");
    this.base = base;
    this.colors = Collections.unmodifiableMap(new TreeMap<>(colors));
    this.statuses = Collections.unmodifiableMap(new TreeMap<>(statuses));
  }

  public static ThemePalette from(UserConfig.Appearance a) {
    var colors = new TreeMap<Integer, Rgba>();
    var statuses = new TreeMap<String, Rgba>();
    Objects.requireNonNull(a.colors).forEach((key, value) -> colors.put(key, Rgba.of(value)));
    Objects.requireNonNull(a.statusColors)
        .forEach((key, value) -> statuses.put(key, Rgba.of(value)));
    return new ThemePalette(a.theme, colors, statuses);
  }

  public static ThemePalette starting(String base) {
    return new ThemePalette(base, Map.of(), Map.of());
  }

  public String base() {
    return base;
  }

  public Map<Integer, Rgba> colors() {
    return colors;
  }

  public Map<String, Rgba> statuses() {
    return statuses;
  }

  public ThemePalette color(int key, Rgba color) {
    var copy = new TreeMap<>(colors);
    if (color == null) copy.remove(key);
    else copy.put(key, color);
    return new ThemePalette(base, copy, statuses);
  }

  public ThemePalette status(String key, Rgba color) {
    if (!ROLES.contains(key)) throw new IllegalArgumentException("Unknown theme status");
    var copy = new TreeMap<>(statuses);
    if (color == null) copy.remove(key);
    else copy.put(key, color);
    return new ThemePalette(base, colors, copy);
  }

  public Rgba status(String key) {
    if (!ROLES.contains(key)) throw new IllegalArgumentException("Unknown theme status");
    if (statuses.containsKey(key)) return statuses.get(key);
    return Rgba.of(
        switch (key) {
          case "Warning" ->
              base.equals("Dark") ? new float[] {1, .74f, .32f, 1} : new float[] {.45f, .23f, 0, 1};
          case "Error" ->
              base.equals("Dark")
                  ? new float[] {1, .5f, .48f, 1}
                  : new float[] {.65f, .06f, .08f, 1};
          case "Success" ->
              base.equals("Dark")
                  ? new float[] {.4f, .88f, .62f, 1}
                  : new float[] {0, .36f, .18f, 1};
          default ->
              base.equals("Dark") ? new float[] {.5f, .8f, 1, 1} : new float[] {0, .25f, .55f, 1};
        });
  }

  public void writeTo(UserConfig.Appearance a) {
    a.theme = base;
    a.colors = new TreeMap<>();
    a.statusColors = new TreeMap<>();
    colors.forEach((key, value) -> a.colors.put(key, value.array()));
    statuses.forEach((key, value) -> a.statusColors.put(key, value.array()));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ThemePalette p
        && base.equals(p.base)
        && colors.equals(p.colors)
        && statuses.equals(p.statuses);
  }

  @Override
  public int hashCode() {
    return Objects.hash(base, colors, statuses);
  }
}
