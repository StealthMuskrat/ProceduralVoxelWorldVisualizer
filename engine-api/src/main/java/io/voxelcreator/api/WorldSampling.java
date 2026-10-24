package io.voxelcreator.api;

import io.voxelcreator.core.NoiseAlgorithms;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;

/** Immutable placement sampling program. Scratch never escapes or aliases a noise evaluator. */
final class WorldSampling {
  private record Step(
      String kind,
      double x,
      double y,
      double z,
      double px,
      double py,
      double pz,
      String order,
      double[] sin,
      double[] cos) {
    void apply(double[] p) {
      if (kind.equals("TRANSLATE")) {
        p[0] += x;
        p[1] += y;
        p[2] += z;
        return;
      }
      p[0] -= px;
      p[1] -= py;
      p[2] -= pz;
      if (kind.equals("SCALE")) {
        p[0] *= x;
        p[1] *= y;
        p[2] *= z;
      } else
        for (int i = 0; i < 3; i++) {
          int axis = order.charAt(i) - 'X';
          int a = (axis + 1) % 3, b = (axis + 2) % 3;
          double va = p[a], vb = p[b];
          p[a] = va * cos[axis] - vb * sin[axis];
          p[b] = va * sin[axis] + vb * cos[axis];
        }
      p[0] += px;
      p[1] += py;
      p[2] += pz;
    }
  }

  private final String ax, ay, az;
  private final double sx, sy, sz, ox, oy, oz;
  private final List<Step> steps;
  private final ThreadLocal<double[]> scratch = ThreadLocal.withInitial(() -> new double[3]);
  private final boolean columnInvariant;

  WorldSampling(Settings s) {
    ax = s.axisX;
    ay = s.axisY;
    az = s.axisZ;
    sx = s.scaleX;
    sy = s.scaleY;
    sz = s.scaleZ;
    ox = s.offsetX;
    oy = s.offsetY;
    oz = s.offsetZ;
    var program = new ArrayList<Step>();
    for (var t : s.samplingTransforms)
      if (t.enabled) {
        double[] sin = new double[3], cos = new double[3], angles = {t.x, t.y, t.z};
        for (int i = 0; i < 3; i++) {
          double radians = StrictMath.toRadians(angles[i] % 360);
          sin[i] = StrictMath.sin(radians);
          cos[i] = StrictMath.cos(radians);
        }
        program.add(
            new Step(
                t.kind, t.x, t.y, t.z, t.pivotX, t.pivotY, t.pivotZ, t.rotationOrder, sin, cos));
      }
    steps = List.copyOf(program);
    // Ordered floating-point programs can retain Y rounding effects even when their real-valued
    // matrices cancel. Keep the existing column fast path only for the provably independent form.
    columnInvariant =
        steps.isEmpty()
            && !(ax.endsWith("Y") && sx != 0)
            && !(az.endsWith("Y") && sz != 0)
            && Double.isFinite(2147483648.0 * StrictMath.abs(sy) + StrictMath.abs(oy));
  }

  boolean columnInvariant() {
    return columnInvariant;
  }

  private static double axis(String axis, double x, double y, double z) {
    double v =
        switch (axis.charAt(axis.length() - 1)) {
          case 'X' -> x;
          case 'Y' -> y;
          case 'Z' -> z;
          default -> throw new AssertionError();
        };
    return axis.charAt(0) == '-' ? -v : v;
  }

  private double[] apply(int x, int y, int z) {
    var p = scratch.get();
    p[0] = axis(ax, x, y, z) * sx + ox;
    p[1] = axis(ay, x, y, z) * sy + oy;
    p[2] = axis(az, x, y, z) * sz + oz;
    for (var step : steps) step.apply(p);
    return p;
  }

  private static void finite(double[] p, int dimension) {
    if (!Double.isFinite(p[0])
        || !Double.isFinite(p[2])
        || dimension == 3 && !Double.isFinite(p[1]))
      throw new ArithmeticException("Nonfinite placement sampling coordinates");
  }

  double sample(NoiseAlgorithms.Field field, int dimension, int x, int y, int z) {
    var p = apply(x, y, z);
    finite(p, dimension);
    return field.sample(p[0], dimension == 2 ? 0 : p[1], p[2]);
  }

  GenerationTrace.SampleCoordinates coordinates(int x, int y, int z, int dimension) {
    var p = apply(x, y, z);
    finite(p, dimension);
    return new GenerationTrace.SampleCoordinates(p[0], dimension == 2 ? 0 : p[1], p[2]);
  }
}
