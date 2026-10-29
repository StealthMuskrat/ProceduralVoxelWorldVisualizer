package io.voxelcreator.editor;

import java.nio.file.Path;
import java.util.*;

/** Complete bounded raw scale draft, independent of active native font/layout state. */
final class UiScaleEditSession {
  static final int MAX_TEXT = 128;
  static final String GLOBAL = "Global";

  record Text(String global, Map<String, String> windows, Set<String> enabled) {
    Text {
      Objects.requireNonNull(global);
      if (!windows.keySet().equals(new HashSet<>(UiScalePreferences.WINDOWS))
          || !UiScalePreferences.WINDOWS.containsAll(enabled))
        throw new IllegalArgumentException("Complete known scale fields are required");
      var copy = new LinkedHashMap<String, String>();
      for (var name : UiScalePreferences.WINDOWS)
        copy.put(name, Objects.requireNonNull(windows.get(name)));
      windows = Collections.unmodifiableMap(copy);
      enabled = Set.copyOf(enabled);
    }
  }

  record Feedback(UiScalePreferences.Values value, Map<String, String> problems) {
    Feedback {
      problems = Collections.unmodifiableMap(new LinkedHashMap<>(problems));
    }
  }

  private UiScalePreferences.Values base;
  private Text opening, text;
  private Feedback feedback;
  private long validations;
  private boolean applying;

  UiScaleEditSession(UserConfig user) {
    revert(user);
  }

  Text text() {
    return text;
  }

  long validations() {
    return validations;
  }

  void editGlobal(String raw) {
    edit(new Text(raw, text.windows(), text.enabled()));
  }

  void editWindow(String name, String raw) {
    if (!UiScalePreferences.WINDOWS.contains(name))
      throw new IllegalArgumentException("Unknown scale target");
    var next = new LinkedHashMap<>(text.windows());
    next.put(name, raw);
    edit(new Text(text.global(), next, text.enabled()));
  }

  void override(String name, boolean enabled) {
    if (!UiScalePreferences.WINDOWS.contains(name))
      throw new IllegalArgumentException("Unknown scale target");
    var next = new HashSet<>(text.enabled());
    if (enabled) next.add(name);
    else next.remove(name);
    edit(new Text(text.global(), text.windows(), next));
  }

  private void edit(Text next) {
    idle();
    if (next.global().length() > MAX_TEXT
        || next.windows().values().stream().anyMatch(raw -> raw.length() > MAX_TEXT))
      throw new IllegalArgumentException("Scale input exceeds 128 characters");
    if (next.equals(text)) return;
    text = next;
    feedback = null;
  }

  static float parse(String raw) {
    if (Objects.requireNonNull(raw).length() > MAX_TEXT)
      throw new IllegalArgumentException("Scale input exceeds 128 characters");
    if (!raw.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
      throw new IllegalArgumentException("Enter a decimal scale from 0.5 to 2.5");
    double value;
    try {
      value = Double.parseDouble(raw);
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException("Scale is not representable");
    }
    if (!Double.isFinite(value)
        || value < UiScalePreferences.MINIMUM
        || value > UiScalePreferences.MAXIMUM)
      throw new IllegalArgumentException("Scale must be from 0.5 to 2.5");
    float result = (float) value;
    UiScalePreferences.validateScale(result);
    return result;
  }

  Feedback feedback() {
    if (feedback != null) return feedback;
    validations++;
    var problems = new LinkedHashMap<String, String>();
    float global = 1;
    try {
      global = parse(text.global());
    } catch (IllegalArgumentException invalid) {
      problems.put(GLOBAL, invalid.getMessage());
    }
    var values = new LinkedHashMap<String, Float>();
    for (var name : UiScalePreferences.WINDOWS)
      if (text.enabled().contains(name)) {
        try {
          values.put(name, parse(text.windows().get(name)));
        } catch (IllegalArgumentException invalid) {
          problems.put(name, invalid.getMessage());
        }
      }
    feedback =
        new Feedback(
            problems.isEmpty() ? new UiScalePreferences.Values(global, values) : null, problems);
    return feedback;
  }

  boolean current(UserConfig user) {
    try {
      return base.equals(UiScalePreferences.from(user));
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  boolean changed() {
    return !opening.equals(text);
  }

  void requireDisplayed(Text displayed) {
    if (!text.equals(displayed))
      throw new IllegalStateException("Displayed scale fields differ from the private draft");
  }

  void apply(UserConfig user, Path file, Text displayed) {
    idle();
    requireDisplayed(displayed);
    if (!current(user))
      throw new IllegalStateException("Active scales changed; Revert to reload them");
    var checked = feedback();
    if (checked.value() == null)
      throw new IllegalArgumentException("Correct the marked scale fields before applying");
    if (!changed()) return;
    applying = true;
    try {
      user.applyScales(checked.value(), file);
      reload(user);
    } finally {
      applying = false;
    }
  }

  void revert(UserConfig user) {
    idle();
    reload(user);
  }

  private void reload(UserConfig user) {
    var next = UiScalePreferences.from(user);
    var windows = new LinkedHashMap<String, String>();
    for (var name : UiScalePreferences.WINDOWS)
      windows.put(name, Float.toString(next.overrides().getOrDefault(name, 1f)));
    base = next;
    opening = text = new Text(Float.toString(next.global()), windows, next.overrides().keySet());
    feedback = null;
  }

  private void idle() {
    if (applying) throw new IllegalStateException("UI scales are being saved");
  }
}
