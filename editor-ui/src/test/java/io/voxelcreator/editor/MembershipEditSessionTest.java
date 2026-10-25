package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MembershipEditSessionTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (int i = 0; i < 4; i++) {
                var t = new VoxelType();
                t.id = "type-" + i;
                p.voxelTypes.add(t);
              }
              var m = new Membership();
              m.id = "set";
              m.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
              p.voxelSets.add(m);
            }));
    state.select("set", state.project().voxelSets.getFirst().uuid);
    return state;
  }

  private static Membership draft(EditorState state) {
    return (Membership) state.draft;
  }

  @Test
  void temporaryEditsAndCancellationPreserveParentDraftDocumentAndHistory() {
    try (var state = state()) {
      var before = ProjectIO.JSON.valueToTree(state.project());
      Object parent = state.draft;
      long history = state.document.historyBytes(), revision = state.revision;
      var edit = new MembershipEditSession(state, draft(state));
      edit.set(state.project().voxelTypes.getLast().uuid, true);
      assertEquals(2, edit.count());
      assertEquals(1, draft(state).voxelTypeUuids.size());
      assertSame(parent, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void applyCommitsMembersOnlyAndOneUndoRestoresAuthoredValues() {
    try (var state = state()) {
      draft(state).id = "pending-id";
      var original = List.copyOf(draft(state).voxelTypeUuids);
      var add = state.project().voxelTypes.getLast().uuid;
      var edit = new MembershipEditSession(state, draft(state));
      edit.set(add, true);
      assertTrue(edit.apply(state));
      assertEquals("set", state.project().voxelSets.getFirst().id);
      assertEquals("pending-id", draft(state).id);
      assertEquals(
          List.of(original.getFirst(), add), state.project().voxelSets.getFirst().voxelTypeUuids);
      state.document.undo();
      assertEquals(original, state.project().voxelSets.getFirst().voxelTypeUuids);
      state.document.redo();
      assertEquals(
          List.of(original.getFirst(), add), state.project().voxelSets.getFirst().voxelTypeUuids);
    }
  }

  @Test
  void visibleBulkEditsRespectFiltersAndPreserveUnrelatedMemberOrder() {
    try (var state = state()) {
      var index = new ResourceTreeIndex(state.project());
      var edit = new MembershipEditSession(state, draft(state));
      edit.setVisible(index.kind("voxel", "type-3"), true);
      edit.setVisible(index.kind("voxel", "type-1"), true);
      assertEquals(
          List.of(
              state.project().voxelTypes.get(0).uuid,
              state.project().voxelTypes.get(3).uuid,
              state.project().voxelTypes.get(1).uuid),
          edit.values());
      edit.setVisible(index.kind("voxel", "type-3"), false);
      assertEquals(2, edit.count());
      assertTrue(edit.contains(state.project().voxelTypes.getFirst().uuid));
      long revision = edit.revision();
      edit.set(state.project().voxelTypes.getFirst().uuid, true);
      assertEquals(revision, edit.revision());
    }
  }

  @Test
  void staleActiveResourceRejectsWithoutOverwritingAnotherMembershipChange() {
    try (var state = state()) {
      var edit = new MembershipEditSession(state, draft(state));
      edit.set(state.project().voxelTypes.get(1).uuid, true);
      assertTrue(
          state.change(p -> p.voxelSets.getFirst().voxelTypeUuids.add(p.voxelTypes.get(2).uuid)));
      var before = ProjectIO.JSON.valueToTree(state.project());
      assertThrows(IllegalStateException.class, () -> edit.apply(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void staleParentDraftOrChangedSelectionCannotBePublished() {
    try (var state = state()) {
      var first = new MembershipEditSession(state, draft(state));
      draft(state).id = "changed-elsewhere";
      assertThrows(IllegalStateException.class, () -> first.apply(state));
      var stale = new MembershipEditSession(state, draft(state));
      state.select("voxel", state.project().voxelTypes.getFirst().uuid);
      assertThrows(IllegalStateException.class, () -> stale.apply(state));
    }
  }

  @Test
  void missingNewlySelectedTypeRejectsWithoutChangingParentOrActiveMembers() {
    try (var state = state()) {
      var edit = new MembershipEditSession(state, draft(state));
      UUID last = state.project().voxelTypes.getLast().uuid;
      edit.set(last, true);
      assertTrue(state.change(p -> p.voxelTypes.removeIf(t -> t.uuid.equals(last))));
      assertThrows(IllegalArgumentException.class, () -> edit.apply(state));
      assertEquals(1, draft(state).voxelTypeUuids.size());
      assertEquals(1, state.project().voxelSets.getFirst().voxelTypeUuids.size());
    }
  }

  @Test
  void membershipSnapshotOwnershipAndInvalidBulkKindsArePreserved() {
    try (var state = state()) {
      var edit = new MembershipEditSession(state, draft(state));
      assertThrows(UnsupportedOperationException.class, () -> edit.values().clear());
      var index = new ResourceTreeIndex(state.project());
      assertThrows(
          IllegalArgumentException.class, () -> edit.setVisible(index.kind("set", ""), true));
      assertEquals(1, edit.count());
      edit.set(state.project().voxelTypes.getLast().uuid, true);
      assertTrue(edit.apply(state));
      draft(state).voxelTypeUuids.clear();
      assertEquals(2, state.project().voxelSets.getFirst().voxelTypeUuids.size());
    }
  }

  @Test
  void unrelatedCommittedCatalogChangesAreAllowedAndEmptySelectionIsLegal() {
    try (var state = state()) {
      var edit = new MembershipEditSession(state, draft(state));
      edit.set(state.project().voxelTypes.getFirst().uuid, false);
      assertTrue(state.change(p -> p.voxelTypes.getLast().color.r = 17));
      assertTrue(edit.apply(state));
      assertTrue(state.project().voxelSets.getFirst().voxelTypeUuids.isEmpty());
      assertEquals(17, state.project().voxelTypes.getLast().color.r);
    }
  }
}
