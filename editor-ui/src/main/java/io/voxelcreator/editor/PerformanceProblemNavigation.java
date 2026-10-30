package io.voxelcreator.editor;

import io.voxelcreator.editor.PerformancePreferences.Field;
import java.util.*;

/** Exact displayed mismatches precede validation feedback; revealing never reparses a field. */
final class PerformanceProblemNavigation {
  private PerformanceProblemNavigation() {}

  static Field first(
      Map<Field, String> owned, Map<Field, String> shown, Map<Field, String> problems) {
    for (var field : Field.values())
      if (!Objects.equals(owned.get(field), shown.get(field))) return field;
    for (var field : Field.values()) if (problems.containsKey(field)) return field;
    return null;
  }
}
