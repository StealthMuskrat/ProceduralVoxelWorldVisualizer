package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred complete-worker/admission gates for portable package review and replacement. */
class PackageCompletionTest {
  @TempDir Path directory;
  private static final long TIMEOUT = 10;

  private final class Form implements AutoCloseable {
    final boolean exporting;
    final EditorState state = ReadyValidationTest.state();
    final ResourceImportPanel panel = new ResourceImportPanel(state, () -> false, () -> {});
    final ExportEditSession source = ReadyResourceExportTest.reviewed(state);
    final ResourcePackageFiles files;
    final ResourceImportLoader loader;
    final ResourceExportWriter writer;
    final Path root, incoming, target;
    final byte[] incomingBytes;
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<Error> cleanupFailure = new AtomicReference<>();
    final AtomicInteger reviews = new AtomicInteger(), replacements = new AtomicInteger();
    final Domain domain = new Domain(state);
    Runnable afterReplace = () -> {};
    boolean blocked, closed;

    Form(boolean exporting, Executor executor, Consumer<Runnable> ui) throws Exception {
      this.exporting = exporting; root = Files.createTempDirectory(directory, "package-");
      target = root.resolve("target.pvresources.json"); incoming = root.resolve("incoming.pvresources.json");
      Files.writeString(target, "prior");
      Files.writeString(incoming, ProjectIO.JSON.writeValueAsString(Map.of("packageVersion", 1, "resources", ReadyPackageLoadTest.incoming())));
      incomingBytes = Files.readAllBytes(incoming); var caller = Thread.currentThread();
      files = new ResourcePackageFiles(new ResourcePackageFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); }
        public void replace(Path temp, Path destination) throws IOException {
          assertSame(caller, Thread.currentThread()); Files.move(temp, destination,
              StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
          replacements.incrementAndGet(); afterReplace.run();
        }
        public void delete(Path temp) throws IOException {
          Files.deleteIfExists(temp); var failed = cleanupFailure.getAndSet(null); if (failed != null) throw failed;
        }
      });
      loader = exporting ? null : new ResourceImportLoader(state, executor, ui, (path, destination) -> {
        reject(failure.get()); return ImportEditSession.prepare(destination, files.read(path));
      }, (prepared, current) -> {
        assertSame(caller, Thread.currentThread());
        boolean opened = panel.openPrepared(prepared, current); if (opened) reviews.incrementAndGet(); return opened;
      }, () -> blocked);
      writer = exporting ? new ResourceExportWriter(state, executor, ui, (path, resources, active) -> {
        reject(failure.get()); return files.prepare(path, resources, active);
      }, () -> blocked) : null;
    }
    String path() { return (exporting ? target : incoming).toString(); }
    boolean start() { return exporting ? writer.start(path(), source, source.preview(), () -> true) : loader.start(path()); }
    void accept() { if (exporting) writer.acceptPrepared(); else loader.acceptPrepared(); }
    void accept(String shown) {
      if (exporting) writer.acceptPrepared(shown, () -> true); else loader.acceptPrepared(shown, () -> true);
    }
    boolean running() { return exporting ? writer.view().phase() == ResourceExportWriter.Phase.RUNNING
        : loader.view().phase() == ResourceImportLoader.Phase.RUNNING; }
    boolean succeeded() { return exporting ? writer.view().phase() == ResourceExportWriter.Phase.READY
        : loader.view().phase() == ResourceImportLoader.Phase.READY; }
    String message() { return exporting ? writer.view().message() : loader.view().message(); }
    Object token() { return exporting ? writer.requestToken() : loader.requestToken(); }
    Object taskOwner() { return exporting ? writer : loader; }
    void cancel() { if (exporting) writer.cancel(); else loader.cancel(); }
    void closeWork() { if (exporting) writer.close(); else loader.close(); }
    long failures() {
      String code = exporting ? "FILE_EXPORT_PREPARATION_FAILED" : "FILE_IMPORT_PREPARATION_FAILED";
      return state.events.snapshot().stream().filter(e -> e.code().equals(code)).mapToLong(EditorEvents.Entry::count).sum();
    }
    long temporary() throws Exception {
      try (var paths = Files.list(root)) { return paths.filter(p -> p.getFileName().toString().startsWith(".voxel-package-")).count(); }
    }
    void unpublished() throws Exception {
      domain.retained(state); assertEquals("prior", Files.readString(target)); assertArrayEquals(incomingBytes, Files.readAllBytes(incoming));
      assertFalse(panel.pending()); assertEquals(0, reviews.get()); assertEquals(0, replacements.get()); assertFalse(source.closed());
    }
    void published() throws Exception {
      assertTrue(succeeded()); assertFalse(running()); assertEquals(0, temporary()); domain.retained(state);
      assertArrayEquals(incomingBytes, Files.readAllBytes(incoming));
      if (exporting) {
        assertEquals(1, replacements.get()); assertTrue(source.closed()); assertFalse(panel.pending()); assertEquals(0, reviews.get());
        var resources = files.read(target); assertEquals(1, resources.noise2D.size());
        assertEquals("field", resources.noise2D.getFirst().id); assertEquals(.125, resources.noise2D.getFirst().algorithm.parameters.get("value"));
      } else {
        assertTrue(panel.pending()); assertEquals(1, reviews.get()); assertEquals(0, replacements.get()); assertFalse(source.closed());
        assertFalse(panel.session().canApply()); assertEquals("prior", Files.readString(target));
      }
    }
    public void close() {
      if (closed) return; closed = true; closeWork(); panel.close(); source.cancel(); state.close();
    }
  }
  private static final class Domain {
    final ProjectFileOwner owner;
    final JsonNode project, user;
    final long history, revision;
    final List<Diagnostic> issues;
    final String scope;
    final boolean undo, redo;
    Domain(EditorState state) {
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project()); user = ProjectIO.JSON.valueToTree(state.user);
      history = state.document.historyBytes(); revision = state.revision; issues = state.issues; scope = state.validationScope;
      undo = state.document.canUndo(); redo = state.document.canRedo();
    }
    void retained(EditorState state) {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user)); assertEquals(history, state.document.historyBytes()); assertEquals(revision, state.revision);
      assertSame(issues, state.issues); assertEquals(scope, state.validationScope); assertEquals(undo, state.document.canUndo()); assertEquals(redo, state.document.canRedo());
    }
  }
  private static void reject(Throwable failure) throws Exception {
    if (failure instanceof Error error) throw error; if (failure instanceof Exception error) throw error;
  }
  private static void await(CountDownLatch latch) {
    boolean interrupted = false; long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) try {
        assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS), "handoff fixture timeout"); return;
      } catch (InterruptedException cancelled) { interrupted = true; }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }
  private static Runnable receipt(BlockingQueue<Runnable> ui) throws InterruptedException {
    var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); return callback;
  }
  private static void replaceTask(Form form, FutureTask<Void> replacement) throws Exception {
    var owner = form.taskOwner(); Field field = owner.getClass().getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false); field.set(owner, replacement);
  }

  @Test
  void realPostingReceiptCannotOpenReviewOrReplaceDestinationUntilWorkerReturn() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(exporting, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          assertTrue(form.start()); var token = form.token(); var callback = receipt(ui); callback.run(); form.accept();
          assertTrue(form.running()); assertSame(token, form.token()); form.unpublished(); assertEquals(exporting ? 1 : 0, form.temporary());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); callback.run(); form.accept(); form.published();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void latePosterFailureKeepsTheReviewOrFilePrivateAndAllowsExplicitRetry() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(exporting, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
      })) {
        try {
          form.start(); var old = receipt(ui); old.run(); form.accept(); form.unpublished();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept();
          assertFalse(form.running()); form.unpublished(); assertEquals(1, form.failures()); assertEquals(0, form.temporary());
          broken.set(false); assertTrue(form.start()); old.run(); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); form.published(); assertEquals(1, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void cancelAndCloseDuringHeldSuccessFailureOrCancellationSuppressLatePostingFaults() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) for (boolean close : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(exporting, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (mode == 1) form.failure.set(new AssertionError("preparation")); else if (mode == 2) form.failure.set(new CancellationException("preparation"));
          form.start(); var callback = receipt(ui); callback.run(); form.accept();
          if (close) form.closeWork(); else form.cancel(); assertEquals(0, form.temporary());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); form.accept();
          assertFalse(form.running()); form.unpublished(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForBothPostingAndExecutorSubmissionToReturn() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>(); var observations = new AtomicInteger();
      try (var form = new Form(exporting, task -> {
        task.run(); owner.get().accept(); assertEquals(0, owner.get().replacements.get() + owner.get().reviews.get()); observations.incrementAndGet();
      }, callback -> {
        callback.run(); owner.get().accept(); assertEquals(0, owner.get().replacements.get() + owner.get().reviews.get()); observations.incrementAndGet();
      })) {
        owner.set(form); assertTrue(form.start()); assertEquals(2, observations.get()); assertTrue(form.running()); form.unpublished();
        form.accept(); form.published();
      }
    }
  }

  @Test
  void inlineAcceptanceCannotHideAFollowingPosterFault() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(exporting, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); throw new AssertionError("poster failed");
      })) {
        owner.set(form); form.start(); form.accept(); assertFalse(form.running()); form.unpublished();
        assertEquals(1, form.failures()); assertEquals(0, form.temporary());
      }
    }
  }

  @Test
  void executorFailureTakesPrecedenceOverUnacceptedValueOrCapturedFailure() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) for (boolean failed : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(exporting, task -> { task.run(); throw new AssertionError("executor failed"); }, callback -> {
        callback.run(); owner.get().accept();
      })) {
        owner.set(form); if (failed) form.failure.set(new AssertionError("captured failure"));
        assertFalse(form.start()); form.accept(); form.unpublished(); assertEquals(1, form.failures());
        assertTrue(form.message().contains("executor failed")); assertFalse(form.message().contains("captured failure")); assertEquals(0, form.temporary());
      }
    }
  }

  @Test
  void capturedFailureWaitsForCompletePostingAndAdmissionBeforeFeedback() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(exporting, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); assertEquals(0, owner.get().failures());
      })) {
        owner.set(form); form.failure.set(new AssertionError("captured failure")); assertTrue(form.start()); assertTrue(form.running());
        assertEquals(0, form.failures()); form.accept(); form.unpublished(); assertEquals(1, form.failures()); assertEquals(0, form.temporary());
      }
    }
  }

  @Test
  void completedWorkerCannotBypassItsUndeliveredReceipt() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(exporting, tasks::add, ui::add)) {
        form.start(); tasks.remove().run(); form.accept(); assertTrue(form.running()); form.unpublished();
        ui.remove().run(); form.accept(); form.published();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsAreContainedWithoutPublication() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
      try (var form = new Form(exporting, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status fault"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read interrupted"); throw new AssertionError("get fault");
          }
        });
        form.accept(); assertFalse(form.running()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.unpublished(); assertEquals(1, form.failures()); assertEquals(0, form.temporary());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalReadCannotConsumeFailOrCancelItsReadyReplacement() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int outcome = mode;
      try (var form = new Form(exporting, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            form.cancel(); assertTrue(form.start()); tasks.remove().run();
            if (outcome == 1) throw new AssertionError("obsolete read");
            if (outcome == 2) throw new CancellationException("obsolete cancellation"); return null;
          }
        });
        form.accept(); assertTrue(form.running()); form.unpublished(); assertEquals(0, form.failures());
        form.accept(); form.published();
      }
    }
  }

  @Test
  void failureMessageGetterCannotOverwriteReadySuccessorOrFinalClosure() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) for (boolean close : new boolean[] {false, true}) {
      try (var form = new Form(exporting, Runnable::run, Runnable::run)) {
        form.failure.set(new AssertionError() {
          public String getMessage() {
            form.failure.set(null); if (close) form.closeWork(); else assertTrue(form.start()); return "obsolete failure";
          }
        });
        form.start(); form.accept(); form.unpublished(); assertEquals(0, form.failures()); assertFalse(form.message().contains("obsolete failure"));
        if (close) { assertFalse(form.running()); assertEquals(0, form.temporary()); }
        else { assertTrue(form.running()); form.accept(); form.published(); }
      }
    }
  }

  @Test
  void changedDisplayedPathRetiresReadyWorkWhilePostingIsStillBlocked() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(exporting, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var callback = receipt(ui); callback.run(); form.accept(form.path() + ".changed"); assertFalse(form.running());
          assertEquals(0, form.temporary()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); form.unpublished(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void closeIsVisibleBeforeFutureCancellationAttemptsANewStart() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var admitted = new AtomicBoolean();
      try (var form = new Form(exporting, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { admitted.set(form.start()); return super.cancel(interrupt); }
        });
        form.closeWork(); assertFalse(admitted.get()); assertTrue(tasks.isEmpty()); form.accept(); form.unpublished(); assertFalse(form.running());
      }
    }
  }

  @Test
  void oldReceiptCannotPopulateOrConsumeASuccessorWhileItsWorkerIsReturning() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(exporting, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var old = receipt(ui); old.run(); form.cancel(); assertTrue(form.start());
          var token = form.token(); old.run(); form.accept(); assertSame(token, form.token()); form.unpublished();
          release.countDown(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          old.run(); form.accept(); form.published(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void acceptedImportCompletionOnlyOpensReviewAndExplicitApplyCommitsOneUndoableTransaction() throws Exception {
    var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
    try (var form = new Form(false, tasks::add, ui::add)) {
      form.start(); tasks.remove().run(); ui.remove().run(); form.accept(); form.published();
      var edit = form.panel.session(); var preview = edit.preview(); assertFalse(edit.canApply());
      form.accept(); assertEquals(1, form.reviews.get()); assertSame(preview, edit.preview());
      edit.markShown(preview); assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
      assertEquals(2, form.state.project().noise2D.size()); assertEquals(form.domain.revision + 1, form.state.revision);
      form.state.document.undo(); assertEquals(form.domain.project, ProjectIO.JSON.valueToTree(form.state.project()));
      form.state.document.redo(); assertEquals(2, form.state.project().noise2D.size());
    }
  }

  @Test
  void aNewActiveProjectCheckpointDuringExportPostingCannotBecomeItsDestination() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor();
        var form = new Form(true, worker, callback -> { ui.add(callback); await(release); })) {
      try {
        form.start(); var callback = receipt(ui); callback.run(); form.state.document.save(form.target);
        var saved = Files.readAllBytes(form.target); var newer = new Domain(form.state); form.accept();
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); form.accept();
        assertFalse(form.running()); newer.retained(form.state); assertArrayEquals(saved, Files.readAllBytes(form.target));
        assertEquals(0, form.replacements.get()); assertEquals(0, form.temporary()); assertFalse(form.source.closed());
      } finally { release.countDown(); }
    }
  }

  @Test
  void exportFailureCannotRelabelAReadySuccessorCreatedByStagingCleanup() throws Exception {
    try (var form = new Form(true, Runnable::run, Runnable::run)) {
      form.start(); form.cleanupFailure.set(new AssertionError() {
        public String getMessage() { assertTrue(form.start()); return "cleanup notice"; }
      });
      replaceTask(form, new FutureTask<Void>(() -> null) {
        public boolean isDone() { return true; }
        public Void get() { throw new AssertionError("obsolete task failure"); }
      });
      form.accept(); assertTrue(form.running()); form.unpublished(); assertEquals(0, form.failures());
      assertFalse(form.message().contains("obsolete task failure")); form.accept(); form.published();
    }
  }

  @Test
  void cancelledOrStaleExportCannotOverwriteCleanupCreatedSuccessorProgress() throws Exception {
    for (boolean stale : new boolean[] {false, true}) try (var form = new Form(true, Runnable::run, Runnable::run)) {
      form.start(); form.cleanupFailure.set(new AssertionError() {
        public String getMessage() { assertTrue(form.start()); return "cleanup notice"; }
      });
      var before = form.token(); if (stale) form.accept(form.path() + ".changed"); else form.cancel();
      assertNotSame(before, form.token()); assertTrue(form.running()); form.unpublished(); assertEquals(0, form.failures());
      assertTrue(form.message().contains("background")); form.accept(); form.published();
    }
  }

  @Test
  void finalCloseRequestedDuringAtomicReplacementWaitsForConsumptionAndCannotReplay() throws Exception {
    try (var form = new Form(true, Runnable::run, Runnable::run)) {
      form.afterReplace = () -> { assertTrue(form.writer.publishing()); form.cancel(); form.closeWork(); };
      form.start(); form.accept(); assertEquals(ResourceExportWriter.Phase.CLOSED, form.writer.view().phase());
      assertTrue(form.source.closed()); assertEquals(1, form.replacements.get()); assertEquals(0, form.temporary()); form.domain.retained(form.state);
      assertEquals("field", form.files.read(form.target).noise2D.getFirst().id); assertFalse(form.start());
      form.accept(); assertEquals(1, form.replacements.get()); assertEquals(0, form.failures());
    }
  }

  @Test
  void aNewBlockerBeforeWorkerReturnPreventsAdoptionButAllowsExplicitRetry() throws Exception {
    for (boolean exporting : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(exporting, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var callback = receipt(ui); callback.run(); form.blocked = true; form.accept(); form.unpublished();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept();
          assertFalse(form.running()); form.unpublished(); assertEquals(0, form.temporary()); assertEquals(0, form.failures());
          form.blocked = false; assertTrue(form.start()); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); form.published();
        } finally { release.countDown(); }
      }
    }
  }
}
