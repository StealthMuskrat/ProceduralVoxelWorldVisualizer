package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred complete-worker/admission gates for direct and guarded Properties publication. */
class DraftApplyCompletionTest {
  private static final long TIMEOUT = 10;
  private enum Route { PROPERTIES, NAVIGATION, SELECTION }

  private static final class Form implements AutoCloseable {
    final EditorState state;
    final Route route;
    final PropertiesApplySession properties;
    final SearchNavigation navigation;
    final ResourceSelectionSession selection;
    final List<Object> completed = new ArrayList<>();

    Form(EditorState state, Route route, Executor worker, Consumer<Runnable> ui) {
      this.state = state;
      this.route = route;
      properties = route == Route.PROPERTIES ? new PropertiesApplySession(state, worker, ui, () -> false) : null;
      navigation = route == Route.NAVIGATION ? new SearchNavigation(state, worker, ui, completed::add) : null;
      selection = route == Route.SELECTION ? new ResourceSelectionSession(state, () -> {}, worker, ui, () -> false, completed::add) : null;
    }

    UUID id(int index) { return state.project().noise2D.get(index).uuid; }
    EditorEvents.Target target(int index) {
      return new EditorEvents.Target(state.project().project.uuid, id(index), null, null, "", false);
    }
    void begin() { begin(1); }
    void begin(int index) {
      if (navigation != null) assertEquals(SearchNavigation.Result.NEEDS_DECISION, navigation.request(target(index)));
      else if (selection != null) assertEquals(ResourceSelectionSession.Result.NEEDS_DECISION, selection.click(id(index), false));
      apply();
    }
    void apply() {
      if (properties != null) properties.start();
      else if (navigation != null) navigation.decide(SearchNavigation.Decision.APPLY);
      else selection.decide(SearchNavigation.Decision.APPLY);
    }
    void accept() {
      if (properties != null) properties.acceptPrepared();
      else if (navigation != null) navigation.acceptPrepared();
      else selection.acceptPrepared();
    }
    void cancel() {
      if (properties != null) properties.cancel();
      else if (navigation != null) navigation.decide(SearchNavigation.Decision.CANCEL);
      else selection.decide(SearchNavigation.Decision.CANCEL);
    }
    boolean preparing() {
      return properties != null ? properties.running() : navigation != null ? navigation.preparing() : selection.preparing();
    }
    long version() {
      return properties != null ? properties.version() : navigation != null ? navigation.workVersion() : selection.workVersion();
    }
    String feedback() {
      return properties != null ? properties.view().message() : navigation != null ? navigation.progress() : selection.progress();
    }
    long failures() {
      return state.events.snapshot().stream().filter(e -> e.code().endsWith("_APPLY_FAILED")).mapToLong(EditorEvents.Entry::count).sum();
    }
    Object taskOwner() { return properties != null ? properties : navigation != null ? navigation : selection; }
    boolean requestAfterClose() {
      return properties != null ? properties.start() : navigation != null
          ? navigation.request(target(2)) == SearchNavigation.Result.NEEDS_DECISION
          : selection.click(id(2), false) == ResourceSelectionSession.Result.NEEDS_DECISION;
    }
    void assertApplied(int destination) {
      assertFalse(preparing());
      assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
      assertEquals(id(route == Route.PROPERTIES ? 0 : destination), state.selected);
      assertEquals(route == Route.PROPERTIES ? 0 : 1, completed.size());
      if (properties != null) assertEquals(PropertiesApplySession.Phase.APPLIED, properties.view().phase());
    }
    public void close() {
      if (properties != null) properties.close(); else if (navigation != null) navigation.close(); else selection.close();
    }
  }

