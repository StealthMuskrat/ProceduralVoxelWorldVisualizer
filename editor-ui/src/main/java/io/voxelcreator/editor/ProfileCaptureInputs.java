package io.voxelcreator.editor;

import io.voxelcreator.api.Profiler;
import java.util.*;

/** Exact, bounded next-capture text; active captures retain their immutable options. */
final class ProfileCaptureInputs {
  static final int MAX_TEXT = 128;

  enum Field {
    DURATION("Duration (ms)", 100, 60000, 10000),
    SAMPLES("Maximum samples", 64, 65536, 8192),
    SERIES("Maximum series", 1, 1024, 128),
    STRIDE("Operation sampling stride", 1, 65536, 256);
    final String label;
    final int minimum, maximum, initial;

    Field(String label, int minimum, int maximum, int initial) {
      this.label = label;
      this.minimum = minimum;
      this.maximum = maximum;
      this.initial = initial;
    }
  }

  record Feedback(Profiler.Options options, Map<Field, String> problems) {
    Feedback {
      problems = Map.copyOf(problems);
    }
  }

  private final Map<Field, String> text = new EnumMap<>(Field.class);
  private boolean work;
  private Feedback cached;

  ProfileCaptureInputs() {
    for (var field : Field.values()) text.put(field, Integer.toString(field.initial));
  }

  Map<Field, String> text() {
    return Map.copyOf(text);
  }

  void edit(Field field, String value) {
    Objects.requireNonNull(field);
    Objects.requireNonNull(value);
    if (value.length() > MAX_TEXT)
      throw new IllegalArgumentException("Capture input text limit " + MAX_TEXT);
    if (!value.equals(text.get(field))) {
      text.put(field, value);
      cached = null;
    }
  }

  boolean work() {
    return work;
  }

  void work(boolean value) {
    if (work != value) {
      work = value;
      cached = null;
    }
  }

  Feedback feedback() {
    if (cached != null) return cached;
    var problems = new EnumMap<Field, String>(Field.class);
    var values = new EnumMap<Field, Integer>(Field.class);
    for (var field : Field.values()) {
      try {
        String raw = text.get(field);
        if (!raw.matches("[+-]?[0-9]+")) throw new NumberFormatException();
        int value = Integer.parseInt(raw);
        if (value < field.minimum || value > field.maximum) throw new NumberFormatException();
        values.put(field, value);
      } catch (NumberFormatException invalid) {
        problems.put(field, "Whole decimal integer: " + field.minimum + ".." + field.maximum);
      }
    }
    var options =
        problems.isEmpty()
            ? new Profiler.Options(
                values.get(Field.DURATION),
                values.get(Field.SAMPLES),
                values.get(Field.SERIES),
                values.get(Field.STRIDE),
                work)
            : null;
    return cached = new Feedback(options, problems);
  }

  Profiler.Options request(Map<Field, String> displayed) {
    if (!displayed.keySet().equals(text.keySet()))
      throw new IllegalStateException("All capture fields are required");
    for (var field : Field.values())
      if (!text.get(field).equals(displayed.get(field)))
        throw new IllegalStateException("Displayed " + field.label + " differs from private input");
    var result = feedback();
    if (result.options() == null)
      throw new IllegalStateException("Correct invalid capture limits before starting");
    return result.options();
  }

  Field firstProblem(Map<Field, String> displayed) {
    for (var field : Field.values())
      if (!Objects.equals(text.get(field), displayed.get(field))) return field;
    for (var field : Field.values()) if (feedback().problems().containsKey(field)) return field;
    return null;
  }
}
