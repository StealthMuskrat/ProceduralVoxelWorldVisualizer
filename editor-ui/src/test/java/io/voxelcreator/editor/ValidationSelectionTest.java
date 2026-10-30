package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred exact publication/document ownership; native row positions never authorize a target. */
class ValidationSelectionTest {
  private Diagnostic issue(String field) {
    return Diagnostic.error("INVALID", "/project/" + field, "Invalid " + field);
  }

  @Test
  void selectingAResultRetainsItsExactTargetAndDoesNotTouchProjectOrPrivateDraft() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var noise = new ProjectData.Noise();
                noise.id = "committed";
                p.noise2D.add(noise);
              }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      var draft = (ProjectData.Noise) state.draft;
      draft.id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      long history = state.document.historyBytes(), revision = state.revision;
      boolean dirty = state.document.dirty();
      state.issues = List.of(issue("seed"), issue("maxY"));
      var before = ProjectIO.JSON.valueToTree(state.project());
      var selection = new ValidationSelection();
      selection.synchronize(state);
      assertNull(selection.selected());
      selection.select(1);
      assertSame(state.issues.get(1), selection.selected());
      assertEquals("/project/maxY", selection.target(state).pointer());
      String publication = selection.publication();
      selection.synchronize(state);
      assertEquals(publication, selection.publication());
      assertEquals(1, selection.index());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(history, state.document.historyBytes());
      assertEquals(revision, state.revision);
      assertEquals(dirty, state.document.dirty());
      assertSame(draft, state.draft);
      assertEquals("private", draft.id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void equalValuedReplacementResultsClearSelectionAndNativePublicationIdentity() {
    try (var state = new EditorState(new UserConfig(), false)) {
      state.issues = new ArrayList<>(List.of(issue("seed")));
      var selection = new ValidationSelection();
      selection.synchronize(state);
      selection.select(0);
      String publication = selection.publication();
      state.issues = new ArrayList<>(state.issues);
      assertFalse(selection.current(state));
      assertNull(selection.target(state));
      selection.synchronize(state);
      assertNull(selection.selected());
      assertNotEquals(publication, selection.publication());
    }
  }

  @Test
  void inPlaceReorderCannotRetargetASelectedIndexAndOwnedRowsRemainImmutable() {
    try (var state = new EditorState(new UserConfig(), false)) {
      state.issues = new ArrayList<>(List.of(issue("seed"), issue("maxY")));
      var selection = new ValidationSelection();
      selection.synchronize(state);
      selection.select(0);
      var owned = selection.rows();
      Collections.reverse(state.issues);
      assertFalse(selection.current(state));
      assertNull(selection.target(state));
      assertEquals("/project/seed", owned.getFirst().jsonPointer());
      assertThrows(UnsupportedOperationException.class, () -> owned.clear());
      selection.synchronize(state);
      assertEquals(-1, selection.index());
      assertEquals("/project/maxY", selection.rows().getFirst().jsonPointer());
    }
  }

  @Test
  void sameUuidNewDocumentLifetimeScopeAndRevisionChangesEachRequireNewSelection() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var selection = new ValidationSelection();
      state.issues = List.of(issue("seed"));
      selection.synchronize(state);
      selection.select(0);
      state.validationScope = "Rejected Project Settings";
      assertFalse(selection.current(state));
      selection.synchronize(state);
      assertNull(selection.selected());
      selection.select(0);
      state.revision++;
      assertNull(selection.target(state));
      selection.synchronize(state);
      assertNull(selection.selected());
      selection.select(0);
      var id = state.project().project.uuid;
      state.newSession();
      state.project().project.uuid = id;
      state.issues = List.of(issue("seed"));
      assertNull(selection.target(state));
      selection.synchronize(state);
      assertNull(selection.selected());
    }
  }

  @Test
  void foreignEditorAndUnsupportedIndicesNeverBorrowAnExistingSelection() {
    try (var state = new EditorState(new UserConfig(), false);
        var other = new EditorState(new UserConfig(), false)) {
      state.issues = List.of(issue("minY"));
      var selection = new ValidationSelection();
      selection.synchronize(state);
      selection.select(0);
      other.project().project.uuid = state.project().project.uuid;
      other.issues = state.issues;
      assertNull(selection.target(other));
      assertThrows(IllegalArgumentException.class, () -> selection.select(-1));
      assertThrows(IllegalArgumentException.class, () -> selection.select(1));
      assertEquals(0, selection.index());
      assertNotNull(selection.target(state));
    }
  }
}
