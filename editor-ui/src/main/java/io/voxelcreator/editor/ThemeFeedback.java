package io.voxelcreator.editor;

import io.voxelcreator.editor.ThemePalette.Rgba;
import java.util.*;
import java.util.function.Function;

/** One immutable resolved palette/audit, reused until authored palette values change. */
final class ThemeFeedback {
  private final Function<ThemePalette, Map<Integer, Rgba>> resolve;
  private ThemePalette palette;
  private Map<Integer, Rgba> colors;
  private List<ThemeContrast.Check> checks;
  private long evaluations;

  ThemeFeedback(Function<ThemePalette, Map<Integer, Rgba>> resolve) {
    this.resolve = Objects.requireNonNull(resolve);
  }

  void update(ThemePalette next) {
    if (next.equals(palette)) return;
    var resolved = Map.copyOf(resolve.apply(next));
    var audited = ThemeContrast.audit(resolved, next);
    palette = next;
    colors = resolved;
    checks = audited;
    evaluations++;
  }

  ThemePalette palette() {
    return palette;
  }

  Map<Integer, Rgba> colors() {
    return colors;
  }

  List<ThemeContrast.Check> checks() {
    return checks;
  }

  long evaluations() {
    return evaluations;
  }
}
