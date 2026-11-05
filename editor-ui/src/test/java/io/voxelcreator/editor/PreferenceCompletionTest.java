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

/** Deferred full handoff/admission and active-save successor ownership; no update transport. */
class PreferenceCompletionTest {
  @TempDir Path directory;
  private static final long TIMEOUT = 10;
  private enum Kind { PERFORMANCE, SCALES, TRUST, THEME, SHORTCUTS, PROFILE, ACTIVE }

  private final class Form implements AutoCloseable {
    final Kind kind;
    final Path root, target;
    final AtomicReference<Error> writeFault = new AtomicReference<>();
    final AtomicReference<Error> deleteFault = new AtomicReference<>();
    final AtomicInteger replacements = new AtomicInteger();
    final List<String> reports = new ArrayList<>();
    final UserConfig user;
    final EditorState state;
    final BackgroundPreferenceApplyTest.Form privateForm;
    final BackgroundAppearanceInputTest.Form appearance;
    final MetadataEditSession edit;
    final PreferenceApplySession work;
    final MetadataApplySession profile;
    final JsonNode beforeUser, beforeProfile;
    final AppMetadata activeProfile;
    final Object appearanceOwner, performanceOwner, inputOwner, workspaceOwner;
    final Domain domain;
    boolean mismatch, closed;

