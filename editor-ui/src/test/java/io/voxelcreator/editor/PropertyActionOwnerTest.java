package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import org.junit.jupiter.api.Test;

/** Deferred footer ownership definitions without native UI calls. */
class PropertyActionOwnerTest {
  private EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(
        s.change(
            p -> {
              for (String id : new String[] {"a", "b"}) {
                var n = new Noise();
                n.id = id;
                p.noise2D.add(n);
              }
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    return s;
  }

  @Test
  void privateTextValuesAndPreferencesRetainTheSameActionOwner() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      long version = owner.synchronize(s, false);
      var committed = ProjectIO.JSON.valueToTree(s.project());
      ((Noise) s.draft).id = "private";
      ((Noise) s.draft).seedOffset = 17;
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      s.user.appearance.globalScale = 2.5f;
      assertEquals(version, owner.synchronize(s, false));
      assertTrue(owner.editable(s, false));
      assertEquals(committed, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void replacementDraftWithTheSameUuidCannotUseThePreviousFooter() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      long version = owner.synchronize(s, false);
      var id = s.selected;
      s.refreshDraft();
      assertEquals(id, s.selected);
      assertFalse(owner.current(s, false));
      assertFalse(owner.editable(s, false));
      assertTrue(owner.synchronize(s, false) > version);
      assertTrue(owner.editable(s, false));
    }
  }

  @Test
  void anotherResourceSelectionCannotAdoptTheOpeningAction() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      long version = owner.synchronize(s, false);
      s.select("noise2D", s.project().noise2D.getLast().uuid);
      assertFalse(owner.editable(s, false));
      assertTrue(owner.synchronize(s, false) > version);
      assertTrue(owner.editable(s, false));
    }
  }

  @Test
  void committedRevisionAndDocumentReplacementRequireNewFooterOwners() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      long first = owner.synchronize(s, false);
      var draft = s.draft;
      assertTrue(s.change(p -> p.project.seed++));
      assertSame(draft, s.draft);
      assertFalse(owner.current(s, false));
      long second = owner.synchronize(s, false);
      assertTrue(second > first);
      s.revision++;
      assertFalse(owner.current(s, false));
      long third = owner.synchronize(s, false);
      assertTrue(third > second);
      s.document.newProject();
      assertFalse(owner.current(s, false));
      assertTrue(owner.synchronize(s, false) > third);
    }
  }

  @Test
  void privateEditorEntryAndExitAdvanceTheScopeWithoutReplacingTheDraft() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      long first = owner.synchronize(s, false);
      var draft = s.draft;
      assertFalse(owner.editable(s, true));
      long second = owner.synchronize(s, true);
      assertTrue(second > first);
      assertFalse(owner.editable(s, true));
      assertEquals(second, owner.synchronize(s, true));
      assertFalse(owner.current(s, false));
      assertTrue(owner.synchronize(s, false) > second);
      assertTrue(owner.editable(s, false));
      assertSame(draft, s.draft);
    }
  }

  @Test
  void emptyMultipleAndMismatchedSelectionContextsCannotApplyOrRevert() {
    try (var s = state()) {
      var owner = new PropertyActionOwner();
      owner.synchronize(s, false);
      s.multiSelection.add(s.project().noise2D.getLast().uuid);
      owner.synchronize(s, false);
      assertFalse(owner.editable(s, false));
      s.multiSelection.clear();
      owner.synchronize(s, false);
      assertFalse(owner.editable(s, false));
      s.multiSelection.add(s.project().noise2D.getLast().uuid);
      owner.synchronize(s, false);
      assertFalse(owner.editable(s, false));
      s.multiSelection.clear();
      s.multiSelection.add(s.selected);
      owner.synchronize(s, false);
      assertTrue(owner.editable(s, false));
      ((Noise) s.draft).uuid = java.util.UUID.randomUUID();
      assertFalse(owner.editable(s, false));
    }
  }
}
