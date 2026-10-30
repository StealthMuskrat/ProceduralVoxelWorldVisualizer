package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class UiScaleProblemNavigationTest {
  private UiScaleEditSession.Text text(
      String global, Map<String, String> overrides, Set<String> enabled) {
    var windows = new LinkedHashMap<String, String>();
    UiScalePreferences.WINDOWS.forEach(name -> windows.put(name, "1.0"));
    windows.putAll(overrides);
    return new UiScaleEditSession.Text(global, windows, enabled);
  }

  @Test
  void equalDisplayDoesNotParseOrNormalizeAnyRawString() {
    var raw = text("+1.2500", Map.of("Help", "-"), Set.of());
    assertNull(UiScaleProblemNavigation.first(raw, raw, Map.of()));
    assertEquals("+1.2500", raw.global());
    assertEquals("-", raw.windows().get("Help"));
  }

  @Test
  void globalDisplayMismatchTakesPriorityOverHiddenFeedback() {
    var owned = text("1.0", Map.of("Help", "-"), Set.of("Help"));
    var displayed = text("1".repeat(129), Map.of("Help", "-"), Set.of("Help"));
    assertEquals(
        UiScaleEditSession.GLOBAL,
        UiScaleProblemNavigation.first(owned, displayed, Map.of("Help", "Invalid scale")));
    assertEquals("1.0", owned.global());
    assertEquals(129, displayed.global().length());
  }

  @Test
  void dormantMismatchedTextCanBeRevealedWithoutEnablingAnOverride() {
    var owned = text("1.0", Map.of(), Set.of());
    var displayed = text("1.0", Map.of("Help", "1".repeat(129)), Set.of());
    assertEquals("Help", UiScaleProblemNavigation.first(owned, displayed, Map.of()));
    assertTrue(owned.enabled().isEmpty());
    assertTrue(displayed.enabled().isEmpty());
    assertEquals("1.0", owned.windows().get("Help"));
    assertEquals(129, displayed.windows().get("Help").length());
  }

  @Test
  void flagMismatchUsesCatalogOrderWithoutChangingEntryPresence() {
    var owned = text("1.0", Map.of(), Set.of());
    var displayed = text("1.0", Map.of(), Set.of("Logs", "Resources"));
    assertEquals("Resources", UiScaleProblemNavigation.first(owned, displayed, Map.of()));
    assertTrue(owned.enabled().isEmpty());
    assertEquals(Set.of("Logs", "Resources"), displayed.enabled());
  }

  @Test
  void feedbackDestinationsUseGlobalThenCatalogOrderRatherThanMapOrder() {
    var raw = text("-", Map.of("Help", "-", "Logs", "-"), Set.of("Help", "Logs"));
    var problems = new LinkedHashMap<String, String>();
    problems.put("Help", "Invalid scale");
    problems.put("Logs", "Invalid scale");
    assertEquals("Logs", UiScaleProblemNavigation.first(raw, raw, problems));
    problems.put(UiScaleEditSession.GLOBAL, "Invalid scale");
    assertEquals(UiScaleEditSession.GLOBAL, UiScaleProblemNavigation.first(raw, raw, problems));
    assertEquals("-", raw.windows().get("Help"));
    assertEquals(Set.of("Help", "Logs"), raw.enabled());
  }
}
