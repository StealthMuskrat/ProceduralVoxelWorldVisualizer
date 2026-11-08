package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MembershipHistoryTest {
  @Test
  void retentionDropsOldStepsButKeepsTheLatestOversizedBulkActionUndoable() {
    var history = new MembershipHistory();
    var values = new ArrayList<UUID>();
    for (int i = 0; i < 105; i++) {
      var before = List.copyOf(values);
      values.add(new UUID(0, i + 1));
      history.record(before, values);
    }
    int undone = 0;
    while (history.undo(values)) undone++;
    assertEquals(100, undone);
    assertEquals(5, values.size());
    while (history.redo(values)) {}
    assertEquals(105, values.size());
    history.clear();
    assertFalse(history.canUndo());
    assertFalse(history.canRedo());

    var bulk = IntStream.range(0, 65_537).mapToObj(i -> new UUID(1, i)).toList();
    values = new ArrayList<>(bulk);
    history.record(List.of(), values);
    assertTrue(history.undo(values));
    assertTrue(values.isEmpty());
    assertTrue(history.redo(values));
    assertEquals(bulk, values);
    var before = List.copyOf(values);
    values.removeFirst();
    history.record(before, values);
    assertTrue(history.undo(values));
    assertEquals(bulk, values);
    assertFalse(history.canUndo());
  }

  @Test
  void removingAndRestoringPrivateDuplicateReferencesPreservesEveryOriginalPosition() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    var before = List.of(a, a, b, c);
    var values = new ArrayList<>(List.of(b));
    var history = new MembershipHistory();
    history.record(before, values);
    assertTrue(history.undo(values));
    assertEquals(before, values);
    assertTrue(history.redo(values));
    assertEquals(List.of(b), values);
  }
}
