package io.voxelcreator.editor;

import io.voxelcreator.core.ScalarCurve;

/** Bounded, immutable display observations. Invalid samples are gaps, never replacement values. */
public final class CurvePreview {
  public static final int SEGMENTS = 512;

  public record Failure(int sample, double input, String message) {}

  private final double[] inputs, values;
  private final int failures;
  private final Failure first;

  private CurvePreview(double[] inputs, double[] values, int failures, Failure first) {
    this.inputs = inputs;
    this.values = values;
    this.failures = failures;
    this.first = first;
  }

  public int size() {
    return inputs.length;
  }

  public double input(int i) {
    return inputs[i];
  }

  public double value(int i) {
    return values[i];
  }

  public boolean valid(int i) {
    return Double.isFinite(values[i]);
  }

  public int failures() {
    return failures;
  }

  public Failure firstFailure() {
    return first;
  }

  public static CurvePreview capture(ScalarCurve curve, double lo, double hi) {
    if (curve == null
        || !Double.isFinite(lo)
        || !Double.isFinite(hi)
        || !Double.isFinite(hi - lo)
        || !(lo < hi))
      throw new IllegalArgumentException("Preview requires a finite positive input span");
    double[] inputs = new double[SEGMENTS + 1], values = new double[SEGMENTS + 1];
    int failures = 0;
    Failure first = null;
    for (int i = 0; i <= SEGMENTS; i++) {
      // Multiply by the normalized fraction, not the integer index: large finite spans
      // must not overflow solely because the display has many samples.
      double x = i == SEGMENTS ? hi : lo + (hi - lo) * (i / (double) SEGMENTS);
      inputs[i] = x;
      try {
        double value = curve.sample(x);
        if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite curve result");
        values[i] = value;
      } catch (ArithmeticException | IllegalArgumentException numeric) {
        values[i] = Double.NaN;
        failures++;
        if (first == null) {
          String message =
              numeric.getMessage() == null
                  ? numeric.getClass().getSimpleName()
                  : numeric.getMessage();
          if (message.length() > 256) message = message.substring(0, 256);
          first = new Failure(i, x, message);
        }
      }
    }
    return new CurvePreview(inputs, values, failures, first);
  }
}
