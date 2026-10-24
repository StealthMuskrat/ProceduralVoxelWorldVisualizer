package io.voxelcreator.core;

import io.voxelcreator.model.ProjectData.Curve;

/**
 * Immutable piecewise curve. Bezier uses endpoint slopes with X handles at one third of each
 * segment.
 */
public final class ScalarCurve {
  private final double[] x, y, in, out, slope;
  private final String[] interpolation;
  private final String outside;

  public ScalarCurve(Curve c) {
    int n = c.points.size();
    if (n < 2 || !(c.min < c.max))
      throw new IllegalArgumentException("Curve needs a positive domain and two endpoints");
    x = new double[n];
    y = new double[n];
    in = new double[n];
    out = new double[n];
    slope = new double[n];
    interpolation = new String[n];
    outside = c.outsideDomain;
    if (!java.util.Set.of("CLAMP", "EXTEND", "REPEAT", "MIRROR").contains(outside))
      throw new IllegalArgumentException("Outside domain policy");
    for (int i = 0; i < n; i++) {
      var p = c.points.get(i);
      x[i] = p.x;
      y[i] = p.y;
      in[i] = p.inTangent;
      out[i] = p.outTangent;
      interpolation[i] = p.segmentInterpolation;
      if (!Double.isFinite(x[i])
          || !Double.isFinite(y[i])
          || !Double.isFinite(in[i])
          || !Double.isFinite(out[i])
          || (i > 0 && x[i] <= x[i - 1]))
        throw new IllegalArgumentException("Strict finite increasing X required");
      if (!java.util.Set.of("STEP", "LINEAR", "SMOOTH", "BEZIER").contains(interpolation[i]))
        throw new IllegalArgumentException("Interpolation kind");
    }
    if (x[0] != c.min || x[n - 1] != c.max)
      throw new IllegalArgumentException("Endpoints must match domain");
    double[] d = new double[n - 1];
    for (int i = 0; i < n - 1; i++) d[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
    slope[0] = d[0];
    slope[n - 1] = d[n - 2];
    for (int i = 1; i < n - 1; i++)
      slope[i] = d[i - 1] * d[i] <= 0 ? 0 : 2 * d[i - 1] * d[i] / (d[i - 1] + d[i]);
    for (int i = 0; i < n - 1; i++) {
      if (d[i] == 0) {
        slope[i] = 0;
        slope[i + 1] = 0;
      } else {
        double a = slope[i] / d[i], b = slope[i + 1] / d[i], s = a * a + b * b;
        if (s > 9) {
          double t = 3 / StrictMath.sqrt(s);
          slope[i] = t * a * d[i];
          slope[i + 1] = t * b * d[i];
        }
      }
    }
  }

  public double sample(double v) {
    if (!Double.isFinite(v)) throw new IllegalArgumentException("Finite curve input required");
    int last = x.length - 1;
    double lo = x[0], hi = x[last], len = hi - lo;
    if (v < lo || v > hi) {
      switch (outside) {
        case "CLAMP":
          return v < lo ? y[0] : y[last];
        case "EXTEND":
          return v < lo
              ? y[0] + (v - lo) * endpointSlope(0)
              : y[last] + (v - hi) * endpointSlope(last);
        case "REPEAT":
          v = lo + (v - lo) - len * StrictMath.floor((v - lo) / len);
          break;
        case "MIRROR":
          double t = (v - lo) - 2 * len * StrictMath.floor((v - lo) / (2 * len));
          v = lo + (t <= len ? t : 2 * len - t);
          break;
        default:
          throw new AssertionError();
      }
    }
    if (v == hi) return y[last];
    int i = java.util.Arrays.binarySearch(x, v);
    if (i >= 0) return y[i];
    i = -i - 2;
    double h = x[i + 1] - x[i], t = (v - x[i]) / h;
    return switch (interpolation[i]) {
      case "STEP" -> y[i];
      case "LINEAR" -> y[i] + t * (y[i + 1] - y[i]);
      case "SMOOTH" -> hermite(y[i], y[i + 1], slope[i] * h, slope[i + 1] * h, t);
      case "BEZIER" -> hermite(y[i], y[i + 1], out[i] * h, in[i + 1] * h, t);
      default -> throw new AssertionError();
    };
  }

  private double endpointSlope(int i) {
    int s = i == 0 ? 0 : i - 1;
    return switch (interpolation[s]) {
      case "STEP" -> 0;
      case "BEZIER" -> i == 0 ? out[0] : in[i];
      case "SMOOTH" -> slope[i];
      default -> (y[s + 1] - y[s]) / (x[s + 1] - x[s]);
    };
  }

  private static double hermite(double a, double b, double ma, double mb, double t) {
    double t2 = t * t, t3 = t2 * t;
    return (2 * t3 - 3 * t2 + 1) * a
        + (t3 - 2 * t2 + t) * ma
        + (-2 * t3 + 3 * t2) * b
        + (t3 - t2) * mb;
  }
}
