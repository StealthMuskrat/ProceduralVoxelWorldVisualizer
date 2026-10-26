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
  private final double[] a, b, c, d;

  public ScalarCurve(Curve c) {
    int n = c.points.size();
    if (c.uuid == null
        || n < 2
        || !Double.isFinite(c.min)
        || !Double.isFinite(c.max)
        || !Double.isFinite(c.max - c.min)
        || !(c.min < c.max)
        || !Double.isFinite(c.displayMin)
        || !Double.isFinite(c.displayMax)
        || !Double.isFinite(c.displayMax - c.displayMin)
        || !(c.displayMin < c.displayMax))
      throw new IllegalArgumentException("Curve needs a positive domain and two endpoints");
    x = new double[n];
    y = new double[n];
    in = new double[n];
    out = new double[n];
    slope = new double[n];
    interpolation = new String[n];
    outside = c.outsideDomain;
    var identities = new java.util.HashSet<java.util.UUID>();
    identities.add(c.uuid);
    if (!java.util.Set.of("CLAMP", "EXTEND", "REPEAT", "MIRROR").contains(outside))
      throw new IllegalArgumentException("Outside domain policy");
    for (int i = 0; i < n; i++) {
      var p = c.points.get(i);
      if (p.uuid == null || !identities.add(p.uuid))
        throw new IllegalArgumentException("Curve/point identities must be unique and non-null");
      if (!java.util.Set.of("AUTO", "ALIGNED", "FREE").contains(p.handleMode))
        throw new IllegalArgumentException("Handle mode");
      if (p.handleMode.equals("ALIGNED") && p.inTangent != p.outTangent)
        throw new IllegalArgumentException("Aligned handles require matching slopes");
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
    for (int i = 0; i < n; i++)
      if (c.points.get(i).handleMode.equals("AUTO")) {
        in[i] = slope[i];
        out[i] = slope[i];
      }
    a = new double[n - 1];
    b = new double[n - 1];
    this.c = new double[n - 1];
    this.d = new double[n - 1];
    for (int i = 0; i < n - 1; i++) {
      double h = x[i + 1] - x[i], delta = y[i + 1] - y[i];
      this.d[i] = y[i];
      if (interpolation[i].equals("LINEAR")) this.c[i] = delta;
      else if (!interpolation[i].equals("STEP")) {
        double m0 = (interpolation[i].equals("SMOOTH") ? slope[i] : out[i]) * h,
            m1 = (interpolation[i].equals("SMOOTH") ? slope[i + 1] : in[i + 1]) * h;
        a[i] = m0 + m1 - 2 * delta;
        b[i] = 3 * delta - 2 * m0 - m1;
        this.c[i] = m0;
      }
      if (!Double.isFinite(a[i])
          || !Double.isFinite(b[i])
          || !Double.isFinite(this.c[i])
          || !Double.isFinite(this.d[i]))
        throw new IllegalArgumentException("Nonfinite curve coefficients");
    }
  }

  public double incomingSlope(int point) {
    return in[point];
  }

  public double outgoingSlope(int point) {
    return out[point];
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
          double repeated = lo + (v - lo) - len * StrictMath.floor((v - lo) / len);
          v = inDomain(repeated, lo, hi) ? repeated : repeatFallback(v, lo, len);
          break;
        case "MIRROR":
          double t = (v - lo) - 2 * len * StrictMath.floor((v - lo) / (2 * len));
          double mirrored = lo + (t <= len ? t : 2 * len - t);
          v = inDomain(mirrored, lo, hi) ? mirrored : mirrorFallback(v, lo, len);
          break;
        default:
          throw new AssertionError();
      }
    }
    if (!inDomain(v, lo, hi))
      throw new ArithmeticException("Curve outside-domain mapping is not representable");
    if (v == hi) return y[last];
    int i = java.util.Arrays.binarySearch(x, v);
    if (i >= 0) return y[i];
    i = -i - 2;
    double h = x[i + 1] - x[i], t = (v - x[i]) / h;
    return ((a[i] * t + b[i]) * t + c[i]) * t + d[i];
  }

  private static boolean inDomain(double value, double lo, double hi) {
    return Double.isFinite(value) && value >= lo && value <= hi;
  }

  private static double positiveRemainder(double value, double period) {
    double remainder = value % period;
    if (remainder < 0) remainder += period;
    return remainder >= period ? 0 : remainder;
  }

  /** Reduce each operand before subtraction so neither the offset nor quotient can overflow. */
  private static double phase(double value, double origin, double period) {
    double result = positiveRemainder(value, period) - positiveRemainder(origin, period);
    if (result < 0) result += period;
    return result >= period ? 0 : result;
  }

  private static double repeatFallback(double value, double lo, double length) {
    return lo + phase(value, lo, length);
  }

  private static double mirrorFallback(double value, double lo, double length) {
    double period = 2 * length;
    if (Double.isFinite(period)) {
      double p = phase(value, lo, period);
      return lo + (p <= length ? p : period - p);
    }
    // Work at half scale when the doubled period cannot be represented. Fold before
    // doubling, so the intermediate distance never exceeds the finite domain length.
    double half = positiveRemainder(value / 2 - lo / 2, length);
    return lo + 2 * Math.min(half, length - half);
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