    Form(Kind kind, Executor executor, Consumer<Runnable> ui) throws Exception {
      this.kind = kind; root = Files.createTempDirectory(directory, "completion-"); target = root.resolve("config.json");
      Files.writeString(target, "previous"); var caller = Thread.currentThread();
      var storage = new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException {
          Files.write(temp, bytes); var failure = writeFault.get(); if (failure != null) throw failure;
        }
        public void replace(Path temp, Path target) throws IOException {
          assertSame(caller, Thread.currentThread()); replacements.incrementAndGet();
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        public void delete(Path temp) throws IOException {
          Files.deleteIfExists(temp); var failure = deleteFault.getAndSet(null); if (failure != null) throw failure;
        }
      });
      user = new UserConfig(storage); user.workspace.docking = "active layout";
      state = new EditorState(user, false); state.metadata = AppMetadata.defaults();
      assertTrue(state.change(p -> { p.project.seed = 41; p.noise2D.add(BackgroundImportApplyTest.noise("source", .125)); }));
      state.select("noise2D", state.project().noise2D.getFirst().uuid);
      ((ProjectData.Noise) state.draft).algorithm.parameters.put("value", .625);
      state.seedInputs.edit(state, "seedOffset", "Seed", "-");
      privateForm = kind.ordinal() <= Kind.TRUST.ordinal() ? new BackgroundPreferenceApplyTest.Form(user, kind.ordinal()) : null;
      appearance = kind == Kind.THEME || kind == Kind.SHORTCUTS ? new BackgroundAppearanceInputTest.Form(user, kind == Kind.THEME) : null;
      edit = kind == Kind.PROFILE ? new MetadataEditSession(state.metadata) : null;
      if (edit != null) edit.draft().controls.get("noise").label = "Private";
      work = edit == null ? new PreferenceApplySession(user, target, executor, ui, reports::add, kind == Kind.ACTIVE) : null;
      profile = edit != null ? new MetadataApplySession(state, executor, ui,
          () -> mismatch ? Map.of("missing", List.of("changed")) : BackgroundMetadataApplyTest.raw(edit), storage) : null;
      beforeUser = ProjectIO.JSON.valueToTree(user); beforeProfile = ProjectIO.JSON.valueToTree(state.metadata);
      activeProfile = state.metadata; appearanceOwner = user.appearance; performanceOwner = user.performance;
      inputOwner = user.input; workspaceOwner = user.workspace; domain = new Domain(state);
    }
    void start() {
      if (profile != null) profile.start(edit, target);
      else if (privateForm != null) {
        var guard = privateForm.guard(); work.start(privateForm.request(), () -> !mismatch && guard.getAsBoolean());
      } else if (appearance != null) {
        var guard = appearance.guard(); work.start(appearance.request(), () -> !mismatch && guard.getAsBoolean());
      } else work.start(user.requestSave(), () -> !mismatch);
    }
    boolean accept() { return profile != null ? profile.accept() : work.accept(); }
    boolean accept(long version) { return profile != null ? profile.accept(version) : work.accept(version); }
    boolean running() { return profile != null ? profile.running() : work.running(); }
    boolean ready() { return profile != null ? profile.ready() : work.ready(); }
    long version() { return profile != null ? profile.version() : work.version(); }
    String message() { return profile != null ? profile.message() : work.message(); }
    long failures() {
      return profile != null ? ReadyPreferenceApplyTest.profileFailures(state) : reports.stream().filter(text ->
          text.startsWith("Preference Apply failed:") || text.startsWith("Preference Save failed:")).count();
    }
    Object raw() {
      return edit != null ? BackgroundMetadataApplyTest.raw(edit) : privateForm != null ? privateForm.raw()
          : appearance != null ? appearance.raw() : "active";
    }
    Object taskOwner() { return profile != null ? profile : work; }
    void cancel() { if (profile != null) profile.cancel(); else work.cancel(); }
    void closeWork() { if (profile != null) profile.close(); else work.close(); }
    long temporary() throws Exception {
      try (var paths = Files.list(root)) { return paths.filter(p -> !p.equals(target)).count(); }
    }
    void retained() throws Exception {
      domain.retained(state); assertEquals(beforeUser, ProjectIO.JSON.valueToTree(user));
      assertSame(activeProfile, state.metadata); assertEquals(beforeProfile, ProjectIO.JSON.valueToTree(state.metadata));
      assertEquals("previous", Files.readString(target)); assertEquals(0, replacements.get());
    }
    void published() throws Exception {
      assertFalse(running()); assertEquals(1, replacements.get()); assertEquals(0, temporary()); domain.retained(state);
      assertSame(appearanceOwner, user.appearance); assertSame(performanceOwner, user.performance);
      assertSame(inputOwner, user.input); assertSame(workspaceOwner, user.workspace);
      if (profile != null) {
        assertEquals("Private", state.metadata.controls.get("noise").label); assertFalse(edit.changed());
        assertEquals(beforeUser, ProjectIO.JSON.valueToTree(user));
        assertEquals(ProjectIO.JSON.valueToTree(state.metadata), ProjectIO.JSON.readTree(target.toFile()));
      } else {
        assertSame(activeProfile, state.metadata); assertEquals(beforeProfile, ProjectIO.JSON.valueToTree(state.metadata));
        assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(target.toFile()));
        switch (kind) {
          case PERFORMANCE -> assertEquals(256, user.performance.cacheMiB);
          case SCALES -> { assertEquals(1.25f, user.appearance.globalScale); assertEquals(1.5f, user.appearance.windowScales.get("Resources")); }
          case TRUST -> assertEquals(BackgroundPreferenceApplyTest.ENDPOINT, user.performance.updateEndpoint);
          case THEME -> assertEquals(BackgroundAppearanceInputTest.palette(), ThemePalette.from(user.appearance));
          case SHORTCUTS -> assertEquals("Alt+F9", user.input.shortcuts.get("Profiler"));
          case ACTIVE -> assertEquals(beforeUser, ProjectIO.JSON.valueToTree(user));
          default -> throw new AssertionError(kind);
        }
      }
    }
    public void close() { if (closed) return; closed = true; closeWork(); state.close(); }
  }

  private static final class Domain {
    final ProjectFileOwner owner;
    final long history, revision;
    final List<Diagnostic> issues;
    final String scope;
    final boolean undo, redo;
    Domain(EditorState state) {
      owner = new ProjectFileOwner(state); history = state.document.historyBytes(); revision = state.revision;
      issues = state.issues; scope = state.validationScope; undo = state.document.canUndo(); redo = state.document.canRedo();
    }
    void retained(EditorState state) {
      assertTrue(owner.valuesCurrent(state)); assertEquals(history, state.document.historyBytes()); assertEquals(revision, state.revision);
      assertSame(issues, state.issues); assertEquals(scope, state.validationScope);
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
    var owner = form.taskOwner(); Field field = owner.getClass().getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false); field.set(owner, replacement);
  }

  @Test
  void realHandoffReceiptCannotReplaceOrActivateUntilFullWorkerReturn() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var raw = form.raw(); form.start(); long version = form.version(); var callback = receipt(ui); callback.run();
          assertFalse(form.accept()); assertTrue(form.running()); assertTrue(form.ready()); assertEquals(version, form.version());
          form.retained(); assertEquals(raw, form.raw()); assertEquals(1, form.temporary());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          assertTrue(form.accept(version)); callback.run(); assertFalse(form.accept()); form.published();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void failureAfterRealReceiptDisposesStagingAndRequiresExplicitRetry() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> {
        ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late post");
      })) {
        try {
          form.start(); var old = receipt(ui); old.run(); assertFalse(form.accept()); form.retained();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); assertFalse(form.accept());
          assertFalse(form.running()); form.retained(); assertEquals(0, form.temporary()); assertEquals(1, form.failures());
          broken.set(false); form.start(); old.run(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          assertTrue(form.accept()); form.published(); assertEquals(1, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void cancelOrCloseDuringHeldSuccessOrFailureSuppressesLateFeedbackAndPublication() throws Exception {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) for (boolean close : new boolean[] {false, true}) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor(); var form = new Form(kind, worker, callback -> {
        ui.add(callback); await(release); throw new AssertionError("retired post");
      })) {
        try {
          if (failed) form.writeFault.set(new AssertionError("captured failure"));
          var raw = form.raw(); form.start(); var callback = receipt(ui); callback.run(); assertFalse(form.accept());
          if (close) form.closeWork(); else form.cancel(); assertEquals(0, form.temporary());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); assertFalse(form.accept());
          assertFalse(form.running()); form.retained(); assertEquals(raw, form.raw()); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForPostingAndSuccessfulExecutorAdmission() throws Exception {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>(); var observations = new AtomicInteger();
      try (var form = new Form(kind, task -> {
        task.run(); assertFalse(owner.get().accept()); assertEquals(0, owner.get().replacements.get()); observations.incrementAndGet();
      }, callback -> {
        callback.run(); assertFalse(owner.get().accept()); assertEquals(0, owner.get().replacements.get()); observations.incrementAndGet();
      })) {
        owner.set(form); form.start(); assertEquals(2, observations.get()); form.retained();
        assertTrue(form.running()); assertTrue(form.accept()); form.published();
      }
    }
  }

  @Test
  void inlineAcceptanceCannotHideALaterPostingFailure() throws Exception {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, Runnable::run, callback -> {
        callback.run(); assertFalse(owner.get().accept()); throw new AssertionError("poster failed");
      })) {
        owner.set(form); form.start(); assertFalse(form.accept()); assertFalse(form.running());
        form.retained(); assertEquals(0, form.temporary()); assertEquals(1, form.failures());
      }
    }
  }

  @Test
  void failedAdmissionWinsOverUnacceptedSuccessOrCapturedFailure() throws Exception {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, task -> { task.run(); throw new AssertionError("executor failed"); }, callback -> {
        callback.run(); assertFalse(owner.get().accept());
      })) {
        owner.set(form); if (failed) form.writeFault.set(new AssertionError("captured failure"));
        form.start(); assertFalse(form.accept()); assertFalse(form.running()); form.retained(); assertEquals(0, form.temporary());
        assertEquals(1, form.failures()); assertTrue(form.message().contains("executor failed"));
        assertFalse(form.message().contains("captured failure"));
      }
    }
  }

  @Test
  void capturedPreparationFailureWaitsForCompleteHandoffAndAdmission() throws Exception {
    for (var kind : Kind.values()) {
      var owner = new AtomicReference<Form>();
      try (var form = new Form(kind, Runnable::run, callback -> {
        callback.run(); assertFalse(owner.get().accept()); assertEquals(0, owner.get().failures());
      })) {
        owner.set(form); form.writeFault.set(new AssertionError("captured failure")); form.start();
        assertTrue(form.running()); assertEquals(0, form.failures()); assertFalse(form.accept());
        form.retained(); assertEquals(1, form.failures()); assertEquals(0, form.temporary());
      }
    }
  }

  @Test
  void completeWorkWithoutAUiReceiptCannotReplaceTheTarget() throws Exception {
    for (var kind : Kind.values()) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, ui::add)) {
        form.start(); tasks.remove().run(); assertFalse(form.accept()); form.retained(); assertTrue(form.running());
        ui.remove().run(); assertTrue(form.accept()); form.published();
      }
    }
  }

  @Test
  void terminalStatusGetAndInterruptedReadsAreContainedWithoutFilePublication() throws Exception {
    for (var kind : Kind.values()) for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { if (fault == 0) throw new AssertionError("status fault"); return true; }
          public Void get() throws InterruptedException {
            if (fault == 2) throw new InterruptedException("read interrupted"); throw new AssertionError("get fault");
          }
        });
        assertFalse(form.accept()); assertFalse(form.running()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        Thread.interrupted(); form.retained(); assertEquals(0, form.temporary()); assertEquals(1, form.failures());
      } finally { Thread.interrupted(); }
    }
  }

  @Test
  void terminalReadCannotConsumeOrFailItsReadyReplacement() throws Exception {
    for (var kind : Kind.values()) for (boolean failed : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() {
            form.cancel(); form.start(); tasks.remove().run();
            if (failed) throw new AssertionError("obsolete read"); return null;
          }
        });
        assertFalse(form.accept()); assertTrue(form.ready()); form.retained(); assertEquals(0, form.failures());
        assertTrue(form.accept()); form.published();
      }
    }
  }

  @Test
  void failureMessageGetterCannotOverwriteAReadySuccessorOrClosedOwner() throws Exception {
    for (var kind : Kind.values()) for (boolean close : new boolean[] {false, true}) {
      try (var form = new Form(kind, Runnable::run, Runnable::run)) {
        form.writeFault.set(new AssertionError() {
          public String getMessage() {
            form.writeFault.set(null); if (close) form.closeWork(); else form.start(); return "obsolete failure";
          }
        });
        form.start(); assertFalse(form.accept()); form.retained(); assertEquals(0, form.failures());
        assertFalse(form.message().contains("obsolete failure"));
        if (close) { assertFalse(form.running()); assertEquals(0, form.temporary()); }
        else { assertTrue(form.ready()); assertTrue(form.accept()); form.published(); }
      }
    }
  }

  @Test
  void currentInputMismatchStillRetiresAnUnfinishedPosting() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); var callback = receipt(ui); callback.run(); form.mismatch = true;
          assertFalse(form.accept()); assertFalse(form.running()); assertEquals(0, form.temporary());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); callback.run(); assertFalse(form.accept());
          form.retained(); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void finalClosureIsVisibleBeforeTaskCancellationCanStartAnotherWorker() throws Exception {
    for (var kind : Kind.values()) {
      var tasks = new ArrayDeque<Runnable>();
      try (var form = new Form(kind, tasks::add, Runnable::run)) {
        form.start(); tasks.remove(); replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) { form.start(); return super.cancel(interrupt); }
        });
        form.closeWork(); assertTrue(tasks.isEmpty()); assertFalse(form.running()); assertFalse(form.accept()); form.retained();
      }
    }
  }

  @Test
  void heldPredecessorAndItsShownVersionCannotConsumeAReadySuccessor() throws Exception {
    for (var kind : Kind.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var worker = Executors.newSingleThreadExecutor();
          var form = new Form(kind, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.start(); long version = form.version(); var old = receipt(ui); old.run(); form.cancel(); form.start();
          old.run(); assertFalse(form.accept(version)); form.retained(); release.countDown();
          receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          old.run(); assertFalse(form.accept(version)); form.retained(); assertTrue(form.accept()); form.published();
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void cleanupFeedbackCannotGiveAnOldFailureAuthorityOverItsReadyReplacement() throws Exception {
    for (var kind : new Kind[] {Kind.PERFORMANCE, Kind.PROFILE}) {
      try (var form = new Form(kind, Runnable::run, Runnable::run)) {
        form.start(); form.deleteFault.set(new AssertionError() {
          public String getMessage() { form.start(); return "cleanup notice"; }
        });
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean isDone() { return true; }
          public Void get() { throw new AssertionError("obsolete task failure"); }
        });
        assertFalse(form.accept()); assertTrue(form.ready()); form.retained(); assertEquals(0, form.failures());
        assertFalse(form.message().contains("obsolete task failure")); assertTrue(form.accept()); form.published();
      }
    }
  }

  @Test
  void activeSaveNotificationWaitsForCompleteWorkerAndPromotesManualRequestsOnlyOnce() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var notifications = new ArrayList<String>();
    var user = new UserConfig(); var file = directory.resolve("active.json"); Files.writeString(file, "previous");
    try (var worker = Executors.newSingleThreadExecutor(); var saves = new PreferenceSaveSession(user, file, worker,
        callback -> { ui.add(callback); await(release); }, notifications::add, text -> fail(text))) {
      try {
        var owner = user.workspace; saves.request(false); var callback = receipt(ui); callback.run(); saves.frame();
        for (int i = 0; i < 8; i++) saves.request(true);
        assertTrue(saves.pending()); assertTrue(notifications.isEmpty()); assertEquals("previous", Files.readString(file));
        release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); saves.frame(); callback.run(); saves.frame();
        assertFalse(saves.pending()); assertEquals(List.of("Preferences saved"), notifications); assertSame(owner, user.workspace);
        assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
      } finally { release.countDown(); }
    }
  }

  @Test
  void activeSaveFailureReporterCanCreateReadyReplacementDuringFrameOrRequestObservation() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var broken = new AtomicBoolean(true); var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      var user = new UserConfig(ReadyPreferenceApplyTest.files(mode == 0 ? broken : new AtomicBoolean(false)));
      var file = directory.resolve("replacement-" + mode + ".json"); Files.writeString(file, "previous");
      var notifications = new AtomicInteger(); var reports = new AtomicInteger(); var ref = new AtomicReference<PreferenceSaveSession>();
      final int fault = mode;
      try (var saves = new PreferenceSaveSession(user, file, tasks::add, callback -> {
        ui.add(callback); if (fault > 0 && broken.get()) throw new AssertionError("post fault");
      }, text -> notifications.incrementAndGet(), text -> {
        reports.incrementAndGet(); broken.set(false); ref.get().request(true); tasks.remove().run(); ui.remove().run();
      })) {
        ref.set(saves); saves.request(false); tasks.remove().run(); ui.remove().run();
        if (mode == 2) saves.request(true); else saves.frame();
        assertTrue(saves.pending()); assertTrue(saves.probeWork().ready()); assertEquals(1, reports.get());
        assertEquals(0, notifications.get()); assertEquals("previous", Files.readString(file));
        saves.frame(); assertFalse(saves.pending()); assertEquals(1, notifications.get());
        assertEquals(ProjectIO.JSON.valueToTree(user), ProjectIO.JSON.readTree(file.toFile()));
      }
    }
  }

  @Test
  void activeSaveErrorReporterClosureCannotBeUndoneByOlderFrameOrRequestCleanup() throws Exception {
    for (int mode = 0; mode < 3; mode++) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var ref = new AtomicReference<PreferenceSaveSession>();
      var user = new UserConfig(ReadyPreferenceApplyTest.files(new AtomicBoolean(mode == 0)));
      var file = directory.resolve("closed-" + mode + ".json"); Files.writeString(file, "previous");
      var reports = new AtomicInteger(); final int fault = mode;
      try (var saves = new PreferenceSaveSession(user, file, tasks::add, callback -> {
        ui.add(callback); if (fault > 0) throw new AssertionError("post fault");
      }, text -> fail("closed save notified"), text -> {
        reports.incrementAndGet(); ref.get().close(); ref.get().request(true);
      })) {
        ref.set(saves); saves.request(false); tasks.remove().run(); ui.remove().run();
        if (mode == 2) saves.request(true); else saves.frame();
        saves.frame(); saves.request(false); assertFalse(saves.pending()); assertTrue(tasks.isEmpty());
        assertEquals(1, reports.get()); assertEquals("previous", Files.readString(file));
      }
    }
  }

  @Test
  void activeFailureMessageGetterCanCreateReadySuccessorWithoutPredecessorReportingOrSettlement() throws Exception {
    for (boolean close : new boolean[] {false, true}) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var fault = new AtomicReference<Error>();
      var ref = new AtomicReference<PreferenceSaveSession>(); var reports = new AtomicInteger(); var notifications = new AtomicInteger();
      var user = new UserConfig(new PreferenceFiles(new PreferenceFiles.Storage() {
        public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); if (fault.get() != null) throw fault.get(); }
        public void replace(Path temp, Path target) throws IOException {
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
      }));
      var file = directory.resolve("message-" + close + ".json"); Files.writeString(file, "previous");
      try (var saves = new PreferenceSaveSession(user, file, tasks::add, ui::add,
          text -> notifications.incrementAndGet(), text -> reports.incrementAndGet())) {
        ref.set(saves); fault.set(new AssertionError() {
          public String getMessage() {
            fault.set(null); if (close) ref.get().close();
            else { ref.get().request(true); tasks.remove().run(); ui.remove().run(); }
            return "obsolete save failure";
          }
        });
        saves.request(false); tasks.remove().run(); ui.remove().run(); saves.frame();
        assertEquals(0, reports.get()); assertEquals(0, notifications.get()); assertEquals("previous", Files.readString(file));
        assertEquals(!close, saves.pending());
        if (!close) { assertTrue(saves.probeWork().ready()); saves.frame(); assertEquals(1, notifications.get()); }
        assertFalse(saves.pending());
      }
    }
  }

  @Test
  void successCallbackCanPrepareAQuietSuccessorBeforeThrowingWithoutReplayingEitherSave() throws Exception {
    var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var user = new UserConfig();
    var ref = new AtomicReference<PreferenceSaveSession>(); var notifications = new AtomicInteger(); var reports = new AtomicInteger();
    var file = directory.resolve("notification.json");
    try (var saves = new PreferenceSaveSession(user, file, tasks::add, ui::add, text -> {
      notifications.incrementAndGet(); user.workspace.width = 1234; ref.get().request(false); tasks.remove().run(); ui.remove().run();
      throw new AssertionError("notification failed");
    }, text -> reports.incrementAndGet())) {
      ref.set(saves); saves.request(true); tasks.remove().run(); ui.remove().run(); saves.frame();
      assertTrue(saves.pending()); assertTrue(saves.probeWork().ready()); assertEquals(1, notifications.get()); assertEquals(1, reports.get());
      saves.frame(); saves.frame(); assertFalse(saves.pending()); assertEquals(1, notifications.get());
      assertEquals(1234, UserConfig.read(file).workspace.width);
    }
  }

  @Test
  void coalescingDuringHeldPostingKeepsLatestSourceAndOnePromotedNotification() throws Exception {
    var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var user = new UserConfig();
    var notifications = new ArrayList<String>(); var file = directory.resolve("coalesced.json"); Files.writeString(file, "previous");
    try (var worker = Executors.newSingleThreadExecutor(); var saves = new PreferenceSaveSession(user, file, worker,
        callback -> { ui.add(callback); await(release); }, notifications::add, text -> fail(text))) {
      try {
        saves.request(false); var old = receipt(ui); old.run(); user.workspace.width = 1234; saves.frame();
        assertTrue(saves.pending()); for (int i = 0; i < 8; i++) saves.request(true);
        old.run(); saves.frame(); assertEquals("previous", Files.readString(file)); assertTrue(notifications.isEmpty());
        release.countDown(); receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); saves.frame();
        old.run(); saves.frame(); assertFalse(saves.pending()); assertEquals(List.of("Preferences saved"), notifications);
        assertEquals(1234, UserConfig.read(file).workspace.width);
      } finally { release.countDown(); }
    }
  }
}
