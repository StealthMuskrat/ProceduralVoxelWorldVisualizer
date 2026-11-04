package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred worker catalog/closure preparation and exact Included-review publication authority. */
class ExportPreviewPlanningTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.removeFirst().run();
    }
  }

  static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  static void fixture(ProjectData p) {
    p.project.seed = 17;
    var base = noise("base", .125);
    var derived = noise("derived", 0);
    var item = ProcessingRegistry.create("ADD", "offset", 2);
    var ref = new Binding();
    ref.sourceKind = "Noise Generator Reference";
    ref.noiseGeneratorUuid = base.uuid;
    item.inputs.put("a", ref);
    item.inputs.put("b", Binding.constant(.5));
    derived.processing.add(item);
    derived.outputBinding = Binding.earlier(item.uuid);
    p.noise2D.add(base);
    p.noise2D.add(derived);
    p.project.rootOrder.put("noise2D", new ArrayList<>(List.of(base.uuid, derived.uuid)));
  }

  static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(ExportPreviewPlanningTest::fixture));
    state.select("noise2D", state.project().noise2D.getLast().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  static Set<UUID> derived(EditorState s) {
    return Set.of(s.project().noise2D.getLast().uuid);
  }

  static Set<UUID> base(EditorState s) {
    return Set.of(s.project().noise2D.getFirst().uuid);
  }

  static ExportEditSession edit(EditorState state, Executor workers, Queue<Runnable> ui) {
    return new ExportEditSession(state, derived(state), workers, ui::add, () -> false);
  }

  static void deliver(Workers workers, Queue<Runnable> ui) {
    workers.run();
    ui.remove().run();
  }

  @Test
  void initialCatalogAndClosureRunOffThreadAndWaitForUiDeliveryAndIncludedReview()
      throws Exception {
    try (var state = state();
        var workers = Executors.newSingleThreadExecutor()) {
      var ui = new LinkedBlockingQueue<Runnable>();
      var thread = new AtomicReference<Thread>();
      var owner = new ProjectFileOwner(state);
      var s =
          edit(
              state,
              task ->
                  workers.execute(
                      () -> {
                        thread.set(Thread.currentThread());
                        task.run();
                      }),
              ui);
      assertNull(s.catalog());
      assertNull(s.preview());
      assertTrue(s.planning());
      assertFalse(s.canExport());
      var callback = ui.poll(10, TimeUnit.SECONDS);
      assertNotNull(callback);
      assertNotSame(Thread.currentThread(), thread.get());
      assertNull(s.preview());
      assertNull(s.catalog());
      assertTrue(owner.valuesCurrent(state));
      callback.run();
      assertEquals(2, s.catalog().size());
      assertEquals(
          List.of("base", "derived"),
          s.preview().resources().noise2D.stream().map(n -> n.id).toList());
      assertFalse(s.canExport());
      s.markShown(s.preview());
      assertTrue(s.canExport());
      assertTrue(owner.valuesCurrent(state));
      s.cancel();
    }
  }

  @Test
  void selectionKeepsTheLastPayloadAndCatalogButRequiresTheExactNewIncludedPlan() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = edit(state, workers, ui);
      deliver(workers, ui);
      var old = s.preview();
      var catalog = s.catalog();
      s.markShown(old);
      s.select(base(state));
      assertSame(old, s.preview());
      assertSame(catalog, s.catalog());
      assertFalse(s.canExport());
      s.markShown(old);
      assertThrows(IllegalStateException.class, () -> s.exportSnapshot(old));
      deliver(workers, ui);
      assertSame(catalog, s.catalog());
      assertNotSame(old, s.preview());
      assertFalse(s.canExport());
      s.markShown(old);
      assertFalse(s.canExport());
      s.markShown(s.preview());
      assertTrue(s.canExport());
      assertEquals("base", s.exportSnapshot(s.preview()).noise2D.getFirst().id);
      assertEquals(2, old.resources().noise2D.size());
    }
  }

  @Test
  void rapidSelectionChangesRetireQueuedAndCompletedWorkWithoutLosingTheLatestSet() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = edit(state, workers, ui);
      deliver(workers, ui);
      var old = s.preview();
      s.select(base(state));
      workers.run();
      var late = ui.remove();
      s.select(Set.of());
      s.select(derived(state));
      late.run();
      assertSame(old, s.preview());
      workers.run();
      assertTrue(ui.isEmpty());
      deliver(workers, ui);
      assertEquals(derived(state), s.selected());
      assertEquals(2, s.preview().resources().noise2D.size());
      assertFalse(s.canExport());
    }
  }

  @Test
  void unchangedSelectionsRetainOneCachedReviewAndSnapshotsCannotMutateIt() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = edit(state, workers, ui);
      deliver(workers, ui);
      var preview = s.preview();
      s.markShown(preview);
      for (int i = 0; i < 32; i++) s.select(new HashSet<>(derived(state)));
      assertTrue(workers.tasks.isEmpty());
      assertEquals(1, s.preparations());
      assertTrue(s.canExport());
      preview.resources().noise2D.clear();
      assertEquals(2, preview.resources().noise2D.size());
      assertThrows(UnsupportedOperationException.class, () -> s.selected().clear());
      assertTrue(s.canExport());
    }
  }

  @Test
  void managedAndRawSourceChangesDiscardPendingResultsAndReloadCapturesFreshDefinitions() {
    for (boolean raw : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = edit(state, workers, ui);
        workers.run();
        if (raw) state.project().noise2D.getFirst().algorithm.parameters.put("value", .25);
        else
          assertTrue(
              state.change(p -> p.noise2D.getFirst().algorithm.parameters.put("value", .25)));
        ui.remove().run();
        assertNull(s.preview());
        assertFalse(s.current());
        assertFalse(s.canExport());
        s.reload();
        deliver(workers, ui);
        assertEquals(
            .25, s.preview().resources().noise2D.getFirst().algorithm.parameters.get("value"));
        assertFalse(s.canExport());
        s.markShown(s.preview());
        assertTrue(s.canExport());
      }
  }

  @Test
  void privateSeedSelectionAndSaveChangesRetireWorkWithoutApplyingAnyDraft() throws Exception {
    for (int mode = 0; mode < 4; mode++)
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = edit(state, workers, ui);
        workers.run();
        if (mode == 0) ((Noise) state.draft).id = "later-private";
        else if (mode == 1) state.seedInputs.edit(state, "seedOffset", "Seed", "+");
        else if (mode == 2) state.select("noise2D", state.project().noise2D.getFirst().uuid);
        else state.document.save(directory.resolve("active-" + mode + ".json"));
        var owner = new ProjectFileOwner(state);
        ui.remove().run();
        assertFalse(s.canExport());
        assertTrue(s.canRetryPlan());
        assertTrue(owner.valuesCurrent(state));
        s.retryPlan();
        deliver(workers, ui);
        assertTrue(owner.valuesCurrent(state));
        assertFalse(s.canExport());
      }
  }

  @Test
  void reloadRetainsMissingSelectionsAndExplicitRemovalPreparesAnEmptyNonExportablePlan() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var selected = new LinkedHashSet<>(derived(state));
      var panel = new ResourceExportPanel(state, selected, workers, ui::add, () -> false);
      panel.open();
      deliver(workers, ui);
      var old = panel.session().preview();
      assertTrue(state.change(p -> ResourceGraph.remove(p, Set.of(p.noise2D.getLast().uuid))));
      panel.reload();
      assertSame(old, panel.session().preview());
      assertFalse(panel.canRemoveUnavailable());
      deliver(workers, ui);
      assertEquals(1, panel.unavailable());
      assertTrue(panel.canRemoveUnavailable());
      assertNull(panel.session().preview());
      panel.removeUnavailable();
      assertTrue(selected.isEmpty());
      deliver(workers, ui);
      assertEquals(0, panel.unavailable());
      assertFalse(panel.canExport());
      panel.reset();
    }
  }

  @Test
  void schedulingFailureKeepsDesiredSelectionAndSupportsExplicitRetryWithoutOldReviewAuthority() {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var reject = new AtomicBoolean(true);
      var s =
          edit(
              state,
              task -> {
                if (reject.get()) throw new RejectedExecutionException("fixture scheduler");
                workers.execute(task);
              },
              ui);
      assertTrue(s.canRetryPlan());
      assertTrue(s.planningMessage().contains("fixture scheduler"));
      assertFalse(s.canExport());
      reject.set(false);
      s.retryPlan();
      deliver(workers, ui);
      assertNotNull(s.preview());
      assertFalse(s.canExport());
    }
  }

  @Test
  void handoffFailureAndCancelledWorkLeaveAnExplicitRetryInsteadOfPermanentPreparation() {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var s =
            new ExportEditSession(
                state,
                derived(state),
                workers,
                task -> {
                  throw new AssertionError("fixture handoff");
                },
                () -> false);
        if (cancel) ((Future<?>) workers.tasks.getFirst()).cancel(false);
        else workers.run();
        assertFalse(s.planning());
        assertTrue(s.canRetryPlan());
        assertFalse(s.canExport());
      }
  }

  @Test
  void invalidCatalogWorkFailsOnWorkerAndExplicitReloadCanRepairTheSource() {
    try (var state = state()) {
      state.project().project.rootOrder =
          null; // Deliberate unsupported corruption exercises the worker boundary.
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = edit(state, workers, ui);
      assertTrue(s.planning());
      deliver(workers, ui);
      assertFalse(s.planning());
      assertTrue(s.canRetryPlan());
      assertNull(s.catalog());
      state.project().project.rootOrder = new LinkedHashMap<>();
      s.reload();
      deliver(workers, ui);
      assertEquals(2, s.catalog().size());
      assertFalse(s.canExport());
    }
  }

  @Test
  void reentrantGateCancellationAndBlockersCannotAdoptRejectedResults() {
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var holder = new ExportEditSession[1];
        var s =
            new ExportEditSession(
                state,
                derived(state),
                workers,
                ui::add,
                () -> {
                  if (cancel) holder[0].cancel();
                  return true;
                });
        holder[0] = s;
        deliver(workers, ui);
        assertNull(s.preview());
        assertFalse(s.canExport());
        assertEquals(cancel, s.closed());
      }
  }

  @Test
  void reviewedAsyncPlanUsesTheExistingGuardedFileBoundaryWithoutMutatingSourceHistory()
      throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var owner = new ProjectFileOwner(state);
      long history = state.document.historyBytes();
      var s = edit(state, workers, ui);
      deliver(workers, ui);
      var file = directory.resolve("selection.json");
      Files.writeString(file, "previous");
      assertThrows(IllegalStateException.class, () -> s.write(file, new ResourcePackageFiles()));
      assertEquals("previous", Files.readString(file));
      s.markShown(s.preview());
      s.write(file, new ResourcePackageFiles());
      assertTrue(s.closed());
      var payload = new ResourcePackageFiles().read(file);
      assertEquals(List.of("base", "derived"), payload.noise2D.stream().map(n -> n.id).toList());
      assertTrue(owner.valuesCurrent(state));
      assertEquals(history, state.document.historyBytes());
      assertEquals("private", ((Noise) state.draft).id);
    }
  }

  @Test
  void writerCannotStageUntilTheLatestCompletedPlanHasBeenShown() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var s = edit(state, workers, ui);
      var files = new ResourcePackageFiles();
      var target = directory.resolve("output.json");
      Files.writeString(target, "previous");
      try (var writer =
          new ResourceExportWriter(state, workers, ui::add, files::prepare, () -> false)) {
        assertFalse(writer.start(target.toString(), s, s.preview(), () -> true));
        assertEquals("previous", Files.readString(target));
        deliver(workers, ui);
        s.markShown(s.preview());
        s.select(base(state));
        var old = s.preview();
        assertFalse(writer.start(target.toString(), s, old, () -> true));
        deliver(workers, ui);
        assertEquals("previous", Files.readString(target));
        s.markShown(s.preview());
        assertTrue(writer.start(target.toString(), s, s.preview(), () -> true));
        deliver(workers, ui);
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        assertEquals(1, files.read(target).noise2D.size());
      }
    }
  }

  @Test
  void newAndIdenticalSameUuidDocumentsCannotAdoptAnEarlierPreview() throws Exception {
    for (boolean reopen : List.of(false, true))
      try (var state = state()) {
        var source = directory.resolve("source-" + reopen + ".json");
        state.document.save(source);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var s = edit(state, workers, ui);
        workers.run();
        if (reopen) assertTrue(state.document.open(source).isEmpty());
        else state.newSession();
        var values = ProjectIO.JSON.valueToTree(state.project());
        ui.remove().run();
        assertNull(s.preview());
        assertFalse(s.current());
        assertFalse(s.canReload());
        assertFalse(s.canRetryPlan());
        assertFalse(s.canExport());
        assertThrows(IllegalStateException.class, s::reload);
        assertEquals(values, ProjectIO.JSON.valueToTree(state.project()));
      }
  }

  @Test
  void cancelledOrClosedDialogRejectsLateCompletionAndCannotOverwriteASuccessor() {
    for (boolean close : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var resources =
            new ResourcePanel(state, new PropertyPanel(state), workers, ui::add, () -> false);
        var imports = new ResourceImportPanel(state, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(state, resources, imports, () -> false);
        assertTrue(dialog.open("Export"));
        var original = resources.exportView().session();
        workers.run();
        var late = ui.remove();
        if (close) dialog.close();
        else dialog.cancel();
        assertTrue(original.closed());
        assertFalse(dialog.pending());
        if (close) assertFalse(dialog.open("Export"));
        else {
          assertTrue(dialog.open("Export"));
          var successor = resources.exportView().session();
          late.run();
          assertSame(successor, resources.exportView().session());
          assertNull(successor.preview());
          deliver(workers, ui);
          assertFalse(successor.canExport());
          dialog.close();
        }
        late.run();
        assertFalse(resources.canExport());
      }
  }
}
