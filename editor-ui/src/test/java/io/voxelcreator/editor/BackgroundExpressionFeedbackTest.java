package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.ProcessingRegistry;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred definitions: no compiler, worker or native execution during the owner testing pause. */
class BackgroundExpressionFeedbackTest {
  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    boolean reject;

    public void execute(Runnable task) {
      if (reject) throw new RejectedExecutionException("feedback queue full");
      tasks.add(task);
    }

    void run() {
      tasks.removeIf(r -> r instanceof Future<?> future && future.isCancelled());
      tasks.remove().run();
    }
  }

  static EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              var noise = new Noise();
              noise.id = "formula";
              var item = ProcessingRegistry.create("EXPRESSION", "formula");
              item.inputs.put("bias", Binding.constant(2));
              item.inputs.put("é", Binding.constant(0));
              item.parameters.put("expression", ProjectIO.JSON.valueToTree("x+bias"));
              noise.processing.add(item);
              noise.outputBinding = Binding.earlier(item.uuid);
              p.noise2D.add(noise);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    return state;
  }

  static Noise noise(EditorState s) {
    return (Noise) s.draft;
  }

  static Item item(EditorState s) {
    return noise(s).processing.getFirst();
  }

  static void text(EditorState s, String value) {
    item(s).parameters.put("expression", ProjectIO.JSON.valueToTree(value));
  }

  static BackgroundExpressionFeedback actor(
      EditorState s, Workers worker, Queue<Runnable> ui, AtomicLong clock) {
    return new BackgroundExpressionFeedback(s, worker, ui::add, clock::get, null);
  }

  static BackgroundExpressionFeedback.View observe(
      BackgroundExpressionFeedback actor, EditorState s) {
    return actor.observe(noise(s), item(s));
  }

  static void deliver(Workers worker, Queue<Runnable> ui) {
    worker.run();
    ui.remove().run();
  }

  @Test
  void compilationUsesAnActualWorkerAndCannotPublishBeforeUiDelivery() throws Exception {
    try (var s = state();
        var worker = Executors.newSingleThreadExecutor()) {
      var queue = new LinkedBlockingQueue<Runnable>();
      var thread = new AtomicReference<Thread>();
      var caller = Thread.currentThread();
      var before = ProjectIO.JSON.valueToTree(s.project());
      var draft = ProjectIO.JSON.valueToTree(s.draft);
      long revision = s.revision, history = s.document.historyBytes();
      var issues = s.issues;
      try (var actor =
          new BackgroundExpressionFeedback(
              s,
              worker,
              queue::add,
              () -> 0L,
              null,
              input -> {
                thread.set(Thread.currentThread());
                return ExpressionFeedback.inspectCaptured(input);
              })) {
        assertNull(observe(actor, s).result());
        var completion = queue.poll(10, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertNotSame(caller, thread.get());
        for (int frame = 0; frame < 12; frame++) {
          assertEquals(BackgroundExpressionFeedback.Phase.RUNNING, observe(actor, s).phase());
          assertNull(actor.view().result());
        }
        completion.run();
        completion.run();
        assertTrue(observe(actor, s).result().valid());
        assertEquals(1, actor.launches());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
        assertSame(issues, s.issues);
        assertEquals(revision, s.revision);
        assertEquals(history, s.document.historyBytes());
      }
    }
  }

  @Test
  void capturedTypesConstantsAndSourceAreImmutableAndAmbientShadowingIsPreserved() {
    try (var s = state()) {
      text(s, "worldHeight+é+bias");
      var captured = ExpressionFeedback.capture(s.project(), noise(s), item(s), null);
      assertEquals("worldHeight+é+bias", captured.source());
      var expected = new ExpressionFeedback().inspect(s.project(), noise(s), item(s));
      item(s).inputs.get("bias").type = "Boolean";
      item(s).inputs.get("bias").value = ProjectIO.JSON.valueToTree(true);
      s.project().project.minY--;
      text(s, "unknown");
      assertEquals(expected, ExpressionFeedback.inspectCaptured(captured));
      assertNotEquals(captured, ExpressionFeedback.capture(s.project(), noise(s), item(s), null));
      item(s).inputs.put("worldHeight", Binding.constant(4));
      item(s).inputs.put("worldMaxY", Binding.constant(5));
      var shadowed = ExpressionFeedback.capture(s.project(), noise(s), item(s), "worldHeight");
      s.project().project.maxY++;
      assertEquals(
          shadowed, ExpressionFeedback.capture(s.project(), noise(s), item(s), "worldHeight"));
    }
  }

  @Test
  void typingCoalescesAndQueuedOldResultsCannotReappearEvenAfterTextReturns() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        workers.run();
        var old = ui.remove();
        text(s, "x+unknown");
        assertNull(observe(actor, s).result());
        clock.addAndGet(100_000_000);
        text(s, "x+5");
        observe(actor, s);
        clock.addAndGet(100_000_000);
        assertEquals(1, actor.launches());
        observe(actor, s);
        old.run();
        assertNull(actor.view().result());
        clock.addAndGet(50_000_000);
        observe(actor, s);
        assertEquals(2, actor.launches());
        workers.run();
        var current = ui.remove();
        text(s, "x+bias");
        observe(actor, s);
        current.run();
        assertNull(actor.view().result());
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertTrue(actor.view().result().valid());
        assertEquals(3, actor.launches());
      }
    }
  }

  @Test
  void unchangedFramesReuseFeedbackButChangedTypesAndSharedValuesSupersedeIt() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      var parameter = new Parameter();
      parameter.id = "bias";
      parameter.value = ProjectIO.JSON.valueToTree(2);
      noise(s).sharedParameters.add(parameter);
      var binding = item(s).inputs.get("bias");
      binding.sourceKind = "Shared Parameter";
      binding.parameterUuid = parameter.uuid;
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        deliver(workers, ui);
        var result = observe(actor, s).result();
        for (int i = 0; i < 12; i++) assertSame(result, observe(actor, s).result());
        parameter.value = ProjectIO.JSON.valueToTree(3);
        assertNull(observe(actor, s).result());
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertNotSame(result, actor.view().result());
        item(s).resultType = "Boolean";
        assertNull(observe(actor, s).result());
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertFalse(actor.view().result().valid());
        assertEquals(0, actor.view().result().start());
        assertEquals("x+bias".length(), actor.view().result().end());
      }
    }
  }

  @Test
  void completionRechecksExactSemanticInputsEvenBeforeAnotherFrameObservesThem() {
    for (int change = 0; change < 4; change++)
      try (var s = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var clock = new AtomicLong();
        try (var actor = actor(s, workers, ui, clock)) {
          observe(actor, s);
          workers.run();
          switch (change) {
            case 0 -> text(s, "x+9");
            case 1 -> item(s).inputs.get("bias").value = ProjectIO.JSON.valueToTree(9);
            case 2 -> item(s).resultType = "Boolean";
            case 3 -> s.project().project.seed++;
          }
          ui.remove().run();
          assertEquals(BackgroundExpressionFeedback.Phase.STALE, actor.view().phase());
          assertNull(actor.view().result());
        }
      }
  }

  @Test
  void parserUtf16SpansRemainSeparateFromBindingRoutes() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      text(s, "é+\nunknown");
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        deliver(workers, ui);
        var error = actor.view().result();
        assertNull(error.input());
        assertEquals(3, error.start());
        assertEquals(10, error.end());
        assertEquals(
            new ExpressionSelection.Range(4, 11),
            ExpressionSelection.utf8Range("é+\nunknown", error.start(), error.end()));
        item(s).inputs.get("bias").value = null;
        observe(actor, s);
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertEquals("bias", actor.view().result().input());
      }
    }
  }

  @Test
  void schedulerWorkerAndHandoffFailuresRequireExplicitRetryAndKeepPrivateInput() {
    for (int failure = 0; failure < 3; failure++)
      try (var s = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var clock = new AtomicLong();
        var broken = new AtomicBoolean(true);
        int mode = failure;
        noise(s).id = " private-id ";
        s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        var draft = ProjectIO.JSON.valueToTree(s.draft);
        long history = s.document.historyBytes();
        try (var actor =
            new BackgroundExpressionFeedback(
                s,
                task -> {
                  if (mode == 0 && broken.get()) throw new RejectedExecutionException();
                  workers.execute(task);
                },
                task -> {
                  if (mode == 2 && broken.get()) throw new AssertionError("handoff unavailable");
                  ui.add(task);
                },
                clock::get,
                null,
                input -> {
                  if (mode == 1 && broken.get()) throw new AssertionError("worker unavailable");
                  return ExpressionFeedback.inspectCaptured(input);
                })) {
          observe(actor, s);
          if (mode != 0) {
            workers.run();
            if (!ui.isEmpty()) ui.remove().run();
          }
          assertTrue(actor.view().retryable());
          long launches = actor.launches();
          for (int i = 0; i < 12; i++) {
            clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
            observe(actor, s);
          }
          assertEquals(launches, actor.launches());
          broken.set(false);
          actor.retry();
          deliver(workers, ui);
          assertTrue(actor.view().result().valid());
          assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
          assertEquals(history, s.document.historyBytes());
          assertEquals("+", s.seedInputs.text("seedOffset"));
          assertEquals(
              1,
              s.events.snapshot().stream()
                  .filter(e -> e.code().equals("EXPRESSION_FEEDBACK_FAILED"))
                  .count());
        }
      }
  }

  @Test
  void externallyCancelledTaskBecomesRetryableInsteadOfRemainingPending() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        ((Future<?>) workers.tasks.getFirst()).cancel(false);
        assertTrue(actor.view().retryable());
        actor.retry();
        deliver(workers, ui);
        assertTrue(actor.view().result().valid());
      }
    }
  }

  @Test
  void retryCannotPublishFailedInputsAfterUnobservedEdits() {
    try (var s = state()) {
      var workers = new Workers();
      workers.reject = true;
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        text(s, "x+10");
        workers.reject = false;
        actor.retry();
        assertEquals(BackgroundExpressionFeedback.Phase.STALE, actor.view().phase());
        assertEquals(1, actor.launches());
        observe(actor, s);
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertTrue(actor.view().result().valid());
      }
    }
  }

  @Test
  void refreshedDraftRemovedItemAndNewDocumentRejectLateFeedback() {
    for (int change = 0; change < 3; change++)
      try (var s = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var clock = new AtomicLong();
        try (var actor = actor(s, workers, ui, clock)) {
          observe(actor, s);
          workers.run();
          switch (change) {
            case 0 -> s.refreshDraft();
            case 1 -> noise(s).processing.clear();
            case 2 -> s.newSession();
          }
          ui.remove().run();
          assertEquals(BackgroundExpressionFeedback.Phase.STALE, actor.view().phase());
          assertNull(actor.view().result());
        }
      }
  }

  @Test
  void reopeningSameUuidsRetiresPredecessorOwnership(@TempDir Path directory) throws Exception {
    try (var s = state()) {
      var path = directory.resolve("same.json");
      s.document.save(path);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      try (var actor = actor(s, workers, ui, clock)) {
        var old = noise(s);
        var oldItem = item(s);
        var id = s.project().project.uuid;
        observe(actor, s);
        workers.run();
        assertTrue(s.document.open(path).isEmpty());
        assertEquals(id, s.project().project.uuid);
        ui.remove().run();
        assertNull(actor.view().result());
        assertEquals(BackgroundExpressionFeedback.Phase.STALE, actor.view().phase());
        assertNull(actor.observe(old, oldItem).result());
      }
    }
  }

  @Test
  void cacheIsBoundedByEntryCountAndEstimatedBytesWithoutCompilingHugeFixtures() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      var valid = new ExpressionFeedback().inspect(s.project(), noise(s), item(s));
      try (var actor =
          new BackgroundExpressionFeedback(s, workers, ui::add, clock::get, null, input -> valid)) {
        for (int i = 0; i < 140; i++) {
          text(s, "x+" + i);
          observe(actor, s);
          clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
          observe(actor, s);
          deliver(workers, ui);
        }
        assertEquals(128, actor.cachedEntries());
        for (int i = 0; i < 20; i++) {
          text(s, " ".repeat(40_000) + i);
          observe(actor, s);
          clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
          observe(actor, s);
          deliver(workers, ui);
        }
        assertTrue(actor.cachedEntries() < 128);
        assertTrue(actor.cachedBytes() <= BackgroundExpressionFeedback.CACHE_BYTES);
        text(s, " ".repeat(400_000));
        observe(actor, s);
        clock.addAndGet(BackgroundExpressionFeedback.QUIET_NANOS);
        observe(actor, s);
        deliver(workers, ui);
        assertTrue(actor.view().result().valid());
        assertTrue(actor.cachedBytes() <= BackgroundExpressionFeedback.CACHE_BYTES);
      }
    }
  }

  @Test
  void cacheHitsAreImmediateButSuspendAndCloseCancelSuccessorPublication() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      var actor = actor(s, workers, ui, clock);
      observe(actor, s);
      deliver(workers, ui);
      var first = actor.view().result();
      text(s, "x+3");
      observe(actor, s);
      text(s, "x+bias");
      assertSame(first, observe(actor, s).result());
      assertEquals(1, actor.launches());
      actor.suspend();
      assertEquals(0, actor.cachedEntries());
      assertEquals(BackgroundExpressionFeedback.Phase.RUNNING, observe(actor, s).phase());
      workers.run();
      var late = ui.remove();
      actor.close();
      late.run();
      assertEquals(BackgroundExpressionFeedback.Phase.CLOSED, observe(actor, s).phase());
      assertNull(actor.view().result());
      assertEquals(0, actor.cachedBytes());
    }
  }

  @Test
  void expandedUseTextStagesExactBufferWhileFeedbackIsStillPending() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      var parent = item(s);
      var committed = ProjectIO.JSON.valueToTree(s.project());
      long revision = s.revision, history = s.document.historyBytes();
      try (var session =
          new ExpressionEditSession(s, noise(s), parent, workers, ui::add, clock::get)) {
        assertNull(session.feedback(s));
        workers.run();
        var late = ui.remove();
        session.edit("é+\nunknown");
        assertNull(session.feedback(s));
        session.stage(s, "é+\nunknown");
        late.run();
        assertTrue(session.closed());
        assertEquals("é+\nunknown", ExpressionFeedback.source(parent));
        assertEquals(committed, ProjectIO.JSON.valueToTree(s.project()));
        assertEquals(history, s.document.historyBytes());
        assertEquals(revision, s.revision);
        assertEquals(
            BackgroundExpressionFeedback.Phase.CLOSED, session.feedbackWork().view().phase());
      }
    }
  }

  @Test
  void expandedFailedFeedbackDoesNotBlockStagingAndCancelDoesNotStage() {
    try (var s = state()) {
      var workers = new Workers();
      workers.reject = true;
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      try (var session =
          new ExpressionEditSession(s, noise(s), item(s), workers, ui::add, clock::get)) {
        assertNull(session.feedback(s));
        assertTrue(session.feedbackRetryable());
        session.edit("x+6");
        session.stage(s, "x+6");
        assertEquals("x+6", ExpressionFeedback.source(item(s)));
      }
      try (var cancelled =
          new ExpressionEditSession(s, noise(s), item(s), workers, ui::add, clock::get)) {
        cancelled.edit("x+9");
        cancelled.feedback(s);
        cancelled.cancel();
        assertEquals("x+6", ExpressionFeedback.source(item(s)));
      }
    }
  }

  @Test
  void semanticSupersessionCancelsErrorSelectionEvenWhenSourceTextIsIdentical() {
    try (var s = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var clock = new AtomicLong();
      text(s, "é+unknown");
      try (var actor = actor(s, workers, ui, clock)) {
        observe(actor, s);
        deliver(workers, ui);
        var selection = new ExpressionSelectionCallback();
        selection.context(
            s.project(), s.draft, actor.selectionOwner(), ExpressionFeedback.source(item(s)));
        selection.request(ExpressionFeedback.source(item(s)), actor.view().result());
        assertTrue(selection.pending());
        item(s).inputs.put("unknown", Binding.constant(1));
        observe(actor, s);
        selection.context(
            s.project(), s.draft, actor.selectionOwner(), ExpressionFeedback.source(item(s)));
        assertFalse(selection.pending());
        assertNull(selection.applied());
      }
    }
  }
}
