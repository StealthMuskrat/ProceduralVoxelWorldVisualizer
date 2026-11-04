package io.voxelcreator.editor;

import java.util.Objects;

/** Retained exact rate text; only complete bounded integers change the live preference. */
final class RegenerationRateInput {
  static final int MINIMUM = 1, MAXIMUM = 20, MAX_TEXT = 32;
  private UserConfig.Editor owner;
  private int accepted;
  private String text = "";
  private long version;
  private boolean active;

  void synchronize(UserConfig.Editor current) {
    Objects.requireNonNull(current);
    if (owner != current || accepted != current.opportunitiesPerSecond) {
      owner = current;
      accepted = current.opportunitiesPerSecond;
      text = Integer.toString(accepted);
      version++;
    }
    if (!active) {
      active = true;
      version++;
    }
  }

  boolean current(UserConfig.Editor current) {
    return active && owner == current && accepted == current.opportunitiesPerSecond;
  }

  boolean edit(UserConfig.Editor current, String raw) {
    if (!current(current)) throw new IllegalStateException("Regeneration rate owner changed");
    Objects.requireNonNull(raw);
    if (raw.length() > MAX_TEXT) throw new IllegalArgumentException("Regeneration rate text limit");
    text = raw;
    try {
      accepted = parse(raw);
      owner.opportunitiesPerSecond = accepted;
      return true;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  void restore(UserConfig.Editor current) {
    if (!current(current)) throw new IllegalStateException("Regeneration rate owner changed");
    text = Integer.toString(accepted);
    version++;
  }

  void suspend() {
    active = false;
  }

  String text() {
    return text;
  }

  int value() {
    return accepted;
  }

  long version() {
    return version;
  }

  String problem() {
    try {
      parse(text);
      return "";
    } catch (IllegalArgumentException invalid) {
      return invalid.getMessage();
    }
  }

  static int parse(String raw) {
    if (raw == null || raw.length() > MAX_TEXT || !raw.matches("[+-]?[0-9]+"))
      throw new IllegalArgumentException("Enter a whole decimal rate from 1 to 20.");
    try {
      int value = Integer.parseInt(raw);
      if (value >= MINIMUM && value <= MAXIMUM) return value;
    } catch (NumberFormatException invalid) {
      /* Keep rejected text and the last active rate. */
    }
    throw new IllegalArgumentException("Regeneration rate must be from 1 to 20.");
  }
}
