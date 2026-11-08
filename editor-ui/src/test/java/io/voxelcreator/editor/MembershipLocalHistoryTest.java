package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred local membership history; project Undo is reserved for the final member transaction. */
class MembershipLocalHistoryTest {
  private static EditorState state(String kind) {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (int i = 0; i < 5; i++) {
                var type = new VoxelType();
                type.id = "type-" + i;
                p.voxelTypes.add(type);
              }
              var members = new Membership();
              members.id = "members";
              for (int index : List.of(0, 2, 1)) members.voxelTypeUuids.add(p.voxelTypes.get(index).uuid);
              (kind.equals("set") ? p.voxelSets : p.voxelGroups).add(members);
            }));
    var members =
        kind.equals("set")
            ? state.project().voxelSets.getFirst()
            : state.project().voxelGroups.getFirst();
    state.select(kind, members.uuid);
    ((Membership) state.draft).id = "private-id";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static UUID type(EditorState state, int index) {
    return state.project().voxelTypes.get(index).uuid;
  }

  private record Before(
      JsonNode project,
      JsonNode draft,
      JsonNode preferences,
      long revision,
      long history,
      boolean dirty) {
    Before(EditorState state) {
      this(
          ProjectIO.JSON.valueToTree(state.project()),
          ProjectIO.JSON.valueToTree(state.draft),
          ProjectIO.JSON.valueToTree(state.user),
          state.revision,
          state.document.historyBytes(),
          state.document.dirty());
    }

    void unchanged(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void checkboxUndoRedoPreservesExactOrderAndAllParentState() {
    for (String kind : List.of("set", "group"))
      try (var state = state(kind);
          var edit = new MembershipEditSession(state, (Membership) state.draft)) {
        var before = new Before(state);
        var original = edit.values();
        Object parent = state.draft;
        edit.set(type(state, 2), false);
        edit.set(type(state, 3), true);
        var changed = List.of(type(state, 0), type(state, 1), type(state, 3));
        assertEquals(changed, edit.values());
        assertTrue(edit.undo());
        assertTrue(edit.undo());
        assertEquals(original, edit.values());
        assertFalse(edit.undo());
        assertTrue(edit.redo());
        assertTrue(edit.redo());
        assertEquals(changed, edit.values());
        assertFalse(edit.redo());
        assertSame(parent, state.draft);
        before.unchanged(state);
      }
  }

  @Test
  void bulkActionsAreOneStepAndInvalidBulkInputCannotPartiallyEditOrRetireRedo() {
    try (var state = state("set");
        var edit = new MembershipEditSession(state, (Membership) state.draft)) {
      var before = new Before(state);
      var original = edit.values();
      var rows = new ResourceTreeIndex(state.project()).kind("voxel", "");
      edit.setVisible(rows, true);
      assertEquals(1, edit.revision());
      assertEquals(5, edit.count());
      assertTrue(edit.undo());
      assertEquals(original, edit.values());
      long revision = edit.revision();
      edit.set(type(state, 0), true);
      edit.setVisible(List.of(rows.getFirst()), true);
      assertEquals(revision, edit.revision());
      assertTrue(edit.canRedo());
      var invalid = new ResourceTreeIndex.Node(null, "invalid", "voxel", "voxel", null, true, null);
      assertThrows(
          NullPointerException.class,
          () -> edit.setVisible(List.of(rows.getLast(), invalid), true));
      assertEquals(original, edit.values());
      assertEquals(revision, edit.revision());
      assertTrue(edit.canRedo());
      assertTrue(edit.redo());
      edit.setVisible(List.of(rows.get(1), rows.get(3)), false);
      assertTrue(edit.undo());
      assertEquals(5, edit.count());
      before.unchanged(state);
    }
  }

  @Test
  void onlyEffectiveNewEditsDiscardTheRedoBranch() {
    try (var state = state("set");
        var edit = new MembershipEditSession(state, (Membership) state.draft)) {
      edit.set(type(state, 3), true);
      edit.set(type(state, 4), true);
      assertTrue(edit.undo());
      edit.set(type(state, 4), false);
      assertTrue(edit.canRedo());
      edit.set(type(state, 2), false);
      assertFalse(edit.canRedo());
      long revision = edit.revision();
      assertFalse(edit.redo());
      assertEquals(revision, edit.revision());
      assertTrue(edit.undo());
      assertEquals(
          List.of(type(state, 0), type(state, 2), type(state, 1), type(state, 3)), edit.values());
    }
  }

  @Test
  void localUndoClearsRejectedFeedbackAndFinalApplyStillHasOneProjectUndo() {
    for (String kind : List.of("set", "group"))
      try (var state = state(kind);
          var edit = new MembershipEditSession(state, (Membership) state.draft)) {
        var before = new Before(state);
        edit.set(type(state, 3), true);
        state.document.setValidator(p -> List.of(Diagnostic.error("REJECTED", "/fixture", "reject")));
        assertFalse(edit.apply(state));
        assertEquals(1, edit.issueCount());
        assertTrue(edit.undo());
        assertEquals(0, edit.issueCount());
        before.unchanged(state);
        assertTrue(edit.redo());
        state.document.setValidator(NoiseCompiler::validate);
        assertTrue(edit.apply(state));
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertEquals("private-id", ((Membership) state.draft).id);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        var accepted = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
        state.document.redo();
        assertEquals(accepted, ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void closedAndReplacementDraftsCannotReusePrivateHistory() {
    for (boolean apply : List.of(false, true))
      try (var state = state("set");
          var edit = new MembershipEditSession(state, (Membership) state.draft)) {
        edit.set(type(state, 3), true);
        if (apply) assertTrue(edit.apply(state));
        else edit.cancel();
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        assertThrows(IllegalStateException.class, edit::redo);
      }
    try (var state = state("group");
        var edit = new MembershipEditSession(state, (Membership) state.draft)) {
      edit.set(type(state, 3), true);
      var values = edit.values();
      state.refreshDraft();
      assertFalse(edit.canUndo());
      assertFalse(edit.canRedo());
      assertThrows(IllegalStateException.class, edit::undo);
      assertEquals(values, edit.values());
    }
  }

  @Test
  void localUndoRetiresQueuedOrReadyApplyAndASeparateRetryPublishesOnlyOnce() {
    for (String kind : List.of("set", "group"))
      for (boolean ready : List.of(false, true))
        try (var state = state(kind)) {
          var work = new ArrayDeque<Runnable>();
          var ui = new ArrayDeque<Runnable>();
          try (var edit =
              new MembershipEditSession(state, (Membership) state.draft, work::add, ui::add)) {
            var before = new Before(state);
            edit.set(type(state, 3), true);
            assertFalse(edit.apply(state));
            var oldWork = work.remove();
            Runnable oldReceipt = null;
            if (ready) {
              oldWork.run();
              oldReceipt = ui.remove();
              oldReceipt.run();
            }
            assertTrue(edit.undo());
            assertFalse(edit.applying());
            oldWork.run();
            if (oldReceipt != null) oldReceipt.run();
            assertFalse(edit.acceptPrepared());
            before.unchanged(state);
            assertTrue(edit.redo());
            assertFalse(edit.apply(state));
            if (oldReceipt != null) oldReceipt.run();
            assertFalse(edit.acceptPrepared());
            work.remove().run();
            ui.remove().run();
            assertTrue(edit.acceptPrepared());
            assertEquals(before.revision() + 1, state.revision);
            assertFalse(edit.canUndo());
            assertEquals("private-id", ((Membership) state.draft).id);
            assertEquals("-", state.seedInputs.text("seedOffset"));
            var accepted = ProjectIO.JSON.valueToTree(state.project());
            state.document.undo();
            assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
            state.document.redo();
            assertEquals(accepted, ProjectIO.JSON.valueToTree(state.project()));
          }
        }
  }
}
