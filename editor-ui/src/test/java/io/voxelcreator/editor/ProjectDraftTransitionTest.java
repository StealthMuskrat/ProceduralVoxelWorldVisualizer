package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred definitions for provisional Properties input across explicit file transitions. */
class ProjectDraftTransitionTest {
  @TempDir Path dir;

  private EditorState state(boolean saved) throws Exception {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              p.project.seed = 41;
              for (String id : List.of("owned", "other")) {
                var noise = new Noise();
                noise.id = id;
                p.noise2D.add(noise);
              }
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    if (saved) state.document.save(dir.resolve(UUID.randomUUID() + ".json"));
    return state;
  }

  @Test
  void cleanDocumentStillProtectsCanonicalAndRawOnlyDraftsForEveryIntent() throws Exception {
    for (var intent : Intent.values())
      for (boolean raw : new boolean[] {false, true})
        try (var state = state(true)) {
          var before = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          else ((Noise) draft).id = "pending";
          var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
          workflow.request(intent);
          assertEquals(Phase.DRAFT, workflow.phase());
          workflow.decideDraft(DraftDecision.CANCEL);
          assertEquals(Phase.IDLE, workflow.phase());
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertSame(draft, state.draft);
          assertFalse(state.document.dirty());
          if (raw) assertEquals("-", state.seedInputs.text("seedOffset"));
        }
  }

