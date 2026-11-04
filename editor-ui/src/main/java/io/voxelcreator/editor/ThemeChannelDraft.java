package io.voxelcreator.editor;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Four bounded raw channels; a rejected edit never replaces the last valid preview color. */
final class ThemeChannelDraft {
  static final int MAX_TEXT = 128;
  private final String[] text = new String[4];
  private ThemePalette.Rgba expected;
  private long version;

  record Input(ThemeChannelDraft owner, long version, List<String> text) {}

  Input input() {
    return new Input(this, version, List.copyOf(Arrays.asList(text)));
  }

  boolean pristine() {
    if (version != 1) return false;
    var values = expected.array();
    for (int axis = 0; axis < 4; axis++)
      if (!text[axis].equals(Float.toString(values[axis]))) return false;
    return true;
  }

  ThemeChannelDraft(ThemePalette.Rgba color) {
    replace(color);
  }

  void synchronize(ThemePalette.Rgba color) {
    if (!expected.equals(color)) replace(color);
  }

  void replace(ThemePalette.Rgba color) {
    expected = Objects.requireNonNull(color);
    var components = color.array();
    for (int axis = 0; axis < 4; axis++) text[axis] = Float.toString(components[axis]);
    version++;
  }

  String text(int axis) {
    requireAxis(axis);
    return text[axis];
  }

  void edit(int axis, String raw) {
    requireAxis(axis);
    Objects.requireNonNull(raw);
    if (raw.length() > MAX_TEXT)
      throw new IllegalArgumentException("Theme channel input exceeds 128 characters");
    if (!raw.equals(text[axis])) {
      text[axis] = raw;
      version++;
    }
  }

  /** NaN is a local rejection marker consumed by ThemeColorIssues, never a palette value. */
  float[] candidate() {
    var values = new float[4];
    for (int axis = 0; axis < 4; axis++) {
      try {
        values[axis] = parse(text[axis]);
      } catch (IllegalArgumentException invalid) {
        values[axis] = Float.NaN;
      }
    }
    return values;
  }

  /** Call only after the complete valid candidate was accepted by the private theme session. */
  void accepted(ThemePalette.Rgba color) {
    if (!ThemePalette.Rgba.of(candidate()).equals(color))
      throw new IllegalArgumentException("Accepted theme color differs from displayed channels");
    expected = color;
  }

  static float parse(String raw) {
    Objects.requireNonNull(raw);
    if (raw.length() > MAX_TEXT
        || !raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
      throw new IllegalArgumentException("Enter a decimal channel from 0 to 1");
    double value;
    try {
      value = Double.parseDouble(raw);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Theme channel is not representable", invalid);
    }
    if (!Double.isFinite(value) || value < 0 || value > 1)
      throw new IllegalArgumentException("Theme channels must be from 0 to 1");
    return (float) value;
  }

  private static void requireAxis(int axis) {
    if (axis < 0 || axis > 3) throw new IllegalArgumentException("Unknown RGBA channel");
  }
}
