package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred worker preparation, private review installation and request lifetime contracts. */
class BackgroundResourceImportTest {
  @TempDir Path directory;

  private static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.removeFirst().run();
    }
  }

  private static Noise noise(String id, double value) {
    var n = new Noise();
    n.id = id;
    n.algorithm.key = "CONSTANT";
    n.algorithm.parameters.clear();
    n.algorithm.parameters.put("value", value);
    return n;
  }

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> p.noise2D.add(noise("existing", .125))));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private Path source(String name) throws Exception {
    var incoming = new ProjectData();
    incoming.noise2D.add(noise("incoming", .75));
    var file = directory.resolve(name);
    Files.writeString(
        file,
        ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", incoming)));
    return file;
  }

  private ResourceImportLoader.Preparation prepare() {
    var files = new ResourcePackageFiles();
    return (source, destination) -> ImportEditSession.prepare(destination, files.read(source));
  }

  private ResourceImportLoader loader(
      EditorState state,
      Executor workers,
      Consumer<Runnable> ui,
      ResourceImportPanel panel,
      BooleanSupplier blocked) {
    return new ResourceImportLoader(state, workers, ui, prepare(), panel::openPrepared, blocked);
  }

  private static void retained(
      EditorState state, ProjectFileOwner owner, Object draft, long history) {
    assertTrue(owner.valuesCurrent(state));
    assertSame(draft, state.draft);
    assertEquals(history, state.document.historyBytes());
    assertEquals("-", state.seedInputs.text("seedOffset"));
  }

  @Test
  void readValidationAndInitialPreviewRunOffThreadWithOwnerReviewAcceptance()
      throws Exception {
    try (var state = state();
        var workers = Executors.newSingleThreadExecutor()) {
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      var queue = new LinkedBlockingQueue<Runnable>();
      var owner = new ProjectFileOwner(state);
      var draft = state.draft;
      long history = state.document.historyBytes();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var uiThread = Thread.currentThread();
      var active = state.project();
      var preparation = prepare();
      try (var loading =
          new ResourceImportLoader(
              state,
              workers,
              queue::add,
              (source, destination) -> {
                assertNotSame(uiThread, Thread.currentThread());
                assertNotSame(active, destination);
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return preparation.prepare(source, destination);
              },
              panel::openPrepared,
              () -> false)) {
        try {
          assertTrue(loading.start(source("空 间.pvresources.json").toString()));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase());
          assertFalse(panel.pending());
          retained(state, owner, draft, history);
        } finally {
          release.countDown();
        }
        var deliver = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(deliver);
        assertFalse(panel.pending());
        var request = loading.requestToken();
        deliver.run();
        assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase());
        assertSame(request, loading.requestToken());
        assertFalse(panel.pending());
        workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
        loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.READY, loading.view().phase());
        assertTrue(panel.pending());
        assertSame(panel.session().preview(), panel.session().preview());
        retained(state, owner, draft, history);
      }
    }
  }

  @Test
  void completedPreparationWaitsForReviewAndExplicitApplyPreservingPrivateState() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        var owner = new ProjectFileOwner(state);
        var draft = state.draft;
        var before = ProjectIO.JSON.valueToTree(state.project());
        long history = state.document.historyBytes();
        assertTrue(loading.start(source("ordinary.json").toString()));
        workers.run();
        assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase());
        assertFalse(panel.pending());
        ui.removeFirst().run();
        loading.acceptPrepared();
        retained(state, owner, draft, history);
        var session = panel.session();
        assertFalse(session.canApply());
        assertEquals(ImportEditSession.Result.NOT_REVIEWED, session.apply());
        session.markShown(session.preview());
        assertEquals(ImportEditSession.Result.APPLIED, session.apply());
        assertEquals(2, state.project().noise2D.size());
        assertSame(draft, state.draft);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        var after = ProjectIO.JSON.valueToTree(state.project());
        state.document.undo();
        assertEquals(before, ProjectIO.JSON.valueToTree(state.project()));
        state.document.redo();
        assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
      }
    }
  }

  @Test
  void cancellationBeforeExecutionDoesNotReadThePackage() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var reads = new AtomicInteger();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading =
          new ResourceImportLoader(
              state,
              workers,
              ui::add,
              (file, destination) -> {
                reads.incrementAndGet();
                return prepare().prepare(file, destination);
              },
              panel::openPrepared,
              () -> false)) {
        assertTrue(loading.start(source("queued.json").toString()));
        loading.cancel();
        workers.run();
        assertEquals(0, reads.get());
        assertTrue(ui.isEmpty());
        assertFalse(panel.pending());
        assertEquals(ResourceImportLoader.Phase.CANCELLED, loading.view().phase());
      }
    }
  }

  @Test
  void completedCancelledResultsCannotOpenOrConsumeASuccessorReview() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        var source = source("late.json").toString();
        assertTrue(loading.start(source));
        workers.run();
        var old = ui.removeFirst();
        loading.cancel();
        assertTrue(loading.start(source));
        old.run();
        assertFalse(panel.pending());
        assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase());
        workers.run();
        ui.removeFirst().run();
        loading.acceptPrepared();
        var session = panel.session();
        old.run();
        assertSame(session, panel.session());
        assertEquals(ResourceImportLoader.Phase.READY, loading.view().phase());
      }
    }
  }

  @Test
  void rawCommittedChangesRejectCompletedPreparationWithoutRollingBackNewerValues()
      throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        assertTrue(loading.start(source("raw.json").toString()));
        workers.run();
        state.project().project.seed = 314;
        var after = ProjectIO.JSON.valueToTree(state.project());
        ui.removeFirst().run();
        loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase());
        assertFalse(panel.pending());
        assertEquals(after, ProjectIO.JSON.valueToTree(state.project()));
      }
    }
  }

  @Test
  void changedPrivateInputsSelectionOrManagedProjectRetireThePendingReview() throws Exception {
    var file = source("private.json").toString();
    for (String mode : List.of("draft", "seed", "selection", "edit"))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var panel = new ResourceImportPanel(state, () -> false, () -> {});
        try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
          assertTrue(loading.start(file));
          workers.run();
          switch (mode) {
            case "draft" -> ((Noise) state.draft).id = "successor-private";
            case "seed" -> state.seedInputs.edit(state, "seedOffset", "Seed", "--");
            case "selection" -> state.multiSelection.clear();
            default -> assertTrue(state.change(p -> p.project.seed++));
          }
          var after = new ProjectFileOwner(state);
          ui.removeFirst().run();
          loading.acceptPrepared();
          assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase());
          assertTrue(after.valuesCurrent(state));
          assertFalse(panel.pending());
        }
      }
  }

  @Test
  void sameUuidOpenAndNewDocumentLifetimeRejectTheOldPackagePreparation() throws Exception {
    for (boolean open : List.of(false, true))
      try (var state = state()) {
        var original = directory.resolve("original-" + open + ".json");
        state.document.save(original);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var panel = new ResourceImportPanel(state, () -> false, () -> {});
        try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
          assertTrue(loading.start(source("lifetime-" + open).toString()));
          workers.run();
          if (open) assertTrue(state.document.open(original).isEmpty());
          else state.newSession();
          var after = new ProjectFileOwner(state);
          ui.removeFirst().run();
          loading.acceptPrepared();
          assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase());
          assertTrue(after.valuesCurrent(state));
          assertFalse(panel.pending());
        }
      }
  }

  @Test
  void malformedPackageFailureKeepsTheSourceBytesAndSupportsExplicitCorrectedRetry()
      throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      var file = directory.resolve("malformed.json");
      Files.writeString(file, "{\"packageVersion\":2}");
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        var owner = new ProjectFileOwner(state);
        assertTrue(loading.start(file.toString()));
        workers.run();
        ui.removeFirst().run();
        loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase());
        assertFalse(panel.pending());
        assertTrue(owner.valuesCurrent(state));
        assertEquals("{\"packageVersion\":2}", Files.readString(file));
        source("malformed.json");
        assertTrue(loading.start(file.toString()));
        workers.run();
        ui.removeFirst().run();
        loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.READY, loading.view().phase());
        assertTrue(panel.pending());
      }
    }
  }

  @Test
  void enabledCompilerInvalidPackagesNeverCreateAPreviewOrChangeCommittedDiagnostics()
      throws Exception {
    try (var state = state()) {
      var incoming = new ProjectData();
      var noise = noise("invalid", .75);
      var expression = ProcessingRegistry.create("EXPRESSION", "formula");
      expression.parameters.put("expression", ProjectIO.JSON.valueToTree("sin("));
      noise.processing.add(expression);
      noise.outputBinding = ProjectData.Binding.earlier(expression.uuid);
      incoming.noise2D.add(noise);
      var file = directory.resolve("compiler-invalid.json");
      Files.writeString(
          file,
          ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", incoming)));
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      var issues = state.issues;
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        assertTrue(loading.start(file.toString()));
        workers.run();
        ui.removeFirst().run();
        loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase());
        assertFalse(panel.pending());
        assertSame(issues, state.issues);
      }
    }
  }

  @Test
  void invalidPathsRejectBeforeSchedulingAndADuplicateStartCannotRetargetTheCurrentRead()
      throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading = loader(state, workers, ui::add, panel, () -> false)) {
        assertFalse(loading.start("\u0000"));
        assertTrue(workers.tasks.isEmpty());
        assertTrue(loading.start(source("valid.json").toString()));
        assertFalse(loading.start("different.json"));
        assertEquals(1, workers.tasks.size());
        workers.run();
        ui.removeFirst().run();
        loading.acceptPrepared();
        assertTrue(panel.pending());
      }
    }
  }

  @Test
  void schedulerAndHandoffFailuresCannotLeavePreparationPermanentlyRunning() throws Exception {
    var file = source("rejection.json").toString();
    try (var state = state()) {
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      try (var loading =
          loader(
              state,
              task -> {
                throw new RejectedExecutionException("full");
              },
              task -> fail("No UI task expected"),
              panel,
              () -> false)) {
        assertFalse(loading.start(file));
        assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase());
      }
      var workers = new Workers();
      try (var loading =
          loader(
              state,
              workers,
              task -> {
                throw new IllegalStateException("handoff");
              },
              panel,
              () -> false)) {
        assertTrue(loading.start(file));
        workers.run();
        assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase());
        assertFalse(panel.pending());
      }
    }
  }

  @Test
  void cancelledWorkerAndFatalPreparationOrReviewFailuresReleaseRequestAuthority()
      throws Exception {
    var file = source("worker-failure.json").toString();
    for (String mode : List.of("cancel", "worker", "review"))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var preparation = prepare();
        try (var loading =
            new ResourceImportLoader(
                state,
                workers,
                ui::add,
                (path, destination) -> {
                  if (mode.equals("worker")) throw new AssertionError("worker failed");
                  return preparation.prepare(path, destination);
                },
                (prepared, current) -> {
                  throw new AssertionError("review failed");
                },
                () -> false)) {
          assertTrue(loading.start(file));
          if (mode.equals("cancel")) ((Future<?>) workers.tasks.getFirst()).cancel(true);
          workers.run();
          while (!ui.isEmpty()) ui.removeFirst().run();
          loading.acceptPrepared();
          assertEquals(
              mode.equals("cancel")
                  ? ResourceImportLoader.Phase.CANCELLED
                  : ResourceImportLoader.Phase.FAILED,
              loading.view().phase());
        }
      }
  }

  @Test
  void completionBlockersAndReentrantReviewCancellationCannotInstallAnOldWorkspace()
      throws Exception {
    var file = source("gate.json").toString();
    for (boolean cancel : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var blocked = new AtomicBoolean();
        var ref = new AtomicReference<ResourceImportLoader>();
        var panel =
            new ResourceImportPanel(
                state,
                () -> {
                  if (cancel) ref.get().cancel();
                  return false;
                },
                () -> {});
        try (var loading = loader(state, workers, ui::add, panel, blocked::get)) {
          ref.set(loading);
          assertTrue(loading.start(file));
          workers.run();
          if (!cancel) blocked.set(true);
          ui.removeFirst().run();
          loading.acceptPrepared();
          assertFalse(panel.pending());
          assertEquals(
              cancel ? ResourceImportLoader.Phase.CANCELLED : ResourceImportLoader.Phase.STALE,
              loading.view().phase());
        }
      }
  }

  @Test
  void preparedPlansOwnTheirInputsAndCanInstallOnlyOnce() throws Exception {
    try (var state = state()) {
      var incoming = new ResourcePackageFiles().read(source("owned.json"));
      var destination = ProjectIO.copy(state.project());
      var prepared = ImportEditSession.prepare(destination, incoming);
      incoming.noise2D.clear();
      destination.noise2D.clear();
      var session = new ImportEditSession(state, prepared);
      assertEquals(1, session.preview().rows().size());
      assertEquals(2, session.preview().candidate().noise2D.size());
      assertThrows(IllegalStateException.class, () -> new ImportEditSession(state, prepared));
      session.cancel();
    }
  }

  @Test
  void backgroundDialogKeepsItsExactPathAndOpeningUntilHandoffOrCancel() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var resources = new ResourcePanel(state, new PropertyPanel(state));
      var panel = new ResourceImportPanel(state, () -> false, () -> {});
      var seen = new AtomicReference<Path>();
      var prepare = prepare();
      try (var dialog =
          new ResourcePackageDialog(
              state,
              resources,
              panel,
              () -> false,
              new ResourcePackageFiles(),
              workers,
              ui::add,
              (path, destination) -> {
                seen.set(path);
                return prepare.prepare(path, destination);
              })) {
        state.document.save(directory.resolve("dialog-project.json"));
        assertTrue(dialog.open("Import"));
        var file = source(dialog.pathText());
        dialog.dispatch(dialog.capture(1), ResourcePackageDialog.Command.SUBMIT, 1);
        assertTrue(dialog.importRunning());
        assertFalse(dialog.open("Export"));
        dialog.dispatch(dialog.capture(2), ResourcePackageDialog.Command.SUBMIT, 2);
        assertEquals(1, workers.tasks.size());
        workers.run();
        assertEquals(Path.of(dialog.pathText()), seen.get());
        assertTrue(dialog.pending());
        assertFalse(panel.pending());
        var shown = dialog.capture(3);
        var request = dialog.importRequestToken();
        ui.removeFirst().run();
        assertTrue(dialog.importRunning());
        assertFalse(panel.pending());
        dialog.acceptImport(shown, request);
        dialog.pollImport();
        assertFalse(dialog.pending());
        assertTrue(panel.pending());
        assertEquals(file.getFileName(), seen.get().getFileName());
      }
    }
  }

  @Test
  void closingTheBackgroundDialogRetiresQueuedAndCompletedResultsAndPreventsReopening()
      throws Exception {
    for (boolean complete : List.of(false, true))
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var resources = new ResourcePanel(state, new PropertyPanel(state));
        var panel = new ResourceImportPanel(state, () -> false, () -> {});
        var dialog =
            new ResourcePackageDialog(
                state,
                resources,
                panel,
                () -> false,
                new ResourcePackageFiles(),
                workers,
                ui::add,
                prepare());
        state.document.save(directory.resolve("closed-project-" + complete + ".json"));
        assertTrue(dialog.open("Import"));
        source(dialog.pathText());
        dialog.dispatch(dialog.capture(1), ResourcePackageDialog.Command.SUBMIT, 1);
        if (complete) workers.run();
        dialog.close();
        dialog.close();
        while (!workers.tasks.isEmpty()) workers.run();
        while (!ui.isEmpty()) ui.removeFirst().run();
        assertFalse(dialog.pending());
        assertFalse(panel.pending());
        assertFalse(dialog.open("Import"));
      }
  }
}
