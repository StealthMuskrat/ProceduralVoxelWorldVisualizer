package io.voxelcreator.editor;

import io.voxelcreator.model.ProjectData.Curve;
import java.util.*;

/**
 * Dialog-local, one-shot focus; unavailable derived/locked controls reveal their editable owner.
 */
final class CurveControlFocus {
  record Destination(String key, String explanation) {}

  private UUID point;
  private String field, submitted, acknowledged;
  private boolean window;

  void request(UUID point, String field) {
    cancel();
    if (field == null
        || !(point == null ? CurveNavigation.CURVE_FIELDS : CurveNavigation.POINT_FIELDS)
            .contains(field)) throw new IllegalArgumentException("Unknown curve control " + field);
    this.point = point;
    this.field = field;
    window = true;
  }

  Destination destination(Curve curve) {
    if (field == null) return null;
    if (point == null) return resolved(field, "");
    int index = -1;
    for (int i = 0; i < curve.points.size(); i++)
      if (point.equals(curve.points.get(i).uuid)) index = i;
    if (index < 0) {
      cancel();
      return null;
    }
    var p = curve.points.get(index);
    if (field.equals("x") && (index == 0 || index == curve.points.size() - 1))
      return resolved(index == 0 ? "min" : "max", "Endpoint X is locked to the curve domain.");
    boolean bezier =
        p.segmentInterpolation.equals("BEZIER")
            || index > 0 && curve.points.get(index - 1).segmentInterpolation.equals("BEZIER");
    if (Set.of("handleMode", "inTangent", "outTangent").contains(field)) {
      if (!bezier)
        return resolved(
            "segmentInterpolation", "Tangent controls require an adjacent Bezier segment.");
      if (!field.equals("handleMode") && p.handleMode.equals("AUTO"))
        return resolved(
            "handleMode", "Auto computes tangent slopes. Choose Aligned or Free to edit them.");
    }
    return resolved(field, "");
  }

  private Destination resolved(String key, String explanation) {
    if (submitted != null && acknowledged == null && !submitted.equals(key)) {
      cancel();
      return null;
    }
    return new Destination(key, explanation);
  }

  boolean windowRequest() {
    boolean result = window;
    window = false;
    return result;
  }

  boolean matches(String key) {
    return field != null && acknowledged == null && Objects.equals(submitted, key);
  }

  boolean submit(String key, Destination destination) {
    if (field == null
        || submitted != null
        || acknowledged != null
        || destination == null
        || !key.equals(destination.key())) return false;
    submitted = key;
    return true;
  }

  void acknowledge(String key) {
    if (matches(key)) acknowledged = key;
  }

  String acknowledged() {
    return acknowledged;
  }

  void cancel() {
    point = null;
    field = submitted = acknowledged = null;
    window = false;
  }
}
