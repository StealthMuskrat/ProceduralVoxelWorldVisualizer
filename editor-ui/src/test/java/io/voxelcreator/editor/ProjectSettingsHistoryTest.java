package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ProjectSettingsHistoryTest {
  @Test
  void gestureCoalescingAndNetZeroChangesPreserveExactRawTextAndRedo() {
    var history = new TextEditHistory();
    history.record("seed", "0", "-", "seed:text");
    history.record("seed", "-", "-0012", "seed:text");
    assertEquals(new TextEditHistory.Change("seed", "0", "-0012"), history.move(false));
    assertTrue(history.canRedo());
    history.record("seed", "0", "+", "seed:text");
    history.record("seed", "+", "0", "seed:text");
    history.finish();
    assertFalse(history.canUndo());
    assertTrue(history.canRedo());
    assertEquals("-0012", history.move(true).after());
    history.focus("seed:slider");
    history.record("seed", "-0012", "-11", "seed:slider");
    history.record("seed", "-11", "-10", "seed:slider");
    history.focus("minY:text");
    assertEquals(new TextEditHistory.Change("seed", "-0012", "-10"), history.move(false));
    assertEquals("0", history.move(false).before());
    history.clear();
    assertFalse(history.canUndo());
    assertFalse(history.canRedo());
  }

  @Test
  void onlyOneHundredCompletedActionsAreRetainedAndAnEffectiveBranchDiscardsRedo() {
    var history = new TextEditHistory();
    for (int i = 0; i < 105; i++)
      history.record("seed", Integer.toString(i), Integer.toString(i + 1), null);
    int count = 0;
    String value = "105";
    while (history.canUndo()) {
      value = history.move(false).before();
      count++;
    }
    assertEquals(100, count);
    assertEquals("5", value);
    assertNull(history.move(false));
    assertEquals("6", history.move(true).after());
    history.record("minY", "-64", "-2", "minY:text");
    assertFalse(history.canRedo());
    history.finish();
    assertNull(history.move(true));
  }

  @Test
  void sharedTextBudgetRetainsNewestOversizedEditAndReclaimsDiscardedRedo() {
    var history = new TextEditHistory();
    String large = "é".repeat(40000);
    history.record("Name", "original", large, null);
    history.record("Name", large, large + "next", null);
    assertEquals(large, history.move(false).before());
    assertFalse(history.canUndo());
    assertTrue(history.canRedo());
    history.record("X", "1", "2", null);
    history.record("Z", "3", "4", null);
    assertFalse(history.canRedo());
    assertEquals("3", history.move(false).before());
    assertEquals("1", history.move(false).before());
    history.clear();
    for (int i = 0; i < 100; i++) history.record("X", "a", "b", null);
    int retained = 0;
    while (history.move(false) != null) retained++;
    assertEquals(100, retained);
  }
}
