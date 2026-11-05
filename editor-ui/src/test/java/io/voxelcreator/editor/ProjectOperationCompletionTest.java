package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred complete-worker/admission gates for settings, transition Apply, Open and Save. */
class ProjectOperationCompletionTest {
  @TempDir Path directory;
  private static final long TIMEOUT = 10;
  private enum Kind { SETTINGS, DRAFT, OPEN, SAVE }

  private static EditorState state() {
    var state = ReadyDirectApplyTest.state();
    ((Noise) state.draft).algorithm.parameters.put("value", .625);
    return state;
  }

  private final class Form implements AutoCloseable {
    final EditorState state;
    final Kind kind;
    final ProjectSettingsSession settings;
    final ProjectFileWorkflow files;
    final Path root, target;
    final byte[] original;
    final AtomicInteger replaced = new AtomicInteger(), exits = new AtomicInteger();
    final Object draft;

    Form(EditorState state, Kind kind, Executor worker, Consumer<Runnable> ui) throws Exception {
      this.state = state; this.kind = kind; draft = state.draft;
      root = Files.createTempDirectory(directory, "operation-"); target = root.resolve("project.json");
      if (kind == Kind.OPEN) {
        var candidate = new ProjectData(); candidate.project.seed = 99;
        candidate.noise2D.add(BackgroundImportApplyTest.noise("incoming", .75));
        new ProjectIO().save(target, candidate);
      } else Files.writeString(target, "prior");
      original = Files.readAllBytes(target);
      settings = kind == Kind.SETTINGS ? new ProjectSettingsSession(state, worker, ui, () -> false) : null;
      files = settings == null ? new ProjectFileWorkflow(state, replaced::incrementAndGet, exits::incrementAndGet, () -> false, worker, ui) : null;
      if (settings != null) {
        settings.edit("seed", "+000017"); settings.edit("minY", "-1"); settings.edit("maxY", "+1");
      }
    }

    Map<String, String> shown() {
      return Map.of("seed", settings.text("seed"), "minY", settings.text("minY"), "maxY", settings.text("maxY"));
    }
    void begin() {
      switch (kind) {
        case SETTINGS -> settings.apply(state, shown());
        case DRAFT -> { files.request(Intent.EXIT); files.decideDraft(DraftDecision.APPLY); }
        case OPEN -> ReadyProjectOpenTest.start(files, target.toString());
        case SAVE -> ReadyProjectSaveTest.save(files, target.toString());
      }
    }
    void retry() {
      switch (kind) {
        case SETTINGS -> settings.apply(state, shown());
        case DRAFT -> files.decideDraft(DraftDecision.APPLY);
        case OPEN, SAVE -> files.submit(target.toString());
      }
    }
    void accept() {
      switch (kind) {
        case SETTINGS -> settings.acceptPrepared(shown());
        case DRAFT -> files.acceptDraft();
        case OPEN -> files.acceptOpen();
        case SAVE -> files.acceptSave();
      }
    }
    boolean running() {
      if (settings != null) return settings.applying();
      return files.phase() == switch (kind) {
        case DRAFT -> Phase.APPLYING; case OPEN -> Phase.LOADING; case SAVE -> Phase.SAVING;
        default -> throw new AssertionError(kind);
      };
    }
    long version() { return settings != null ? settings.workVersion() : files.openVersion(); }
    void cancelWork() { if (settings != null) settings.suspendApply(); else files.cancel(); }
    String feedback() { return settings != null ? settings.applyFailure() + settings.progress() : files.error(); }
    long failures() {
      String code = switch (kind) {
        case SETTINGS -> "PROJECT_SETTINGS_APPLY_FAILED"; case DRAFT -> "PROJECT_DRAFT_APPLY_FAILED";
        case OPEN -> "PROJECT_OPEN_FAILED"; case SAVE -> "PROJECT_SAVE_FAILED";
      };
      return state.events.snapshot().stream().filter(e -> e.code().equals(code)).mapToLong(EditorEvents.Entry::count).sum();
    }
    long staging() throws Exception {
      try (var paths = Files.walk(root)) {
        return paths.filter(p -> p.getFileName().toString().startsWith(".voxel-save-")).count();
      }
    }
    void priorBytes() throws Exception { assertArrayEquals(original, Files.readAllBytes(target)); }
    void noContinuation() { assertEquals(0, replaced.get()); assertEquals(0, exits.get()); }
    boolean requestAfterClose() {
      if (settings == null) {
        long before = files.requestVersion(); files.request(Intent.EXIT); return files.requestVersion() != before;
      }
      long before = settings.workVersion();
      try { settings.apply(state, shown()); } catch (IllegalStateException closed) { return false; }
      return settings.workVersion() != before;
    }
    Object taskOwner() { return settings != null ? settings : files; }
    String taskField() {
      return switch (kind) { case SETTINGS -> "task"; case DRAFT -> "draftTask"; case OPEN -> "openTask"; case SAVE -> "saveTask"; };
    }
    void published() throws Exception {
      assertFalse(running()); assertEquals(0, staging());
      switch (kind) {
        case SETTINGS -> {
          assertTrue(settings.closed()); assertEquals(17, state.project().project.seed);
          assertEquals(-1, state.project().project.minY); assertEquals(1, state.project().project.maxY);
          assertSame(draft, state.draft); noContinuation(); priorBytes();
        }
        case DRAFT -> {
          assertEquals(Phase.GUARD, files.phase()); assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
          noContinuation(); priorBytes();
        }
        case OPEN -> {
          assertEquals(Phase.IDLE, files.phase()); assertEquals(99, state.project().project.seed);
          assertNull(state.draft); assertNull(state.selected); assertEquals(1, replaced.get()); assertEquals(0, exits.get());
          assertEquals(target, state.document.path()); assertFalse(state.document.dirty()); priorBytes();
        }
        case SAVE -> {
          assertEquals(Phase.IDLE, files.phase()); assertEquals(target, state.document.path()); assertFalse(state.document.dirty());
          assertSame(draft, state.draft); noContinuation();
          var saved = new ProjectIO().load(target).project();
          assertEquals(ProjectIO.JSON.valueToTree(state.project()), ProjectIO.JSON.valueToTree(saved));
          assertEquals(.125, saved.noise2D.getFirst().algorithm.parameters.get("value"));
        }
      }
    }
    public void close() { if (settings != null) settings.cancel(); else files.close(); }
  }

