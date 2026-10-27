package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class NoiseInputSelectionTest {
  private Noise noise() {
    var n = new Noise();
    for (int i = 0; i < 2; i++) {
      var item = ProcessingRegistry.create("EXPRESSION", "expression" + i);
      item.inputs.put("first", Binding.constant(1));
      item.inputs.put("second", Binding.constant(2));
      n.processing.add(item);
    }
    return n;
  }

  @Test
  void inputChoicesBelongToEachItemAndManualChoiceOverridesNavigation() {
    var n = noise();
    var selection = new NoiseInputSelection();
    var a = n.processing.getFirst();
    var b = n.processing.getLast();
    assertEquals("first", selection.current(n, a));
    assertTrue(selection.select(n, a, "second"));
    assertTrue(selection.select(n, b, "first"));
    assertEquals("second", selection.current(n, a));
    assertTrue(selection.select(n, a, "first"));
    for (int i = 0; i < 10; i++) assertEquals("first", selection.current(n, a));
    assertEquals("first", selection.current(n, b));
  }

  @Test
  void unavailableNamesAndForeignItemsDoNotReplaceAnOwnedChoice() {
    var n = noise();
    var selection = new NoiseInputSelection();
    var a = n.processing.getFirst();
    selection.select(n, a, "second");
    assertFalse(selection.select(n, a, "missing"));
    var foreign = noise().processing.getFirst();
    assertFalse(selection.select(n, foreign, "first"));
    assertNull(selection.current(n, foreign));
    assertEquals("second", selection.current(n, a));
  }

  @Test
  void draftReplacementWithTheSameUuidsCannotInheritOldInputSelection() {
    var n = noise();
    var selection = new NoiseInputSelection();
    var a = n.processing.getFirst();
    selection.select(n, a, "second");
    var replacement = ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(n), Noise.class);
    assertEquals(n.uuid, replacement.uuid);
    assertEquals("first", selection.current(replacement, replacement.processing.getFirst()));
  }

  @Test
  void removingOrRenamingAnInputFallsBackInAuthoredOrderWithoutChangingTheDraft() {
    var n = noise();
    var a = n.processing.getFirst();
    var selection = new NoiseInputSelection();
    selection.select(n, a, "second");
    a.inputs.remove("second");
    var before = ProjectIO.JSON.valueToTree(n);
    assertEquals("first", selection.current(n, a));
    assertEquals(before, ProjectIO.JSON.valueToTree(n));
    a.inputs.clear();
    assertNull(selection.current(n, a));
    a.inputs.put("new", Binding.constant(3));
    assertEquals("new", selection.current(n, a));
  }

  @Test
  void reorderingItemsPreservesUuidChoicesAndCoordinateItemsUseTheSameOwnership() {
    var n = noise();
    var a = n.processing.getFirst();
    var selection = new NoiseInputSelection();
    selection.select(n, a, "second");
    Collections.reverse(n.processing);
    assertEquals("second", selection.current(n, a));
    n.processing.remove(a);
    n.coordinateTransforms.add(a);
    assertEquals("second", selection.current(n, a));
  }

  @Test
  void retainedSelectionsAreBoundedAndEvictionDoesNotMutateAuthoredInputs() {
    var n = noise();
    var selection = new NoiseInputSelection();
    var first = n.processing.getFirst();
    selection.select(n, first, "second");
    for (int i = 0; i < 200; i++) {
      var item = ProcessingRegistry.create("EXPRESSION", "extra" + i);
      item.inputs.put("first", Binding.constant(i));
      item.inputs.put("second", Binding.constant(i + 1));
      n.processing.add(item);
      selection.select(n, item, "second");
    }
    assertEquals(128, selection.retained());
    var before = ProjectIO.JSON.valueToTree(n);
    assertEquals("first", selection.current(n, first));
    assertEquals(before, ProjectIO.JSON.valueToTree(n));
    assertEquals(128, selection.retained());
  }
}