  @Test
  void discardForOpenKeepsDraftThroughPathCancellationAndFailedOpen() throws Exception {
    for (boolean saved : new boolean[] {false, true})
      try (var state = state(saved)) {
        state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        var draft = state.draft;
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision, history = state.document.historyBytes();
        var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
        workflow.request(Intent.OPEN);
        workflow.decideDraft(DraftDecision.DISCARD);
        if (workflow.phase() == Phase.GUARD) workflow.decide(Decision.DISCARD);
        assertEquals(Phase.OPEN, workflow.phase());
        workflow.submit(dir.resolve("missing.json").toString());
        assertEquals(Phase.OPEN, workflow.phase());
        workflow.cancel();
        assertSame(draft, state.draft);
        assertEquals("+", state.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(!saved, state.document.dirty());
        assertEquals(revision, state.revision);
        assertEquals(history, state.document.historyBytes());
      }
  }

  @Test
  void applyPrecedesSaveGuardAndLaterCancelKeepsOneUndoableApply() throws Exception {
    for (var intent : Intent.values())
      try (var state = state(true)) {
        var before = ProjectIO.JSON.valueToTree(state.project());
        ((Noise) state.draft).seedOffset = Long.MIN_VALUE;
        long revision = state.revision;
        var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
        workflow.request(intent);
        workflow.decideDraft(DraftDecision.APPLY);
        assertEquals(Phase.GUARD, workflow.phase());
        assertEquals(revision + 1, state.revision);
        assertEquals(Long.MIN_VALUE, state.project().noise2D.getFirst().seedOffset);
        workflow.decide(Decision.CANCEL);
        assertTrue(state.document.dirty());
        state.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void invalidRawOrCanonicalApplyCannotAdvanceIntoSaveOrDestructiveTransition() throws Exception {
    for (var intent : Intent.values())
      for (boolean raw : new boolean[] {false, true})
        try (var state = state(true)) {
          if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "9223372036854775808");
          else ((Noise) state.draft).id = " invalid ";
          var before = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          long revision = state.revision, history = state.document.historyBytes();
          var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
          workflow.request(intent);
          workflow.decideDraft(DraftDecision.APPLY);
          assertEquals(Phase.DRAFT, workflow.phase());
          assertFalse(workflow.error().isEmpty());
          assertSame(draft, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertEquals(revision, state.revision);
          assertEquals(history, state.document.historyBytes());
          workflow.cancel();
        }
  }

  @Test
  void discardThenSaveWritesOnlyCommittedDataAndCanceledOpenRetainsPrivateText() throws Exception {
    try (var state = state(true)) {
      Path path = state.document.path();
      state.change(p -> p.project.seed = 42);
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var draft = state.draft;
      var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
      workflow.request(Intent.OPEN);
      workflow.decideDraft(DraftDecision.DISCARD);
      workflow.decide(Decision.SAVE);
      assertEquals(Phase.OPEN, workflow.phase());
      workflow.cancel();
      var loaded = new ProjectIO().load(path).project();
      assertEquals(42, loaded.project.seed);
      assertEquals(0, loaded.noise2D.getFirst().seedOffset);
      assertEquals("owned", loaded.noise2D.getFirst().id);
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void standaloneSaveRemainsCommittedOnlyAndDoesNotNeedPrivateEditorPermission() throws Exception {
    try (var state = state(true)) {
      state.change(p -> p.project.seed = 42);
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var workflow =
          new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"), () -> true);
      workflow.save(false);
      assertEquals(Phase.IDLE, workflow.phase());
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(42, new ProjectIO().load(state.document.path()).project().project.seed);
      workflow.request(Intent.NEW);
      assertEquals(Phase.IDLE, workflow.phase());
      assertFalse(workflow.error().isEmpty());
      assertEquals(2, state.project().noise2D.size());
    }
  }

  @Test
  void projectDraftSelectionIdentityRawOrPathChangesInvalidateEveryPendingPhase() throws Exception {
    for (var phase : List.of(Phase.DRAFT, Phase.GUARD, Phase.OPEN, Phase.SAVE))
      for (int mutation = 0; mutation < 6; mutation++)
        try (var state = state(true)) {
          var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
          if (phase == Phase.DRAFT) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          if (phase == Phase.GUARD) state.change(p -> p.project.seed++);
          if (phase == Phase.SAVE) workflow.save(true);
          else workflow.request(phase == Phase.OPEN ? Intent.OPEN : Intent.EXIT);
          assertEquals(phase, workflow.phase());
          switch (mutation) {
            case 0 -> state.change(p -> p.project.seed++);
            case 1 -> state.refreshDraft();
            case 2 -> ((Noise) state.draft).id = "newer";
            case 3 -> state.seedInputs.edit(state, "seedOffset", "Seed", "--");
            case 4 -> state.multiSelection.add(state.project().noise2D.getLast().uuid);
            case 5 -> state.document.save(dir.resolve(UUID.randomUUID() + "-other.json"));
          }
          var before = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          Path target = dir.resolve(UUID.randomUUID() + "-never.json");
          if (phase == Phase.DRAFT) workflow.decideDraft(DraftDecision.DISCARD);
          else if (phase == Phase.GUARD) workflow.decide(Decision.DISCARD);
          else workflow.submit(target.toString());
          assertEquals(Phase.IDLE, workflow.phase());
          assertTrue(workflow.error().contains("changed"));
          assertSame(draft, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertFalse(Files.exists(target));
        }
  }

  @Test
  void externalSaveToSamePathInvalidatesADirtySaveAsBaseline() throws Exception {
    try (var state = state(true)) {
      state.change(p -> p.project.seed++);
      var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"));
      workflow.save(true);
      state.document.save(state.document.path());
      var target = dir.resolve("no-stale-save.json");
      workflow.submit(target.toString());
      assertEquals(Phase.IDLE, workflow.phase());
      assertFalse(Files.exists(target));
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void blockerAppearingAfterARequestAbortsInsteadOfReplacingItsPrivateEditor() throws Exception {
    try (var state = state(true)) {
      ((Noise) state.draft).id = "pending";
      var blocked = new AtomicBoolean();
      var workflow =
          new ProjectFileWorkflow(state, () -> fail("replace"), () -> fail("exit"), blocked::get);
      workflow.request(Intent.EXIT);
      blocked.set(true);
      workflow.decideDraft(DraftDecision.DISCARD);
      assertEquals(Phase.IDLE, workflow.phase());
      assertEquals("pending", ((Noise) state.draft).id);
    }
  }

  @Test
  void successfulNewAndOpenReleaseOldSeedOwnershipExactlyOnce() throws Exception {
    for (var intent : List.of(Intent.NEW, Intent.OPEN))
      try (var state = state(true)) {
        state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        int[] replacements = {0};
        var workflow = new ProjectFileWorkflow(state, () -> replacements[0]++, () -> fail("exit"));
        workflow.request(intent);
        workflow.decideDraft(DraftDecision.DISCARD);
        if (intent == Intent.OPEN) {
          var incoming = new ProjectData();
          incoming.project.seed = 99;
          var path = dir.resolve("incoming.json");
          new ProjectIO().save(path, incoming);
          workflow.submit(path.toString());
        }
        assertEquals(Phase.IDLE, workflow.phase());
        assertEquals(1, replacements[0]);
        assertFalse(state.seedInputs.pending(state));
        assertNull(state.draft);
        assertNull(state.selected);
        assertTrue(state.multiSelection.isEmpty());
        assertFalse(state.document.canUndo());
      }
  }

  @Test
  void failedRequiredSaveAfterApplyAbortsExitAndRetryOnlySavesTheAppliedDefinition()
      throws Exception {
    try (var state = state(false)) {
      ((Noise) state.draft).seedOffset = Long.MAX_VALUE;
      int[] exits = {0};
      var workflow = new ProjectFileWorkflow(state, () -> fail("replace"), () -> exits[0]++);
      workflow.request(Intent.EXIT);
      workflow.decideDraft(DraftDecision.APPLY);
      workflow.decide(Decision.SAVE);
      workflow.submit("\u0000");
      assertEquals(Phase.SAVE, workflow.phase());
      var path = dir.resolve("applied.json");
      workflow.submit(path.toString());
      assertEquals(Phase.IDLE, workflow.phase());
      assertEquals(0, exits[0]);
      assertEquals(
          Long.MAX_VALUE, new ProjectIO().load(path).project().noise2D.getFirst().seedOffset);
      workflow.request(Intent.EXIT);
      assertEquals(1, exits[0]);
    }
  }

  @Test
  void browserPrivateInteractionsAndRawProjectSettingsExposeTransitionBlockers() throws Exception {
    try (var state = state(true)) {
      var settings = new ProjectSettingsSession(state);
      assertFalse(settings.modified());
      settings.edit("seed", "-");
      assertTrue(settings.modified());
      settings.cancel();
      assertFalse(settings.modified());
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      assertFalse(resources.blocksProjectTransition());
      resources.shortcut("Rename");
      assertTrue(resources.blocksProjectTransition());
      var workflow =
          new ProjectFileWorkflow(
              state, () -> fail("replace"), () -> fail("exit"), resources::blocksProjectTransition);
      workflow.request(Intent.NEW);
      assertEquals(Phase.IDLE, workflow.phase());
      resources.reset();
      assertFalse(resources.blocksProjectTransition());
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      resources.shortcut("Duplicate");
      assertTrue(resources.blocksProjectTransition());
    }
  }
}
