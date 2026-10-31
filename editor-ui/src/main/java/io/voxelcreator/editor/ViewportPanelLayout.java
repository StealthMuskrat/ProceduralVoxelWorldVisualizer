package io.voxelcreator.editor;

/** Finite view budgets: scroll controls without consuming the entire preview surface. */
final class ViewportPanelLayout {
  record Split(float controls, float preview, float gap) {}

  record Surface(float histogram, float histogramGap, float image, float status, float statusGap) {}

  static Split split(float height, float content, float line, float padding, float gap) {
    finite(height);
    nonnegative(content);
    positive(line);
    nonnegative(padding);
    nonnegative(gap);
    double available = Math.max(2, height),
        spacing = Math.min(gap, available - 2),
        usable = available - spacing;
    double cap = Math.min(usable - 1, .6 * usable);
    double minimum = Math.min(cap, Math.max(1, 2.0 * line + 2.0 * padding));
    double wanted = content > 0 ? content : 8.0 * line + 2.0 * padding;
    double controls = Math.max(1, Math.min(cap, Math.max(minimum, wanted)));
    return new Split((float) controls, (float) (usable - controls), (float) spacing);
  }

  static Surface surface(float height, float line, float gap, boolean histogram, boolean status) {
    finite(height);
    positive(line);
    nonnegative(gap);
    double available = Math.max(1, height),
        footer = status ? Math.min(line, Math.max(0, available - 1)) : 0;
    double footerGap = footer > 0 ? Math.min(gap, available - footer - 1) : 0;
    double usable = available - footer - footerGap;
    double plot = histogram ? Math.min(6.0 * line, Math.max(0, (usable - 1) * .25)) : 0;
    double plotGap = plot > 0 ? Math.min(gap, usable - plot - 1) : 0;
    return new Surface(
        (float) plot,
        (float) plotGap,
        (float) Math.max(1, usable - plot - plotGap),
        (float) footer,
        (float) footerGap);
  }

  private static void finite(float value) {
    if (!Float.isFinite(value)) throw new IllegalArgumentException("Nonfinite viewport metric");
  }

  private static void nonnegative(float value) {
    finite(value);
    if (value < 0) throw new IllegalArgumentException("Negative viewport metric");
  }

  private static void positive(float value) {
    finite(value);
    if (value <= 0) throw new IllegalArgumentException("Nonpositive viewport metric");
  }

  private ViewportPanelLayout() {}
}
