package io.voxelcreator.editor;

import java.util.Locale;

/** Presentation only: never parse a readout back into an authored or sampled value. */
final class ScalarDisplay {
  private static final double SMALL = 1e-5, LARGE = 1e7;

  private ScalarDisplay() {}

  static String sliderFormat(double value) {
    double magnitude = Math.abs(value);
    return Double.isFinite(value) && magnitude != 0 && (magnitude < SMALL || magnitude >= LARGE)
        ? "%.5e"
        : "%.5f";
  }

  static String compact(double value) {
    if (!Double.isFinite(value)) return exact(value);
    return trim(String.format(Locale.ROOT, sliderFormat(value), value));
  }

  /** Keep the whole signed voxel-coordinate part visible, including near the int boundaries. */
  static String coordinate(double value) {
    if (Double.isFinite(value)
        && Math.abs(value) >= SMALL
        && value >= Integer.MIN_VALUE
        && value <= (double) Integer.MAX_VALUE + 1)
      return trim(String.format(Locale.ROOT, "%.5f", value));
    return compact(value);
  }

  static String exact(double value) {
    return Double.toString(value);
  }

  private static String trim(String text) {
    int exponent = text.indexOf('e');
    String mantissa = exponent < 0 ? text : text.substring(0, exponent);
    int end = mantissa.length();
    while (end > 0 && mantissa.charAt(end - 1) == '0') end--;
    if (end > 0 && mantissa.charAt(end - 1) == '.') end--;
    mantissa = mantissa.substring(0, end);
    if (exponent < 0) return mantissa;
    int power = Integer.parseInt(text.substring(exponent + 1));
    return mantissa + "e" + (power >= 0 ? "+" : "") + power;
  }
}
