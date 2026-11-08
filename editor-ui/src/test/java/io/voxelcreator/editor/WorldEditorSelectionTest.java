package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred independent World Editor selection and guarded navigation contracts. */
class WorldEditorSelectionTest {
  @TempDir Path directory;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(WorldEditorSelectionTest::fixture));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private-name";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static void fixture(ProjectData p) {
    var noise = new Noise();
    noise.id = "shared-field";
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", .25);
    p.noise2D.add(noise);
    for (int i = 0; i < 2; i++) {
      var layer = new Layer();
      layer.id = "layer-" + i;
      for (int j = 0; j < 3; j++) {
        var operation = new Operation();
        operation.id = "placement-" + j;
        operation.noiseGeneratorUuid = noise.uuid;
        layer.operations.add(operation);
      }
      p.worldGenerator.layers.add(layer);
    }
  }

  private static UUID layer(EditorState state, int index) {
    return state.project().worldGenerator.layers.get(index).uuid;
  }

  private static UUID operation(EditorState state, int layer, int index) {
    return state.project().worldGenerator.layers.get(layer).operations.get(index).uuid;
  }

  private static void click(WorldEditorSelection selection, UUID id, boolean toggle, boolean range) {
    selection.select(id, toggle, range, selection.rows(""));
  }

  @Test
  void catalogPreservesExecutionOrderDisabledItemsAndSearchContext() {
    try (var state = state()) {
      assertTrue(state.change(p -> p.worldGenerator.layers.getFirst().enabled = false));
      var selection = new WorldEditorSelection();
      assertTrue(selection.synchronize(state));
      var rows = selection.rows("");
      assertEquals(8, rows.size());
      assertEquals(layer(state, 0), rows.getFirst().uuid());
      assertEquals(operation(state, 0, 0), rows.get(1).uuid());
      assertEquals(layer(state, 1), rows.get(4).uuid());
      assertEquals(List.of(1, 1, 2, 3, 2, 1, 2, 3), rows.stream().map(WorldEditorSelection.Row::stage).toList());
      assertTrue(rows.subList(0, 4).stream().noneMatch(WorldEditorSelection.Row::enabled));
      assertEquals(8, selection.rows(" SHARED-FIELD ").size());
      assertSame(selection.rows("shared-field"), selection.rows(" SHARED-FIELD "));
      assertEquals(4, selection.rows("layer-0").size());
      assertEquals(2, selection.rows(operation(state, 0, 2).toString()).size());
      assertTrue(selection.rows("no-match").isEmpty());
      assertThrows(UnsupportedOperationException.class, rows::clear);
      assertFalse(selection.synchronize(state));
      assertSame(rows, selection.rows(""));
    }
  }

  @Test
  void worldSelectionDoesNotReplaceResourcesPrivateDraftPreferencesOrProjectHistory() {
    try (var state = state()) {
      var project = ProjectIO.JSON.valueToTree(state.project());
      var draft = ProjectIO.JSON.valueToTree(state.draft);
      var user = ProjectIO.JSON.valueToTree(state.user);
      var resources = Set.copyOf(state.multiSelection);
      var active = state.selected;
      Object privateOwner = state.draft;
      long revision = state.revision, history = state.document.historyBytes();
      boolean dirty = state.document.dirty();
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, layer(state, 0), false, false);
      click(selection, layer(state, 1), true, false);
      assertEquals(2, selection.selected().size());
      assertNull(selection.single());
      assertNull(selection.ticket());
      assertEquals(resources, state.multiSelection);
      assertEquals(active, state.selected);
      assertSame(privateOwner, state.draft);
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void togglingChangesContextRatherThanMixingLayersOrDifferentLayersPlacements() {
    try (var state = state()) {
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, layer(state, 0), false, false);
      click(selection, layer(state, 1), true, false);
      click(selection, operation(state, 0, 0), true, false);
      assertEquals(Set.of(operation(state, 0, 0)), selection.selected());
      click(selection, operation(state, 0, 1), true, false);
      assertEquals(2, selection.selected().size());
      click(selection, operation(state, 1, 0), true, false);
      assertEquals(Set.of(operation(state, 1, 0)), selection.selected());
      click(selection, operation(state, 1, 0), true, false);
      assertTrue(selection.selected().isEmpty());
      assertThrows(UnsupportedOperationException.class, () -> selection.selected().add(layer(state, 0)));
    }
  }

  @Test
  void shiftRangesFollowDisplayedExecutionOrderAndHiddenAnchorsFallBackToOneItem() {
    try (var state = state()) {
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, operation(state, 0, 2), false, false);
      click(selection, operation(state, 0, 0), false, true);
      assertEquals(3, selection.selected().size());
      click(selection, operation(state, 0, 0), false, false);
      click(selection, operation(state, 0, 2), true, true);
      assertEquals(3, selection.selected().size());
      var filtered = selection.rows("placement-1");
      selection.select(operation(state, 0, 1), false, true, filtered);
      assertEquals(Set.of(operation(state, 0, 1)), selection.selected());
      selection.rows("no-match");
      assertEquals(Set.of(operation(state, 0, 1)), selection.selected());
      selection.select(operation(state, 0, 2), true, false, filtered);
      assertEquals(Set.of(operation(state, 0, 1)), selection.selected());
    }
  }

  @Test
  void deletedOrMovedItemsArePrunedAndUndoDoesNotResurrectTransientSelection() {
    try (var state = state()) {
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      UUID first = operation(state, 0, 0), second = operation(state, 0, 1);
      click(selection, first, false, false);
      click(selection, second, true, false);
      assertTrue(state.change(p -> {
        var moved = p.worldGenerator.layers.getFirst().operations.removeFirst();
        p.worldGenerator.layers.getLast().operations.add(moved);
      }));
      selection.synchronize(state);
      assertEquals(Set.of(second), selection.selected());
      state.document.undo();
      state.revision++;
      selection.synchronize(state);
      assertEquals(Set.of(second), selection.selected());
      assertTrue(state.change(p -> p.worldGenerator.layers.getFirst().operations.remove(1)));
      selection.synchronize(state);
      assertTrue(selection.selected().isEmpty());
    }
  }

  @Test
  void reopeningTheSameProjectClearsSelectionAndInvalidatesNavigationTickets() throws Exception {
    try (var state = state()) {
      Path file = directory.resolve("same-project.json");
      state.document.save(file);
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, layer(state, 0), false, false);
      var ticket = selection.ticket();
      UUID project = state.project().project.uuid;
      state.document.open(file);
      assertEquals(project, state.project().project.uuid);
      assertFalse(selection.current(state, ticket));
      assertTrue(selection.selected().isEmpty());
      state.document.newProject();
      selection.synchronize(state);
      assertTrue(selection.rows("").isEmpty());
    }
  }

  @Test
  void selectionTicketsCannotReviveAfterChangingAwayAndBack() {
    try (var state = state()) {
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, layer(state, 0), false, false);
      var ticket = selection.ticket();
      assertTrue(selection.current(state, ticket));
      click(selection, layer(state, 1), false, false);
      click(selection, layer(state, 0), false, false);
      assertFalse(selection.current(state, ticket));
      assertTrue(selection.current(state, selection.ticket()));
    }
  }

  @Test
  void explicitPropertiesNavigationUsesCancelDiscardAndSelectionFreshnessGuards() {
    try (var state = state();
        var properties = new PropertyPanel(state);
        var navigation = new GuardedNavigation(state, properties, target -> true)) {
      var selection = new WorldEditorSelection();
      selection.synchronize(state);
      click(selection, layer(state, 0), false, false);
      var ticket = selection.ticket();
      var target = new EditorEvents.Target(state.project().project.uuid, ticket.resource(), null, null, "", false);
      Object draft = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertEquals(SearchNavigation.Result.NEEDS_DECISION,
          navigation.request(target, () -> selection.current(state, ticket)));
      assertEquals(SearchNavigation.Result.CANCELLED, navigation.decide(SearchNavigation.Decision.CANCEL));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(SearchNavigation.Result.NEEDS_DECISION,
          navigation.request(target, () -> selection.current(state, ticket)));
      click(selection, layer(state, 1), false, false);
      assertEquals(SearchNavigation.Result.UNAVAILABLE, navigation.decide(SearchNavigation.Decision.DISCARD));
      assertSame(draft, state.draft);
      click(selection, layer(state, 0), false, false);
      var retry = selection.ticket();
      assertEquals(SearchNavigation.Result.NEEDS_DECISION,
          navigation.request(target, () -> selection.current(state, retry)));
      assertEquals(SearchNavigation.Result.NAVIGATED, navigation.decide(SearchNavigation.Decision.DISCARD));
      assertEquals(ticket.resource(), state.selected);
      assertEquals(Set.of(ticket.resource()), selection.selected());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }
}
