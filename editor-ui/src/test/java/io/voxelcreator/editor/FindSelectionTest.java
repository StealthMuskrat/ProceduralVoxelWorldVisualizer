package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class FindSelectionTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new ProjectData.Noise();
              n.id = "field";
              p.noise2D.add(n);
            }));
    return state;
  }

  private List<ResourceSearchIndex.Hit> hits(EditorState state) {
    return new ResourceSearchIndex(state.project()).search(ResourceSearchIndex.Filter.all());
  }

  @Test
  void onlyAnActualShownHitCanBecomeTheOwnedSelection() {
    try (var state = state()) {
      var shown = hits(state);
      var selection = new FindSelection();
      selection.synchronize(state);
      var hit = shown.getFirst();
      selection.select(state, hit, shown);
      assertTrue(selection.owns(state, hit));
      var equalFromAnotherIndex =
          hits(state).stream().filter(h -> h.key().equals(hit.key())).findFirst().orElseThrow();
      assertNotSame(hit, equalFromAnotherIndex);
      assertThrows(
          IllegalStateException.class, () -> selection.select(state, equalFromAnotherIndex, shown));
      assertSame(hit, selection.hit());
    }
  }

  @Test
  void filterChangesAndIdleSynchronizationDoNotSubstituteAnotherHit() {
    try (var state = state()) {
      var shown = hits(state);
      var selection = new FindSelection();
      selection.synchronize(state);
      selection.select(state, shown.getFirst(), shown);
      var hit = selection.hit();
      assertTrue(
          new ResourceSearchIndex(state.project())
              .search(
                  new ResourceSearchIndex.Filter(
                      "absent",
                      "All",
                      "All",
                      "All",
                      null,
                      null,
                      false,
                      ResourceSearchIndex.Mode.ID))
              .isEmpty());
      selection.synchronize(state);
      assertTrue(selection.owns(state, hit));
      assertSame(hit, selection.hit());
    }
  }

  @Test
  void trackedRevisionAndSameUuidNewDocumentInvalidatePreviousActivation() {
    try (var state = state()) {
      var selection = new FindSelection();
      var shown = hits(state);
      selection.synchronize(state);
      selection.select(state, shown.getFirst(), shown);
      state.revision++;
      assertFalse(selection.owns(state, shown.getFirst()));
      selection.synchronize(state);
      assertNull(selection.hit());
      shown = hits(state);
      selection.select(state, shown.getFirst(), shown);
      var old = selection.hit();
      var id = state.project().project.uuid;
      state.newSession();
      state.project().project.uuid = id;
      assertFalse(selection.owns(state, old));
      selection.synchronize(state);
      assertNull(selection.hit());
    }
  }

  @Test
  void privateDraftAndRawSeedEditsRemainIndependentOfFindSelection() {
    try (var state = state()) {
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (ProjectData.Noise) state.draft;
      draft.id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      long history = state.document.historyBytes(), revision = state.revision;
      var selection = new FindSelection();
      var shown = hits(state);
      selection.synchronize(state);
      selection.select(state, shown.getFirst(), shown);
      assertTrue(selection.owns(state, shown.getFirst()));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void foreignEditorsAndExplicitClearCannotBorrowTheOldTarget() {
    try (var state = state();
        var other = state()) {
      var selection = new FindSelection();
      var shown = hits(state);
      selection.synchronize(state);
      selection.select(state, shown.getFirst(), shown);
      other.project().project.uuid = state.project().project.uuid;
      assertFalse(selection.owns(other, shown.getFirst()));
      selection.clear();
      assertNull(selection.hit());
      assertFalse(selection.current(state));
    }
  }
}
