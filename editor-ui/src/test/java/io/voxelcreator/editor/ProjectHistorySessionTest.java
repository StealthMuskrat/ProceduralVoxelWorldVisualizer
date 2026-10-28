package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred contracts for private Properties ownership and committed project history. */
class ProjectHistorySessionTest {
  @TempDir Path dir;

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "owned";
              p.noise2D.add(noise);
              var folder = new Folder();
              folder.id = "folder";
              folder.rootKind = "noise2D";
              p.folders.add(folder);
            }));
    assertTrue(state.change(p -> p.noise2D.getFirst().seedOffset = 17));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  private void prepare(EditorState state, Direction direction) {
    if (direction == Direction.REDO) {
      state.document.undo();
      state.revision++;
      state.refreshDraft();
    }
  }

  @Test
  void unmodifiedHistoryMovesOnceRefreshesCanonicalValuesAndDoesNotChangePreferences() {
    try (var state = state()) {
      var user = ProjectIO.JSON.valueToTree(state.user);
      UUID source = state.selected;
      long revision = state.revision, bytes = state.document.historyBytes();
      var history = new ProjectHistorySession(state, () -> false);
      assertEquals(Result.MOVED, history.request(Direction.UNDO));
      assertEquals(0, ((Noise) state.draft).seedOffset);
      assertEquals(source, state.selected);
      assertEquals(revision + 1, state.revision);
      assertEquals(Result.MOVED, history.request(Direction.REDO));
      assertEquals(17, ((Noise) state.draft).seedOffset);
      assertEquals(revision + 2, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user));
    }
  }

  @Test
  void rawAndCanonicalInputNeedAnExplicitDecisionAndCancelPreservesEverything() {
    for (var direction : Direction.values())
      for (boolean raw : new boolean[] {false, true})
        try (var state = state()) {
          prepare(state, direction);
          if (raw) state.seedInputs.edit(state, "seedOffset", "Seed", "-");
          else ((Noise) state.draft).id = "pending";
          var draft = state.draft;
          var before = ProjectIO.JSON.valueToTree(state.project());
          long revision = state.revision, bytes = state.document.historyBytes();
          var history = new ProjectHistorySession(state, () -> false);
          assertEquals(Result.NEEDS_DECISION, history.request(direction));
          assertEquals(Result.CANCELLED, history.decide(Decision.CANCEL));
          assertSame(draft, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertEquals(revision, state.revision);
          assertEquals(bytes, state.document.historyBytes());
          if (raw) assertEquals("-", state.seedInputs.text("seedOffset"));
        }
  }

  @Test
  void discardMovesTheExistingDeltaWithoutApplyingOrAddingThePrivateDraft() {
    for (var direction : Direction.values())
      try (var state = state()) {
        prepare(state, direction);
        ((Noise) state.draft).seedOffset = 99;
        var history = new ProjectHistorySession(state, () -> false);
        history.request(direction);
        assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
        assertEquals(direction == Direction.UNDO ? 0 : 17, ((Noise) state.draft).seedOffset);
        assertEquals("owned", ((Noise) state.draft).id);
        assertEquals(
            Result.MOVED,
            history.request(direction == Direction.UNDO ? Direction.REDO : Direction.UNDO));
        assertEquals(direction == Direction.UNDO ? 17 : 0, ((Noise) state.draft).seedOffset);
      }
  }

  @Test
  void deletingSelectedResourceThroughUndoClearsSelectionAndRedoDoesNotReplayNavigation() {
    try (var state = state()) {
      UUID[] added = {null};
      state.change(
          p -> {
            var n = new Noise();
            n.id = "added";
            p.noise2D.add(n);
            added[0] = n.uuid;
          });
      state.select("noise2D", added[0]);
      state.seedInputs.edit(state, "seedOffset", "Seed", "+");
      var history = new ProjectHistorySession(state, () -> false);
      history.request(Direction.UNDO);
      assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
      assertNull(state.selected);
      assertNull(state.draft);
      assertEquals("", state.selectedKind);
      assertTrue(state.multiSelection.isEmpty());
      assertFalse(state.seedInputs.pending(state));
      assertEquals(Result.MOVED, history.request(Direction.REDO));
      assertNotNull(EditorState.find(state.project(), added[0]));
      assertNull(state.selected);
    }
  }

  @Test
  void historyScopeChangesRetainOnlySurvivingSiblingsOfTheActiveSelection() {
    try (var state = state()) {
      UUID a = state.selected, folder = state.project().folders.getFirst().uuid;
      UUID[] b = {null};
      state.change(
          p -> {
            var n = new Noise();
            n.id = "b";
            p.noise2D.add(n);
            b[0] = n.uuid;
          });
      state.change(p -> ResourceGraph.move(p, List.of(a), "noise2D", folder));
      state.change(p -> ResourceGraph.move(p, List.of(b[0]), "noise2D", folder));
      state.selectResources(List.of(a, b[0]), b[0], "noise2D:" + folder, true);
      var history = new ProjectHistorySession(state, () -> false);
      assertEquals(Result.MOVED, history.request(Direction.UNDO));
      assertEquals(b[0], state.selected);
      assertEquals(Set.of(b[0]), state.multiSelection);
      assertNull(((Noise) state.draft).parentFolderUuid);
    }
  }

  @Test
  void changedDocumentDraftRawSelectionOrSavedPathRejectsOlderDiscardDecisions() throws Exception {
    for (var direction : Direction.values())
      for (int mutation = 0; mutation < 6; mutation++)
        try (var state = state()) {
          prepare(state, direction);
          ((Noise) state.draft).id = "pending";
          var history = new ProjectHistorySession(state, () -> false);
          history.request(direction);
          switch (mutation) {
            case 0 -> state.change(p -> p.project.seed++);
            case 1 -> state.refreshDraft();
            case 2 -> ((Noise) state.draft).id = "newer";
            case 3 -> state.seedInputs.edit(state, "seedOffset", "Seed", "-");
            case 4 -> state.multiSelection.add(state.project().folders.getFirst().uuid);
            case 5 -> state.document.save(dir.resolve(UUID.randomUUID() + ".json"));
          }
          var before = ProjectIO.JSON.valueToTree(state.project());
          var draft = state.draft;
          assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
          assertSame(draft, state.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertFalse(history.pending());
        }
  }

  @Test
  void rejectedHistoryPreservesTheDocumentPrivateSeedAndBothStackDirections() {
    try (var state = state()) {
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      long revision = state.revision, bytes = state.document.historyBytes();
      var history = new ProjectHistorySession(state, () -> false);
      history.request(Direction.UNDO);
      state.document.setValidator(
          p ->
              List.of(
                  new Diagnostic(
                      "HISTORY_FIXTURE",
                      Diagnostic.Severity.ERROR,
                      "Rejected",
                      "",
                      null,
                      null,
                      null)));
      assertEquals(Result.REJECTED, history.decide(Decision.DISCARD));
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(revision, state.revision);
      assertEquals(bytes, state.document.historyBytes());
      assertTrue(state.document.canUndo());
      assertFalse(state.document.canRedo());
      state.document.setValidator(NoiseCompiler::validate);
      history.request(Direction.UNDO);
      assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
      assertFalse(state.seedInputs.pending(state));
    }
  }

  @Test
  void emptyHistoryHasNoPromptAndCannotDiscardEvenAnOrphanPrivateDraft() {
    try (var state = new EditorState(new UserConfig(), false)) {
      var draft = new Noise();
      state.draft = draft;
      state.selected = draft.uuid;
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var history =
          new ProjectHistorySession(
              state,
              () -> {
                fail("Empty history blocker");
                return false;
              });
      for (var direction : Direction.values())
        assertEquals(Result.UNAVAILABLE, history.request(direction));
      assertFalse(history.pending());
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(0, state.revision);
    }
  }

  @Test
  void aPendingDirectionCannotBeReplacedAndBlockersAreCheckedAgainAtConfirmation() {
    try (var state = state()) {
      ((Noise) state.draft).id = "pending";
      var blocked = new AtomicBoolean(true);
      var history = new ProjectHistorySession(state, blocked::get);
      assertEquals(Result.UNAVAILABLE, history.request(Direction.UNDO));
      blocked.set(false);
      assertEquals(Result.NEEDS_DECISION, history.request(Direction.UNDO));
      assertEquals(Result.UNAVAILABLE, history.request(Direction.REDO));
      assertEquals(Direction.UNDO, history.direction());
      blocked.set(true);
      assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
      assertEquals("pending", ((Noise) state.draft).id);
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
    }
  }

  @Test
  void historyChangesDirtyStateButNeverWritesTheExplicitSaveDestination() throws Exception {
    try (var state = state()) {
      var path = dir.resolve("saved.json");
      state.document.save(path);
      byte[] saved = Files.readAllBytes(path);
      var history = new ProjectHistorySession(state, () -> false);
      assertFalse(state.document.dirty());
      history.request(Direction.UNDO);
      assertTrue(state.document.dirty());
      assertArrayEquals(saved, Files.readAllBytes(path));
      history.request(Direction.REDO);
      assertFalse(state.document.dirty());
      assertArrayEquals(saved, Files.readAllBytes(path));
    }
  }

  @Test
  void saveBaselineChangesMakePendingDiscardStaleEvenWithoutAProjectRevisionChange()
      throws Exception {
    try (var state = state()) {
      var path = dir.resolve("baseline.json");
      state.document.save(path);
      state.change(p -> p.project.seed++);
      ((Noise) state.draft).id = "pending";
      var history = new ProjectHistorySession(state, () -> false);
      history.request(Direction.UNDO);
      long revision = state.revision;
      state.document.save(path);
      assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
      assertEquals(revision, state.revision);
      assertEquals("pending", ((Noise) state.draft).id);
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void projectFileAndHistoryDecisionsBlockEachOtherWithoutExecutingTheOtherAction() {
    try (var state = state()) {
      ProjectHistorySession[] holder = {null};
      var files =
          new ProjectFileWorkflow(
              state,
              () -> fail("replace"),
              () -> fail("exit"),
              () -> holder[0] != null && holder[0].pending());
      var history =
          new ProjectHistorySession(state, () -> files.phase() != ProjectFileWorkflow.Phase.IDLE);
      holder[0] = history;
      files.request(ProjectFileWorkflow.Intent.OPEN);
      assertEquals(Result.UNAVAILABLE, history.request(Direction.UNDO));
      files.cancel();
      ((Noise) state.draft).id = "pending";
      history.request(Direction.UNDO);
      files.request(ProjectFileWorkflow.Intent.EXIT);
      assertEquals(ProjectFileWorkflow.Phase.IDLE, files.phase());
      assertTrue(history.pending());
      history.decide(Decision.CANCEL);
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
    }
  }
}
