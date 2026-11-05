package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.api.*;
import io.voxelcreator.model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred canonical encoding, current-control publication and reviewed-capture ownership. */
class BackgroundProfileExportTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;
    public void execute(Runnable task) { if (reject) throw new RejectedExecutionException("capture queue"); tasks.add(task); }
    void run() { tasks.removeIf(t -> t instanceof Future<?> f && f.isCancelled()); tasks.remove().run(); }
  }

  static Profiler.Snapshot snapshot(long value) {
    try (var profiler = new Profiler()) {
      var capture = profiler.start(Profiler.Options.defaults());
      profiler.scope(7, null, null).gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, value, "reviewed,雪");
      profiler.stop(); return capture.snapshot();
    }
  }

  static void canonical(Path path, Profiler.Snapshot snapshot, boolean csv) throws IOException {
    if (csv) ProfileIO.writeCSV(path, snapshot); else ProfileIO.writeJSON(path, snapshot);
  }

  Path target() throws IOException { var path = directory.resolve("capture.json"); Files.writeString(path, "previous"); return path; }

  void noTemporaries() throws IOException {
    try (var paths = Files.walk(directory)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".profile-"))); }
  }

  static void deliver(Workers workers, Queue<Runnable> ui) { workers.run(); ui.remove().run(); }

  static BackgroundProfileExport work(Workers workers, Queue<Runnable> ui, ProfileExportFiles files,
      Supplier<Path> active, Consumer<String> errors, Consumer<String> cleanup) {
    return new BackgroundProfileExport(workers, ui::add, files, active, errors, cleanup);
  }

  @Test
  void stagedJsonAndCsvRetainPriorBytesUntilOneAtomicPublicationAndMatchCanonicalOutput() throws Exception {
    for (boolean csv : new boolean[] {false, true}) {
      var snapshot = snapshot(123); var file = target(); var canonical = directory.resolve("canonical");
      canonical(canonical, snapshot, csv);
      try (var prepared = new ProfileExportFiles().prepare(file, snapshot, csv, null)) {
        assertEquals("previous", Files.readString(file)); prepared.publish(null);
        assertArrayEquals(Files.readAllBytes(canonical), Files.readAllBytes(file));
        assertThrows(IllegalStateException.class, () -> prepared.publish(null));
      }
      noTemporaries();
    }
  }

  @Test
  void realWorkerEncodesAndForcesWhileOnlyOwnerAcceptsTheReplacement() throws Exception {
    var writer = new AtomicReference<Thread>(); var caller = Thread.currentThread(); var file = target();
    var review = new ProfileExportSession(snapshot(123)); var ui = new LinkedBlockingQueue<Runnable>();
    var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
      public void write(Path temp, Profiler.Snapshot value, boolean csv) throws IOException {
        writer.set(Thread.currentThread()); canonical(temp, value, csv);
      }
      public void replace(Path temp, Path path) throws IOException {
        assertSame(caller, Thread.currentThread()); Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      }
    });
    try (var worker = Executors.newSingleThreadExecutor();
        var work = new BackgroundProfileExport(worker, ui::add, files, () -> null, text -> {}, text -> {})) {
      assertTrue(work.start(review, file.toString(), false, () -> true)); long version = work.version();
      var callback = ui.poll(10, TimeUnit.SECONDS); assertNotNull(callback); assertNotSame(caller, writer.get());
      callback.run(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      assertTrue(work.ready()); assertTrue(work.running()); assertEquals(version, work.version());
      assertEquals("previous", Files.readString(file)); assertFalse(review.available());
      assertEquals(file, work.accept(version)); assertFalse(review.available()); assertNull(work.accept(version));
      assertEquals(123, ProjectIO.JSON.readTree(file.toFile()).path("samples").get(0).path("value").asLong());
      noTemporaries();
    }
  }

  @Test
  void readySuccessOrPreparationFailureCanBeCancelledOrClosedWithoutPrimaryFeedback() throws Exception {
    for (boolean failure : new boolean[] {false, true}) for (boolean close : new boolean[] {false, true}) {
      var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
        public void write(Path path, Profiler.Snapshot value, boolean csv) throws IOException {
          canonical(path, value, csv); if (failure) throw new AssertionError("write");
        }
      });
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
      var review = new ProfileExportSession(snapshot(123)); var file = target();
      try (var work = work(workers, ui, files, () -> null, errors::add, errors::add)) {
        work.start(review, file.toString(), false, () -> true); long version = work.version(); deliver(workers, ui);
        assertTrue(work.ready()); assertEquals(version, work.version()); assertTrue(errors.isEmpty());
        if (close) work.close(); else work.cancel();
        assertNull(work.accept(version)); assertTrue(review.available()); assertTrue(errors.isEmpty());
        assertEquals("previous", Files.readString(file)); noTemporaries();
      }
    }
  }

  @Test
  void liveGrowthReplacementClearAndNewDocumentCannotRetargetFrozenExport() throws Exception {
    try (var state = BackgroundResourceSearchTest.state(1)) {
      var capture = state.engine.profiler().start(Profiler.Options.defaults());
      state.engine.profiler().scope(7, state.project().project.uuid, null)
          .gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 123, "opening");
      var frozen = capture.snapshot(); var review = new ProfileExportSession(frozen);
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      try (var work = work(workers, ui, new ProfileExportFiles(), state.document::path, text -> {}, text -> {})) {
        work.start(review, file.toString(), false, () -> true);
        state.engine.profiler().scope(7, null, null).gauge(Profiler.Kind.QUEUE_DEPTH, Profiler.Unit.COUNT, 999, "later");
        state.engine.profiler().start(Profiler.Options.defaults()); state.engine.profiler().clear(); state.newSession();
        deliver(workers, ui); assertEquals(file, work.accept(work.version()));
        var json = ProjectIO.JSON.readTree(file.toFile());
        assertEquals(frozen.captureId().toString(), json.path("captureId").asText());
        assertEquals(1, json.path("samples").size()); assertEquals(123, json.path("samples").get(0).path("value").asLong());
      }
    }
  }

  @Test
  void rawPathOrReviewGateChangeRetiresReadyWorkAndRequiresExplicitRetry() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var gate = new AtomicBoolean(true);
    var review = new ProfileExportSession(snapshot(123)); var file = target();
    try (var work = work(workers, ui, new ProfileExportFiles(), () -> null, text -> {}, text -> {})) {
      work.start(review, file.toString(), false, gate::get); deliver(workers, ui); gate.set(false);
      assertNull(work.accept(work.version())); assertFalse(work.running()); assertTrue(review.available());
      assertEquals("previous", Files.readString(file)); noTemporaries(); gate.set(true);
      work.start(review, file.toString(), true, gate::get); deliver(workers, ui);
      assertEquals(file, work.accept(work.version())); assertTrue(Files.readString(file).contains(",123,COUNT,"));
    }
  }

  @Test
  void latestActiveProjectPathIsProtectedAfterStagingInBothFormats() throws Exception {
    for (boolean csv : new boolean[] {false, true}) {
      var active = new AtomicReference<Path>(); var workers = new Workers(); var ui = new ArrayDeque<Runnable>();
      var errors = new ArrayList<String>(); var review = new ProfileExportSession(snapshot(123)); var file = target();
      try (var work = work(workers, ui, new ProfileExportFiles(), active::get, errors::add, errors::add)) {
        work.start(review, file.toString(), csv, () -> true); deliver(workers, ui);
        Files.writeString(file, "new active project"); active.set(file.resolveSibling("./capture.json"));
        assertNull(work.accept(work.version())); assertTrue(review.available()); assertEquals(1, errors.size());
        assertEquals("new active project", Files.readString(file)); noTemporaries();
      }
    }
  }

  @Test
  void oldShownVersionAndLateCallbacksCannotConsumeAReadySuccessor() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var review = new ProfileExportSession(snapshot(123));
    var file = target();
    try (var work = work(workers, ui, new ProfileExportFiles(), () -> null, text -> {}, text -> {})) {
      work.start(review, file.toString(), false, () -> true); long old = work.version(); workers.run(); var callback = ui.remove();
      work.cancel(); work.start(review, file.toString(), true, () -> true); deliver(workers, ui); callback.run();
      assertNull(work.accept(old)); assertTrue(work.ready()); assertEquals("previous", Files.readString(file));
      assertEquals(file, work.accept(work.version())); callback.run(); assertFalse(review.available()); noTemporaries();
    }
  }

  @Test
  void gateCanPrepareReadySuccessorAndReturnTrueFalseOrThrowWithoutOldPublication() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      int result = mode; var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      var first = new ProfileExportSession(snapshot(123)); var next = new ProfileExportSession(snapshot(999));
      var ref = new AtomicReference<BackgroundProfileExport>(); var fire = new AtomicBoolean(); var errors = new ArrayList<String>();
      try (var work = work(workers, ui, new ProfileExportFiles(), () -> null, errors::add, errors::add)) {
        ref.set(work); work.start(first, file.toString(), false, () -> {
          if (fire.getAndSet(false)) {
            ref.get().cancel(); ref.get().start(next, file.toString(), false, () -> true); deliver(workers, ui);
            if (result == 2) throw new IllegalStateException("retired guard"); return result == 0;
          }
          return true;
        });
        deliver(workers, ui); fire.set(true); assertNull(work.accept(work.version()));
        assertTrue(work.ready()); assertTrue(errors.isEmpty()); assertTrue(first.available());
        assertEquals(file, work.accept(work.version()));
        assertEquals(999, ProjectIO.JSON.readTree(file.toFile()).path("samples").get(0).path("value").asLong());
      }
    }
  }

  @Test
  void activePathCallbackCannotConsumeOrInvalidateItsReadySuccessor() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
    var first = new ProfileExportSession(snapshot(123)); var next = new ProfileExportSession(snapshot(999));
    var ref = new AtomicReference<BackgroundProfileExport>(); var fire = new AtomicBoolean();
    try (var work = work(workers, ui, new ProfileExportFiles(), () -> {
      if (fire.getAndSet(false)) { ref.get().cancel(); ref.get().start(next, file.toString(), false, () -> true); deliver(workers, ui); }
      return null;
    }, text -> {}, text -> {})) {
      ref.set(work); work.start(first, file.toString(), false, () -> true); deliver(workers, ui); fire.set(true);
      assertNull(work.accept(work.version())); assertTrue(work.ready()); assertEquals("previous", Files.readString(file));
      assertEquals(file, work.accept(work.version())); assertTrue(first.available());
    }
  }

  @Test
  void queueAndInlineHandoffOrExecutorFailureRetainReviewAndPermitExplicitRetry() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      int failureMode = mode; var broken = new AtomicBoolean(true); var file = target(); var errors = new ArrayList<String>();
      var review = new ProfileExportSession(snapshot(123));
      Executor worker = task -> {
        if (broken.get() && failureMode == 0) throw new RejectedExecutionException("queue");
        task.run(); if (broken.get() && failureMode == 2) throw new AssertionError("execute after delivery");
      };
      try (var work = new BackgroundProfileExport(worker, callback -> {
        callback.run(); if (broken.get() && failureMode == 1) throw new AssertionError("handoff after delivery");
      }, new ProfileExportFiles(), () -> null, errors::add, errors::add)) {
        work.start(review, file.toString(), false, () -> true); assertNull(work.accept(work.version()));
        assertEquals(1, errors.size()); assertTrue(review.available()); assertEquals("previous", Files.readString(file)); noTemporaries();
        broken.set(false); assertTrue(work.start(review, file.toString(), false, () -> true));
        assertEquals(file, work.accept(work.version())); assertFalse(review.available());
      }
    }
  }

  @Test
  void preparationAndReplacementErrorsReportOnceAndRetrySameFrozenReview() throws Exception {
    for (boolean writing : new boolean[] {false, true}) {
      var broken = new AtomicBoolean(true); var errors = new ArrayList<String>(); var file = target();
      var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
        public void write(Path path, Profiler.Snapshot value, boolean csv) throws IOException {
          canonical(path, value, csv); if (writing && broken.get()) throw new AssertionError("write");
        }
        public void replace(Path temp, Path target) throws IOException {
          if (!writing && broken.get()) throw new AtomicMoveNotSupportedException(temp.toString(), target.toString(), "provider");
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      });
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var review = new ProfileExportSession(snapshot(123));
      try (var work = work(workers, ui, files, () -> null, errors::add, errors::add)) {
        work.start(review, file.toString(), false, () -> true); deliver(workers, ui); assertTrue(errors.isEmpty());
        assertNull(work.accept(work.version())); assertEquals(1, errors.size()); assertTrue(review.available());
        assertEquals("previous", Files.readString(file)); noTemporaries(); broken.set(false);
        work.start(review, file.toString(), false, () -> true); deliver(workers, ui); assertEquals(file, work.accept(work.version()));
      }
    }
  }

  @Test
  void reservationCloseIsExactAndSynchronousWritesCannotReenterAnAsyncAttempt() throws Exception {
    var review = new ProfileExportSession(snapshot(123)); var file = target();
    var old = review.reserve(); assertFalse(review.available());
    assertThrows(IOException.class, () -> review.write(file.toString(), false, null));
    assertThrows(IllegalStateException.class, review::cancel);
    old.close(); assertTrue(review.available()); var next = review.reserve(); old.close(); assertFalse(review.available());
    try (var prepared = new ProfileExportFiles().prepare(file, review.snapshot(), false, null)) {
      assertThrows(IOException.class, () -> old.publish(prepared, null)); next.publish(prepared, null);
    }
    assertFalse(review.available()); next.close(); assertFalse(review.available()); noTemporaries();
  }

  @Test
  void cleanupReporterCanCreateReadySuccessorWithoutCancelClobberingIt() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
    var first = new ProfileExportSession(snapshot(123)); var next = new ProfileExportSession(snapshot(999));
    var ref = new AtomicReference<BackgroundProfileExport>(); var fire = new AtomicBoolean(true);
    var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
      public void delete(Path path) throws IOException { Files.deleteIfExists(path); throw new IOException("cleanup"); }
    });
    try (var work = work(workers, ui, files, () -> null, text -> {}, text -> {
      if (fire.getAndSet(false)) { ref.get().start(next, file.toString(), false, () -> true); deliver(workers, ui); }
    })) {
      ref.set(work); work.start(first, file.toString(), false, () -> true); deliver(workers, ui); work.cancel();
      assertTrue(work.ready()); assertTrue(work.message().startsWith("Preparing"));
      assertEquals(file, work.accept(work.version())); assertTrue(first.available()); noTemporaries();
    }
  }

  @Test
  void finalCloseRejectsCleanupReporterStartingANewWorker() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target(); var reports = new AtomicInteger();
    var review = new ProfileExportSession(snapshot(123)); var ref = new AtomicReference<BackgroundProfileExport>();
    var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
      public void delete(Path path) throws IOException { Files.deleteIfExists(path); throw new IOException("cleanup"); }
    });
    try (var work = work(workers, ui, files, () -> null, text -> {}, text -> {
      reports.incrementAndGet(); assertFalse(ref.get().start(review, file.toString(), false, () -> true));
    })) {
      ref.set(work); work.start(review, file.toString(), false, () -> true); deliver(workers, ui); work.close();
      assertEquals(1, reports.get()); assertTrue(workers.tasks.isEmpty()); assertTrue(review.available()); noTemporaries();
    }
  }

  @Test
  void lateNoncooperativeWorkerCleansItsUnpublishedFileWithoutQueuingCallback() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var file = target();
    var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
      public void write(Path path, Profiler.Snapshot value, boolean csv) throws IOException {
        canonical(path, value, csv); entered.countDown(); boolean interrupted = false;
        try { while (true) try {
          if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); break;
        } catch (InterruptedException cancelled) { interrupted = true; } }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
      }
    });
    var review = new ProfileExportSession(snapshot(123)); var ui = new LinkedBlockingQueue<Runnable>();
    try (var worker = Executors.newSingleThreadExecutor();
        var work = new BackgroundProfileExport(worker, ui::add, files, () -> null, text -> {}, text -> {})) {
      work.start(review, file.toString(), false, () -> true); assertTrue(entered.await(10, TimeUnit.SECONDS));
      work.close(); release.countDown(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
      assertTrue(ui.isEmpty()); assertTrue(review.available()); assertEquals("previous", Files.readString(file)); noTemporaries();
    } finally { release.countDown(); }
  }

  @Test
  void publicationConsumesBeforeDeferredCloseAndDoesNotUnlinkReusedTemporaryName() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
    var ref = new AtomicReference<BackgroundProfileExport>(); var reused = new AtomicReference<Path>();
    var files = new ProfileExportFiles(new ProfileExportFiles.Storage() {
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(temp, "new owner"); reused.set(temp); ref.get().close();
      }
    });
    var review = new ProfileExportSession(snapshot(123));
    try (var work = work(workers, ui, files, () -> null, text -> {}, text -> {})) {
      ref.set(work); work.start(review, file.toString(), false, () -> true); deliver(workers, ui);
      assertEquals(file, work.accept(work.version())); assertFalse(review.available());
      assertEquals("new owner", Files.readString(reused.get())); Files.delete(reused.get()); noTemporaries();
    }
  }

  @Test
  void invalidPathsDoNotScheduleAndPreserveReviewedInputForExplicitCorrection() throws Exception {
    var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var review = new ProfileExportSession(snapshot(123));
    var errors = new ArrayList<String>();
    try (var work = work(workers, ui, new ProfileExportFiles(), () -> null, errors::add, errors::add)) {
      for (String path : new String[] {"", " ", "bad\u0000path"}) {
        assertFalse(work.start(review, path, false, () -> true)); assertTrue(review.available());
      }
      assertTrue(workers.tasks.isEmpty()); assertEquals(3, errors.size()); noTemporaries();
    }
  }

  @Test
  void captureExportDoesNotModifyProjectHistoryPrivateSeedOrPreferences() throws Exception {
    try (var state = BackgroundResourceSearchTest.state(1)) {
      state.select("noise2D", state.project().noise2D.getFirst().uuid); var draft = (ProjectData.Noise) state.draft; draft.id = "private";
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      var project = ProjectIO.JSON.valueToTree(state.project()); var user = ProjectIO.JSON.valueToTree(state.user);
      long revision = state.revision, history = state.document.historyBytes();
      var workers = new Workers(); var ui = new ArrayDeque<Runnable>(); var file = target();
      try (var work = work(workers, ui, new ProfileExportFiles(), state.document::path, text -> {}, text -> {})) {
        work.start(new ProfileExportSession(snapshot(123)), file.toString(), false, () -> true); deliver(workers, ui);
        assertEquals(file, work.accept(work.version())); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
        assertEquals(user, ProjectIO.JSON.valueToTree(state.user)); assertEquals(revision, state.revision);
        assertEquals(history, state.document.historyBytes()); assertSame(draft, state.draft); assertEquals("private", draft.id);
        assertEquals("-", state.seedInputs.text("seedOffset"));
      }
    }
  }
}
