package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectHistorySession.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class ProjectHistoryCompletionTest {
  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var n = new Noise();
              n.id = "owned";
              p.noise2D.add(n);
            }));
    assertTrue(state.change(p -> p.noise2D.getFirst().seedOffset = 17));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    return state;
  }

  @Test
  void aRequestGateCannotOverwriteOrClearItsReentrantSuccessorEvenWhenItThrows() {
    for (boolean fail : new boolean[] {false, true})
      try (var state = state()) {
        var once = new AtomicBoolean(true);
        var holder = new ProjectHistorySession[1];
        var before = ProjectIO.JSON.valueToTree(state.project());
        var history =
            new ProjectHistorySession(
                state,
                () -> {
                  if (once.getAndSet(false)) {
                    ((Noise) state.draft).id = "successor";
                    assertEquals(Result.NEEDS_DECISION, holder[0].request(Direction.UNDO));
                    if (fail) throw new IllegalStateException("request boundary");
                  }
                  return false;
                });
        holder[0] = history;
        assertEquals(fail ? Result.REJECTED : Result.UNAVAILABLE, history.request(Direction.UNDO));
        assertTrue(history.pending());
        assertEquals(1, history.requestVersion());
        assertEquals("successor", ((Noise) state.draft).id);
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(Result.CANCELLED, history.decide(Decision.CANCEL));
      }
  }

  @Test
  void aDecisionGateCannotOverwriteOrClearItsReentrantSuccessorEvenWhenItThrows() {
    for (boolean fail : new boolean[] {false, true})
      try (var state = state()) {
        var calls = new AtomicInteger();
        var holder = new ProjectHistorySession[1];
        var before = ProjectIO.JSON.valueToTree(state.project());
        var history =
            new ProjectHistorySession(
                state,
                () -> {
                  if (calls.incrementAndGet() == 2) {
                    assertEquals(Result.CANCELLED, holder[0].decide(Decision.CANCEL));
                    state.seedInputs.edit(state, "seedOffset", "Seed", "--");
                    assertEquals(Result.NEEDS_DECISION, holder[0].request(Direction.UNDO));
                    if (fail) throw new IllegalStateException("decision boundary");
                  }
                  return false;
                });
        holder[0] = history;
        history.request(Direction.UNDO);
        long old = history.requestVersion();
        assertEquals(fail ? Result.REJECTED : Result.UNAVAILABLE, history.decide(Decision.DISCARD));
        assertTrue(history.pending());
        assertEquals(old + 1, history.requestVersion());
        assertEquals("--", state.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        history.decide(Decision.CANCEL);
      }
  }

  @Test
  void gatesThatChangePrivateInputCannotAuthorizeAnOldRequestOrDecision() {
    for (boolean decision : new boolean[] {false, true})
      try (var state = state()) {
        var calls = new AtomicInteger();
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision;
        var history =
            new ProjectHistorySession(
                state,
                () -> {
                  if (calls.incrementAndGet() == (decision ? 2 : 1))
                    state.seedInputs.edit(state, "seedOffset", "Seed", "newer-invalid");
                  return false;
                });
        if (decision) {
          assertEquals(Result.NEEDS_DECISION, history.request(Direction.UNDO));
          assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
        } else assertEquals(Result.UNAVAILABLE, history.request(Direction.UNDO));
        assertFalse(history.pending());
        assertEquals("newer-invalid", state.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(revision, state.revision);
      }
  }

  @Test
  void aThrowingGateRetiresOnlyItsOldDecisionAndCannotReplayIt() {
    try (var state = state()) {
      var calls = new AtomicInteger();
      var history =
          new ProjectHistorySession(
              state,
              () -> {
                if (calls.incrementAndGet() > 1) throw new IllegalStateException("x".repeat(4000));
                return false;
              });
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      history.request(Direction.UNDO);
      assertEquals(Result.REJECTED, history.decide(Decision.DISCARD));
      assertFalse(history.busy());
      assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
      assertEquals(2, calls.get());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(revision, state.revision);
      assertEquals("private", ((Noise) state.draft).id);
      var error = state.events.view().entries().getFirst();
      assertEquals("PROJECT_HISTORY_GUARD_FAILED", error.code());
      assertEquals(1, error.count());
      assertTrue(error.message().length() <= 1024);
    }
  }

  @Test
  void validationAndRefreshCannotStartAnotherHistoryOrProjectTransition() {
    try (var state = state()) {
      var holder = new ProjectHistorySession[1];
      var validations = new AtomicInteger();
      var files =
          new ProjectFileWorkflow(
              state, () -> fail("replace"), () -> fail("exit"), () -> holder[0].busy());
      var history =
          new ProjectHistorySession(
              state,
              () -> false,
              () -> {
                assertTrue(holder[0].busy());
                assertFalse(holder[0].pending());
                assertEquals(Result.UNAVAILABLE, holder[0].request(Direction.REDO));
                state.reconcileSelection(state.multiSelection, state.selected);
              });
      holder[0] = history;
      state.document.setValidator(
          p -> {
            validations.incrementAndGet();
            assertTrue(history.busy());
            assertFalse(history.pending());
            assertEquals(Result.UNAVAILABLE, history.request(Direction.UNDO));
            assertEquals(Result.UNAVAILABLE, history.decide(Decision.CANCEL));
            files.request(ProjectFileWorkflow.Intent.NEW);
            assertEquals(ProjectFileWorkflow.Phase.IDLE, files.phase());
            return NoiseCompiler.validate(p);
          });
      history.request(Direction.UNDO);
      long revision = state.revision;
      assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
      assertEquals(1, validations.get());
      assertFalse(history.busy());
      assertEquals(0, state.project().noise2D.getFirst().seedOffset);
      assertEquals(revision + 1, state.revision);
    }
  }

  @Test
  void aRefreshFailurePreservesOneMovedDeltaAndAllowsOnlyAnExplicitNewAction() {
    try (var state = state()) {
      var fail = new AtomicBoolean(true);
      var calls = new AtomicInteger();
      var history =
          new ProjectHistorySession(
              state,
              () -> false,
              () -> {
                calls.incrementAndGet();
                state.reconcileSelection(state.multiSelection, state.selected);
                if (fail.get()) throw new IllegalStateException("refresh boundary");
              });
      long revision = state.revision;
      history.request(Direction.UNDO);
      assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
      assertEquals(0, state.project().noise2D.getFirst().seedOffset);
      assertEquals(revision + 1, state.revision);
      assertFalse(history.busy());
      assertEquals(Result.UNAVAILABLE, history.decide(Decision.DISCARD));
      assertEquals(1, calls.get());
      var error = state.events.view().entries().getFirst();
      assertEquals("PROJECT_HISTORY_REFRESH_FAILED", error.code());
      assertTrue(error.message().startsWith("Undo completed;"));
      assertEquals(1, error.count());
      fail.set(false);
      assertEquals(Result.MOVED, history.request(Direction.REDO));
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
      assertEquals(revision + 2, state.revision);
      assertEquals(2, calls.get());
    }
  }

  @Test
  void failedModelHistoryReleasesTheMoveFenceAndPreservesPrivateValuesAndStacks() {
    try (var state = state()) {
      var refreshes = new AtomicInteger();
      var history = new ProjectHistorySession(state, () -> false, refreshes::incrementAndGet);
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      long revision = state.revision, bytes = state.document.historyBytes();
      history.request(Direction.UNDO);
      state.document.setValidator(
          p -> {
            throw new IllegalStateException("model boundary");
          });
      assertEquals(Result.REJECTED, history.decide(Decision.DISCARD));
      assertFalse(history.busy());
      assertEquals(0, refreshes.get());
      assertSame(draft, state.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(bytes, state.document.historyBytes());
      assertEquals(revision, state.revision);
      assertTrue(state.document.canUndo());
      assertFalse(state.document.canRedo());
      state.document.setValidator(NoiseCompiler::validate);
      history.request(Direction.UNDO);
      assertEquals(Result.MOVED, history.decide(Decision.DISCARD));
      assertFalse(history.busy());
      assertEquals(1, refreshes.get());
    }
  }

  @Test
  void cancelKeepsPrivateValuesWithoutConsultingAChangedGate() {
    try (var state = state()) {
      var calls = new AtomicInteger();
      var history =
          new ProjectHistorySession(
              state,
              () -> {
                if (calls.incrementAndGet() > 1) throw new IllegalStateException("cancel gate");
                return false;
              });
      history.request(Direction.UNDO);
      assertEquals(Result.CANCELLED, history.decide(Decision.CANCEL));
      assertEquals(1, calls.get());
      assertFalse(history.busy());
      assertEquals("private", ((Noise) state.draft).id);
      assertEquals(17, state.project().noise2D.getFirst().seedOffset);
      assertTrue(state.events.view().entries().isEmpty());
    }
  }
}
