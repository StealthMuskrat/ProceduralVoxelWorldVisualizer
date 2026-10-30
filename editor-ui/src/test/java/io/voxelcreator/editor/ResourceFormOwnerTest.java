package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData.*;
import io.voxelcreator.model.ProjectIO;
import org.junit.jupiter.api.Test;

/** Deferred browser form lifetime contracts, independent of native ImGui state. */
class ResourceFormOwnerTest {
  @Test
  void privateInputSelectionAndPreferencesDoNotRetargetACommittedFormOwner() {
    try (var state = new EditorState(new UserConfig(), false)) {
      assertTrue(
          state.change(
              p -> {
                var n = new Noise();
                n.id = "field";
                p.noise2D.add(n);
              }));
      var owner = new ResourceFormOwner(state);
      var before = ProjectIO.JSON.valueToTree(state.project());
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      ((Noise) state.draft).id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      state.user.appearance.globalScale = 2.5f;
      assertTrue(owner.current(state));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void sameObjectWithANewerRevisionCannotSubmitItsOpeningForm() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var owner = new ResourceFormOwner(state);
      var project = state.project();
      state.revision++;
      assertSame(project, state.project());
      assertFalse(owner.current(state));
    }
  }

  @Test
  void equalProjectIdentityWithANewSessionCannotAdoptTheOldForm() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var owner = new ResourceFormOwner(state);
      var id = state.project().project.uuid;
      long revision = state.revision;
      state.document.newProject();
      state.project().project.uuid = id;
      assertEquals(revision, state.revision);
      assertFalse(owner.current(state));
    }
  }

  @Test
  void cancelledOwnershipCannotBecomeCurrentAndANewRequestHasADistinctNativeId() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var old = new ResourceFormOwner(state);
      String id = old.nativeId();
      old.close();
      old.close();
      var next = new ResourceFormOwner(state);
      assertFalse(old.current(state));
      assertTrue(next.current(state));
      assertNotEquals(id, next.nativeId());
      assertEquals(id, old.nativeId());
    }
  }

  @Test
  void pendingFormsRejectRetargetingAndResetClearsAllFormAndProjectBlockers() {
    for (String form : new String[] {"Create", "Placement", "Delete"})
      try (var state = new EditorState(new UserConfig(), false)) {
        assertTrue(
            state.change(
                p -> {
                  var n = new Noise();
                  n.id = "field";
                  p.noise2D.add(n);
                  var l = new Layer();
                  l.id = "layer";
                  p.worldGenerator.layers.add(l);
                }));
        var resources = new ResourcePanel(state, new PropertyPanel(state));
        var target =
            form.equals("Placement")
                ? state.project().worldGenerator.layers.getFirst().uuid
                : state.project().noise2D.getFirst().uuid;
        resources.probeOpenForm(form, target);
        String id = resources.probeFormId(form);
        assertTrue(resources.probeFormOpen(form));
        assertTrue(resources.blocksProjectTransition());
        resources.probeOpenForm("Create", target);
        assertEquals(id, resources.probeFormId(form));
        resources.reset();
        assertFalse(resources.probeFormOpen(form));
        assertFalse(resources.blocksProjectTransition());
        resources.probeOpenForm(form, target);
        assertNotEquals(id, resources.probeFormId(form));
        resources.reset();
      }
  }
}
