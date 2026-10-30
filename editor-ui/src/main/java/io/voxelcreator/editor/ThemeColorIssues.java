package io.voxelcreator.editor;

import java.util.*;

/**
 * Bounded rejected-channel ledger; only a correction of that channel or explicit reset clears it.
 */
final class ThemeColorIssues {
  private final Set<String> known = new HashSet<>();
  private final Map<String, Integer> masks = new LinkedHashMap<>();

  ThemeColorIssues() {
    ThemePalette.ROLES.forEach(role -> known.add("status:" + role));
    ThemeRegistry.COLORS.forEach(color -> known.add("color:" + color.key()));
  }

  boolean observe(String row, float[] candidate, int edited) {
    requireRow(row);
    if (candidate == null || candidate.length != 4 || edited < 0 || edited > 15)
      throw new IllegalArgumentException("Expected four RGBA components and a channel mask");
    int mask = masks.getOrDefault(row, 0);
    for (int axis = 0; axis < 4; axis++) {
      int bit = 1 << axis;
      boolean valid =
          Float.isFinite(candidate[axis]) && candidate[axis] >= 0 && candidate[axis] <= 1;
      if (!valid) mask |= bit;
      else if ((edited & bit) != 0) mask &= ~bit;
    }
    if (mask == 0) masks.remove(row);
    else masks.put(row, mask);
    return mask == 0;
  }

  boolean blocked(String row) {
    requireRow(row);
    return masks.containsKey(row);
  }

  boolean bad(String row, int axis) {
    requireRow(row);
    if (axis < 0 || axis > 3) throw new IllegalArgumentException("Unknown RGBA channel");
    return (masks.getOrDefault(row, 0) & (1 << axis)) != 0;
  }

  boolean any() {
    return !masks.isEmpty();
  }

  void clear(String row) {
    requireRow(row);
    masks.remove(row);
  }

  void clearAll() {
    masks.clear();
  }

  Map<String, Set<Integer>> problems() {
    var result = new LinkedHashMap<String, Set<Integer>>();
    masks.forEach(
        (row, mask) -> {
          var axes = new LinkedHashSet<Integer>();
          for (int i = 0; i < 4; i++) if ((mask & (1 << i)) != 0) axes.add(i);
          result.put(row, Collections.unmodifiableSet(axes));
        });
    return Collections.unmodifiableMap(result);
  }

  private void requireRow(String row) {
    if (!known.contains(row)) throw new IllegalArgumentException("Unknown theme row");
  }
}
