package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import java.util.*;
import org.junit.jupiter.api.Test;

class PinBrowserTest {
  private final Noise noise = new Noise();
  private final PinBrowser browser = new PinBrowser();

  private Pin pin(String label, String group) {
    var pin = new Pin();
    pin.targetUuid = UUID.randomUUID();
    pin.label = label;
    pin.group = group;
    noise.pinnedControls.add(pin);
    return pin;
  }

  @Test
  void reorderingKeepsTheExactSelectedPinAndItsTransientToken() {
    var first = pin("same", "A");
    var second = pin("same", "B");
    browser.synchronize(noise, 0);
    assertSame(first, browser.selected());
    String key = browser.rows().get(1).key();
    assertTrue(browser.choose(key));
    assertTrue(browser.move(-1));
    browser.synchronize(noise, 1);
    assertSame(second, browser.selected());
    assertEquals(key, browser.token());
    assertSame(second, noise.pinnedControls.getFirst());
    assertFalse(browser.move(-1));
    assertTrue(browser.move(1));
    browser.synchronize(noise, 2);
    assertSame(second, browser.selected());
    assertEquals(key, browser.token());
    assertThrows(IllegalArgumentException.class, () -> browser.move(0));
  }

  @Test
  void groupFilteringIsReadOnlyAndDoesNotReplaceAHiddenSelectedPin() {
    var selected = pin("keep", "Common");
    pin("other", "Other");
    pin("empty", "");
    var before = ProjectIO.JSON.valueToTree(noise);
    browser.synchronize(noise, 0);
    assertTrue(browser.showGroup("group:Other"));
    assertSame(selected, browser.selected());
    assertNull(browser.visibleToken());
    assertEquals(1, browser.rows().size());
    assertTrue(browser.showGroup("empty"));
    assertEquals("empty", browser.rows().getFirst().label());
    assertFalse(browser.showGroup("missing"));
    assertEquals(before, ProjectIO.JSON.valueToTree(noise));
    assertTrue(browser.showGroup("all"));
    assertNotNull(browser.visibleToken());
  }

  @Test
  void specialGroupNamesHaveDistinctKeysAndDeletedGroupsFallBackToAll() {
    var a = pin("a", "All groups");
    pin("b", "Ungrouped");
    pin("c", " ");
    browser.synchronize(noise, 0);
    assertEquals(4, browser.groups().size());
    assertTrue(browser.showGroup("group:All groups"));
    assertEquals("a", browser.rows().getFirst().label());
    a.group = "Renamed";
    browser.synchronize(noise, 1);
    assertEquals("all", browser.group());
    assertSame(a, browser.selected());
    assertTrue(browser.showGroup("group:Ungrouped"));
    assertEquals("b", browser.rows().getFirst().label());
  }

  @Test
  void draftReplacementWithTheSameUuidsCannotReuseOldTokensOrSelectionPositions() {
    pin("first", "A");
    pin("second", "B");
    browser.synchronize(noise, 0);
    String old = browser.rows().getLast().key();
    browser.choose(old);
    var replacement = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(noise), Noise.class);
    assertTrue(browser.synchronize(replacement, 0));
    assertSame(replacement.pinnedControls.getFirst(), browser.selected());
    assertFalse(browser.choose(old));
    assertNotEquals(old, browser.token());
  }

  @Test
  void removeChoosesTheActualNeighborAndStaleTokensCannotSelectRemovedObjects() {
    var first = pin("first", "");
    var second = pin("second", "");
    browser.synchronize(noise, 0);
    String old = browser.token();
    assertTrue(browser.remove());
    browser.synchronize(noise, 1);
    assertSame(second, browser.selected());
    assertFalse(browser.choose(old));
    assertTrue(browser.remove());
    browser.synchronize(noise, 2);
    assertNull(browser.selected());
    assertTrue(browser.rows().isEmpty());
    assertFalse(browser.remove());
    assertFalse(noise.pinnedControls.contains(first));
  }

  @Test
  void uniqueTargetNavigationDoesNotGuessBetweenDuplicateReferences() {
    var first = pin("first", "A");
    var second = pin("second", "B");
    browser.synchronize(noise, 0);
    assertTrue(browser.selectTarget(second.targetUuid));
    assertSame(second, browser.selected());
    assertEquals("group:B", browser.group());
    var duplicate = pin("duplicate", "C");
    duplicate.targetUuid = first.targetUuid;
    browser.synchronize(noise, 1);
    assertFalse(browser.selectTarget(first.targetUuid));
    assertSame(second, browser.selected());
    assertEquals("all", browser.group());
  }

  @Test
  void unchangedDraftVersionsReuseCataloguesAndMissingReferencesRemainRemovable() {
    var missing = pin("missing", "");
    missing.targetUuid = null;
    browser.synchronize(noise, 0);
    var rows = browser.rows();
    var groups = browser.groups();
    assertFalse(browser.synchronize(noise, 0));
    assertSame(rows, browser.rows());
    assertSame(groups, browser.groups());
    assertTrue(rows.getFirst().detail().contains("Missing control"));
    assertTrue(browser.remove());
  }

  @Test
  void navigationRecognizesPinKeysWithoutReplayingHistoricalArrayIndices() {
    UUID project = UUID.randomUUID(), owner = UUID.randomUUID(), control = UUID.randomUUID();
    assertEquals(
        "pin/label",
        PinBrowser.navigation(new EditorEvents.Target(project, owner, control, "pin", "", false)));
    assertEquals(
        "pin/group",
        PinBrowser.navigation(
            new EditorEvents.Target(project, owner, control, "pin/group", "", false)));
    assertEquals(
        "pins",
        PinBrowser.navigation(
            new EditorEvents.Target(
                project, owner, null, "label", "/noise2D/999/pinnedControls/777/label", false)));
    assertNull(
        PinBrowser.navigation(
            new EditorEvents.Target(
                project, owner, null, "label", "/voxelTypes/0/pinnedControls/0/label", false)));
    assertNull(
        PinBrowser.navigation(
            new EditorEvents.Target(project, owner, control, "parameters/value", "", false)));
  }
}
