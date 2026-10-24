package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ShortcutsTest {
  private static Shortcuts.Context context(Shortcuts.Focus focus) {
    return new Shortcuts.Context(focus, false, false, false, false);
  }

  private static String match(
      Map<String, String> bindings, Shortcuts.Focus focus, int mods, String key) {
    return Shortcuts.match(bindings, context(focus), mods, key::equals)
        .map(Shortcuts.Action::id)
        .orElse(null);
  }

  @Test
  void canonicalizationIsCaseInsensitiveAndModifierOrderIndependent() {
    var c = Shortcuts.parse(" super + ALT + ctrl + shift + f12 ").orElseThrow();
    assertEquals(15, c.modifiers());
    assertEquals("Ctrl+Shift+Alt+Super+F12", c.text());
    assertEquals(c, Shortcuts.parse(c.text()).orElseThrow());
    assertTrue(Shortcuts.parse("  ").isEmpty());
  }

  @Test
  void everyAdvertisedPhysicalKeyRoundTripsAndMapsToOneNativeKey() {
    var seen = new HashSet<Integer>();
    for (String key : Shortcuts.keys())
      for (int mods = 0; mods < 16; mods++) {
        var chord = new Shortcuts.Chord(mods, key);
        assertEquals(chord, Shortcuts.parse(chord.text()).orElseThrow());
        if (mods == 0) assertTrue(seen.add(ShortcutKeys.code(key)), key);
      }
    assertEquals(Shortcuts.keys().size(), seen.size());
  }

  @Test
  void malformedOrReservedChordsRejectWithoutSilentlyDroppingModifiers() {
    for (String value :
        List.of(
            "Ctrl++S",
            "Ctrl+Ctrl+S",
            "Shift+Shift+A",
            "S+Ctrl",
            "Control+S",
            "Meta+S",
            "Ctrl",
            "Escape",
            "Ctrl+Escape",
            "F13",
            "Keypad10",
            "Ctrl+?",
            "Ctrl+",
            "+A",
            "A B",
            "A".repeat(81)))
      assertThrows(IllegalArgumentException.class, () -> Shortcuts.parse(value), value);
    assertThrows(IllegalArgumentException.class, () -> Shortcuts.parse(null));
    assertThrows(IllegalArgumentException.class, () -> new Shortcuts.Chord(16, "A"));
  }

  @Test
  void defaultsAreConflictFreeAndDefensive() {
    var defaults = Shortcuts.defaults();
    assertTrue(Shortcuts.problems(defaults).isEmpty());
    assertEquals(24, defaults.size());
    defaults.put("Save", "");
    assertEquals("Ctrl+S", Shortcuts.defaults().get("Save"));
    assertThrows(UnsupportedOperationException.class, () -> Shortcuts.actions().clear());
    assertThrows(
        UnsupportedOperationException.class,
        () -> Shortcuts.compile(Shortcuts.defaults()).values().clear());
  }

  @Test
  void allModifierCombinationsRequireExactEquality() {
    for (int expected = 0; expected < 16; expected++) {
      var bindings = new LinkedHashMap<String, String>();
      bindings.put("Save", new Shortcuts.Chord(expected, "F6").text());
      for (int actual = 0; actual < 16; actual++)
        assertEquals(
            actual == expected ? "Save" : null,
            match(bindings, Shortcuts.Focus.GENERAL, actual, "F6"));
    }
    assertEquals("SaveAs", match(Shortcuts.defaults(), Shortcuts.Focus.GENERAL, 3, "S"));
    assertNull(match(Shortcuts.defaults(), Shortcuts.Focus.GENERAL, 5, "S"));
  }

  @Test
  void documentResourceAndCurveContextsResolveOnlyTheirEligibleCommands() {
    var bindings = Shortcuts.defaults();
    assertEquals("Copy", match(bindings, Shortcuts.Focus.RESOURCES, 1, "C"));
    assertNull(match(bindings, Shortcuts.Focus.GENERAL, 1, "C"));
    assertEquals("Undo", match(bindings, Shortcuts.Focus.RESOURCES, 1, "Z"));
    assertEquals("CurveUndo", match(bindings, Shortcuts.Focus.CURVE, 1, "Z"));
    assertEquals("Delete", match(bindings, Shortcuts.Focus.RESOURCES, 0, "Delete"));
    assertEquals("CurveDelete", match(bindings, Shortcuts.Focus.CURVE, 0, "Delete"));
    assertNull(match(bindings, Shortcuts.Focus.GENERAL, 0, "Delete"));
    assertNull(match(bindings, Shortcuts.Focus.FLYING, 1, "S"));
  }

  @Test
  void textPopupsActiveWidgetsRecordingAndFlyingSuppressEveryCommand() {
    for (var focus : Shortcuts.Focus.values())
      for (int mask = 1; mask < 16; mask++) {
        var blocked =
            new Shortcuts.Context(
                focus, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
        assertTrue(Shortcuts.match(Shortcuts.defaults(), blocked, 1, k -> true).isEmpty());
      }
    assertTrue(
        Shortcuts.compile(Shortcuts.defaults())
            .match(context(Shortcuts.Focus.GENERAL), 1, k -> false)
            .isEmpty());
  }

  @Test
  void conflictDetectionUsesCanonicalChordsAndOverlappingScopes() {
    var bindings = Shortcuts.defaults();
    bindings.put("Find", " ctrl + s ");
    assertEquals(
        Set.of("Save", "Find"),
        new HashSet<>(
            Shortcuts.problems(bindings).stream().map(Shortcuts.Problem::action).toList()));
    bindings = Shortcuts.defaults();
    bindings.put("Rename", "Ctrl+Z");
    assertFalse(Shortcuts.problems(bindings).isEmpty());
    bindings = Shortcuts.defaults();
    bindings.put("CurveDelete", "Ctrl+D");
    assertTrue(Shortcuts.problems(bindings).isEmpty());
    bindings.put("Help", "Ctrl+D");
    assertFalse(Shortcuts.problems(bindings).isEmpty());
  }

  @Test
  void unboundMissingAndUnknownActionsHaveExplicitBehavior() {
    var bindings = new LinkedHashMap<String, String>();
    bindings.put("Save", "");
    assertNull(match(bindings, Shortcuts.Focus.GENERAL, 1, "S"));
    assertEquals(24, Shortcuts.canonical(bindings).size());
    assertTrue(Shortcuts.canonical(bindings).values().stream().allMatch(String::isEmpty));
    bindings.put("Typo", "Ctrl+S");
    assertFalse(Shortcuts.problems(bindings).isEmpty());
    assertThrows(IllegalArgumentException.class, () -> Shortcuts.compile(bindings));
    assertFalse(Shortcuts.problems(null).isEmpty());
  }

  @Test
  void legacyUpgradePreservesAuthoredChoicesAndDisablesConflictingNewDefaults() {
    var old =
        new LinkedHashMap<>(
            Map.of("Save", "Ctrl+N", "Undo", "Ctrl+Z", "Redo", "Ctrl+Y", "Find", "Ctrl+F"));
    var upgraded = Shortcuts.upgrade(old);
    assertEquals("Ctrl+N", upgraded.get("Save"));
    assertEquals("", upgraded.get("New"));
    assertEquals("Ctrl+O", upgraded.get("Open"));
    assertEquals("Ctrl+Z", upgraded.get("CurveUndo"));
    assertTrue(Shortcuts.problems(upgraded).isEmpty());
    assertEquals(old.size(), 4);
    assertEquals(upgraded, Shortcuts.upgrade(upgraded));
  }

  @Test
  void historicalMissingCoreBindingsRemainUnboundAndAllAuthoredBindingsWin() {
    var old = Map.of("Find", "Ctrl+Shift+F", "Copy", "Ctrl+Shift+P");
    var upgraded = Shortcuts.upgrade(old);
    assertEquals("", upgraded.get("Save"));
    assertEquals("", upgraded.get("Undo"));
    assertEquals("", upgraded.get("FindReferences"));
    assertEquals("", upgraded.get("Profiler"));
    assertEquals("Ctrl+Shift+P", upgraded.get("Copy"));
    assertEquals("Ctrl+Shift+F", upgraded.get("Find"));
  }

  @Test
  void compiledBindingsAreImmutableSnapshotsAndOnlyOneActionIsSelectedPerFrame() {
    var bindings = Shortcuts.defaults();
    var compiled = Shortcuts.compile(bindings);
    bindings.put("Save", "");
    assertEquals(
        "Save",
        compiled.match(context(Shortcuts.Focus.GENERAL), 1, "S"::equals).orElseThrow().id());
    assertEquals(
        "New", compiled.match(context(Shortcuts.Focus.GENERAL), 1, k -> true).orElseThrow().id());
    for (var a : Shortcuts.Scope.values())
      for (var b : Shortcuts.Scope.values())
        assertEquals(Shortcuts.overlap(a, b), Shortcuts.overlap(b, a));
  }
}
