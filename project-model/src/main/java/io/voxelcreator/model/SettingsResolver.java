package io.voxelcreator.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Presence, including explicit zero/false, wins over live layer defaults. Null is invalid. */
public final class SettingsResolver {
  public static final Set<String> PROPERTIES;

  static {
    var s = new LinkedHashSet<String>();
    for (var f : ProjectData.Settings.class.getFields()) s.add(f.getName());
    PROPERTIES = Collections.unmodifiableSet(s);
  }

  public static ProjectData.Settings resolve(ProjectData.Layer layer, ProjectData.Operation op) {
    ObjectNode n = ProjectIO.JSON.valueToTree(layer.defaults);
    op.overrides.forEach(
        (key, value) -> {
          if (!PROPERTIES.contains(key) || value == null || value.isNull())
            throw new IllegalArgumentException("Invalid override " + key);
          n.set(key, value.deepCopy());
        });
    return ProjectIO.JSON.convertValue(n, ProjectData.Settings.class);
  }

  public static String source(ProjectData.Operation op, String property) {
    return op.overrides.containsKey(property) ? "Placement override" : "Layer default";
  }
}
