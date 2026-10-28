package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Deferred membership ownership, lifecycle and rejected-candidate contracts. */
class MembershipOwnershipTest {
  @Test
  void reusedValidatorListRetainsCandidateDetailsOnEveryExplicitRetry() {
    try (var state = state()) {
      var edit = edit(state);
      var rejection = List.of(Diagnostic.error("SAME_LIST", "/fixture", "Rejected"));
      state.document.setValidator(p -> rejection);
      for (int attempt = 0; attempt < 2; attempt++) {
        assertFalse(edit.apply(state));
        assertEquals(1, edit.issueCount());
        assertEquals(rejection, edit.applyIssues());
      }
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(edit.apply(state));
      assertEquals(0, edit.issueCount());
    }
  }

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              for (int i = 0; i < 3; i++) {
                var type = new VoxelType();
                type.id = "type-" + i;
                p.voxelTypes.add(type);
              }
              var set = new Membership();
              set.id = "members";
              set.voxelTypeUuids.add(p.voxelTypes.getFirst().uuid);
              p.voxelSets.add(set);
            }));
    state.select("set", state.project().voxelSets.getFirst().uuid);
    return state;
  }

  private MembershipEditSession edit(EditorState state) {
    var edit = new MembershipEditSession(state, (Membership) state.draft);
    edit.set(state.project().voxelTypes.getLast().uuid, true);
    return edit;
  }

  @Test
  void equalValueReplacementDraftCannotReceiveAnOldSelection() {
    try (var state = state()) {
      var edit = edit(state);
      Object old = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long bytes = state.document.historyBytes(), revision = state.revision;
      state.refreshDraft();
      assertNotSame(old, state.draft);
      assertEquals(ProjectIO.JSON.valueToTree(old), ProjectIO.JSON.valueToTree(state.draft));
      assertFalse(edit.currentOwner(state));
      assertThrows(IllegalStateException.class, () -> edit.apply(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(bytes, state.document.historyBytes());
      assertEquals(revision, state.revision);
    }
  }

  @Test
  void sameActiveResourceWithDifferentSiblingSelectionRejectsWithoutChangingDraft() {
    try (var state = state()) {
      var edit = edit(state);
      Object draft = state.draft;
      state.multiSelection.add(state.project().voxelTypes.getFirst().uuid);
      assertFalse(edit.currentOwner(state));
      assertThrows(IllegalStateException.class, () -> edit.apply(state));
      assertSame(draft, state.draft);
      assertEquals(1, ((Membership) draft).voxelTypeUuids.size());
    }
  }

  @Test
  void foreignEditorAndOffscreenDraftCannotBorrowTheSession() {
    try (var state = state();
        var other = state()) {
      var edit = edit(state);
      assertTrue(other.change(p -> p.project.uuid = state.project().project.uuid));
      other.draft = state.draft;
      other.selected = state.selected;
      other.selectedKind = state.selectedKind;
      other.multiSelection.clear();
      other.multiSelection.addAll(state.multiSelection);
      assertFalse(edit.currentOwner(other));
      assertThrows(IllegalStateException.class, () -> edit.apply(other));
      var copy =
          ProjectIO.JSON.convertValue(ProjectIO.JSON.valueToTree(state.draft), Membership.class);
      assertThrows(IllegalArgumentException.class, () -> new MembershipEditSession(state, copy));
    }
  }

  @Test
  void successfulOrCancelledSessionsCannotPublishOrEditAgain() {
    try (var state = state()) {
      var cancelled = edit(state);
      cancelled.cancel();
      cancelled.cancel();
      assertFalse(cancelled.currentOwner(state));
      assertThrows(IllegalStateException.class, () -> cancelled.apply(state));
      assertThrows(IllegalStateException.class, () -> cancelled.set(UUID.randomUUID(), true));
      assertThrows(IllegalStateException.class, () -> cancelled.setVisible(List.of(), true));
      var accepted = edit(state);
      assertTrue(accepted.apply(state));
      var after = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, bytes = state.document.historyBytes();
      assertThrows(IllegalStateException.class, () -> accepted.apply(state));
      assertThrows(IllegalStateException.class, () -> accepted.set(UUID.randomUUID(), false));
      assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
    }
  }

  @Test
  void rejectedCandidateKeepsPrivateMembersAndBoundedDiagnosticsUntilRetry() {
    try (var state = state()) {
      ((Membership) state.draft).id = "pending-id";
      var edit = edit(state);
      Object parent = state.draft;
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision, bytes = state.document.historyBytes();
      var issues =
          IntStream.range(0, 300)
              .mapToObj(
                  i -> Diagnostic.error("INJECTED_" + i, "/voxelSets/0", "Rejected members " + i))
              .toList();
      state.document.setValidator(p -> issues);
      assertFalse(edit.apply(state));
      assertEquals(300, edit.issueCount());
      assertEquals(256, edit.applyIssues().size());
      assertEquals(issues.getFirst(), edit.applyIssues().getFirst());
      assertThrows(UnsupportedOperationException.class, () -> edit.applyIssues().clear());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(parent, state.draft);
      assertEquals(1, ((Membership) parent).voxelTypeUuids.size());
      assertEquals(2, edit.count());
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(edit.apply(state));
      assertEquals("pending-id", ((Membership) parent).id);
      assertEquals("members", state.project().voxelSets.getFirst().id);
      assertEquals(2, ((Membership) parent).voxelTypeUuids.size());
      assertEquals(0, edit.issueCount());
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void thrownValidatorDoesNotReuseEarlierCandidateDiagnosticsAndCanRetry() {
    try (var state = state()) {
      var edit = edit(state);
      var earlier = List.of(Diagnostic.error("EARLIER", "", "Earlier rejection"));
      state.document.setValidator(p -> earlier);
      assertFalse(edit.apply(state));
      assertEquals(earlier, edit.applyIssues());
      var before = ProjectIO.JSON.valueToTree(state.project());
      state.document.setValidator(
          p -> {
            throw new IllegalStateException();
          });
      assertFalse(edit.apply(state));
      assertEquals(0, edit.issueCount());
      assertTrue(edit.applyIssues().isEmpty());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(2, edit.count());
      state.document.setValidator(NoiseCompiler::validate);
      assertTrue(edit.apply(state));
    }
  }
}
