package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ShortcutProblemNavigationTest {
  @Test
  void equalRawTextIsNotParsedNormalizedOrReplaced() {
    var raw = new LinkedHashMap<>(Shortcuts.defaults());
    raw.put("Find", "Ctrl+");
    assertNull(ShortcutProblemNavigation.first(raw, raw, List.of()));
    assertEquals("Ctrl+", raw.get("Find"));
  }

  @Test
  void oversizedNativeMismatchPrecedesAnOwnedValidationProblemWithoutPublishingIt() {
    var owned = new LinkedHashMap<>(Shortcuts.defaults());
    owned.put("Find", "Ctrl+");
    var displayed = new LinkedHashMap<>(owned);
    displayed.put("Save", "x".repeat(257));
    assertEquals(
        "Save", ShortcutProblemNavigation.first(owned, displayed, Shortcuts.problems(owned)));
    assertEquals("Ctrl+S", owned.get("Save"));
    assertEquals(257, displayed.get("Save").length());
  }

  @Test
  void problemOrderFollowsTheActionRegistryAndDoesNotRepairTheDraft() {
    var raw = new LinkedHashMap<>(Shortcuts.defaults());
    raw.put("Find", "Ctrl+");
    raw.put("Open", "Ctrl+");
    var problems = new ArrayList<>(Shortcuts.problems(raw));
    Collections.reverse(problems);
    assertEquals("Open", ShortcutProblemNavigation.first(raw, raw, problems));
    assertEquals("Ctrl+", raw.get("Open"));
    assertEquals("Ctrl+", raw.get("Find"));
  }
}
