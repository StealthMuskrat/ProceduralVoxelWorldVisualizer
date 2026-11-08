package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred choice history; no project transaction is produced until explicit reviewed Import. */
class ImportChoiceHistoryTest {
  @TempDir Path directory;

  private static Noise noise(String id, double value) {
    var noise = new Noise();
    noise.id = id;
    noise.algorithm.key = "CONSTANT";
    noise.algorithm.parameters.clear();
    noise.algorithm.parameters.put("value", value);
    return noise;
  }

  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> p.noise2D.addAll(List.of(noise("editing", .125), noise("shared", .25)))));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private-name";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private static ProjectData incoming() {
    var incoming = new ProjectData();
    incoming.noise2D.addAll(List.of(noise("shared", .25), noise("added", .75)));
    return incoming;
  }

  private static ResourceImport.Row row(ResourceImport.Plan plan, UUID id) {
    return plan.rows().stream().filter(row -> row.uuid().equals(id)).findFirst().orElseThrow();
  }

  private record Before(
      JsonNode project, JsonNode draft, JsonNode user, Object owner, UUID selected,
      Set<UUID> resources, long revision, long history, boolean dirty) {
    Before(EditorState state) {
      this(ProjectIO.JSON.valueToTree(state.project()), ProjectIO.JSON.valueToTree(state.draft),
          ProjectIO.JSON.valueToTree(state.user), state.draft, state.selected,
          Set.copyOf(state.multiSelection), state.revision, state.document.historyBytes(), state.document.dirty());
    }

    void unchanged(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(draft, ProjectIO.JSON.valueToTree(state.draft));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
      assertSame(owner, state.draft);
      assertEquals(selected, state.selected);
      assertEquals(resources, state.multiSelection);
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
      assertEquals("-", state.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void undoRedoRestoresExactChoicesAndCopyIdentitiesWithoutTouchingParentState() {
    try (var state = state()) {
      var before = new Before(state);
      var incoming = incoming();
      UUID shared = incoming.noise2D.getFirst().uuid, added = incoming.noise2D.getLast().uuid;
      var edit = new ImportEditSession(state, incoming);
      var initial = edit.preview();
      UUID copied = row(initial, shared).targetUuid();
      edit.choose(shared, ResourceImport.Choice.REUSE);
      edit.choose(added, ResourceImport.Choice.SKIP);
      var changed = ProjectIO.JSON.valueToTree(edit.preview().candidate());
      assertTrue(edit.undo());
      assertTrue(edit.undo());
      assertEquals(Map.of(), edit.choices());
      assertEquals(copied, row(edit.preview(), shared).targetUuid());
      assertEquals(ProjectIO.JSON.valueToTree(initial.candidate()), ProjectIO.JSON.valueToTree(edit.preview().candidate()));
      assertTrue(edit.redo());
      assertTrue(edit.redo());
      assertEquals(changed, ProjectIO.JSON.valueToTree(edit.preview().candidate()));
      before.unchanged(state);
      edit.cancel();
    }
  }

  @Test
  void noopsAndInvalidResourcesPreserveReviewedPlansRedoAndChoiceVersion() {
    try (var state = state()) {
      var incoming = incoming();
      UUID id = incoming.noise2D.getLast().uuid;
      var edit = new ImportEditSession(state, incoming);
      var initial = edit.preview();
      edit.markShown(initial);
      edit.choose(id, ResourceImport.Choice.COPY);
      assertSame(initial, edit.preview());
      assertTrue(edit.canApply());
      edit.choose(id, ResourceImport.Choice.SKIP);
      assertTrue(edit.undo());
      var restored = edit.preview();
      edit.markShown(restored);
      long version = edit.choiceVersion();
      edit.choose(id, ResourceImport.Choice.COPY);
      assertThrows(IllegalArgumentException.class, () -> edit.choose(UUID.randomUUID(), ResourceImport.Choice.COPY));
      assertFalse(edit.undo());
      assertEquals(version, edit.choiceVersion());
      assertSame(restored, edit.preview());
      assertTrue(edit.canApply());
      assertTrue(edit.canRedo());
      assertTrue(edit.redo());
      assertFalse(edit.canApply());
      edit.cancel();
    }
  }

  @Test
  void effectiveEditsDiscardRedoAndRetentionKeepsOneHundredLocalSteps() {
    try (var state = state()) {
      var incoming = incoming();
      UUID id = incoming.noise2D.getLast().uuid, other = incoming.noise2D.getFirst().uuid;
      var edit = new ImportEditSession(state, incoming);
      edit.choose(id, ResourceImport.Choice.SKIP);
      assertTrue(edit.undo());
      edit.choose(other, ResourceImport.Choice.REUSE);
      assertFalse(edit.canRedo());
      edit.reload();
      for (int i = 0; i < 105; i++)
        edit.choose(id, i % 2 == 0 ? ResourceImport.Choice.SKIP : ResourceImport.Choice.COPY);
      int undone = 0;
      while (edit.undo()) undone++;
      assertEquals(100, undone);
      assertEquals(ResourceImport.Choice.SKIP, edit.choice(id));
      assertEquals(ResourceImport.Choice.REUSE, edit.choice(other));
      int redone = 0;
      while (edit.redo()) redone++;
      assertEquals(100, redone);
      edit.cancel();
    }
  }

  @Test
  void rejectionCanBeUndoneAndOnlyReviewedFinalImportEntersProjectHistory() {
    try (var state = state()) {
      var before = new Before(state);
      var incoming = incoming();
      var edit = new ImportEditSession(state, incoming);
      edit.choose(incoming.noise2D.getLast().uuid, ResourceImport.Choice.SKIP);
      edit.markShown(edit.preview());
      state.document.setValidator(p -> List.of(Diagnostic.error("REJECTED", "/fixture", "reject")));
      assertEquals(ImportEditSession.Result.REJECTED, edit.apply());
      assertEquals(1, edit.issueCount());
      assertTrue(edit.undo());
      assertEquals(0, edit.issueCount());
      assertEquals(ImportEditSession.Result.NOT_REVIEWED, edit.apply());
      before.unchanged(state);
      state.document.setValidator(NoiseCompiler::validate);
      edit.markShown(edit.preview());
      assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
      assertFalse(edit.canUndo());
      assertFalse(edit.canRedo());
      assertEquals(before.revision() + 1, state.revision);
      assertEquals("private-name", ((Noise) state.draft).id);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      var accepted = ProjectIO.JSON.valueToTree(state.project());
      state.document.undo();
      assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
      state.document.redo();
      assertEquals(accepted, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void explicitReloadRetainsChoicesButStartsANewIdentityAndHistoryBaseline() {
    try (var state = state()) {
      var incoming = incoming();
      UUID id = incoming.noise2D.getLast().uuid, shared = incoming.noise2D.getFirst().uuid;
      var edit = new ImportEditSession(state, incoming);
      UUID oldCopy = row(edit.preview(), shared).targetUuid();
      edit.choose(id, ResourceImport.Choice.SKIP);
      assertTrue(state.change(p -> p.project.seed++));
      assertFalse(edit.canUndo());
      assertThrows(IllegalStateException.class, edit::undo);
      edit.reload();
      assertFalse(edit.canUndo());
      assertFalse(edit.canRedo());
      assertEquals(ResourceImport.Choice.SKIP, edit.choice(id));
      assertNotEquals(oldCopy, row(edit.preview(), shared).targetUuid());
      edit.choose(id, ResourceImport.Choice.COPY);
      assertTrue(edit.undo());
      assertEquals(ResourceImport.Choice.SKIP, edit.choice(id));
      assertFalse(edit.canUndo());
      edit.cancel();
    }
  }

  @Test
  void cancelAndSameUuidReopenCannotReuseAnEarlierHistory() throws Exception {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var incoming = incoming();
        var edit = new ImportEditSession(state, incoming);
        edit.choose(incoming.noise2D.getLast().uuid, ResourceImport.Choice.SKIP);
        if (cancel) edit.cancel();
        else {
          Path file = directory.resolve("same-project.json");
          state.document.save(file);
          assertTrue(state.document.open(file).isEmpty());
        }
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        assertThrows(IllegalStateException.class, edit::redo);
        edit.cancel();
      }
  }

  @Test
  void undoSupersedesQueuedAndReadyPlanningWithoutRevivingAnOlderReceipt() {
    for (boolean ready : List.of(false, true))
      try (var state = state()) {
        var before = new Before(state);
        var incoming = incoming();
        UUID id = incoming.noise2D.getLast().uuid;
        var work = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var edit = new ImportEditSession(state, incoming, work::add, ui::add, () -> false);
        UUID copied = row(edit.preview(), id).targetUuid();
        edit.choose(id, ResourceImport.Choice.SKIP);
        var oldWork = work.remove();
        Runnable oldReceipt = null;
        if (ready) {
          oldWork.run();
          oldReceipt = ui.remove();
          oldReceipt.run();
        }
        assertTrue(edit.undo());
        oldWork.run();
        if (oldReceipt != null) oldReceipt.run();
        edit.acceptPlanning();
        assertTrue(edit.planning());
        assertFalse(edit.canApply());
        work.remove().run();
        ui.remove().run();
        edit.acceptPlanning();
        assertFalse(edit.planning());
        assertEquals(copied, row(edit.preview(), id).targetUuid());
        assertEquals(ResourceImport.Choice.COPY, edit.choice(id));
        assertTrue(edit.canRedo());
        assertFalse(edit.canApply());
        before.unchanged(state);
        edit.cancel();
      }
  }

  @Test
  void schedulingFailureRetainsChoiceHistoryForExplicitUndoAndPlanningRetry() {
    try (var state = state()) {
      var incoming = incoming();
      UUID id = incoming.noise2D.getLast().uuid;
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var attempts = new AtomicInteger();
      var edit = new ImportEditSession(state, incoming, task -> {
        if (attempts.getAndIncrement() == 0) throw new RejectedExecutionException("fixture");
        work.add(task);
      }, ui::add, () -> false);
      edit.choose(id, ResourceImport.Choice.SKIP);
      assertFalse(edit.planning());
      assertTrue(edit.canUndo());
      assertTrue(edit.undo());
      work.remove().run();
      ui.remove().run();
      edit.acceptPlanning();
      assertEquals(ResourceImport.Choice.COPY, edit.choice(id));
      assertTrue(edit.canRedo());
      assertFalse(edit.canApply());
      edit.cancel();
    }
  }

  @Test
  void aCapturedHistoryDecisionCannotUseSuccessorChoicesWhileTheOldPlanIsStillVisible() {
    try (var state = state()) {
      var incoming = incoming();
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var panel = new ResourceImportPanel(state, () -> false, () -> {}, work::add, ui::add)) {
        assertTrue(panel.open(incoming));
        var edit = panel.session();
        var old = panel.capture(17);
        UUID id = incoming.noise2D.getLast().uuid;
        edit.choose(id, ResourceImport.Choice.SKIP);
        assertSame(old.preview(), edit.preview());
        panel.dispatch(old, ResourceImportPanel.Command.UNDO, 17);
        assertEquals(ResourceImport.Choice.SKIP, edit.choice(id));
        assertEquals(1, work.size());
        panel.dispatch(panel.capture(18), ResourceImportPanel.Command.UNDO, 18);
        assertEquals(ResourceImport.Choice.COPY, edit.choice(id));
        assertEquals(2, work.size());
        assertTrue(edit.canRedo());
      }
    }
  }

  @Test
  void applyingLocksChoicesAndSuccessfulOrCancelledApplyConsumesLocalHistory() {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var before = new Before(state);
        var incoming = incoming();
        var work = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        var edit = new ImportEditSession(state, incoming, work::add, ui::add, () -> false);
        edit.choose(incoming.noise2D.getFirst().uuid, ResourceImport.Choice.REUSE);
        work.remove().run();
        ui.remove().run();
        edit.acceptPlanning();
        edit.markShown(edit.preview());
        assertEquals(ImportEditSession.Result.STARTED, edit.apply());
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        assertThrows(IllegalStateException.class, edit::undo);
        var pending = work.remove();
        if (cancel) edit.cancel();
        pending.run();
        while (!ui.isEmpty()) ui.remove().run();
        edit.acceptPrepared();
        assertTrue(edit.closed());
        assertFalse(edit.canUndo());
        assertFalse(edit.canRedo());
        if (cancel) before.unchanged(state);
        else {
          assertEquals(before.revision() + 1, state.revision);
          state.document.undo();
          assertEquals(before.project(), ProjectIO.JSON.valueToTree(state.project()));
        }
      }
  }
}
