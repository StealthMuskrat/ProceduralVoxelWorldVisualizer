package io.voxelcreator.core;

import java.math.BigDecimal;

/** Ordinary interpolation first; recover only nonfinite intermediates from finite operands. */
final class FiniteInterpolation {
  private FiniteInterpolation() {}

  static double between(double a, double b, double weight) {
    double result = a + (b - a) * weight;
    if (Double.isFinite(result)
        || !Double.isFinite(a)
        || !Double.isFinite(b)
        || !Double.isFinite(weight)) return result;
    // These constructors capture the actual binary double values, not their rounded decimal text.
    // Exact finite-double arithmetic has bounded operand sizes; do not round until conversion back
    // to double. A truly out-of-range result remains infinite for the caller's existing boundary.
    var start = new BigDecimal(a);
    return start
        .add(new BigDecimal(b).subtract(start).multiply(new BigDecimal(weight)))
        .doubleValue();
  }
}
