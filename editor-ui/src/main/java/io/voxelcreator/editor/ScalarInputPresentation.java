package io.voxelcreator.editor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded readout cache and ink geometry; no formatted string becomes an editable value. */
final class ScalarInputPresentation {
  static final int MAX_FIELDS = 512;
  static final String EXACT_FORMAT = "%.17g";

  record Ink(float x, float y, float width, float height) {
    boolean visible() {
      return width > 0 && height > 0;
    }
  }

  private record Readout(long bits, boolean coordinate, String text) {}

  private final Map<Integer, Readout> readouts = new LinkedHashMap<>(16, .75f, true);
  private long formats;

  String compact(int id, double value) {
    return readout(id, value, false);
  }

  String coordinate(int id, double value) {
    return readout(id, value, true);
  }

  private String readout(int id, double value, boolean coordinate) {
    long bits = Double.doubleToRawLongBits(value);
    var old = readouts.get(id);
    if (old != null && old.bits() == bits && old.coordinate() == coordinate) return old.text();
    String text = coordinate ? ScalarDisplay.coordinate(value) : ScalarDisplay.compact(value);
    formats++;
    readouts.put(id, new Readout(bits, coordinate, text));
    if (readouts.size() > MAX_FIELDS) readouts.remove(readouts.keySet().iterator().next());
    return text;
  }

  void clear() {
    readouts.clear();
  }

  int size() {
    return readouts.size();
  }

  long formats() {
    return formats;
  }

  static boolean compact(boolean allowed, int id, int activeId) {
    return allowed && id != activeId;
  }

  static String visibleLabel(String label) {
    int hidden = Objects.requireNonNull(label).indexOf("##");
    return hidden < 0 ? label : label.substring(0, hidden);
  }

  static Ink ink(
      DialogBounds.Rect item, float frameWidth, float frameHeight, float paddingX, float paddingY) {
    if (!Float.isFinite(frameWidth)
        || !Float.isFinite(frameHeight)
        || !Float.isFinite(paddingX)
        || !Float.isFinite(paddingY)
        || frameWidth <= 0
        || frameHeight <= 0
        || paddingX < 0
        || paddingY < 0) throw new IllegalArgumentException("Invalid scalar input ink metrics");
    float width = Math.min(frameWidth, item.width()), height = Math.min(frameHeight, item.height());
    return new Ink(
        item.x() + Math.min(paddingX, width),
        item.y() + Math.min(paddingY, height),
        (float) Math.max(0, width - 2 * (double) paddingX),
        (float) Math.max(0, height - 2 * (double) paddingY));
  }
}