  private static final class Source {
    final ProjectFileOwner owner;
    final JsonNode project, user;
    final List<Diagnostic> issues;
    final String scope;
    final long history, revision;
    final boolean undo, redo;
    Source(EditorState state) {
      owner = new ProjectFileOwner(state); project = ProjectIO.JSON.valueToTree(state.project());
      user = ProjectIO.JSON.valueToTree(state.user); issues = state.issues; scope = state.validationScope;
      history = state.document.historyBytes(); revision = state.revision;
      undo = state.document.canUndo(); redo = state.document.canRedo();
    }
    void retained(EditorState state) {
      assertTrue(owner.valuesCurrent(state)); assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(user, ProjectIO.JSON.valueToTree(state.user)); assertSame(issues, state.issues); assertEquals(scope, state.validationScope);
      assertEquals(history, state.document.historyBytes()); assertEquals(revision, state.revision);
      assertEquals(undo, state.document.canUndo()); assertEquals(redo, state.document.canRedo());
    }
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
    var owner = form.taskOwner(); Field field = owner.getClass().getDeclaredField(form.taskField()); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false); field.set(owner, replacement);
  }

  @Test
  void aRealReceiptCannotPublishUntilTheWorkersEntireHandoffReturnsNormally() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Source(state); form.begin(); long version = form.version(); var callback = receipt(ui); callback.run();
          form.accept(); assertTrue(form.running()); assertEquals(version, form.version()); before.retained(state); form.priorBytes();
          assertEquals(kind == Kind.SAVE ? 1 : 0, form.staging()); form.noContinuation();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.accept(); callback.run(); form.accept(); form.published();
          if (kind == Kind.SETTINGS || kind == Kind.DRAFT) {
            assertEquals(before.revision + 1, state.revision); state.document.undo();
            assertEquals(before.project, ProjectIO.JSON.valueToTree(state.project())); state.document.redo();
            assertEquals(kind == Kind.SETTINGS ? 17 : 5, state.project().project.seed);
          }
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void aLatePostingFailurePreservesTheSourceAndFileThenAllowsOnlyExplicitRetry() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, kind, worker, callback -> {
            ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
          })) {
        try {
          var before = new Source(state); form.begin(); var old = receipt(ui); old.run(); form.accept(); before.retained(state);
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); assertFalse(form.running());
          before.retained(state); form.priorBytes(); assertEquals(0, form.staging()); form.noContinuation();
          assertEquals(1, form.failures()); assertTrue(form.feedback().contains("late post"));
          broken.set(false); form.retry(); old.run(); form.accept(); receipt(ui).run();
          worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); form.published(); assertEquals(1, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void cancellationDuringHeldHandoffRetiresSuccessRejectionAndFatalPayloads() throws Exception {
    for (var kind : Kind.values()) for (int mode = 0; mode < 3; mode++) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); final int result = mode;
      try (var state = state(); var worker = Executors.newSingleThreadExecutor()) {
        state.document.setValidator(p -> {
          if (result == 2) throw new AssertionError("captured failure");
          return result == 1 ? List.of(Diagnostic.error("REJECT", "/", "rejected")) : List.of();
        });
        try (var form = new Form(state, kind, worker, callback -> {
          ui.add(callback); await(release); throw new AssertionError("retired post");
        })) {
          try {
            var before = new Source(state); form.begin(); var callback = receipt(ui); callback.run(); form.accept(); form.cancelWork();
            assertEquals(0, form.staging()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
            callback.run(); form.accept(); assertFalse(form.running()); before.retained(state); form.priorBytes();
            assertEquals(0, form.failures()); form.noContinuation();
          } finally { release.countDown(); }
        }
      }
    }
  }

  @Test
  void finalClosureDuringRealHandoffRejectsLateDeliveryAndNewAdmission() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Source(state); form.begin(); var callback = receipt(ui); callback.run(); form.close();
          assertEquals(0, form.staging()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); assertFalse(form.requestAfterClose()); before.retained(state); form.priorBytes();
          assertEquals(0, form.failures()); form.noContinuation();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForPostingAndExecutorAdmissionToReturnSuccessfully() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var owner = new AtomicReference<Form>(); var before = new Source(state); var observations = new AtomicInteger();
      try (var form = new Form(state, kind, task -> {
        task.run(); owner.get().accept(); before.retained(state); observations.incrementAndGet();
      }, callback -> {
        callback.run(); owner.get().accept(); before.retained(state); observations.incrementAndGet();
      })) {
        owner.set(form); form.begin(); assertEquals(2, observations.get()); assertTrue(form.running());
        before.retained(state); form.priorBytes(); form.noContinuation(); form.accept(); form.published();
      }
    }
  }

  @Test
  void inlineAcceptanceBeforeALaterPosterFaultCannotPublishOrReplace() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var owner = new AtomicReference<Form>(); var before = new Source(state);
      try (var form = new Form(state, kind, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); before.retained(state); throw new AssertionError("poster failed");
      })) {
        owner.set(form); form.begin(); form.accept(); assertFalse(form.running()); before.retained(state); form.priorBytes();
        assertEquals(0, form.staging()); assertEquals(1, form.failures()); form.noContinuation();
      }
    }
  }

  @Test
  void failedExecutorAdmissionWinsOverUnacceptedSuccessAndCapturedFailure() throws Exception {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) try (var state = state()) {
      if (failed) state.document.setValidator(p -> { throw new AssertionError("captured failure"); });
      var owner = new AtomicReference<Form>(); var before = new Source(state);
      try (var form = new Form(state, kind, task -> { task.run(); throw new AssertionError("executor failed"); }, callback -> {
        callback.run(); owner.get().accept(); before.retained(state);
      })) {
        owner.set(form); form.begin(); form.accept(); before.retained(state); form.priorBytes(); assertFalse(form.running());
        assertEquals(0, form.staging()); assertEquals(1, form.failures()); form.noContinuation();
        assertTrue(form.feedback().contains("executor failed")); assertFalse(form.feedback().contains("captured failure"));
      }
    }
  }

  @Test
  void capturedFatalFeedbackWaitsForTheCompleteGateBeforeReporting() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      state.document.setValidator(p -> { throw new AssertionError("captured failure"); });
      var owner = new AtomicReference<Form>(); var before = new Source(state);
      try (var form = new Form(state, kind, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); assertEquals(0, owner.get().failures()); before.retained(state);
      })) {
        owner.set(form); form.begin(); assertTrue(form.running()); assertEquals(0, form.failures());
        form.accept(); before.retained(state); form.priorBytes(); assertFalse(form.running());
        assertEquals(1, form.failures()); assertEquals(0, form.staging()); form.noContinuation();
      }
    }
  }

  @Test
  void completedWorkersCannotBypassAnUndeliveredReadinessReceipt() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(state, kind, tasks::add, ui::add)) {
        var before = new Source(state); form.begin(); tasks.remove().run(); form.accept();
        assertTrue(form.running()); before.retained(state); form.priorBytes();
        ui.remove().run(); form.accept(); form.published();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadFaultsCannotEscapeOrPublish() throws Exception {
    for (var kind : Kind.values()) for (int mode = 0; mode < 3; mode++) try (var state = state()) {
      var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
      try (var form = new Form(state, kind, tasks::add, Runnable::run)) {
        var before = new Source(state); form.begin(); tasks.remove();
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status failed"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("terminal read interrupted"); throw new AssertionError("get failed");
          }
        });
        form.accept(); assertFalse(form.running()); before.retained(state);
        assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        // Observe the owner's preserved flag before unrelated fixture file I/O.
        Thread.interrupted(); form.priorBytes();
        assertEquals(1, form.failures()); assertEquals(0, form.staging()); form.noContinuation();
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalObservationCannotConsumeOrFailTheReadySuccessorItCreates() throws Exception {
    for (var kind : Kind.values()) for (boolean failedRead : new boolean[] {false, true}) try (var state = state()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(state, kind, tasks::add, Runnable::run)) {
        var before = new Source(state); form.begin(); tasks.remove();
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            form.cancelWork(); form.begin(); tasks.remove().run();
            if (failedRead) throw new AssertionError("obsolete terminal read"); return null;
          }
        });
        form.accept(); assertTrue(form.running()); before.retained(state); form.priorBytes();
        assertEquals(0, form.failures()); form.noContinuation(); form.accept(); form.published();
      }
    }
  }

  @Test
  void failureMessageGetterCannotOverwriteOrReportAgainstAReadySuccessor() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var owner = new AtomicReference<Form>(); var first = new AtomicBoolean(true);
      var failure = new AssertionError() {
        public String getMessage() { owner.get().cancelWork(); owner.get().begin(); return "obsolete failure"; }
      };
      state.document.setValidator(p -> { if (first.getAndSet(false)) throw failure; return List.of(); });
      try (var form = new Form(state, kind, Runnable::run, Runnable::run)) {
        var before = new Source(state); owner.set(form); form.begin(); form.accept();
        assertTrue(form.running()); before.retained(state); form.priorBytes(); assertEquals(0, form.failures());
        assertFalse(form.feedback().contains("obsolete failure")); form.accept(); form.published();
      }
    }
  }

  @Test
  void fullyReadyCapturedFailuresRemainCancellableBeforeTheirFeedbackIsAccepted() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      state.document.setValidator(p -> { throw new AssertionError("captured failure"); });
      try (var form = new Form(state, kind, Runnable::run, Runnable::run)) {
        var before = new Source(state); form.begin(); assertTrue(form.running()); form.cancelWork(); form.accept();
        before.retained(state); form.priorBytes(); assertEquals(0, form.failures()); assertEquals(0, form.staging()); form.noContinuation();
      }
    }
  }

  @Test
  void saveHandoffFailureCannotContinueNewOpenOrExitOrAdvanceTheCheckpoint() throws Exception {
    for (var intent : Intent.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, Kind.SAVE, worker, callback -> {
            ui.add(callback); await(release); throw new AssertionError("transition post failed");
          })) {
        try {
          var before = new Source(state); ReadyProjectSaveTest.transition(form.files, intent, form.target.toString());
          receipt(ui).run(); form.accept(); assertTrue(form.running()); before.retained(state); form.priorBytes(); form.noContinuation();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept();
          assertEquals(Phase.SAVE, form.files.phase()); before.retained(state); form.priorBytes();
          assertEquals(0, form.staging()); assertEquals(1, form.failures()); form.noContinuation();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void successfulSaveContinuesEachIntentOnlyAfterCompleteHandoff() throws Exception {
    for (var intent : Intent.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, Kind.SAVE, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Source(state); ReadyProjectSaveTest.transition(form.files, intent, form.target.toString());
          var callback = receipt(ui); callback.run(); form.accept(); before.retained(state); form.priorBytes(); form.noContinuation();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); callback.run(); form.accept();
          assertEquals(before.project, ProjectIO.JSON.valueToTree(new ProjectIO().load(form.target).project()));
          assertEquals(intent == Intent.NEW ? 1 : 0, form.replaced.get()); assertEquals(intent == Intent.EXIT ? 1 : 0, form.exits.get());
          assertEquals(intent == Intent.OPEN ? Phase.OPEN : Phase.IDLE, form.files.phase()); assertEquals(0, form.staging());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void displayedSettingsComparisonCannotConsumeTheReadySuccessorItCreates() throws Exception {
    for (boolean failedMap : new boolean[] {false, true}) try (var state = state();
        var form = new Form(state, Kind.SETTINGS, Runnable::run, Runnable::run)) {
      var before = new Source(state); form.begin(); var shown = form.shown(); var once = new AtomicBoolean(true);
      var reentrant = new AbstractMap<String, String>() {
        public Set<Map.Entry<String, String>> entrySet() { return shown.entrySet(); }
        public String get(Object key) {
          if (once.getAndSet(false)) {
            form.cancelWork(); form.begin(); if (failedMap) throw new AssertionError("obsolete displayed map");
          }
          return shown.get(key);
        }
      };
      assertFalse(form.settings.acceptPrepared(reentrant)); assertTrue(form.running()); before.retained(state);
      assertEquals(0, form.failures()); form.accept(); form.published();
    }
  }

  @Test
  void settingsStillPreserveIndependentPropertiesRawInputEditedDuringHandoff() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
    try (var state = state(); var worker = Executors.newSingleThreadExecutor();
        var form = new Form(state, Kind.SETTINGS, worker, callback -> { ui.add(callback); await(release); })) {
      try {
        form.begin(); receipt(ui).run(); state.seedInputs.edit(state, "seedOffset", "Seed", "-");
        long inputs = state.seedInputs.version(state); var privateValues = ProjectIO.JSON.valueToTree(state.draft);
        form.accept(); assertTrue(form.running()); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
        form.accept(); form.published(); assertEquals(inputs, state.seedInputs.version(state));
        assertEquals("-", state.seedInputs.text("seedOffset")); assertEquals(privateValues, ProjectIO.JSON.valueToTree(state.draft));
      } finally { release.countDown(); }
    }
  }

  @Test
  void closedOwnersAreVisibleBeforeFutureCancellationAttemptsAnotherAdmission() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var tasks = new ArrayDeque<Runnable>(); var admitted = new AtomicBoolean();
      try (var form = new Form(state, kind, tasks::add, Runnable::run)) {
        var before = new Source(state); form.begin(); tasks.remove();
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { admitted.set(form.requestAfterClose()); return super.cancel(interrupt); }
        });
        form.close(); assertFalse(admitted.get()); assertTrue(tasks.isEmpty()); before.retained(state); form.priorBytes();
        assertFalse(form.running()); assertEquals(0, form.failures()); form.noContinuation();
      }
    }
  }

  @Test
  void failureMessageGetterClosingTheOwnerCannotPublishRetiredFeedback() throws Exception {
    for (var kind : Kind.values()) try (var state = state()) {
      var owner = new AtomicReference<Form>();
      var failure = new AssertionError() {
        public String getMessage() { owner.get().close(); return "closed failure"; }
      };
      state.document.setValidator(p -> { throw failure; });
      try (var form = new Form(state, kind, Runnable::run, Runnable::run)) {
        var before = new Source(state); owner.set(form); form.begin(); form.accept();
        assertFalse(form.running()); before.retained(state); form.priorBytes(); assertEquals(0, form.staging());
        assertEquals(0, form.failures()); assertFalse(form.feedback().contains("closed failure")); form.noContinuation();
      }
    }
  }

  @Test
  void mismatchedDisplayedFieldsOrPathsStillRetireWorkWhilePostingIsUnfinished() throws Exception {
    for (var kind : new Kind[] {Kind.SETTINGS, Kind.OPEN, Kind.SAVE}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Source(state); form.begin(); var callback = receipt(ui); callback.run();
          if (kind == Kind.SETTINGS) assertFalse(form.settings.acceptPrepared(Map.of("seed", "18", "minY", "-1", "maxY", "+1")));
          else if (kind == Kind.OPEN) form.files.acceptOpen(form.target + ".changed", () -> true);
          else form.files.acceptSave(form.target + ".changed", () -> true);
          assertFalse(form.running()); before.retained(state); form.priorBytes(); assertEquals(0, form.staging());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); form.accept();
          assertFalse(form.running()); before.retained(state); form.priorBytes(); form.noContinuation();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void newerCommittedValuesDuringHandoffCannotBeOverwrittenWhenItReturns() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.begin(); var callback = receipt(ui); callback.run(); state.project().project.seed++;
          var newer = new Source(state); form.accept(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); assertFalse(form.running()); newer.retained(state); form.priorBytes();
          assertEquals(0, form.staging()); assertEquals(0, form.failures()); form.noContinuation();
        } finally { release.countDown(); }
      }
    }
  }
}
