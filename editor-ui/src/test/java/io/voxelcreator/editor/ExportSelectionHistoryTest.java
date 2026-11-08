package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ExportSelectionHistoryTest {
  @Test
  void deltasOwnTheirInputsAndNoopsPreserveRedoWhileEffectiveChangesBranch() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    var history = new ExportSelectionHistory();
    var before = new HashSet<>(Set.of(a, b));
    var after = new HashSet<>(Set.of(b, c));
    history.record(before, after);
    before.clear();
    after.clear();
    var restored = history.move(Set.of(b, c), false);
    assertEquals(Set.of(a, b), restored);
    assertThrows(UnsupportedOperationException.class, restored::clear);
    history.record(restored, Set.of(a, b));
    assertTrue(history.canRedo());
    assertEquals(Set.of(b, c), history.move(restored, true));
    assertEquals(Set.of(a, b), history.move(Set.of(b, c), false));
    history.record(Set.of(a, b), Set.of(a));
    assertFalse(history.canRedo());
    assertNull(history.move(Set.of(a), true));
    history.clear();
    assertFalse(history.canUndo());
    assertFalse(history.canRedo());
  }

  @Test
  void stepAndReferenceBudgetsRetainTheNewestOversizedSelectionAsOneAction() {
    var history = new ExportSelectionHistory();
    Set<UUID> values = Set.of();
    for (int i = 0; i < 105; i++) {
      var next = new HashSet<>(values);
      next.add(new UUID(0, i + 1));
      history.record(values, next);
      values = Set.copyOf(next);
    }
    int undone = 0;
    while (history.canUndo()) {
      values = history.move(values, false);
      undone++;
    }
    assertEquals(100, undone);
    assertEquals(5, values.size());
    while (history.canRedo()) values = history.move(values, true);
    assertEquals(105, values.size());
    history.clear();
    var bulk = IntStream.range(0, 65_537).mapToObj(i -> new UUID(1, i)).collect(Collectors.toSet());
    history.record(Set.of(), bulk);
    assertEquals(Set.of(), history.move(bulk, false));
    assertEquals(bulk, history.move(Set.of(), true));
    var smaller = new HashSet<>(bulk);
    smaller.remove(new UUID(1, 0));
    history.record(bulk, smaller);
    assertEquals(bulk, history.move(smaller, false));
    assertFalse(history.canUndo());
  }
}