  private static final class Snapshot {
    final JsonNode project, privateValues;
    final Object draft;
    final UUID selected;
    final List<UUID> selection;
    final List<Diagnostic> issues;
    final String scope;
    final long revision, history, inputs;
    final boolean undo, redo;
    Snapshot(EditorState state) {
      project = ProjectIO.JSON.valueToTree(state.project()); privateValues = ProjectIO.JSON.valueToTree(state.draft);
      draft = state.draft; selected = state.selected; selection = List.copyOf(state.multiSelection);
      issues = state.issues; scope = state.validationScope; revision = state.revision;
      history = state.document.historyBytes(); inputs = state.seedInputs.version(state);
      undo = state.document.canUndo(); redo = state.document.canRedo();
    }
    void retained(EditorState state) {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); assertSame(draft, state.draft);
      assertEquals(privateValues, ProjectIO.JSON.valueToTree(state.draft)); assertEquals(selected, state.selected);
      assertEquals(selection, List.copyOf(state.multiSelection)); assertSame(issues, state.issues);
      assertEquals(scope, state.validationScope); assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes()); assertEquals(inputs, state.seedInputs.version(state));
      assertEquals(undo, state.document.canUndo()); assertEquals(redo, state.document.canRedo());
    }
  }

  private static void await(CountDownLatch latch) {
    boolean interrupted = false;
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
    try {
      while (true) {
        try {
          assertTrue(latch.await(Math.max(0, end - System.nanoTime()), TimeUnit.NANOSECONDS), "handoff fixture timeout");
          return;
        } catch (InterruptedException cancelled) { interrupted = true; }
      }
    } finally { if (interrupted) Thread.currentThread().interrupt(); }
  }

  private static Runnable receipt(BlockingQueue<Runnable> ui) throws InterruptedException {
    var callback = ui.poll(TIMEOUT, TimeUnit.SECONDS); assertNotNull(callback); return callback;
  }

  private static void replaceTask(Form form, FutureTask<Void> replacement) throws Exception {
    Object owner = form.taskOwner(); Field field = owner.getClass().getDeclaredField("task"); field.setAccessible(true);
    ((Future<?>) field.get(owner)).cancel(false); field.set(owner, replacement);
  }

  @Test
  void aRealWorkersReceiptCannotCommitUntilItsEntireHandoffReturnsNormally() throws Exception {
    for (var route : Route.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, route, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Snapshot(state); form.begin(); long version = form.version();
          var callback = receipt(ui); callback.run(); form.accept();
          assertTrue(form.preparing()); assertEquals(version, form.version()); before.retained(state);
          assertTrue(form.completed.isEmpty()); assertEquals(0, form.failures());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.accept(); form.accept(); callback.run(); form.assertApplied(1);
          assertEquals(before.revision + 1, state.revision);
          state.document.undo(); assertEquals(before.project, ProjectIO.JSON.valueToTree(state.project()));
          state.document.redo(); assertEquals(.625, state.project().noise2D.getFirst().algorithm.parameters.get("value"));
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void lateHandoffFailurePreservesPrivateDocumentAndExplicitRetryCommitsOnce() throws Exception {
    for (var route : Route.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); var broken = new AtomicBoolean(true);
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, route, worker, callback -> {
            ui.add(callback); await(release); if (broken.get()) throw new AssertionError("late handoff");
          })) {
        try {
          var before = new Snapshot(state); form.begin(); var old = receipt(ui); old.run(); form.accept();
          before.retained(state); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          form.accept(); assertFalse(form.preparing()); before.retained(state);
          assertTrue(form.feedback().contains("late handoff")); assertEquals(1, form.failures());
          assertTrue(form.completed.isEmpty()); broken.set(false); form.apply(); old.run(); form.accept();
          receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS); form.accept(); form.assertApplied(1);
          assertEquals(1, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void cancellationDuringHandoffRetiresSuccessRejectionAndCapturedFailureBeforeLatePosterFault() throws Exception {
    for (var route : Route.values()) for (int mode = 0; mode < 3; mode++) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>(); final int result = mode;
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor()) {
        state.document.setValidator(p -> {
          if (result == 2) throw new AssertionError("captured worker");
          return result == 1 ? List.of(Diagnostic.error("REJECT", "/", "rejected")) : List.of();
        });
        try (var form = new Form(state, route, worker, callback -> {
          ui.add(callback); await(release); throw new AssertionError("cancelled poster");
        })) {
          try {
            var before = new Snapshot(state); form.begin(); var callback = receipt(ui); callback.run();
            form.accept(); form.cancel(); release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
            callback.run(); form.accept(); assertFalse(form.preparing()); before.retained(state);
            assertEquals(0, form.failures()); assertTrue(form.completed.isEmpty());
          } finally { release.countDown(); }
        }
      }
    }
  }

  @Test
  void closingWhileTheRealHandoffIsBlockedRejectsLateReceiptsAndNewRequests() throws Exception {
    for (var route : Route.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, route, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Snapshot(state); form.begin(); var callback = receipt(ui); callback.run(); form.close();
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); assertFalse(form.requestAfterClose()); before.retained(state);
          assertEquals(0, form.failures()); assertTrue(form.completed.isEmpty());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void inlineAcceptanceWaitsForBothPostingAndSuccessfulExecutorReturn() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var owner = new AtomicReference<Form>(); var observations = new AtomicInteger(); var before = new Snapshot(state);
      try (var form = new Form(state, route, task -> {
        task.run(); owner.get().accept(); before.retained(state); observations.incrementAndGet();
      }, callback -> {
        callback.run(); owner.get().accept(); before.retained(state); observations.incrementAndGet();
      })) {
        owner.set(form); form.begin(); assertEquals(2, observations.get()); assertTrue(form.preparing());
        before.retained(state); form.accept(); form.assertApplied(1);
      }
    }
  }

  @Test
  void inlineReceiptThenPosterFailureCannotCommitOrInstallDestination() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var owner = new AtomicReference<Form>(); var before = new Snapshot(state);
      try (var form = new Form(state, route, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); before.retained(state); throw new AssertionError("poster failed");
      })) {
        owner.set(form); form.begin(); form.accept(); assertFalse(form.preparing()); before.retained(state);
        assertTrue(form.completed.isEmpty()); assertEquals(1, form.failures());
        assertTrue(form.feedback().contains("poster failed"));
      }
    }
  }

  @Test
  void executorFaultAfterInlineAcceptanceTakesPrecedenceOverUnacceptedWorkerOutcome() {
    for (var route : Route.values()) for (boolean failed : new boolean[] {false, true})
      try (var state = ReadyGuardedApplyTest.state()) {
        if (failed) state.document.setValidator(p -> { throw new AssertionError("captured worker"); });
        var before = new Snapshot(state); var owner = new AtomicReference<Form>();
        try (var form = new Form(state, route, task -> { task.run(); throw new AssertionError("executor failed"); }, callback -> {
          callback.run(); owner.get().accept(); before.retained(state);
        })) {
          owner.set(form); form.begin(); form.accept(); before.retained(state); assertFalse(form.preparing());
          assertEquals(1, form.failures()); assertTrue(form.completed.isEmpty());
          assertTrue(form.feedback().contains("executor failed")); assertFalse(form.feedback().contains("captured worker"));
        }
      }
  }

  @Test
  void capturedWorkerFailureCannotReportInsideInlineHandoff() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      state.document.setValidator(p -> { throw new AssertionError("captured worker"); });
      var before = new Snapshot(state); var owner = new AtomicReference<Form>();
      try (var form = new Form(state, route, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); assertEquals(0, owner.get().failures()); before.retained(state);
      })) {
        owner.set(form); form.begin(); assertTrue(form.preparing()); assertEquals(0, form.failures());
        form.accept(); before.retained(state); assertEquals(1, form.failures());
        assertTrue(form.feedback().contains("captured worker"));
      }
    }
  }

  @Test
  void capturedRejectionCannotPublishDiagnosticsInsideInlineHandoff() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var issue = Diagnostic.error("REJECT", "/", "rejected"); state.document.setValidator(p -> List.of(issue));
      var owner = new AtomicReference<Form>(); var before = new Snapshot(state);
      try (var form = new Form(state, route, Runnable::run, callback -> {
        callback.run(); owner.get().accept(); before.retained(state);
      })) {
        owner.set(form); form.begin(); assertTrue(form.preparing()); before.retained(state); form.accept();
        assertEquals(List.of(issue), state.issues); assertFalse(form.preparing());
        assertEquals(before.project, ProjectIO.JSON.valueToTree(state.project())); assertSame(before.draft, state.draft);
        assertTrue(form.completed.isEmpty()); assertEquals(0, form.failures());
      }
    }
  }

  @Test
  void normalWorkerCompletionAloneCannotBypassItsDelayedUiReceipt() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var tasks = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var form = new Form(state, route, tasks::add, ui::add)) {
        var before = new Snapshot(state); form.begin(); tasks.remove().run(); form.accept();
        assertTrue(form.preparing()); before.retained(state); ui.remove().run(); form.accept(); form.assertApplied(1);
      }
    }
  }

  @Test
  void statusGetAndInterruptedTerminalReadFaultsStayOwnedAndDoNotCommit() throws Exception {
    for (var route : Route.values()) for (int mode = 0; mode < 3; mode++)
      try (var state = ReadyGuardedApplyTest.state()) {
        var tasks = new ArrayDeque<Runnable>(); final int fault = mode;
        try (var form = new Form(state, route, tasks::add, Runnable::run)) {
          var before = new Snapshot(state); form.begin(); tasks.remove();
          replaceTask(form, new FutureTask<Void>(() -> null) {
            public boolean isDone() { if (fault == 0) throw new AssertionError("future status"); return true; }
            public Void get() throws InterruptedException {
              if (fault == 2) throw new InterruptedException("terminal read interrupted");
              throw new AssertionError("future get");
            }
          });
          form.accept(); before.retained(state); assertFalse(form.preparing()); assertEquals(1, form.failures());
          assertTrue(form.completed.isEmpty()); assertEquals(mode == 2, Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
      }
  }

  @Test
  void terminalReadCannotConsumeOrFailAReadySuccessorItCreates() throws Exception {
    for (var route : Route.values()) for (boolean failedRead : new boolean[] {false, true})
      try (var state = ReadyGuardedApplyTest.state()) {
        var tasks = new ArrayDeque<Runnable>();
        try (var form = new Form(state, route, tasks::add, Runnable::run)) {
          var before = new Snapshot(state); form.begin(); tasks.remove();
          replaceTask(form, new FutureTask<Void>(() -> null) {
            public boolean isDone() { return true; }
            public Void get() {
              form.cancel(); form.begin(2); tasks.remove().run();
              if (failedRead) throw new AssertionError("obsolete terminal read"); return null;
            }
          });
          form.accept(); assertTrue(form.preparing()); before.retained(state);
          assertEquals(0, form.failures()); assertTrue(form.completed.isEmpty());
          form.accept(); form.assertApplied(2);
        }
      }
  }

  @Test
  void readingFailureMessageCannotOverwriteOrReportAgainstReadySuccessor() {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var owner = new AtomicReference<Form>(); var first = new AtomicBoolean(true); var before = new Snapshot(state);
      var failure = new AssertionError() {
        public String getMessage() { owner.get().cancel(); owner.get().begin(2); return "obsolete failure"; }
      };
      state.document.setValidator(p -> { if (first.getAndSet(false)) throw failure; return List.of(); });
      try (var form = new Form(state, route, Runnable::run, Runnable::run)) {
        owner.set(form); form.begin(); form.accept(); assertTrue(form.preparing()); before.retained(state);
        assertEquals(0, form.failures()); assertFalse(form.feedback().contains("obsolete failure"));
        form.accept(); form.assertApplied(2);
      }
    }
  }

  @Test
  void blockedPredecessorCannotPopulateOrConsumeAReplacementAfterCancellation() throws Exception {
    for (var route : Route.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, route, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          var before = new Snapshot(state); form.begin(); var old = receipt(ui); old.run(); form.cancel(); form.begin(2);
          old.run(); form.accept(); before.retained(state); release.countDown();
          receipt(ui).run(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          old.run(); form.accept(); form.assertApplied(2); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void newerFeedbackWhileHandoffIsBlockedRetiresPreparationBeforeLaterReturn() throws Exception {
    for (var route : Route.values()) {
      var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var state = ReadyGuardedApplyTest.state(); var worker = Executors.newSingleThreadExecutor();
          var form = new Form(state, route, worker, callback -> { ui.add(callback); await(release); })) {
        try {
          form.begin(); var callback = receipt(ui); callback.run();
          state.issues = List.of(Diagnostic.error("NEW", "/", "newer feedback")); state.validationScope = "newer scope";
          var before = new Snapshot(state); form.accept(); assertFalse(form.preparing());
          release.countDown(); worker.submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
          callback.run(); form.accept(); before.retained(state); assertEquals(0, form.failures());
        } finally { release.countDown(); }
      }
    }
  }

  @Test
  void finalCloseIsVisibleBeforeFutureCancellationCanAttemptANewRequest() throws Exception {
    for (var route : Route.values()) try (var state = ReadyGuardedApplyTest.state()) {
      var tasks = new ArrayDeque<Runnable>(); var admitted = new AtomicBoolean();
      try (var form = new Form(state, route, tasks::add, Runnable::run)) {
        var before = new Snapshot(state); form.begin(); tasks.remove();
        replaceTask(form, new FutureTask<Void>(() -> null) {
          public boolean cancel(boolean interrupt) {
            admitted.set(form.requestAfterClose()); return super.cancel(interrupt);
          }
        });
        form.close(); assertFalse(admitted.get()); assertTrue(tasks.isEmpty()); before.retained(state);
        assertFalse(form.preparing()); assertTrue(form.completed.isEmpty());
      }
    }
  }
}
