package io.voxelcreator.editor;

import java.util.*;

/** Raw owned values only; malformed external colors are compared without accepting them. */
record ThemeActionValues(
    String base,
    Map<Integer, List<Float>> colors,
    Map<String, List<Float>> statuses,
    boolean paused,
    Object pausedStyleOwner) {
  static ThemeActionValues from(UserConfig user, boolean paused) {
    var appearance = user.appearance;
    return new ThemeActionValues(
        appearance.theme,
        snapshot(appearance.colors),
        snapshot(appearance.statusColors),
        paused,
        paused ? ThemeStyle.owner() : null);
  }

  private static <K> Map<K, List<Float>> snapshot(Map<K, float[]> source) {
    if (source == null) return null;
    var result = new LinkedHashMap<K, List<Float>>();
    source.forEach(
        (key, value) -> {
          if (value == null) result.put(key, null);
          else {
            var components = new ArrayList<Float>(value.length);
            for (float component : value) components.add(component);
            result.put(key, Collections.unmodifiableList(components));
          }
        });
    return Collections.unmodifiableMap(result);
  }
}
