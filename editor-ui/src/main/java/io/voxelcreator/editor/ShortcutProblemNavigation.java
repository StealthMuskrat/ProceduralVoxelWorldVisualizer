package io.voxelcreator.editor;

import java.util.*;

/** Finds hidden/mismatched binding text without normalizing or replacing either snapshot. */
final class ShortcutProblemNavigation {
  private ShortcutProblemNavigation() {}

  static String first(
      Map<String, String> owned, Map<String, String> displayed, List<Shortcuts.Problem> problems) {
    for (var action : Shortcuts.actions())
      if (!Objects.equals(owned.get(action.id()), displayed.get(action.id()))) return action.id();
    for (var action : Shortcuts.actions())
      if (problems.stream().anyMatch(problem -> action.id().equals(problem.action())))
        return action.id();
    return null;
  }
}
