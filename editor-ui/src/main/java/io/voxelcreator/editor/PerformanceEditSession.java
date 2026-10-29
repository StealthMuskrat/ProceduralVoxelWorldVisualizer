package io.voxelcreator.editor;

import static io.voxelcreator.editor.PerformancePreferences.*;

import java.nio.file.Path;
import java.util.*;

/** UI-thread private exact numeric text; publication requires one complete displayed candidate. */
final class PerformanceEditSession {
  static final int MAX_TEXT = 128;

  record Feedback(Values value, Map<Field, String> problems) {
    Feedback {
      var copy = new EnumMap<Field, String>(Field.class);
      copy.putAll(problems);
      problems = Collections.unmodifiableMap(copy);
    }
  }

  private Values base;
  private Map<Field, String> text;
  private Feedback feedback;
  private long validations;
  private boolean applying;

  PerformanceEditSession(UserConfig user) {
    revert(user);
  }

  Map<Field, String> text() {
    return text;
  }

  long validations() {
    return validations;
  }

  void edit(Field field, String raw) {
    requireIdle();
    Objects.requireNonNull(field);
    Objects.requireNonNull(raw);
    if (raw.length() > MAX_TEXT)
      throw new IllegalArgumentException("Performance input exceeds 128 characters");
    if (raw.equals(text.get(field))) return;
    var next = new EnumMap<Field, String>(Field.class);
    next.putAll(text);
    next.put(field, raw);
    text = Collections.unmodifiableMap(next);
    feedback = null;
  }

  Feedback feedback() {
    if (feedback != null) return feedback;
    validations++;
    var values = new EnumMap<Field, Long>(Field.class);
    var problems = new EnumMap<Field, String>(Field.class);
    for (var field : Field.values()) {
      try {
        String raw = text.get(field);
        if (!raw.matches("[+-]?[0-9]+"))
          throw new IllegalArgumentException("Enter a whole decimal integer");
        long value;
        try {
          value = Long.parseLong(raw);
        } catch (NumberFormatException overflow) {
          throw new IllegalArgumentException("Integer is outside the signed 64-bit range");
        }
        field.validate(value);
        values.put(field, value);
      } catch (IllegalArgumentException invalid) {
        problems.put(field, invalid.getMessage());
      }
    }
    if (values.containsKey(Field.DEFAULT_EXTENT)
        && values.containsKey(Field.MAX_DIMENSION)
        && values.get(Field.DEFAULT_EXTENT) > values.get(Field.MAX_DIMENSION))
      problems.put(
          Field.DEFAULT_EXTENT, "Default preview extent exceeds Maximum preview dimension");
    feedback = new Feedback(problems.isEmpty() ? new Values(values) : null, problems);
    return feedback;
  }

  boolean current(UserConfig user) {
    try {
      return base.equals(PerformancePreferences.from(user));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  boolean changed() {
    for (var field : Field.values())
      if (!text.get(field).equals(Long.toString(base.get(field)))) return true;
    return false;
  }

  void requireDisplayed(Map<Field, String> displayed) {
    if (!text.equals(displayed))
      throw new IllegalStateException("Displayed performance fields differ from the private draft");
  }

  void apply(UserConfig user, Path file, Map<Field, String> displayed) {
    requireIdle();
    requireDisplayed(displayed);
    if (!current(user))
      throw new IllegalStateException("Active performance settings changed; Revert to reload them");
    var checked = feedback();
    if (checked.value() == null)
      throw new IllegalArgumentException("Correct the marked performance fields before applying");
    if (!changed()) return;
    applying = true;
    try {
      user.applyPerformance(checked.value(), file);
      reload(user);
    } finally {
      applying = false;
    }
  }

  void revert(UserConfig user) {
    requireIdle();
    reload(user);
  }

  private void reload(UserConfig user) {
    var next = PerformancePreferences.from(user);
    var raw = new EnumMap<Field, String>(Field.class);
    for (var field : Field.values()) raw.put(field, Long.toString(next.get(field)));
    base = next;
    text = Collections.unmodifiableMap(raw);
    feedback = null;
  }

  private void requireIdle() {
    if (applying) throw new IllegalStateException("Performance settings are being saved");
  }
}
