package io.voxelcreator.editor;

import static io.voxelcreator.editor.ResourceSelectionSession.Result.*;
import static io.voxelcreator.editor.SearchNavigation.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.CompiledProject;
import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deferred tree/manager selection Apply, owned sibling plans and preparation/publication
 * boundaries.
 */
class BackgroundBrowserSelectionTest {
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
              for (String id : List.of("a", "b", "c")) {
                var n = new Noise();
                n.id = id;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", .125);
                p.noise2D.add(n);
              }
              var v = new VoxelType();
              v.id = "solid";
              p.voxelTypes.add(v);
            }));
    s.select("noise2D", id(s, 0));
    ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static UUID id(EditorState s, int index) {
    return s.project().noise2D.get(index).uuid;
  }

  static ResourceSelectionSession session(
      EditorState s,
      Executor worker,
      Queue<Runnable> ui,
      List<ResourceSelectionSession.Result> done) {
    return new ResourceSelectionSession(s, () -> {}, worker, ui::add, () -> false, done::add);
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  @Test
  void actualWorkerValidationWaitsForOwnerDeliveryBeforeOneEditAndSelection() throws Exception {
    try (var s = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var done = new ArrayList<ResourceSelectionSession.Result>();
      var seen = new AtomicReference<Thread>();
      var owner = Thread.currentThread();
      s.document.setValidator(
          p -> {
            seen.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var selection = session(s, worker, ui, done);
      var draft = s.draft;
      var before = ProjectIO.JSON.valueToTree(s.project());
      UUID source = id(s, 0), target = id(s, 1);
      assertEquals(NEEDS_DECISION, selection.click(target, false));
      assertEquals(NEEDS_DECISION, selection.decide(APPLY));
      var callback = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(owner, seen.get());
      assertTrue(selection.preparing());
      assertFalse(selection.busy());
      assertSame(draft, s.draft);
      assertEquals(source, s.selected);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      callback.run();
      assertEquals(List.of(SELECTED), done);
      assertFalse(selection.pending());
      assertEquals(target, s.selected);
      assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      var accepted = ProjectIO.JSON.valueToTree(s.project());
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertEquals(target, s.selected);
      s.document.redo();
      assertEquals(accepted, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void rangeEmptySameActiveAndManagerPlansInstallOnlyAfterSuccessfulApply() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var selection = session(s, worker, ui, new ArrayList<>());
        var ids =
            mode == 0
                ? new ArrayList<>(List.of(id(s, 0), id(s, 1), id(s, 2)))
                : mode == 1
                    ? new ArrayList<UUID>()
                    : mode == 2
                        ? new ArrayList<>(List.of(id(s, 0), id(s, 1)))
                        : new ArrayList<>(List.of(s.project().voxelTypes.getFirst().uuid));
        UUID active =
            mode == 0 ? id(s, 2) : mode == 1 ? null : mode == 2 ? id(s, 0) : ids.getFirst();
        var expected = List.copyOf(ids);
        assertEquals(NEEDS_DECISION, selection.request(ids, active));
        ids.clear();
        selection.decide(APPLY);
        assertEquals(id(s, 0), s.selected);
        deliver(worker, ui);
        assertEquals(active, s.selected);
        assertEquals(expected, List.copyOf(s.multiSelection));
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        if (mode == 1) assertNull(s.draft);
      }
  }

  @Test
  void anExistingSiblingSelectionAppliesOnlyItsOwnedActiveDraft() {
    try (var s = state()) {
      s.selectResources(List.of(id(s, 0), id(s, 1)), id(s, 0), "noise2D:null", false);
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var selection = session(s, worker, ui, new ArrayList<>());
      assertEquals(NEEDS_DECISION, selection.click(id(s, 2), false));
      selection.decide(APPLY);
      deliver(worker, ui);
      assertEquals(id(s, 2), s.selected);
      assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      assertEquals(.125, s.project().noise2D.get(1).algorithm.parameters.get("value"));
    }
  }

  @Test
  void invalidRawSeedCannotScheduleAndCancelDoesNotConsultALaterBlocker() {
    try (var s = state()) {
      s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var draft = s.draft;
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var blocked = new AtomicBoolean();
      var selection =
          new ResourceSelectionSession(s, () -> {}, worker, ui::add, blocked::get, value -> {});
      selection.click(id(s, 1), false);
      assertEquals(NEEDS_DECISION, selection.decide(APPLY));
      assertTrue(worker.tasks.isEmpty());
      assertEquals("-", s.seedInputs.text("seedOffset"));
      blocked.set(true);
      assertEquals(CANCELLED, selection.decide(CANCEL));
      assertSame(draft, s.draft);
    }
  }

  @Test
  void immutableRejectedDiagnosticsRetainTheIntentForExplicitPolicyRetryOrDiscard() {
    for (boolean discard : new boolean[] {false, true})
      try (var s = state()) {
        var issues = new ArrayList<Diagnostic>();
        issues.add(Diagnostic.error("REJECTED", "/fixture", "Rejected"));
        s.document.setValidator(p -> issues);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var selection = session(s, worker, ui, new ArrayList<>());
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        deliver(worker, ui);
        assertTrue(selection.pending());
        assertFalse(selection.preparing());
        issues.clear();
        assertEquals(1, s.issues.size());
        assertEquals(id(s, 0), s.selected);
        s.document.setValidator(NoiseCompiler::validate);
        if (discard) assertEquals(SELECTED, selection.decide(DISCARD));
        else {
          selection.decide(APPLY);
          deliver(worker, ui);
        }
        assertEquals(id(s, 1), s.selected);
        assertEquals(
            discard ? .125 : .625,
            s.project().noise2D.getFirst().algorithm.parameters.get("value"));
      }
  }

  @Test
  void duplicateApplyDiscardAndRetargetCannotReplaceTheRunningSiblingPlan() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var selection = session(s, worker, ui, new ArrayList<>());
      selection.click(id(s, 1), false);
      selection.decide(APPLY);
      long work = selection.workVersion();
      assertEquals(NEEDS_DECISION, selection.decide(APPLY));
      assertEquals(NEEDS_DECISION, selection.decide(DISCARD));
      assertEquals(UNAVAILABLE, selection.click(id(s, 2), false));
      assertEquals(1, worker.tasks.size());
      assertEquals(work, selection.workVersion());
      deliver(worker, ui);
      assertEquals(id(s, 1), s.selected);
    }
  }

  @Test
  void privateRawSelectionManagedAndHiddenCommittedChangesRejectOldWork() {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceSelectionSession.Result>();
        var selection = session(s, worker, ui, done);
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        worker.run();
        if (mode == 0) ((Noise) s.draft).id = "new-private";
        else if (mode == 1) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else if (mode == 2) s.multiSelection.add(id(s, 2));
        else if (mode == 3) assertTrue(s.change(p -> p.project.seed = 17));
        else s.project().noise2D.getLast().id = "new-live";
        var values = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        var ids = List.copyOf(s.multiSelection);
        ui.remove().run();
        assertEquals(List.of(UNAVAILABLE), done);
        assertSame(draft, s.draft);
        assertEquals(ids, List.copyOf(s.multiSelection));
        assertEquals(values, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void savePolicyHistoryAndDocumentLifetimeChangesSupersedePreparedAuthority() throws Exception {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state()) {
        var path = directory.resolve("source-" + mode + ".json");
        s.document.save(path);
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var selection = session(s, worker, ui, new ArrayList<>());
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        worker.run();
        if (mode == 0) s.document.save(path);
        else if (mode == 1) s.document.setValidator(p -> List.of());
        else if (mode == 2) {
          s.document.undo();
          s.document.redo();
        } else if (mode == 3) s.newSession();
        else assertTrue(s.document.open(path).isEmpty());
        var values = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        ui.remove().run();
        assertFalse(selection.pending());
        assertSame(draft, s.draft);
        assertEquals(values, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void cancelCloseAndSuccessorIgnoreAnAlreadyCompletedOldCallback() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceSelectionSession.Result>();
        var selection = session(s, worker, ui, done);
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        worker.run();
        if (mode == 0) selection.decide(CANCEL);
        else if (mode == 1) selection.close();
        else {
          selection.clear();
          selection.click(id(s, 2), false);
        }
        ui.remove().run();
        assertTrue(done.isEmpty());
        assertEquals(id(s, 0), s.selected);
        assertEquals(mode == 2, selection.pending());
        if (mode == 1) assertEquals(UNAVAILABLE, selection.click(id(s, 1), false));
      }
  }

  @Test
  void workerSchedulerHandoffAndTaskFailureLeaveExplicitRetryAndCancelAvailable() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        int action = mode;
        if (mode == 0)
          s.document.setValidator(
              p -> {
                if (fail.get()) throw new AssertionError("worker fixture");
                return NoiseCompiler.validate(p);
              });
        var selection =
            new ResourceSelectionSession(
                s,
                () -> {},
                task -> {
                  if (action == 1 && fail.get())
                    throw new RejectedExecutionException("scheduler fixture");
                  worker.execute(task);
                },
                task -> {
                  if (action == 2 && fail.get()) throw new AssertionError("handoff fixture");
                  ui.add(task);
                },
                () -> false,
                value -> {});
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        if (mode == 0) deliver(worker, ui);
        else if (mode == 2) worker.run();
        else if (mode == 3) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertFalse(selection.preparing());
        assertTrue(selection.pending());
        assertTrue(selection.progress().contains("failed"));
        assertEquals(id(s, 0), s.selected);
        if (mode == 3) worker.run();
        fail.set(false);
        selection.decide(APPLY);
        deliver(worker, ui);
        assertEquals(id(s, 1), s.selected);
      }
  }

  @Test
  void newerSharedDiagnosticsCannotBeReplacedByTheOlderApply() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var selection = session(s, worker, ui, new ArrayList<>());
      selection.click(id(s, 1), false);
      selection.decide(APPLY);
      worker.run();
      var newer = List.of(Diagnostic.error("NEWER", "/fixture", "New result"));
      s.issues = newer;
      ui.remove().run();
      assertSame(newer, s.issues);
      assertEquals(id(s, 0), s.selected);
      assertFalse(selection.pending());
    }
  }

  @Test
  void blockerCancellationReentryAndFatalAdmissionCannotCommitOrDuplicateOldWork() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state()) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var holder = new ResourceSelectionSession[1];
        var calls = new AtomicInteger();
        int action = mode;
        var selection =
            new ResourceSelectionSession(
                s,
                () -> {},
                worker,
                ui::add,
                () -> {
                  int call = calls.incrementAndGet();
                  if (action == 0 && call == 1 || action == 1 && call == 2)
                    holder[0].decide(CANCEL);
                  if (action == 2 && call == 1)
                    assertEquals(NEEDS_DECISION, holder[0].decide(APPLY));
                  if (action == 3) throw new AssertionError("gate fixture");
                  return false;
                },
                value -> {});
        holder[0] = selection;
        selection.click(id(s, 1), false);
        selection.decide(APPLY);
        if (mode == 1 || mode == 2) deliver(worker, ui);
        else assertTrue(worker.tasks.isEmpty());
        assertEquals(mode == 2 ? id(s, 1) : id(s, 0), s.selected);
        if (mode == 3) {
          assertTrue(selection.pending());
          assertFalse(selection.preparing());
          selection.decide(CANCEL);
        }
      }
  }

  @Test
  void applyingAParentCanRemoveTheDestinationWithoutReplayingOrUndoingItsCommit() {
    try (var s = state()) {
      assertTrue(
          s.change(
              p -> {
                var layer = new Layer();
                layer.id = "parent";
                var operation = new Operation();
                operation.id = "child";
                operation.noiseGeneratorUuid = id(s, 0);
                layer.operations.add(operation);
                p.worldGenerator.layers.add(layer);
              }));
      var layer = s.project().worldGenerator.layers.getFirst();
      var target = layer.operations.getFirst().uuid;
      s.select("layer", layer.uuid);
      ((Layer) s.draft).operations.clear();
      var before = ProjectIO.JSON.valueToTree(s.project());
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<ResourceSelectionSession.Result>();
      var selection = session(s, worker, ui, done);
      selection.click(target, false);
      selection.decide(APPLY);
      deliver(worker, ui);
      assertEquals(List.of(UNAVAILABLE), done);
      assertFalse(selection.pending());
      assertEquals(layer.uuid, s.selected);
      assertTrue(s.project().worldGenerator.layers.getFirst().operations.isEmpty());
      assertEquals(UNAVAILABLE, selection.decide(APPLY));
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void installationFenceRejectsReentrantRequestsAndContainsRefreshFailureAfterOneCommit() {
    try (var s = state()) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var holder = new ResourceSelectionSession[1];
      var done = new ArrayList<ResourceSelectionSession.Result>();
      long revision = s.revision;
      var selection =
          new ResourceSelectionSession(
              s,
              () -> {
                assertTrue(holder[0].busy());
                assertEquals(UNAVAILABLE, holder[0].click(id(s, 2), false));
                holder[0].clear();
                throw new AssertionError("refresh fixture");
              },
              worker,
              ui::add,
              () -> false,
              done::add);
      holder[0] = selection;
      selection.click(id(s, 1), false);
      selection.decide(APPLY);
      deliver(worker, ui);
      assertEquals(List.of(UNAVAILABLE), done);
      assertEquals(id(s, 1), s.selected);
      assertEquals(revision + 1, s.revision);
      assertFalse(selection.pending());
      assertFalse(selection.busy());
      assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
    }
  }

  @Test
  void acceptedNoOpKeepsRedoAndOldCompiledFieldsWhileStillInstallingSelection() {
    try (var s = state()) {
      var old = new CompiledProject(s.project()).noise2D("a", 0);
      assertTrue(s.change(p -> p.project.seed = 17));
      s.document.undo();
      s.document.setValidator(
          p -> {
            p.noise2D.getFirst().algorithm.parameters.put("value", .125);
            return List.of();
          });
      long bytes = s.document.historyBytes();
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var selection = session(s, worker, ui, new ArrayList<>());
      selection.click(id(s, 1), false);
      selection.decide(APPLY);
      deliver(worker, ui);
      assertEquals(id(s, 1), s.selected);
      assertTrue(s.document.canRedo());
      assertEquals(bytes, s.document.historyBytes());
      assertEquals(.125, old.sample(-3, 2));
    }
  }
}
