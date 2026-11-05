package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred display-only completion, callback ownership and terminal handoff definitions. */
class AdvisoryCompletionOwnershipTest {
  static long failures(EditorState state, String code) {
    return state.events.snapshot().stream().filter(e -> e.code().equals(code))
        .mapToLong(EditorEvents.Entry::count).sum();
  }

  static void deliver(BackgroundResourceSearchTest.Workers workers, Queue<Runnable> ui) {
    workers.run(); ui.remove().run();
  }

  static void deliver(BackgroundExpressionFeedbackTest.Workers workers, Queue<Runnable> ui) {
    workers.run(); ui.remove().run();
  }

  @Test
  void findDeliveryDoesNotCallPresentationOrInstallAnIndexUntilOwnerObservation() {
    try (var state = BackgroundResourceSearchTest.state(3)) {
      var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var live = BackgroundResourceSearchTest.text("needle"); var reads = new AtomicInteger();
      var project = ProjectIO.JSON.valueToTree(state.project()); long revision = state.revision;
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
        reads.incrementAndGet(); return live;
      })) {
        find.observe(state.project(), state.revision, live, 0, true); long version = find.version();
        workers.run(); var callback = ui.remove(); callback.run(); callback.run();
        assertEquals(0, reads.get()); assertEquals(version, find.version());
        assertTrue(find.matches(live)); assertTrue(reads.get() > 0);
        assertTrue(find.indexed() > 0); long accepted = find.version(); callback.run();
        assertEquals(accepted, find.version()); assertEquals(1, find.builds());
        assertEquals(project, ProjectIO.JSON.valueToTree(state.project())); assertEquals(revision, state.revision);
      }
    }
  }

  @Test
  void findInlineDeliveryCannotAuthorizeRowsBeforeHandoffAndExecutorReturnSuccessfully() {
    for (boolean executorFailure : new boolean[] {false, true}) try (var state = BackgroundResourceSearchTest.state(3)) {
      var live = BackgroundResourceSearchTest.text("needle");
      var ref = new AtomicReference<BackgroundResourceSearch>();
      Executor worker = executorFailure ? task -> {
        task.run(); assertFalse(ref.get().matches(live)); throw new AssertionError("execute after delivery");
      } : Runnable::run;
      try (var find = new BackgroundResourceSearch(state, worker, callback -> {
        callback.run(); assertFalse(ref.get().matches(live));
        if (!executorFailure) throw new AssertionError("handoff after delivery");
      }, () -> live)) {
        ref.set(find); find.observe(state.project(), state.revision, live, 0, true);
        assertTrue(find.failed()); assertFalse(find.matches(live)); assertEquals(0, find.indexed());
        assertEquals(1, failures(state, "FIND_SEARCH_FAILED"));
      }
    }
  }

  @Test
  void findPresentationCloseClearOrCancelCannotBeReversedByOlderCompletion() {
    for (int action = 0; action < 3; action++) for (boolean rejected : new boolean[] {false, true})
      try (var state = BackgroundResourceSearchTest.state(3)) {
        int decision = action; var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
        var live = BackgroundResourceSearchTest.text("needle"); var ref = new AtomicReference<BackgroundResourceSearch>();
        try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
          switch (decision) { case 0 -> ref.get().close(); case 1 -> ref.get().clear(); case 2 -> ref.get().cancelPending(); }
          return live;
        }, project -> {
          if (rejected) throw new AssertionError("retired index failure"); return new ResourceSearchIndex(project);
        }, ResourceSearchIndex::search)) {
          ref.set(find); find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
          assertFalse(find.matches(live)); assertFalse(find.pending()); assertEquals(0, find.indexed());
          assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
        }
      }
  }

  @Test
  void findCallbackCanAdoptAReadySuccessorThenReturnOldNewOrThrowWithoutLosingIt() {
    for (int mode = 0; mode < 3; mode++) try (var state = BackgroundResourceSearchTest.state(3)) {
      int result = mode; var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var original = BackgroundResourceSearchTest.text("needle"); var next = BackgroundResourceSearchTest.text("field-0");
      var live = new AtomicReference<>(original); var fire = new AtomicBoolean(true);
      var ref = new AtomicReference<BackgroundResourceSearch>();
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
        if (fire.getAndSet(false)) {
          ref.get().clear(); live.set(next); ref.get().observe(state.project(), state.revision, next, 0, true);
          deliver(workers, ui); assertTrue(ref.get().matches(next));
          if (result == 2) throw new IllegalStateException("retired presentation");
          return result == 0 ? original : next;
        }
        return live.get();
      })) {
        ref.set(find); find.observe(state.project(), state.revision, original, 0, true); deliver(workers, ui);
        assertFalse(find.matches(original)); assertTrue(find.matches(next));
        assertEquals(List.of("field-0"), find.observe(state.project(), state.revision, next, 0, false)
            .stream().map(ResourceSearchIndex.Hit::label).toList());
        assertEquals(2, find.builds()); assertEquals(2, find.queries());
        assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
      }
    }
  }

  @Test
  void findRechecksFullCommittedValuesAfterPresentationCallback() {
    for (boolean transaction : new boolean[] {false, true}) try (var state = BackgroundResourceSearchTest.state(3)) {
      var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var live = BackgroundResourceSearchTest.text("needle"); var fire = new AtomicBoolean(true);
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
        if (fire.getAndSet(false)) {
          if (transaction) assertTrue(state.change(p -> p.noise2D.getLast().id = "changed"));
          else state.project().noise2D.getLast().id = "changed";
        }
        return live;
      })) {
        find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
        assertFalse(find.matches(live)); assertTrue(find.failed()); assertEquals(0, find.indexed());
        assertEquals(1, failures(state, "FIND_SEARCH_FAILED"));
        find.retry(); find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
        assertTrue(find.matches(live));
        assertTrue(find.observe(state.project(), state.revision, live, 0, false).isEmpty());
      }
    }
  }

  @Test
  void acceptedFindRowAuthorityIsRecheckedAfterItsLiveFilterCallback() {
    for (boolean close : new boolean[] {false, true}) try (var state = BackgroundResourceSearchTest.state(3)) {
      var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var live = BackgroundResourceSearchTest.text("needle"); var fire = new AtomicBoolean();
      var ref = new AtomicReference<BackgroundResourceSearch>();
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
        if (fire.getAndSet(false)) { if (close) ref.get().close(); else ref.get().clear(); }
        return live;
      })) {
        ref.set(find); find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
        assertTrue(find.matches(live)); fire.set(true); assertFalse(find.matches(live));
        assertFalse(find.pending()); assertEquals(0, find.indexed());
        assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
      }
    }
  }

  @Test
  void findPresentationMayReadItsOwnPendingStatusWithoutConsumingTheSamePayloadTwice() {
    try (var state = BackgroundResourceSearchTest.state(3)) {
      var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var live = BackgroundResourceSearchTest.text("needle"); var ref = new AtomicReference<BackgroundResourceSearch>();
      var inspect = new AtomicBoolean(true);
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> {
        if (inspect.getAndSet(false)) assertTrue(ref.get().pending());
        return live;
      })) {
        ref.set(find); find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
        assertTrue(find.matches(live)); assertFalse(inspect.get());
        assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
      }
    }
  }

  @Test
  void readyFindFailureCanBeRetiredBeforeAnObserverReportsIt() {
    for (boolean close : new boolean[] {false, true}) try (var state = BackgroundResourceSearchTest.state(3)) {
      var workers = new BackgroundResourceSearchTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var live = BackgroundResourceSearchTest.text("needle");
      try (var find = new BackgroundResourceSearch(state, workers, ui::add, () -> live,
          project -> { throw new AssertionError("index failure"); }, ResourceSearchIndex::search)) {
        find.observe(state.project(), state.revision, live, 0, true); deliver(workers, ui);
        assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
        if (close) find.close(); else find.cancelPending();
        assertFalse(find.failed()); assertEquals(0, failures(state, "FIND_SEARCH_FAILED"));
      }
    }
  }

  @Test
  void expressionDeliveryDoesNotReadSourceOrPopulateCacheUntilOwnerObservation() {
    try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var reads = new AtomicInteger();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, () -> {
        reads.incrementAndGet(); return "x+bias";
      })) {
        BackgroundExpressionFeedbackTest.observe(actor, state); long version = actor.version(); int before = reads.get();
        workers.run(); var callback = ui.remove(); callback.run(); callback.run();
        assertEquals(before, reads.get()); assertEquals(version, actor.version()); assertEquals(0, actor.cachedEntries());
        assertTrue(actor.view().result().valid()); assertEquals(1, actor.cachedEntries());
        long accepted = actor.version(); callback.run(); assertEquals(accepted, actor.version());
      }
    }
  }

  @Test
  void expressionInlineDeliveryCannotAuthorizeAResultBeforeHandoffAndExecutorReturn() {
    for (boolean executorFailure : new boolean[] {false, true}) try (var state = BackgroundExpressionFeedbackTest.state()) {
      var ref = new AtomicReference<BackgroundExpressionFeedback>();
      Executor worker = executorFailure ? task -> {
        task.run(); assertNull(ref.get().view().result()); throw new AssertionError("execute after delivery");
      } : Runnable::run;
      try (var actor = new BackgroundExpressionFeedback(state, worker, callback -> {
        callback.run(); assertNull(ref.get().view().result());
        if (!executorFailure) throw new AssertionError("handoff after delivery");
      }, () -> 0L, null)) {
        ref.set(actor); BackgroundExpressionFeedbackTest.observe(actor, state);
        assertTrue(actor.view().retryable()); assertNull(actor.view().result()); assertEquals(0, actor.cachedEntries());
        assertEquals(1, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      }
    }
  }

  @Test
  void expressionSourceCloseOrSuspendCannotBeReversedByOlderCompletion() {
    for (boolean close : new boolean[] {false, true}) for (boolean throwing : new boolean[] {false, true})
      try (var state = BackgroundExpressionFeedbackTest.state()) {
        var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
        var fire = new AtomicBoolean(); var ref = new AtomicReference<BackgroundExpressionFeedback>();
        try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, () -> {
          if (fire.getAndSet(false)) {
            if (close) ref.get().close(); else ref.get().suspend();
            if (throwing) throw new IllegalStateException("retired source");
          }
          return "x+bias";
        })) {
          ref.set(actor); BackgroundExpressionFeedbackTest.observe(actor, state); deliver(workers, ui); fire.set(true);
          var view = actor.view(); assertNull(view.result());
          assertEquals(close ? BackgroundExpressionFeedback.Phase.CLOSED : BackgroundExpressionFeedback.Phase.IDLE, view.phase());
          assertEquals(0, actor.cachedEntries()); assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
        }
      }
  }

  @Test
  void expressionSourceCanAdoptAReadySuccessorThenReturnOldNewOrThrow() {
    for (int mode = 0; mode < 3; mode++) try (var state = BackgroundExpressionFeedbackTest.state()) {
      int result = mode; var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var text = new AtomicReference<>("x+bias"); var fire = new AtomicBoolean(); var clock = new AtomicLong();
      var ref = new AtomicReference<BackgroundExpressionFeedback>();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, clock::get, () -> {
        if (fire.getAndSet(false)) {
          ref.get().supersede(); text.set("x+unknown"); BackgroundExpressionFeedbackTest.observe(ref.get(), state);
          clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS); BackgroundExpressionFeedbackTest.observe(ref.get(), state);
          deliver(workers, ui); assertFalse(ref.get().view().result().valid());
          if (result == 2) throw new IllegalStateException("retired source");
          return result == 0 ? "x+bias" : text.get();
        }
        return text.get();
      })) {
        ref.set(actor); BackgroundExpressionFeedbackTest.observe(actor, state); deliver(workers, ui); fire.set(true);
        var view = actor.view(); assertFalse(view.result().valid()); assertEquals(2, view.result().start());
        assertEquals(1, actor.cachedEntries()); assertEquals(2, actor.launches());
        assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      }
    }
  }

  @Test
  void expressionInitialObservationCannotResurrectSourceCallbackClosedOrSuspendedOwner() {
    for (boolean close : new boolean[] {false, true}) try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var ref = new AtomicReference<BackgroundExpressionFeedback>();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, () -> {
        if (close) ref.get().close(); else ref.get().suspend();
        return "x+bias";
      })) {
        ref.set(actor); var view = BackgroundExpressionFeedbackTest.observe(actor, state);
        assertEquals(close ? BackgroundExpressionFeedback.Phase.CLOSED : BackgroundExpressionFeedback.Phase.IDLE, view.phase());
        assertNull(view.result()); assertTrue(workers.tasks.isEmpty()); assertEquals(0, actor.launches());
      }
    }
  }

  @Test
  void expressionRetryCannotReviveSourceCallbackClosedOwner() {
    try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); workers.reject = true;
      var ui = new ArrayDeque<Runnable>(); var fire = new AtomicBoolean(); var ref = new AtomicReference<BackgroundExpressionFeedback>();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, () -> {
        if (fire.getAndSet(false)) { ref.get().close(); throw new IllegalStateException("retired retry"); }
        return "x+bias";
      })) {
        ref.set(actor); assertTrue(BackgroundExpressionFeedbackTest.observe(actor, state).retryable());
        workers.reject = false; fire.set(true); actor.retry();
        assertEquals(BackgroundExpressionFeedback.Phase.CLOSED, actor.view().phase());
        assertEquals(1, actor.launches()); assertEquals(1, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      }
    }
  }

  @Test
  void expressionClockCallbacksCannotResurrectObserveSupersedeOrRetryAfterClose() {
    for (int operation = 0; operation < 3; operation++) try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var ref = new AtomicReference<BackgroundExpressionFeedback>(); var fire = new AtomicBoolean(operation == 0);
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> {
        if (fire.getAndSet(false)) ref.get().close(); return 0L;
      }, null)) {
        ref.set(actor); workers.reject = operation == 2;
        BackgroundExpressionFeedbackTest.observe(actor, state);
        long launches = actor.launches(); fire.set(true); workers.reject = false;
        if (operation == 1) actor.supersede(); else if (operation == 2) actor.retry();
        assertEquals(BackgroundExpressionFeedback.Phase.CLOSED, actor.view().phase()); assertEquals(launches, actor.launches());
      }
    }
  }

  @Test
  void expressionSourceMayReadItsOwnStatusDuringAdoptionWithoutDoubleConsumption() {
    try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var ref = new AtomicReference<BackgroundExpressionFeedback>(); var fire = new AtomicBoolean();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, () -> {
        if (fire.getAndSet(false)) assertNull(ref.get().view().result()); return "x+bias";
      })) {
        ref.set(actor); BackgroundExpressionFeedbackTest.observe(actor, state); deliver(workers, ui); fire.set(true);
        assertTrue(actor.view().result().valid()); assertEquals(1, actor.cachedEntries());
        assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      }
    }
  }

  @Test
  void expressionCapturedFailureCanBeSupersededOrClosedBeforeOwnerObservation() {
    for (boolean close : new boolean[] {false, true}) try (var state = BackgroundExpressionFeedbackTest.state()) {
      var workers = new BackgroundExpressionFeedbackTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var actor = new BackgroundExpressionFeedback(state, workers, ui::add, () -> 0L, null,
          request -> { throw new AssertionError("retired parser"); })) {
        BackgroundExpressionFeedbackTest.observe(actor, state); deliver(workers, ui);
        assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
        if (close) actor.close(); else actor.supersede();
        assertFalse(actor.view().retryable()); assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      }
    }
  }

  @Test
  void expressionWorkerFinishingAfterCloseCannotEnqueueItsPayloadOrFailure() throws Exception {
    for (boolean rejected : new boolean[] {false, true}) try (var state = BackgroundExpressionFeedbackTest.state();
        var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var ui = new LinkedBlockingQueue<Runnable>();
      try (var actor = new BackgroundExpressionFeedback(state, worker, ui::add, () -> 0L, null, input -> {
        entered.countDown(); boolean interrupted = false;
        try {
          while (true) try {
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture timeout"); break;
          } catch (InterruptedException cancelled) { interrupted = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (rejected) throw new AssertionError("retired parser"); return ExpressionFeedback.inspectCaptured(input);
      })) {
        BackgroundExpressionFeedbackTest.observe(actor, state); assertTrue(entered.await(10, TimeUnit.SECONDS));
        actor.close(); release.countDown(); worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
        assertTrue(ui.isEmpty()); assertEquals(BackgroundExpressionFeedback.Phase.CLOSED, actor.view().phase());
        assertEquals(0, actor.cachedEntries()); assertEquals(0, failures(state, "EXPRESSION_FEEDBACK_FAILED"));
      } finally { release.countDown(); }
    }
  }
}
