package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Item;

/** Immutable exact signed-distance functions in transformed definition space. */
final class DistanceShapes {
  static NoiseAlgorithms.Field compile(Item item, int dimension) {
    var c = ProcessingExtensions.controls(item, dimension);
    double cx = number(c, "centerX"), cy = number(c, "centerY"), cz = number(c, "centerZ");
    String kind = item.kind;
    int axis = c.containsKey("axis") ? "xyz".indexOf(c.get("axis").asText()) : 0;
    if (kind.equals("PLANE")) {
      double offset = number(c, "offset"),
          direction = c.get("direction").asText().equals("NEGATIVE") ? -1 : 1;
      return (x, y, z) -> direction * ((axis == 0 ? x : axis == 1 ? y : z) - offset);
    }
    if (kind.equals("CAPSULE")) {
      double ax = number(c, "startX"),
          ay = number(c, "startY"),
          az = number(c, "startZ"),
          bx = number(c, "endX"),
          by = number(c, "endY"),
          bz = number(c, "endZ"),
          radius = number(c, "radius");
      double dx = bx - ax,
          dy = by - ay,
          dz = bz - az,
          length = StrictMath.hypot(StrictMath.hypot(dx, dy), dz);
      if (!Double.isFinite(length))
        throw new ProcessingExtensions.InvalidControl(
            "endX", "Capsule endpoint distance must be finite");
      double ux = length == 0 ? 0 : dx / length,
          uy = length == 0 ? 0 : dy / length,
          uz = length == 0 ? 0 : dz / length;
      return (x, y, z) -> {
        double px = x - ax, py = dimension == 2 ? 0 : y - ay, pz = z - az;
        double t = Expression.clamp(px * ux + py * uy + pz * uz, 0, length);
        return StrictMath.hypot(StrictMath.hypot(px - t * ux, py - t * uy), pz - t * uz) - radius;
      };
    }
    if (kind.equals("CYLINDER")) {
      double radius = number(c, "radius"), height = number(c, "halfHeight");
      return (x, y, z) -> {
        double px = x - cx, py = y - cy, pz = z - cz;
        double axial = axis == 0 ? px : axis == 1 ? py : pz;
        double radial =
            axis == 0
                ? StrictMath.hypot(py, pz)
                : axis == 1 ? StrictMath.hypot(px, pz) : StrictMath.hypot(px, py);
        double a = radial - radius, b = StrictMath.abs(axial) - height;
        return StrictMath.hypot(StrictMath.max(a, 0), StrictMath.max(b, 0))
            + StrictMath.min(StrictMath.max(a, b), 0);
      };
    }
    if (kind.equals("TORUS")) {
      double major = number(c, "majorRadius"), minor = number(c, "minorRadius");
      if (minor > major)
        throw new ProcessingExtensions.InvalidControl(
            "minorRadius", "minorRadius cannot exceed majorRadius");
      return (x, y, z) -> {
        double px = x - cx, py = y - cy, pz = z - cz;
        double axial = axis == 0 ? px : axis == 1 ? py : pz;
        double radial =
            axis == 0
                ? StrictMath.hypot(py, pz)
                : axis == 1 ? StrictMath.hypot(px, pz) : StrictMath.hypot(px, py);
        return StrictMath.hypot(radial - major, axial) - minor;
      };
    }
    double ex = number(c, "extentX"),
        ey = number(c, "extentY"),
        ez = number(c, "extentZ"),
        radius = number(c, "roundRadius");
    if (radius > StrictMath.min(ex, ez) || dimension == 3 && radius > ey)
      throw new ProcessingExtensions.InvalidControl(
          "roundRadius", "roundRadius cannot exceed an active outer half extent");
    return (x, y, z) -> {
      double qx = StrictMath.abs(x - cx) - ex + radius,
          qy = dimension == 2 ? Double.NEGATIVE_INFINITY : StrictMath.abs(y - cy) - ey + radius,
          qz = StrictMath.abs(z - cz) - ez + radius;
      return StrictMath.hypot(
              StrictMath.hypot(StrictMath.max(qx, 0), StrictMath.max(qy, 0)), StrictMath.max(qz, 0))
          + StrictMath.min(StrictMath.max(qx, StrictMath.max(qy, qz)), 0)
          - radius;
    };
  }

  private static double number(
      java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> controls, String key) {
    return controls.containsKey(key) ? controls.get(key).doubleValue() : 0;
  }

  static double smoothMaximum(double a, double b, double smoothness) {
    // Compact support: exact max outside the smoothing band, including extreme signed inputs.
    double difference = StrictMath.abs(a - b);
    if (difference >= smoothness) return StrictMath.max(a, b);
    double h = 1 - difference / smoothness;
    return StrictMath.max(a, b) + smoothness * .25 * h * h;
  }
}
