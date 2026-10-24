package io.voxelcreator.model;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Presence, including explicit zero/false, wins over live layer defaults. Null is invalid. */
public final class SettingsResolver {
  public static final Set<String> PROPERTIES;

  static {
    PROPERTIES =
        Collections.unmodifiableSet(
            new LinkedHashSet<>(
                List.of(
                    "baseHeight",
                    "heightScale",
                    "threshold",
                    "strength",
                    "probability",
                    "minY",
                    "maxY",
                    "axisX",
                    "axisY",
                    "axisZ",
                    "scaleX",
                    "scaleY",
                    "scaleZ",
                    "offsetX",
                    "offsetY",
                    "offsetZ",
                    "samplingTransforms",
                    "seedOffset",
                    "action",
                    "rounding",
                    "typeSource",
                    "voxelTypeUuid",
                    "typeIntervals",
                    "probabilityDriver",
                    "strengthDriver",
                    "parameterDrivers",
                    "voxelStatePredicate",
                    "fieldGates")));
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
