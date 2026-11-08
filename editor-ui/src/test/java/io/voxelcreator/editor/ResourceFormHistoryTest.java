package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Deferred raw/choice restoration and private-owner isolation for all three browser forms. */
class ResourceFormHistoryTest {
  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> {
      for (String name : List.of("first", "second")) {
        var noise = new Noise(); noise.id = name; p.noise2D.add(noise);
      }
      var layer = new Layer(); layer.id = "layer"; p.worldGenerator.layers.add(layer);
      var used = new VoxelType(); used.id = "used";
      var replacement = new VoxelType(); replacement.id = "replacement";
      p.voxelTypes.addAll(List.of(used, replacement));
      var group = new Membership(); group.id = "group";
      group.voxelTypeUuids.add(used.uuid); p.voxelGroups.add(group);
    }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private record Before(
      com.fasterxml.jackson.databind.JsonNode project,
      com.fasterxml.jackson.databind.JsonNode preferences,
      com.fasterxml.jackson.databind.JsonNode privateValues,
      Object draft, Object engine, long revision, long bytes, boolean dirty) {
    Before(EditorState state) {
      this(ProjectIO.JSON.valueToTree(state.project()), ProjectIO.JSON.valueToTree(state.user),
          ProjectIO.JSON.valueToTree(state.draft), state.draft, state.engine,
          state.revision, state.document.historyBytes(), state.document.dirty());
    }

    void unchanged(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      assertEquals(privateValues, ProjectIO.JSON.valueToTree(state.draft));
      assertSame(draft, state.draft);
      assertSame(engine, state.engine);
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void groupedTypingExactTextAndNoOpRedoHaveVersionOwnedRestoration() {
    try (var state = state()) {
      var owner = new ResourceFormOwner(state);
      var old = owner.capture();
      String scope = owner.interactionId();
      owner.record(state, "id", "", "e", "id:text");
      owner.record(state, "id", "e", "e\u0301", "id:text");
      assertEquals(scope, owner.interactionId());
      assertNull(owner.move(state, old, false));
      var change = owner.move(state, owner.capture(), false);
      assertEquals("", change.before());
      assertEquals("e\u0301", change.after());
      assertNotEquals(scope, owner.interactionId());
      assertFalse(owner.canUndo(state));
      assertTrue(owner.canRedo(state));
      var same = owner.capture();
      assertFalse(owner.record(state, "id", "", "", null));
      assertTrue(owner.owns(state, same));
      owner.record(state, "id", "", "+", "id:text");
      owner.record(state, "id", "+", "", "id:text");
      owner.finishEdit();
      assertTrue(owner.canRedo(state));
      assertEquals("e\u0301", owner.move(state, owner.capture(), true).after());
      assertFalse(owner.owns(state, same));
    }
  }

  @Test
  void foreignEqualValueRoundTripAndClosedTicketsCannotMoveHistory() {
    try (var state = state()) {
      var owner = new ResourceFormOwner(state);
      owner.record(state, "noise", "", "selected", null);
      var ticket = owner.capture();
      var other = new ResourceFormOwner(state);
      assertNull(owner.move(state, other.capture(), false));
      assertNotNull(owner.move(state, ticket, false));
      assertNotNull(owner.move(state, owner.capture(), true));
      assertNull(owner.move(state, ticket, false));
      owner.close();
      assertFalse(owner.canUndo(state));
      assertNull(owner.move(state, owner.capture(), false));
      assertThrows(IllegalStateException.class, () -> owner.record(state, "id", "a", "b", null));
    }
  }

  @Test
  void createRestoresRawIdAndOptionalAlgorithmAcrossFieldsWithoutPublishing() {
    try (var state = state(); var resources = new ResourcePanel(state, new PropertyPanel(state))) {
      var before = new Before(state);
      resources.probeOpenForm("Create", null);
      String raw = " invalid\n" + "é界".repeat(300);
      resources.probeFormName(raw);
      resources.probeFormAlgorithm("CONSTANT");
      resources.probeFormAlgorithm("GRADIENT_Y");
      assertTrue(resources.probeFormHistory("Create", false));
      assertEquals("CONSTANT", resources.probeCreationAlgorithm());
      assertEquals(raw, resources.probeFormValue("Create"));
      assertTrue(resources.probeFormHistory("Create", false));
      assertNull(resources.probeCreationAlgorithm());
      assertTrue(resources.probeFormHistory("Create", false));
      assertEquals("", resources.probeFormValue("Create"));
      assertTrue(resources.probeFormHistory("Create", true));
      assertEquals(raw, resources.probeFormValue("Create"));
      assertTrue(resources.probeFormHistory("Create", true));
      assertTrue(resources.probeFormHistory("Create", true));
      assertEquals("GRADIENT_Y", resources.probeCreationAlgorithm());
      before.unchanged(state);
    }
  }

  @Test
  void placementRestoresExactUuidAndNullSelectionWithNoOpPreservingRedo() {
    try (var state = state(); var resources = new ResourcePanel(state, new PropertyPanel(state))) {
      var before = new Before(state);
      resources.probeOpenForm("Placement", state.project().worldGenerator.layers.getFirst().uuid);
      UUID first = state.project().noise2D.getFirst().uuid, second = state.project().noise2D.getLast().uuid;
      resources.probePlacement(first);
      resources.probePlacement(second);
      assertTrue(resources.probeFormHistory("Placement", false));
      assertEquals(first.toString(), resources.probeFormValue("Placement"));
      resources.probePlacement(first);
      assertTrue(resources.probeFormOwner("Placement").canRedo(state));
      assertTrue(resources.probeFormHistory("Placement", false));
      assertEquals("", resources.probeFormValue("Placement"));
      assertTrue(resources.probeFormHistory("Placement", true));
      assertTrue(resources.probeFormHistory("Placement", true));
      assertEquals(second.toString(), resources.probeFormValue("Placement"));
      before.unchanged(state);
    }
  }

  @Test
  void deleteRestoresReplacementAndUnresolvedChoiceWithoutTouchingDependents() {
    try (var state = state(); var resources = new ResourcePanel(state, new PropertyPanel(state))) {
      var before = new Before(state);
      resources.probeOpenForm("Delete", state.project().voxelTypes.getFirst().uuid);
      UUID replacement = state.project().voxelTypes.getLast().uuid;
      resources.probeReplacement(replacement);
      assertTrue(resources.probeFormHistory("Delete", false));
      assertEquals("", resources.probeFormValue("Delete"));
      assertTrue(resources.probeFormHistory("Delete", true));
      assertEquals(replacement.toString(), resources.probeFormValue("Delete"));
      resources.probeReplacement(null);
      assertTrue(resources.probeFormHistory("Delete", false));
      assertEquals(replacement.toString(), resources.probeFormValue("Delete"));
      before.unchanged(state);
    }
  }

  @Test
  void resetConsumesEveryFormHistoryAndANewOpeningStartsEmpty() {
    for (String form : List.of("Create", "Placement", "Delete"))
      try (var state = state(); var resources = new ResourcePanel(state, new PropertyPanel(state))) {
        var before = new Before(state);
        UUID target = form.equals("Placement") ? state.project().worldGenerator.layers.getFirst().uuid
            : state.project().voxelTypes.getFirst().uuid;
        resources.probeOpenForm(form, target);
        if (form.equals("Create")) resources.probeFormName("new");
        else if (form.equals("Placement")) resources.probePlacement(state.project().noise2D.getFirst().uuid);
        else resources.probeReplacement(state.project().voxelTypes.getLast().uuid);
        var old = resources.probeFormOwner(form);
        assertTrue(old.canUndo(state));
        resources.reset();
        assertFalse(old.canUndo(state));
        assertNull(old.move(state, old.capture(), false));
        resources.probeOpenForm(form, target);
        assertFalse(resources.probeFormOwner(form).canUndo(state));
        assertFalse(resources.probeFormHistory(form, true));
        if (form.equals("Create")) resources.probeFormName("closing");
        else if (form.equals("Placement")) resources.probePlacement(state.project().noise2D.getFirst().uuid);
        else resources.probeReplacement(state.project().voxelTypes.getLast().uuid);
        var closing = resources.probeFormOwner(form);
        assertTrue(closing.canUndo(state));
        resources.close();
        assertFalse(closing.canUndo(state));
        assertFalse(resources.probeFormOpen(form));
        before.unchanged(state);
      }
  }

  @Test
  void revisionOrSameUuidDocumentReplacementBlocksRestorationAndPreservesPendingText() {
    for (boolean replace : new boolean[] {false, true})
      try (var state = state(); var resources = new ResourcePanel(state, new PropertyPanel(state))) {
        resources.probeOpenForm("Create", null);
        resources.probeFormName("pending");
        var owner = resources.probeFormOwner("Create");
        if (replace) {
          UUID id = state.project().project.uuid;
          state.document.newProject(); state.project().project.uuid = id;
        } else state.revision++;
        assertFalse(owner.canUndo(state));
        assertFalse(resources.probeFormHistory("Create", false));
        assertEquals("pending", resources.probeFormValue("Create"));
      }
  }
}
