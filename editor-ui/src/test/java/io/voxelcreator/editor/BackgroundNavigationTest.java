package io.voxelcreator.editor;

import static io.voxelcreator.editor.SearchNavigation.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred prepared Apply for owned Find, reference and source-link navigation intents. */
class BackgroundNavigationTest {
  @Test
  void fatalAdmissionChecksKeepPrivateInputAndLeaveExplicitRecoveryAvailable() {
    try (var s = state();
        var properties = new PropertyPanel(s)) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var nav = nav(s, worker, ui, new ArrayList<>());
      var draft = s.draft;
      assertEquals(
          Result.NEEDS_DECISION,
          nav.decide(
              Decision.APPLY,
              () -> {
                throw new AssertionError("admission fixture");
              },
              () -> true));
      assertTrue(nav.pending());
      assertFalse(nav.preparing());
      assertSame(draft, s.draft);
      assertTrue(worker.tasks.isEmpty());
      nav.decide(Decision.CANCEL);
      var guard = new GuardedNavigation(s, properties, value -> false, worker, ui::add);
      var calls = new AtomicInteger();
      guard.request(
          target(s, 1),
          () -> {
            if (calls.incrementAndGet() > 1) throw new AssertionError("source admission fixture");
            return true;
          });
      assertEquals(Result.UNAVAILABLE, guard.decide(Decision.APPLY));
      assertSame(draft, s.draft);
      assertFalse(guard.pending());
      assertTrue(worker.tasks.isEmpty());
      guard.close();
    }
  }

  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.remove().run();
    }
  }

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(
        s.change(
            p -> {
              for (String id : List.of("source", "destination", "successor")) {
                var n = new Noise();
                n.id = id;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .125);
                p.noise2D.add(n);
              }
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static EditorEvents.Target target(EditorState s, int index) {
    return new EditorEvents.Target(
        s.project().project.uuid, s.project().noise2D.get(index).uuid, null, null, "", false);
  }

  static SearchNavigation nav(
      EditorState s, Executor executor, Queue<Runnable> ui, List<Completion> completed) {
    var nav = new SearchNavigation(s, executor, ui::add, completed::add);
    assertEquals(Result.NEEDS_DECISION, nav.request(target(s, 1)));
    return nav;
  }

  static void deliver(Workers worker, Queue<Runnable> ui, Runnable accept) {
    worker.run();
    ui.remove().run();
    accept.run();
  }

  @Test
  void actualWorkerValidationWaitsForOwnerAcceptanceThenNavigatesWithOneUndoRedo() throws Exception {
    try (var s = state();
        var executor = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var completed = new ArrayList<Completion>();
      var thread = new AtomicReference<Thread>();
      var owner = Thread.currentThread();
      s.document.setValidator(
          p -> {
            thread.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var before = ProjectIO.JSON.valueToTree(s.project());
      var draft = s.draft;
      var nav = nav(s, executor, ui, completed);
      var destination = nav.target();
      assertEquals(Result.NEEDS_DECISION, nav.decide(Decision.APPLY));
      var callback = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(owner, thread.get());
      assertTrue(nav.preparing());
      assertSame(draft, s.draft);
      assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      callback.run();
      assertTrue(nav.pending());
      assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
      nav.acceptPrepared();
      assertFalse(nav.pending());
      assertEquals(destination.resource(), s.selected);
      assertEquals(List.of(new Completion(destination, Result.NAVIGATED, true)), completed);
      var accepted = ProjectIO.JSON.valueToTree(s.project());
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo();
      assertEquals(accepted, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void invalidRawSeedStaysPrivateWithoutSchedulingAndCancelNeverConsultsTheGate() {
    try (var s = state()) {
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var draft = s.draft;
      var worker = new Workers();
      var nav = nav(s, worker, new ArrayDeque<>(), new ArrayList<>());
      assertEquals(Result.NEEDS_DECISION, nav.decide(Decision.APPLY));
      assertTrue(worker.tasks.isEmpty());
      assertTrue(nav.pending());
      assertFalse(nav.preparing());
      assertEquals(
          Result.CANCELLED,
          nav.decide(
              Decision.CANCEL,
              () -> {
                throw new AssertionError();
              },
              () -> false));
      assertSame(draft, s.draft);
      assertEquals("-", s.seedInputs.text("seedOffset"));
    }
  }

  @Test
  void rejectedCandidateKeepsOwnedImmutableDiagnosticsAndCanExplicitlyRetry() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<Completion>();
      var diagnostics = new ArrayList<Diagnostic>();
      diagnostics.add(Diagnostic.error("REJECTED", "/fixture", "Rejected"));
      s.document.setValidator(p -> diagnostics);
      var nav = nav(s, worker, ui, done);
      nav.decide(Decision.APPLY);
      deliver(worker, ui, nav::acceptPrepared);
      assertTrue(nav.pending());
      assertFalse(nav.preparing());
      assertEquals(1, s.issues.size());
      diagnostics.clear();
      assertEquals(1, s.issues.size());
      assertTrue(done.isEmpty());
      s.document.setValidator(NoiseCompiler::validate);
      nav.decide(Decision.APPLY);
      deliver(worker, ui, nav::acceptPrepared);
      assertFalse(nav.pending());
      assertEquals(1, done.size());
      assertTrue(done.getFirst().applied());
    }
  }

  @Test
  void duplicateApplyAndDiscardCannotScheduleOrNavigateWhilePreparing() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var nav = nav(s, worker, ui, new ArrayList<>());
      nav.decide(Decision.APPLY);
      long version = nav.workVersion();
      assertEquals(Result.NEEDS_DECISION, nav.decide(Decision.APPLY));
      assertEquals(Result.NEEDS_DECISION, nav.decide(Decision.DISCARD));
      assertEquals(1, worker.tasks.size());
      assertEquals(version, nav.workVersion());
      deliver(worker, ui, nav::acceptPrepared);
      assertFalse(nav.pending());
    }
  }

  @Test
  void privateRawSelectionManagedAndHiddenChangesRetireOldWorkWithoutReplacingNewValues() {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<Completion>();
        var nav = nav(s, worker, ui, done);
        nav.decide(Decision.APPLY);
        worker.run();
        if (mode == 0) ((Noise) s.draft).id = "newer-private";
        else if (mode == 1) s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        else if (mode == 2) s.select("noise2D", s.project().noise2D.getLast().uuid);
        else if (mode == 3) assertTrue(s.change(p -> p.project.seed = 17));
        else s.project().noise2D.getLast().algorithm.parameters.put("value", .875);
        var values = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        ui.remove().run();
        nav.acceptPrepared();
        assertFalse(nav.pending());
        assertSame(draft, s.draft);
        assertEquals(values, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(Result.UNAVAILABLE, done.getFirst().result());
        assertFalse(done.getFirst().applied());
      }
  }

  @Test
  void savePolicyHistoryNewAndSameUuidOpenInvalidatePreparedDocumentAuthority() throws Exception {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state()) {
        var path = directory.resolve("source-" + mode + ".json");
        s.document.save(path);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var nav = nav(s, worker, ui, new ArrayList<>());
        nav.decide(Decision.APPLY);
        worker.run();
        if (mode == 0) s.document.save(path);
        else if (mode == 1) s.document.setValidator(p -> List.of());
        else if (mode == 2) {
          s.document.undo();
          s.document.redo();
        } else if (mode == 3) s.newSession();
        else assertTrue(s.document.open(path).isEmpty());
        var values = ProjectIO.JSON.valueToTree(s.project());
        ui.remove().run();
        nav.acceptPrepared();
        assertFalse(nav.pending());
        assertEquals(values, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void cancelCloseAndReopenedSuccessorIgnoreCompletedCallbacks() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<Completion>();
        var nav = nav(s, worker, ui, done);
        nav.decide(Decision.APPLY);
        worker.run();
        if (mode == 0) nav.decide(Decision.CANCEL);
        else if (mode == 1) nav.close();
        else {
          nav.clear();
          assertEquals(Result.NEEDS_DECISION, nav.request(target(s, 2)));
        }
        var values = ProjectIO.JSON.valueToTree(s.project());
        ui.remove().run();
        nav.acceptPrepared();
        assertEquals(values, ProjectIO.JSON.valueToTree(s.project()));
        assertTrue(done.isEmpty());
        assertEquals(mode == 2, nav.pending());
        if (mode == 1) assertEquals(Result.UNAVAILABLE, nav.request(target(s, 1)));
      }
  }

  @Test
  void schedulingHandoffAndExternallyCancelledTasksLeaveRetryAndCancelAvailable() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        int action = mode;
        var nav =
            new SearchNavigation(
                s,
                task -> {
                  if (action == 0 && fail.get())
                    throw new RejectedExecutionException("scheduler fixture");
                  worker.execute(task);
                },
                task -> {
                  if (action == 1 && fail.get()) throw new AssertionError("handoff fixture");
                  ui.add(task);
                },
                value -> {});
        nav.request(target(s, 1));
        nav.decide(Decision.APPLY);
        if (mode == 1) worker.run();
        else if (mode == 2) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertFalse(nav.preparing());
        assertTrue(nav.pending());
        assertTrue(nav.progress().contains("failed"));
        if (mode == 2) worker.run();
        fail.set(false);
        nav.decide(Decision.APPLY);
        deliver(worker, ui, nav::acceptPrepared);
        assertFalse(nav.pending());
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
  }

  @Test
  void workerFailureRetainsPrivateCorrectionAndNeverRetriesAutomatically() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var draft = s.draft;
      s.document.setValidator(
          p -> {
            throw new AssertionError("worker fixture");
          });
      var nav = nav(s, worker, ui, new ArrayList<>());
      nav.decide(Decision.APPLY);
      deliver(worker, ui, nav::acceptPrepared);
      assertTrue(nav.pending());
      assertFalse(nav.preparing());
      assertSame(draft, s.draft);
      assertTrue(nav.progress().contains("worker fixture"));
      assertTrue(worker.tasks.isEmpty());
      nav.decide(Decision.CANCEL);
      assertSame(draft, s.draft);
    }
  }

  @Test
  void aNewSharedValidationResultCannotBeOverwrittenByTheOlderApply() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var nav = nav(s, worker, ui, new ArrayList<>());
      nav.decide(Decision.APPLY);
      worker.run();
      var newer = List.of(Diagnostic.error("NEWER", "/fixture", "New result"));
      s.issues = newer;
      ui.remove().run();
      nav.acceptPrepared();
      assertSame(newer, s.issues);
      assertFalse(nav.pending());
      assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void reentrantAdmissionAndPublicationCancellationCannotQueueOrCommitTheOldRequest() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var nav = nav(s, worker, ui, new ArrayList<>());
        var calls = new AtomicInteger();
        int action = mode;
        nav.decide(
            Decision.APPLY,
            () -> {
              int call = calls.incrementAndGet();
              if (action == 0 && call == 1 || action == 1 && call == 2) nav.decide(Decision.CANCEL);
              if (action == 2 && call == 1)
                assertEquals(Result.NEEDS_DECISION, nav.decide(Decision.APPLY));
              return true;
            },
            () -> true);
        if (mode > 0) deliver(worker, ui, nav::acceptPrepared);
        else assertTrue(worker.tasks.isEmpty());
        assertEquals(
            mode == 2 ? .625 : .125,
            s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
  }

  @Test
  void postApplyFalseFailureOrNewPrivateInputKeepsOneCommitWithoutFollowingTheOldTarget() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<Completion>();
        var nav = nav(s, worker, ui, done);
        var source = s.selected;
        long revision = s.revision;
        int action = mode;
        nav.decide(
            Decision.APPLY,
            () -> true,
            () -> {
              if (action == 1) throw new AssertionError("post Apply fixture");
              if (action == 2) ((Noise) s.draft).id = "new-private";
              return action == 2;
            });
        deliver(worker, ui, nav::acceptPrepared);
        assertFalse(nav.pending());
        assertEquals(source, s.selected);
        assertEquals(revision + 1, s.revision);
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        assertEquals(Result.UNAVAILABLE, done.getFirst().result());
        assertTrue(done.getFirst().applied());
        assertEquals(Result.UNAVAILABLE, nav.decide(Decision.APPLY));
      }
  }

  @Test
  void postApplySuccessorKeepsItsOwnDraftAndNoOldCompletionOrReveal() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<Completion>();
      var nav = nav(s, worker, ui, done);
      var destination = target(s, 2);
      var source = s.selected;
      nav.decide(
          Decision.APPLY,
          () -> true,
          () -> {
            ((Noise) s.draft).id = "successor-private";
            assertEquals(Result.NEEDS_DECISION, nav.request(destination));
            return true;
          });
      deliver(worker, ui, nav::acceptPrepared);
      assertTrue(nav.pending());
      assertEquals(destination, nav.target());
      assertTrue(done.isEmpty());
      assertEquals(source, s.selected);
      assertEquals("successor-private", ((Noise) s.draft).id);
      assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void oldCompiledFieldIsImmutableAndPostpublicationRevealFailureCannotReplayTheCommit() {
    try (var s = state()) {
      var old = new CompiledProject(s.project()).noise2D("source", 0);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var nav =
          new SearchNavigation(
              s,
              worker,
              ui::add,
              value -> {
                throw new AssertionError("reveal fixture");
              });
      nav.request(target(s, 1));
      nav.decide(Decision.APPLY);
      deliver(worker, ui, nav::acceptPrepared);
      assertFalse(nav.pending());
      assertEquals(.125, old.sample(-3, 2));
      assertEquals(.625, new CompiledProject(s.project()).noise2D("source", 0).sample(-3, 2));
      assertEquals(Result.UNAVAILABLE, nav.decide(Decision.APPLY));
    }
  }

  @Test
  void guardedSourceCompletionInvokesItsCurrentRevealerOnceAfterAcceptance() {
    try (var s = state();
        var properties = new PropertyPanel(s)) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var reveals = new AtomicInteger();
      var guard =
          new GuardedNavigation(
              s,
              properties,
              target -> {
                reveals.incrementAndGet();
                return false;
              },
              worker,
              ui::add);
      guard.request(target(s, 1));
      guard.decide(Decision.APPLY);
      assertTrue(guard.preparing());
      assertEquals(0, reveals.get());
      deliver(worker, ui, guard::acceptPrepared);
      assertEquals(1, reveals.get());
      assertFalse(guard.pending());
      guard.close();
    }
  }

  @Test
  void propertyReferenceApplyUsesTheSameWorkerAndRevealsOnlyAfterSuccessfulPublication() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var properties = new PropertyPanel(s, worker, ui::add)) {
        var destination = target(s, 1).resource();
        var source = s.selected;
        properties.goToControl(destination, null);
        assertTrue(properties.searchBlocked());
        assertEquals(Result.NEEDS_DECISION, properties.decideReferenceNavigation(Decision.APPLY));
        assertEquals(source, s.selected);
        deliver(worker, ui, properties::probeAcceptReferenceNavigation);
        assertEquals(destination, s.selected);
        assertFalse(properties.searchBlocked());
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
    }
  }
}
