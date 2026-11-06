package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Curve ownership comes from UUIDs; a historical pointer contributes only a recognized leaf. */
final class CurveNavigation {
  static final Set<String> CURVE_FIELDS =
      Set.of("min", "max", "displayMin", "displayMax", "outsideDomain");
  static final Set<String> POINT_FIELDS =
      Set.of("x", "y", "segmentInterpolation", "handleMode", "inTangent", "outTangent");

  record Route(Item item, UUID point, String field) {}

  static Route resolve(Noise noise, EditorEvents.Target target) {
    if (target == null || !noise.uuid.equals(target.resource()) || target.control() == null)
      return null;
    for (var item : noise.processing) {
      if (!"CURVE".equals(item.kind)) continue;
      UUID point = null;
      boolean owns = item.uuid.equals(target.control());
      if (!owns) {
        var curve = item.parameters.get("curve");
        if (curve != null)
          for (var candidate : curve.path("points"))
            if (target.control().toString().equals(candidate.path("uuid").asText())) {
              point = target.control();
              owns = true;
              break;
            }
      }
      if (!owns) continue;
      String key = Objects.requireNonNullElse(target.parameter(), "");
      boolean explicitParent = key.equals("parameters/curve");
      String[] tokens = DiagnosticPaths.tokens(target.pointer());
      boolean curvePath =
          tokens.length >= 7
              && DiagnosticPaths.controlPath(tokens, noise.uuid, item.uuid)
              && Set.of("processing", "control").contains(tokens[3])
              && tokens[5].equals("parameters")
              && tokens[6].equals("curve");
      if (point == null && key.isEmpty() && !curvePath) return null;
      String prefix = "parameters/curve/";
      if (key.startsWith(prefix)) key = key.substring(prefix.length());
      else if (key.startsWith("curve/")) key = key.substring(6);
      var allowed = point == null ? CURVE_FIELDS : POINT_FIELDS;
      if (allowed.contains(key)) return new Route(item, point, key);
      boolean generic = Set.of("", "curve", "parameters/curve").contains(key);
      if (!generic) return null;
      if (!explicitParent && curvePath && tokens.length >= 8) {
        // A point UUID is already required; the historical index below is never resolved.
        if (point != null
            && tokens.length == 10
            && tokens[7].equals("points")
            && DiagnosticPaths.identity(tokens[8], point)
            && POINT_FIELDS.contains(tokens[9])) key = tokens[9];
        else if (point == null && tokens.length == 8 && CURVE_FIELDS.contains(tokens[7]))
          key = tokens[7];
      }
      return new Route(item, point, allowed.contains(key) ? key : point == null ? "min" : "y");
    }
    return null;
  }
}
