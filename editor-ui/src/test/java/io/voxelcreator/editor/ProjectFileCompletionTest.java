package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectFileCompletionTest {
  @TempDir Path directory;

  private static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              p.project.seed = 41;
              var n = new Noise();
              n.id = "owned";
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    return state;
  }

  private static void arm(ProjectFileWorkflow files, Phase phase) {
    files.request(Intent.OPEN);
    if (phase != Phase.DRAFT) files.decideDraft(DraftDecision.DISCARD);
    if (phase == Phase.OPEN) files.decide(Decision.DISCARD);
    else if (phase == Phase.SAVE) files.decide(Decision.SAVE);
    assertEquals(phase, files.phase());
  }

  private Path candidate(String name, long seed) throws Exception {
    var p = new ProjectData();
    p.project.seed = seed;
    var file = directory.resolve(name);
    new ProjectIO().save(file, p);
    return file;
  }

  @Test
  void aRequestBlockerCannotReplaceOrClearItsSuccessorEvenWhenItThrows() {
    for (boolean fail : new boolean[] {false, true})
      try (var state = state()) {
        var once = new AtomicBoolean(true);
        var holder = new ProjectFileWorkflow[1];
        var files =
            new ProjectFileWorkflow(
                state,
                () -> fail("replace"),
                () -> fail("exit"),
                () -> {
                  if (once.getAndSet(false)) {
                    state.seedInputs.edit(state, "seedOffset", "Seed", "--");
                    holder[0].request(Intent.EXIT);
                    if (fail) throw new IllegalStateException("request boundary");
                  }
                  return false;
                });
        holder[0] = files;
        var before = ProjectIO.JSON.valueToTree(state.project());
        files.request(Intent.OPEN);
        assertEquals(Phase.DRAFT, files.phase());
        assertEquals("Exit", files.action());
        assertEquals(1, files.requestVersion());
        assertEquals("", files.error());
        assertEquals("--", state.seedInputs.text("seedOffset"));
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        files.cancel();
      }
  }

  @Test
  void decisionBlockersCannotClearNewerRequestsInAnyTransitionPhase() {
    for (var phase : new Phase[] {Phase.DRAFT, Phase.GUARD, Phase.OPEN, Phase.SAVE})
      for (boolean fail : new boolean[] {false, true})
        try (var state = state()) {
          var armed = new AtomicBoolean();
          var holder = new ProjectFileWorkflow[1];
          var files =
              new ProjectFileWorkflow(
                  state,
                  () -> fail("replace"),
                  () -> fail("exit"),
                  () -> {
                    if (armed.getAndSet(false)) {
                      holder[0].cancel();
                      state.seedInputs.edit(state, "seedOffset", "Seed", "--");
                      holder[0].request(Intent.EXIT);
                      if (fail) throw new IllegalStateException("decision boundary");
                    }
                    return false;
                  });
          holder[0] = files;
          arm(files, phase);
          long old = files.requestVersion();
          var before = ProjectIO.JSON.valueToTree(state.project());
          armed.set(true);
          var target = directory.resolve(phase + "-must-not-write.json");
          if (phase == Phase.DRAFT) files.decideDraft(DraftDecision.DISCARD);
          else if (phase == Phase.GUARD) files.decide(Decision.DISCARD);
          else files.submit(target.toString());
          assertEquals(Phase.DRAFT, files.phase());
          assertEquals(old + 1, files.requestVersion());
          assertEquals("Exit", files.action());
          assertEquals("", files.error());
          assertEquals("--", state.seedInputs.text("seedOffset"));
          assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
          assertFalse(Files.exists(target));
          files.cancel();
        }
  }

  @Test
  void privateInputChangedByADecisionGateCannotAuthorizeApplyOrDiscard() {
    for (var decision : new DraftDecision[] {DraftDecision.APPLY, DraftDecision.DISCARD})
      try (var state = state()) {
        var calls = new AtomicInteger();
        var files =
            new ProjectFileWorkflow(
                state,
                () -> fail("replace"),
                () -> fail("exit"),
                () -> {
                  if (calls.incrementAndGet() == 2)
                    state.seedInputs.edit(state, "seedOffset", "Seed", "--");
                  return false;
                });
        var before = ProjectIO.JSON.valueToTree(state.project());
        long revision = state.revision;
        files.request(Intent.NEW);
        files.decideDraft(decision);
        assertEquals(Phase.IDLE, files.phase());
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(revision, state.revision);
        assertEquals("--", state.seedInputs.text("seedOffset"));
        assertEquals("STALE_PROJECT_FILE_ACTION", state.events.view().entries().getFirst().code());
      }
  }

  @Test
  void anOpenRefreshFailureReportsThePublishedReplacementWithoutReplayingIt() throws Exception {
    try (var state = state()) {
      var target = candidate("opened.json", 99);
      var calls = new AtomicInteger();
      var files =
          new ProjectFileWorkflow(
              state,
              () -> {
                calls.incrementAndGet();
                throw new IllegalStateException("refresh boundary");
              },
              () -> fail("exit"));
      long revision = state.revision;
      arm(files, Phase.OPEN);
      files.submit(target.toString());
      assertEquals(99, state.project().project.seed);
      assertEquals(revision + 1, state.revision);
      assertNull(state.draft);
      assertNull(state.selected);
      assertFalse(state.document.canUndo());
      assertEquals(Phase.IDLE, files.phase());
      assertFalse(files.busy());
      assertTrue(files.error().startsWith("Project opened;"));
      files.submit(target.toString());
      files.decide(Decision.DISCARD);
      assertEquals(1, calls.get());
      assertEquals(revision + 1, state.revision);
      assertTrue(
          state.events.view().entries().stream()
              .anyMatch(
                  e -> e.code().equals("PROJECT_REPLACEMENT_REFRESH_FAILED") && e.count() == 1));
      assertTrue(
          state.events.view().entries().stream()
              .noneMatch(e -> e.code().equals("PROJECT_OPEN_FAILED")));
    }
  }

  @Test
  void aNewCompletionCanOpenASaveSuccessorWithoutItsFailureOverwritingIt() throws Exception {
    try (var state = state()) {
      var calls = new AtomicInteger();
      var holder = new ProjectFileWorkflow[1];
      var files =
          new ProjectFileWorkflow(
              state,
              () -> {
                calls.incrementAndGet();
                holder[0].save(true);
                throw new IllegalStateException("new refresh boundary");
              },
              () -> fail("exit"));
      holder[0] = files;
      files.request(Intent.NEW);
      files.decideDraft(DraftDecision.DISCARD);
      long old = files.requestVersion();
      files.decide(Decision.DISCARD);
      assertTrue(state.project().noise2D.isEmpty());
      assertEquals(Phase.SAVE, files.phase());
      assertEquals(old + 1, files.requestVersion());
      assertEquals("", files.error());
      var target = directory.resolve("new-successor.json");
      files.submit(target.toString());
      assertEquals(Phase.IDLE, files.phase());
      assertEquals(1, calls.get());
      assertTrue(new ProjectIO().load(target).project().noise2D.isEmpty());
    }
  }

  @Test
  void anExitCallbackFailureConsumesOnlyItsOldRequestAndAllowsExplicitRetry() {
    try (var state = state()) {
      var calls = new AtomicInteger();
      var failing = new AtomicBoolean(true);
      var files =
          new ProjectFileWorkflow(
              state,
              () -> fail("replace"),
              () -> {
                calls.incrementAndGet();
                if (failing.get()) throw new IllegalStateException("exit boundary");
              });
      var before = ProjectIO.JSON.valueToTree(state.project());
      var draft = state.draft;
      files.request(Intent.EXIT);
      files.decideDraft(DraftDecision.DISCARD);
      files.decide(Decision.DISCARD);
      assertEquals(Phase.IDLE, files.phase());
      assertFalse(files.busy());
      assertTrue(files.error().startsWith("Exit request callback failed:"));
      files.decide(Decision.DISCARD);
      assertEquals(1, calls.get());
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
      assertSame(draft, state.draft);
      failing.set(false);
      files.request(Intent.EXIT);
      files.decideDraft(DraftDecision.DISCARD);
      files.decide(Decision.DISCARD);
      assertEquals(2, calls.get());
      assertEquals(Phase.IDLE, files.phase());
    }
  }

  @Test
  void aNamedSaveRemainsBusyThroughValidationDespiteItsIdlePresentationPhase() throws Exception {
    try (var state = state()) {
      var target = directory.resolve("named.json");
      state.document.save(target);
      var calls = new AtomicInteger();
      var files =
          new ProjectFileWorkflow(
              state,
              () -> fail("replace"),
              () -> fail("exit"),
              () -> {
                fail("Standalone save consulted transition blocker");
                return true;
              });
      var history = new ProjectHistorySession(state, files::busy);
      state.document.setValidator(
          p -> {
            calls.incrementAndGet();
            assertTrue(files.busy());
            assertEquals(Phase.IDLE, files.phase());
            long request = files.requestVersion();
            files.request(Intent.NEW);
            files.save(true);
            files.cancel();
            assertEquals(request, files.requestVersion());
            assertEquals(
                ProjectHistorySession.Result.UNAVAILABLE,
                history.request(ProjectHistorySession.Direction.UNDO));
            return NoiseCompiler.validate(p);
          });
      files.save(false);
      assertEquals(1, calls.get());
      assertEquals(Phase.IDLE, files.phase());
      assertFalse(files.busy());
      assertEquals("private", ((Noise) state.draft).id);
      assertEquals("owned", new ProjectIO().load(target).project().noise2D.getFirst().id);
    }
  }

  @Test
  void aLateBlockerAfterApplyKeepsOneCommittedDraftWithoutContinuingTheTransition() {
    try (var state = state()) {
      var calls = new AtomicInteger();
      var files =
          new ProjectFileWorkflow(
              state, () -> fail("replace"), () -> fail("exit"), () -> calls.incrementAndGet() == 3);
      var before = ProjectIO.JSON.valueToTree(state.project());
      long revision = state.revision;
      files.request(Intent.NEW);
      files.decideDraft(DraftDecision.APPLY);
      assertEquals(Phase.IDLE, files.phase());
      assertTrue(files.error().startsWith("Properties applied."));
      assertEquals("private", state.project().noise2D.getFirst().id);
      assertEquals(revision + 1, state.revision);
      files.decideDraft(DraftDecision.APPLY);
      assertEquals(revision + 1, state.revision);
      state.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
    }
  }

  @Test
  void aRequestGuardFailureIsBoundedAndDoesNotAdmitAnAction() {
    try (var state = state()) {
      var fail = new AtomicBoolean(true);
      var files =
          new ProjectFileWorkflow(
              state,
              () -> fail("replace"),
              () -> fail("exit"),
              () -> {
                if (fail.get()) throw new IllegalStateException("x".repeat(4000));
                return false;
              });
      var draft = state.draft;
      files.request(Intent.NEW);
      assertEquals(Phase.IDLE, files.phase());
      assertFalse(files.busy());
      assertEquals(0, files.requestVersion());
      assertSame(draft, state.draft);
      assertTrue(files.error().length() <= 1024);
      assertEquals("PROJECT_FILE_GUARD_FAILED", state.events.view().entries().getFirst().code());
      fail.set(false);
      files.request(Intent.NEW);
      assertEquals(Phase.DRAFT, files.phase());
      assertEquals(1, files.requestVersion());
      files.cancel();
    }
  }

  @Test
  void anOpenCallbackCanOpenAnotherPathWithoutOldCleanupRetiringIt() throws Exception {
    try (var state = state()) {
      var first = candidate("first.json", 99);
      var second = candidate("second.json", 101);
      var once = new AtomicBoolean(true);
      var calls = new AtomicInteger();
      var holder = new ProjectFileWorkflow[1];
      var files =
          new ProjectFileWorkflow(
              state,
              () -> {
                calls.incrementAndGet();
                if (once.getAndSet(false)) {
                  holder[0].request(Intent.OPEN);
                  throw new IllegalStateException("open successor boundary");
                }
              },
              () -> fail("exit"));
      holder[0] = files;
      arm(files, Phase.OPEN);
      long old = files.requestVersion();
      files.submit(first.toString());
      assertEquals(99, state.project().project.seed);
      assertEquals(Phase.OPEN, files.phase());
      assertEquals(old + 1, files.requestVersion());
      assertEquals("", files.error());
      files.submit(second.toString());
      assertEquals(101, state.project().project.seed);
      assertEquals(Phase.IDLE, files.phase());
      assertEquals(2, calls.get());
    }
  }
}
