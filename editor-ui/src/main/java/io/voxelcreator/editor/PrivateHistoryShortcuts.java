package io.voxelcreator.editor;

import java.util.*;
import java.util.function.Predicate;

/** A focused private modal owns existing Undo/Redo bindings while native input keeps its keys. */
final class PrivateHistoryShortcuts {
  private PrivateHistoryShortcuts() {}

  static String match(
      Map<String, String> bindings,
      boolean focused,
      boolean editing,
      int modifiers,
      Predicate<String> pressed) {
    if (!focused || editing) return null;
    for (String action : List.of("Undo", "Redo")) {
      try {
        var chord = Shortcuts.parse(bindings.getOrDefault(action, ""));
        if (chord.isPresent()
            && chord.get().modifiers() == modifiers
            && pressed.test(chord.get().key())) return action;
      } catch (IllegalArgumentException invalid) {
        // An invalid applied binding must not trap the private editor.
      }
    }
    return null;
  }
}
