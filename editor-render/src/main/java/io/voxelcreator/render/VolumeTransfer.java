package io.voxelcreator.render;

/** Finite GPU coefficients derived from canonical controls and the accepted texture encoding. */
public record VolumeTransfer(float gain, float bias, float threshold, float opacity, int steps) {
  public record Settings(double min, double max, double opacity, double threshold, int steps) {
    public Settings {
      if (!Double.isFinite(min)
          || !Double.isFinite(max)
          || min >= max
          || !Double.isFinite(opacity)
          || opacity < 0
          || !Double.isFinite(threshold)
          || steps < 16
          || steps > 512) throw new IllegalArgumentException("Volume display controls");
    }
  }

  public VolumeTransfer {
    if (!Float.isFinite(gain)
        || gain < 0
        || !Float.isFinite(bias)
        || !Float.isFinite(threshold)
        || !Float.isFinite(opacity)
        || opacity < 0
        || opacity > 10
        || steps < 16
        || steps > 512) throw new IllegalArgumentException("Volume transfer coefficients");
  }

  public static VolumeTransfer of(VolumeData.Encoding encoding, Settings settings) {
    double width = settings.max - settings.min;
    double gain;
    if (Double.isFinite(width)) gain = encoding.scale() / width;
    else {
      double scale =
          Math.max(Math.abs(settings.min), Math.max(Math.abs(settings.max), encoding.scale()));
      gain = (encoding.scale() / scale) / (settings.max / scale - settings.min / scale);
    }
    double numerator = encoding.offset() - settings.min;
    double bias;
    if (Double.isFinite(numerator) && Double.isFinite(width)) bias = numerator / width;
    else {
      double scale =
          Math.max(
              Math.abs(encoding.offset()),
              Math.max(Math.abs(settings.min), Math.abs(settings.max)));
      bias =
          (encoding.offset() / scale - settings.min / scale)
              / (settings.max / scale - settings.min / scale);
    }
    double delta = settings.threshold - encoding.offset();
    double threshold;
    if (Double.isFinite(delta)) threshold = delta / encoding.scale();
    else {
      double scale =
          Math.max(
              Math.abs(settings.threshold),
              Math.max(Math.abs(encoding.offset()), encoding.scale()));
      threshold =
          (settings.threshold / scale - encoding.offset() / scale) / (encoding.scale() / scale);
    }
    return new VolumeTransfer(
        finite(gain),
        finite(bias),
        finite(threshold),
        (float) Math.min(10, settings.opacity),
        settings.steps);
  }

  private static float finite(double value) {
    if (Double.isNaN(value)) throw new IllegalArgumentException("Indeterminate volume transfer");
    return (float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, value));
  }

  public float mapped(float encoded) {
    return Math.max(0, Math.min(1, encoded * gain + bias));
  }
}
