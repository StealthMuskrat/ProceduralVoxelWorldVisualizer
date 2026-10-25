package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Item;
import java.util.*;

/** Bounded procedural stencils; each compiled operation owns its thread-local median scratch. */
final class FieldCalculus {
  @FunctionalInterface
  interface Components {
    double sample(double x, double y, double z, int component);
  }

  record Plan(Components field, long samples) {}

  static Plan compile(Item item, int dimension, NoiseAlgorithms.Field field) {
    var controls = ProcessingExtensions.controls(item, dimension);
    double spacing = controls.get("spacing").doubleValue();
    if (item.kind.equals("GRADIENT") && !Double.isFinite(2 * spacing)
        || item.kind.equals("LAPLACIAN")
            && (!Double.isFinite(spacing * spacing) || spacing * spacing == 0))
      throw new ProcessingExtensions.InvalidControl(
          "spacing", "spacing: derivative denominator must be finite and nonzero");
    if (item.kind.equals("GRADIENT"))
      return new Plan(
          (x, y, z, k) -> {
            double dx = k == 0 ? spacing : 0,
                dy = dimension == 3 && k == 1 ? spacing : 0,
                dz = k == (dimension == 2 ? 1 : 2) ? spacing : 0;
            return (field.sample(x + dx, y + dy, z + dz) - field.sample(x - dx, y - dy, z - dz))
                / (2 * spacing);
          },
          2L * dimension);
    if (item.kind.equals("LAPLACIAN"))
      return new Plan(
          (x, y, z, k) -> {
            double center = field.sample(x, y, z);
            double sum =
                (field.sample(x + spacing, y, z) - center)
                    + (field.sample(x - spacing, y, z) - center);
            if (dimension == 3)
              sum +=
                  (field.sample(x, y + spacing, z) - center)
                      + (field.sample(x, y - spacing, z) - center);
            sum +=
                (field.sample(x, y, z + spacing) - center)
                    + (field.sample(x, y, z - spacing) - center);
            return sum / (spacing * spacing);
          },
          2L * dimension + 1);
    int radius = controls.get("radius").intValue(), width = 2 * radius + 1;
    int count = dimension == 2 ? width * width : width * width * width;
    double strength = controls.get("strength").doubleValue();
    ThreadLocal<double[]> scratch =
        item.kind.equals("MEDIAN_FILTER") ? ThreadLocal.withInitial(() -> new double[count]) : null;
    String kind = item.kind;
    return new Plan(
        (x, y, z, k) -> {
          // Zero strength is explicitly lazy. Admission still accounts for the authored full
          // stencil.
          double center = field.sample(x, y, z);
          if (strength == 0 || radius == 0) return center;
          double value =
              kind.equals("MIN_FILTER") ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
          double[] values = scratch == null ? null : scratch.get();
          int index = 0;
          for (int yy = dimension == 2 ? 0 : -radius; yy <= (dimension == 2 ? 0 : radius); yy++)
            for (int zz = -radius; zz <= radius; zz++)
              for (int xx = -radius; xx <= radius; xx++) {
                double sample =
                    xx == 0 && yy == 0 && zz == 0
                        ? center
                        : field.sample(x + xx * spacing, y + yy * spacing, z + zz * spacing);
                if (values != null) values[index++] = sample;
                else
                  value =
                      kind.equals("MIN_FILTER")
                          ? StrictMath.min(value, sample)
                          : StrictMath.max(value, sample);
              }
          if (values != null) {
            Arrays.sort(values);
            value = values[count / 2];
          }
          // Preserve the exact endpoint and avoid an unnecessary overflowing difference at
          // strength=1.
          return strength == 1 ? value : center + (value - center) * strength;
        },
        count);
  }
}
