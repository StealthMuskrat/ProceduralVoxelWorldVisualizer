package io.voxelcreator.editor;

import static io.voxelcreator.editor.ResourceEditSession.Result.*;
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

/** Deferred staged browser actions, private validation ordering and exact publication authority. */
class BackgroundResourceEditTest {
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

  static EditorState state(boolean dirty) {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(
        s.change(
            p -> {
              p.project.minY = -2;
              p.project.maxY = 2;
              for (String id : List.of("a", "b")) {
                var n = new Noise();
                n.id = id;
                n.algorithm.key = "CONSTANT";
                n.algorithm.parameters.clear();
                n.algorithm.parameters.put("value", id.equals("a") ? .125 : .375);
                p.noise2D.add(n);
              }
              var f = new Folder();
              f.id = "folder";
              f.rootKind = "noise2D";
              p.folders.add(f);
              var v = new VoxelType();
              v.id = "solid";
              p.voxelTypes.add(v);
              var layer = new Layer();
              layer.id = "bounds";
              layer.defaults.action = "SET";
              layer.defaults.voxelTypeUuid = v.uuid;
              var op = new Operation();
              op.id = "density";
              op.noiseGeneratorUuid = p.noise2D.getFirst().uuid;
              op.interpretation = "DENSITY";
              op.overrides.put("threshold", ProjectIO.JSON.valueToTree(.5));
              layer.operations.add(op);
              p.worldGenerator.layers.add(layer);
            }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    if (dirty) ((Noise) s.draft).algorithm.parameters.put("value", .625);
    return s;
  }

  static ResourceEditSession session(
      EditorState s, Executor worker, Queue<Runnable> ui, List<ResourceEditSession.Result> done) {
    return new ResourceEditSession(s, () -> {}, worker, ui::add, () -> false, done::add);
  }

  static ResourceBrowserEdits.Edit rename(EditorState s) {
    return ResourceBrowserEdits.rename(s.project().noise2D.getFirst().uuid, "renamed");
  }

  static void deliver(Workers worker, Queue<Runnable> ui, ResourceEditSession edits) {
    worker.run();
    ui.remove().run();
    edits.acceptPrepared();
  }

  @Test
  void cleanActionsPrepareImmediatelyOnAWorkerButWaitForOwnerPublication() throws Exception {
    try (var s = state(false);
        var executor = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var done = new ArrayList<ResourceEditSession.Result>();
      var seen = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      s.document.setValidator(
          p -> {
            seen.set(Thread.currentThread());
            return NoiseCompiler.validate(p);
          });
      var edits = session(s, executor, ui, done);
      var before = ProjectIO.JSON.valueToTree(s.project());
      var draft = s.draft;
      assertEquals(NEEDS_DECISION, edits.request("Rename", rename(s)));
      assertFalse(edits.privateDecision());
      var callback = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(caller, seen.get());
      assertTrue(edits.preparing());
      assertFalse(edits.busy());
      assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      callback.run();
      callback.run();
      assertTrue(edits.preparing());
      assertSame(draft, s.draft);
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      assertTrue(done.isEmpty());
      executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
      edits.acceptPrepared();
      assertEquals(List.of(APPLIED), done);
      assertFalse(edits.pending());
      assertEquals("renamed", s.project().noise2D.getFirst().id);
      s.document.undo();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void applyAndDiscardKeepTheOriginalDraftUntilOneCombinedEditPublishes() {
    for (boolean apply : new boolean[] {false, true})
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceEditSession.Result>();
        var edits = session(s, worker, ui, done);
        var before = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        edits.request("Rename", rename(s));
        assertTrue(worker.tasks.isEmpty());
        assertTrue(edits.privateDecision());
        assertEquals(NEEDS_DECISION, edits.decide(apply ? APPLY : DISCARD));
        worker.run();
        assertSame(draft, s.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        ui.remove().run();
        edits.acceptPrepared();
        assertEquals(List.of(APPLIED), done);
        assertEquals(
            apply ? .625 : .125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        var accepted = ProjectIO.JSON.valueToTree(s.project());
        s.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo();
        assertEquals(accepted, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void invalidDraftCannotBeHiddenByDeletionAndRawSeedNeverQueuesApply() {
    for (boolean raw : new boolean[] {false, true})
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var edits = session(s, worker, ui, new ArrayList<>());
        var calls = new AtomicInteger();
        UUID a = s.selected, b = s.project().noise2D.getLast().uuid;
        var draft = s.draft;
        if (raw) s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        else ((Noise) s.draft).id = " invalid ";
        edits.request(
            "Delete",
            p -> {
              calls.incrementAndGet();
              return ResourceBrowserEdits.delete(a, b).apply(p);
            });
        var result = edits.decide(APPLY);
        if (raw) {
          assertEquals(REJECTED, result);
          assertTrue(worker.tasks.isEmpty());
        } else {
          assertEquals(NEEDS_DECISION, result);
          deliver(worker, ui, edits);
          assertFalse(s.issues.isEmpty());
        }
        assertEquals(0, calls.get());
        assertSame(draft, s.draft);
        assertNotNull(EditorState.find(s.project(), a));
        edits.decide(DISCARD);
        worker.run();
        assertSame(draft, s.draft);
        ui.remove().run();
        edits.acceptPrepared();
        assertEquals(1, calls.get());
        assertNull(EditorState.find(s.project(), a));
        assertNull(s.draft);
        assertFalse(s.seedInputs.pending(s));
      }
  }

  @Test
  void everyOwnedBrowserCommandUsesTheCombinedCandidateAndCompleteUndoRedo() {
    for (int mode = 0; mode < 10; mode++)
      try (var s = state(true)) {
        UUID a = s.selected,
            b = s.project().noise2D.getLast().uuid,
            folder = s.project().folders.getFirst().uuid,
            layer = s.project().worldGenerator.layers.getFirst().uuid;
        ResourceBrowserEdits.Edit edit;
        if (mode == 0) edit = ResourceBrowserEdits.rename(b, "renamed");
        else if (mode == 1) {
          var n = new Noise();
          n.id = "created";
          edit = ResourceBrowserEdits.insert("noise2D", n);
        } else if (mode == 2) edit = ResourceBrowserEdits.duplicate(List.of(a));
        else if (mode == 3)
          edit =
              ResourceBrowserEdits.paste(
                  new ResourceClipboard(s.project(), List.of(a), false), "noise2D", folder);
        else if (mode == 4)
          edit =
              ResourceBrowserEdits.paste(
                  new ResourceClipboard(s.project(), List.of(b), true), "noise2D", folder);
        else if (mode == 5) edit = ResourceBrowserEdits.shift(List.of(b), -1, false);
        else if (mode == 6) edit = ResourceBrowserEdits.before(List.of(b), a);
        else if (mode == 7) edit = ResourceBrowserEdits.delete(a, b);
        else if (mode == 8) edit = ResourceBrowserEdits.placement(layer, b);
        else edit = ResourceBrowserEdits.move(List.of(a), "noise2D", folder);
        var before = ProjectIO.JSON.valueToTree(s.project());
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceEditSession.Result>();
        var edits = session(s, worker, ui, done);
        edits.request("Command", edit);
        edits.decide(APPLY);
        deliver(worker, ui, edits);
        assertEquals(List.of(APPLIED), done);
        if (mode != 7)
          assertEquals(
              .625, ((Noise) EditorState.find(s.project(), a)).algorithm.parameters.get("value"));
        if (mode == 2) assertNotEquals(a, s.selected);
        if (mode == 8)
          assertTrue(
              s.project()
                  .worldGenerator
                  .layers
                  .getFirst()
                  .operations
                  .getLast()
                  .overrides
                  .isEmpty());
        var accepted = ProjectIO.JSON.valueToTree(s.project());
        s.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo();
        assertEquals(accepted, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void newerPrivateRawSelectionManagedAndHiddenValuesCannotBeReplacedByOldWork() {
    for (int mode = 0; mode < 5; mode++)
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceEditSession.Result>();
        var edits = session(s, worker, ui, done);
        edits.request("Rename", rename(s));
        edits.decide(APPLY);
        worker.run();
        if (mode == 0) ((Noise) s.draft).id = "new-private";
        else if (mode == 1) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else if (mode == 2) s.multiSelection.add(s.project().noise2D.getLast().uuid);
        else if (mode == 3) assertTrue(s.change(p -> p.project.seed = 17));
        else s.project().noise2D.getLast().algorithm.parameters.put("value", .875);
        var before = ProjectIO.JSON.valueToTree(s.project());
        var draft = s.draft;
        ui.remove().run();
        edits.acceptPrepared();
        assertEquals(List.of(UNAVAILABLE), done);
        assertSame(draft, s.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
  }

  @Test
  void checkpointPolicyHistoryNewAndSameUuidOpenSupersedeBothAcceptedAndRejectedWork()
      throws Exception {
    for (boolean rejected : new boolean[] {false, true})
      for (int mode = 0; mode < 5; mode++)
        try (var s = state(true)) {
          var path = directory.resolve("source-" + rejected + "-" + mode + ".json");
          s.document.save(path);
          if (rejected)
            s.document.setValidator(
                p ->
                    !p.noise2D.isEmpty() && p.noise2D.getFirst().id.equals("renamed")
                        ? List.of(Diagnostic.error("FINAL", "/fixture", "Rejected"))
                        : NoiseCompiler.validate(p));
          var worker = new Workers();
          var ui = new ArrayDeque<Runnable>();
          var edits = session(s, worker, ui, new ArrayList<>());
          edits.request("Rename", rename(s));
          edits.decide(APPLY);
          worker.run();
          if (mode == 0) s.document.save(path);
          else if (mode == 1) s.document.setValidator(p -> List.of());
          else if (mode == 2) {
            s.document.undo();
            s.document.redo();
          } else if (mode == 3) s.newSession();
          else assertTrue(s.document.open(path).isEmpty());
          var before = ProjectIO.JSON.valueToTree(s.project());
          var draft = s.draft;
          ui.remove().run();
          edits.acceptPrepared();
          assertFalse(edits.pending());
          assertSame(draft, s.draft);
          assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        }
  }

  @Test
  void cancelCloseAndSuccessorsIgnoreCompletedCallbacksAndDuplicateDecisions() {
    for (int mode = 0; mode < 3; mode++)
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var done = new ArrayList<ResourceEditSession.Result>();
        var edits = session(s, worker, ui, done);
        edits.request("Rename", rename(s));
        edits.decide(APPLY);
        assertEquals(NEEDS_DECISION, edits.decide(APPLY));
        assertEquals(NEEDS_DECISION, edits.decide(DISCARD));
        assertEquals(UNAVAILABLE, edits.request("Retarget", p -> null));
        assertEquals(1, worker.tasks.size());
        worker.run();
        if (mode == 0) edits.decide(CANCEL);
        else if (mode == 1) edits.close();
        else {
          edits.clear();
          edits.request("Successor", ResourceBrowserEdits.rename(s.selected, "successor"));
        }
        ui.remove().run();
        edits.acceptPrepared();
        assertEquals("a", s.project().noise2D.getFirst().id);
        assertTrue(done.isEmpty());
        assertEquals(mode == 2, edits.pending());
      }
  }

  @Test
  void schedulerWorkerHandoffAndExternalTaskFaultsDoNotRetryOrLosePrivateInput() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var fail = new AtomicBoolean(true);
        int action = mode;
        var edits =
            new ResourceEditSession(
                s,
                () -> {},
                task -> {
                  if (action == 0 && fail.get())
                    throw new RejectedExecutionException("scheduler fixture");
                  worker.execute(task);
                },
                task -> {
                  if (action == 2 && fail.get()) throw new AssertionError("handoff fixture");
                  ui.add(task);
                },
                () -> false,
                value -> {});
        var rename = rename(s);
        var draft = s.draft;
        edits.request(
            "Rename",
            p -> {
              if (action == 1 && fail.get()) {
                p.noise2D.clear();
                throw new AssertionError("action fixture");
              }
              return rename.apply(p);
            });
        edits.decide(APPLY);
        if (mode == 1) deliver(worker, ui, edits);
        else if (mode == 2) worker.run();
        else if (mode == 3) ((Future<?>) worker.tasks.getFirst()).cancel(false);
        assertFalse(edits.preparing());
        assertTrue(edits.pending());
        assertTrue(edits.progress().contains("failed"));
        assertSame(draft, s.draft);
        if (mode == 3) worker.run();
        fail.set(false);
        edits.decide(APPLY);
        deliver(worker, ui, edits);
        assertEquals("renamed", s.project().noise2D.getFirst().id);
      }
  }

  @Test
  void immutableFinalDiagnosticsAndNewerSharedResultsRetainTheirOwnAuthority() {
    try (var s = state(true)) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var issues = new ArrayList<Diagnostic>();
      issues.add(Diagnostic.error("FINAL", "/fixture", "Rejected"));
      s.document.setValidator(p -> issues);
      var edits = session(s, worker, ui, new ArrayList<>());
      edits.request("Rename", rename(s));
      edits.decide(APPLY);
      deliver(worker, ui, edits);
      issues.clear();
      assertEquals(1, s.issues.size());
      assertTrue(edits.pending());
      s.document.setValidator(NoiseCompiler::validate);
      edits.decide(APPLY);
      worker.run();
      var newer = List.of(Diagnostic.error("NEWER", "/fixture", "New result"));
      s.issues = newer;
      ui.remove().run();
      edits.acceptPrepared();
      assertSame(newer, s.issues);
      assertEquals("a", s.project().noise2D.getFirst().id);
      assertFalse(edits.pending());
    }
  }

  @Test
  void admissionReentryCancellationAndFatalBlockersCannotCommitOrDuplicateTheRequest() {
    for (int mode = 0; mode < 4; mode++)
      try (var s = state(true)) {
        var worker = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var holder = new ResourceEditSession[1];
        var calls = new AtomicInteger();
        int action = mode;
        var edits =
            new ResourceEditSession(
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
        holder[0] = edits;
        edits.request("Rename", rename(s));
        edits.decide(APPLY);
        if (mode == 1 || mode == 2) deliver(worker, ui, edits);
        else assertTrue(worker.tasks.isEmpty());
        assertEquals(mode == 2 ? "renamed" : "a", s.project().noise2D.getFirst().id);
        if (mode == 3) {
          assertTrue(edits.pending());
          assertFalse(edits.preparing());
          edits.decide(CANCEL);
        }
      }
  }

  @Test
  void publicationFenceAndRefreshErrorsConsumeOneCommitAndOneUiCompletion() {
    try (var s = state(true)) {
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var holder = new ResourceEditSession[1];
      var done = new ArrayList<ResourceEditSession.Result>();
      var edits =
          new ResourceEditSession(
              s,
              () -> {
                assertTrue(holder[0].busy());
                assertEquals(UNAVAILABLE, holder[0].request("Nested", p -> null));
                holder[0].clear();
                throw new AssertionError("refresh fixture");
              },
              worker,
              ui::add,
              () -> false,
              done::add);
      holder[0] = edits;
      long revision = s.revision;
      edits.request("Rename", rename(s));
      edits.decide(APPLY);
      deliver(worker, ui, edits);
      assertEquals(List.of(APPLIED), done);
      assertFalse(edits.pending());
      assertEquals(revision + 1, s.revision);
      assertEquals("renamed", s.project().noise2D.getFirst().id);
      var count = new AtomicInteger();
      var completion =
          new ResourceEditCompletion(
              s,
              () -> {
                count.incrementAndGet();
                throw new AssertionError("ui fixture");
              });
      completion.complete();
      completion.complete();
      assertEquals(1, count.get());
    }
  }

  @Test
  void cleanNoOpPreservesRedoAndOldCompiledFieldsWithoutRequiringAPrivateDraft() {
    try (var s = state(false)) {
      var old = new CompiledProject(s.project()).noise2D("a", 0);
      s.change(p -> p.project.seed = 6);
      s.document.undo();
      long bytes = s.document.historyBytes();
      var worker = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var edits = session(s, worker, ui, new ArrayList<>());
      assertEquals(NEEDS_DECISION, edits.request("No op", p -> null));
      deliver(worker, ui, edits);
      assertTrue(s.document.canRedo());
      assertEquals(bytes, s.document.historyBytes());
      assertEquals(.125, old.sample(-3, 2));
      s.selectResources(List.of(), null, "", true);
      edits.request("Retry without selection", p -> null);
      deliver(worker, ui, edits);
      assertFalse(edits.pending());
      var fail = new AtomicBoolean(true);
      edits.request(
          "Clean retry",
          p -> {
            if (fail.get()) throw new AssertionError("clean fixture");
            return null;
          });
      deliver(worker, ui, edits);
      assertTrue(edits.pending());
      assertFalse(edits.privateDecision());
      fail.set(false);
      assertEquals(NEEDS_DECISION, edits.decide(APPLY));
      deliver(worker, ui, edits);
      assertFalse(edits.pending());
    }
  }
}
