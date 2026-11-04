package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred reviewed-source staging, UI-only publication and request cleanup contracts. */
class BackgroundResourceExportTest {
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

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(state.change(p -> p.noise2D.addAll(PreparedResourceExportTest.resources().noise2D)));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "private";
    state.seedInputs.edit(state, "seedOffset", "Seed", "-");
    return state;
  }

  private ExportEditSession reviewed(EditorState state) {
    var source = new ExportEditSession(state, Set.of(state.selected));
    source.markShown(source.preview());
    return source;
  }

  private ResourceExportWriter writer(
      EditorState state,
      Executor workers,
      java.util.function.Consumer<Runnable> ui,
      ResourcePackageFiles files) {
    return new ResourceExportWriter(state, workers, ui, files::prepare, () -> false);
  }

  private void noTemporaryFiles() throws IOException {
    try (var paths = Files.walk(directory)) {
      assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-package-")));
    }
  }

  @Test
  void workerStagesAnOwnedReviewedPayloadAndOnlyUiDeliveryCanReplace() throws Exception {
    try (var state = state();
        var workers = Executors.newSingleThreadExecutor()) {
      var source = reviewed(state);
      var preview = source.preview();
      var queue = new LinkedBlockingQueue<Runnable>();
      var files = new ResourcePackageFiles();
      var uiThread = Thread.currentThread();
      var target = directory.resolve("资源 package.json");
      Files.writeString(target, "prior");
      var authority = new ProjectFileOwner(state);
      long history = state.document.historyBytes();
      try (var writer =
          new ResourceExportWriter(
              state,
              workers,
              queue::add,
              (file, payload, active) -> {
                assertNotSame(uiThread, Thread.currentThread());
                return files.prepare(file, payload, active);
              },
              () -> false)) {
        assertTrue(writer.start(target.toString(), source, preview, () -> true));
        var completion = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertEquals("prior", Files.readString(target));
        assertFalse(source.closed());
        assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase());
        completion.run();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        assertTrue(source.closed());
        assertTrue(authority.valuesCurrent(state));
        assertEquals(history, state.document.historyBytes());
        assertEquals("field", files.read(target).noise2D.getFirst().id);
        assertEquals("-", state.seedInputs.text("seedOffset"));
        noTemporaryFiles();
      }
    }
  }

  @Test
  void anUnreviewedPreviewCannotScheduleFilesystemWork() {
    try (var state = state()) {
      var source = new ExportEditSession(state, Set.of(state.selected));
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
        assertFalse(
            writer.start(
                directory.resolve("not-reviewed.json").toString(),
                source,
                source.preview(),
                () -> true));
        assertTrue(workers.tasks.isEmpty());
        assertTrue(ui.isEmpty());
        assertFalse(source.closed());
      }
    }
  }

  @Test
  void cancellationBeforeExecutionAndAfterStagingPreservesThePriorDestination() throws Exception {
    for (boolean completed : List.of(false, true))
      try (var state = state()) {
        var source = reviewed(state);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var target = directory.resolve("cancel-" + completed);
        Files.writeString(target, "prior");
        try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
          assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
          if (completed) workers.run();
          writer.cancel();
          while (!workers.tasks.isEmpty()) workers.run();
          while (!ui.isEmpty()) ui.removeFirst().run();
          assertEquals(ResourceExportWriter.Phase.CANCELLED, writer.view().phase());
          assertEquals("prior", Files.readString(target));
          assertFalse(source.closed());
          noTemporaryFiles();
        }
      }
  }

  @Test
  void aLateNonCooperativePreparationDisposesItsSiblingAfterCancellation() throws Exception {
    try (var state = state();
        var workers = Executors.newSingleThreadExecutor()) {
      var source = reviewed(state);
      var files = new ResourcePackageFiles();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var disposed = new CountDownLatch(1);
      var target = directory.resolve("late.json");
      Files.writeString(target, "prior");
      var queue = new ConcurrentLinkedQueue<Runnable>();
      var storage =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temp, byte[] content) throws IOException {
                  Files.write(temp, content);
                }

                public void replace(Path temp, Path to) {
                  fail("Cancelled worker published");
                }

                public void delete(Path temp) throws IOException {
                  Files.delete(temp);
                  disposed.countDown();
                }
              });
      try (var writer =
          new ResourceExportWriter(
              state,
              workers,
              queue::add,
              (file, payload, active) -> {
                entered.countDown();
                boolean waiting = true;
                while (waiting)
                  try {
                    release.await();
                    waiting = false;
                  } catch (InterruptedException ignored) {
                    /* simulated provider */
                  }
                return storage.prepare(file, payload, active);
              },
              () -> false)) {
        try {
          assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          writer.cancel();
        } finally {
          release.countDown();
        }
        assertTrue(disposed.await(5, TimeUnit.SECONDS));
        assertTrue(queue.isEmpty());
        assertEquals("prior", Files.readString(target));
        noTemporaryFiles();
      }
    }
  }

  @Test
  void rawManagedPrivateSelectionAndLifetimeChangesRejectTheStagedCandidate() throws Exception {
    for (String mode : List.of("raw", "edit", "private", "seed", "selection", "new", "open"))
      try (var state = state()) {
        var original = directory.resolve("original-" + mode + ".json");
        state.document.save(original);
        var source = reviewed(state);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var target = directory.resolve("stale-" + mode);
        Files.writeString(target, "prior");
        var selected = new AtomicBoolean(true);
        try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
          assertTrue(writer.start(target.toString(), source, source.preview(), selected::get));
          workers.run();
          switch (mode) {
            case "raw" -> state.project().project.seed++;
            case "edit" -> assertTrue(state.change(p -> p.project.seed++));
            case "private" -> ((Noise) state.draft).id = "new-private";
            case "seed" -> state.seedInputs.edit(state, "seedOffset", "Seed", "--");
            case "selection" -> selected.set(false);
            case "new" -> state.newSession();
            default -> assertTrue(state.document.open(original).isEmpty());
          }
          var after = new ProjectFileOwner(state);
          ui.removeFirst().run();
          assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase());
          assertTrue(after.valuesCurrent(state));
          assertEquals("prior", Files.readString(target));
          assertFalse(source.closed());
          noTemporaryFiles();
        }
      }
  }

  @Test
  void lateSaveAsCannotMakeTheActiveProjectAnExportDestination() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var target = directory.resolve("became-project.json");
      try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        state.document.save(target);
        byte[] bytes = Files.readAllBytes(target);
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase());
        assertArrayEquals(bytes, Files.readAllBytes(target));
        assertTrue(new ProjectIO().load(target).success());
        noTemporaryFiles();
      }
    }
  }

  @Test
  void replacementFailureKeepsTheReviewedPreviewForExplicitRestagedRetry() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var preview = source.preview();
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var fail = new AtomicBoolean(true);
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temp, byte[] content) throws IOException {
                  Files.write(temp, content);
                }

                public void replace(Path temp, Path target) throws IOException {
                  if (fail.getAndSet(false))
                    throw new AtomicMoveNotSupportedException(
                        temp.toString(), target.toString(), "injected");
                  Files.move(
                      temp,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }
              });
      var target = directory.resolve("retry.json");
      Files.writeString(target, "prior");
      try (var writer = writer(state, workers, ui::add, files)) {
        assertTrue(writer.start(target.toString(), source, preview, () -> true));
        workers.run();
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.FAILED, writer.view().phase());
        assertTrue(source.canExport());
        assertSame(preview, source.preview());
        assertEquals("prior", Files.readString(target));
        noTemporaryFiles();
        assertTrue(writer.start(target.toString(), source, preview, () -> true));
        workers.run();
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        assertTrue(source.closed());
        assertEquals("field", files.read(target).noise2D.getFirst().id);
        noTemporaryFiles();
      }
    }
  }

  @Test
  void schedulerAndUiHandoffFailuresReleaseStagingAndPermitRetry() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var files = new ResourcePackageFiles();
      var target = directory.resolve("handoff.json");
      Files.writeString(target, "prior");
      try (var writer =
          writer(
              state,
              r -> {
                throw new RejectedExecutionException("full");
              },
              r -> fail("unexpected UI task"),
              files)) {
        assertFalse(writer.start(target.toString(), source, source.preview(), () -> true));
        assertEquals(ResourceExportWriter.Phase.FAILED, writer.view().phase());
      }
      var workers = new Workers();
      try (var writer =
          writer(
              state,
              workers,
              r -> {
                throw new AssertionError("handoff");
              },
              files)) {
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        assertEquals(ResourceExportWriter.Phase.FAILED, writer.view().phase());
        assertEquals("prior", Files.readString(target));
        noTemporaryFiles();
        assertTrue(source.canExport());
      }
    }
  }

  @Test
  void externalWorkerCancellationAndPreparationCancellationRemainExplicitCancellation()
      throws Exception {
    for (boolean future : List.of(false, true))
      try (var state = state()) {
        var source = reviewed(state);
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var target = directory.resolve("external-cancel-" + future);
        Files.writeString(target, "prior");
        try (var writer =
            new ResourceExportWriter(
                state,
                workers,
                ui::add,
                (file, payload, active) -> {
                  throw new CancellationException("cancelled");
                },
                () -> false)) {
          assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
          if (future) ((Future<?>) workers.tasks.getFirst()).cancel(true);
          workers.run();
          while (!ui.isEmpty()) ui.removeFirst().run();
          assertEquals(ResourceExportWriter.Phase.CANCELLED, writer.view().phase());
          assertEquals("prior", Files.readString(target));
          assertTrue(source.canExport());
          noTemporaryFiles();
        }
      }
  }

  @Test
  void aCancelledCallbackCannotConsumeOrDisposeASuccessorPayload() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var target = directory.resolve("successor.json");
      Files.writeString(target, "prior");
      try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        var old = ui.removeFirst();
        writer.cancel();
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        old.run();
        assertEquals("prior", Files.readString(target));
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        old.run();
        assertTrue(source.closed());
        noTemporaryFiles();
      }
    }
  }

  @Test
  void reentrantCompletionGatesCannotRetireASuccessorRequest() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var target = directory.resolve("gate.json");
      Files.writeString(target, "prior");
      var reference = new AtomicReference<ResourceExportWriter>();
      var once = new AtomicBoolean();
      try (var writer = writer(state, workers, ui::add, new ResourcePackageFiles())) {
        reference.set(writer);
        assertTrue(
            writer.start(
                target.toString(),
                source,
                source.preview(),
                () -> {
                  if (once.compareAndSet(false, true)) {
                    reference.get().cancel();
                    assertTrue(
                        reference
                            .get()
                            .start(target.toString(), source, source.preview(), () -> true));
                    return false;
                  }
                  return true;
                }));
        workers.run();
        var old = ui.removeFirst();
        old.run();
        assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase());
        workers.run();
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        noTemporaryFiles();
      }
    }
  }

  @Test
  void completionBlockersCleanupErrorsAndClosedRequestsCannotPublishOrTrapTheReview()
      throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var blocked = new AtomicBoolean();
      var target = directory.resolve("blocked.json");
      Files.writeString(target, "prior");
      var files = new ResourcePackageFiles();
      try (var writer =
          new ResourceExportWriter(state, workers, ui::add, files::prepare, blocked::get)) {
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        blocked.set(true);
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase());
        assertEquals("prior", Files.readString(target));
        noTemporaryFiles();
        blocked.set(false);
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        writer.close();
        writer.close();
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.CLOSED, writer.view().phase());
        assertFalse(writer.start(target.toString(), source, source.preview(), () -> true));
        assertFalse(source.closed());
        assertEquals("prior", Files.readString(target));
        noTemporaryFiles();
      }
    }
  }

  @Test
  void cleanupFailureCannotOverwriteCancellationOrPreventASuccessorExport() throws Exception {
    try (var state = state()) {
      var source = reviewed(state);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var cleanup = new AtomicBoolean(true);
      var files =
          new ResourcePackageFiles(
              new ResourcePackageFiles.Storage() {
                public void write(Path temp, byte[] content) throws IOException {
                  Files.write(temp, content);
                }

                public void replace(Path temp, Path target) throws IOException {
                  Files.move(
                      temp,
                      target,
                      StandardCopyOption.ATOMIC_MOVE,
                      StandardCopyOption.REPLACE_EXISTING);
                }

                public void delete(Path temp) throws IOException {
                  Files.deleteIfExists(temp);
                  if (cleanup.getAndSet(false)) throw new IOException("cleanup feedback");
                }
              });
      var target = directory.resolve("cleanup.json");
      Files.writeString(target, "prior");
      try (var writer = writer(state, workers, ui::add, files)) {
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        assertDoesNotThrow(writer::cancel);
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.CANCELLED, writer.view().phase());
        assertEquals("prior", Files.readString(target));
        assertTrue(source.canExport());
        assertTrue(writer.start(target.toString(), source, source.preview(), () -> true));
        workers.run();
        ui.removeFirst().run();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        noTemporaryFiles();
      }
    }
  }
}
