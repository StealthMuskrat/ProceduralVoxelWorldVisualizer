package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.*;
import io.voxelcreator.core.NoiseAuthoring.*;
import io.voxelcreator.model.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred current-control ownership of private structural previews and subsequent staging. */
class ReadyNoiseEditTest {
  @TempDir Path directory;

  static Request unused(EditorState s) {
    return Request.simple(Action.DELETE_PARAMETER, BackgroundNoiseEditTest.noise(s).sharedParameters.getLast().uuid);
  }

  static Runnable deliver(BackgroundNoiseEditTest.Workers worker, Queue<Runnable> ui) {
    worker.run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void readinessKeepsVersionAndOldFeedbackUntilAcceptanceThenOnlyPrivateStageAndParentApplyChangeValues() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var n = BackgroundNoiseEditTest.noise(s); n.id = "private"; s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s);
      var before = ProjectIO.JSON.valueToTree(s.project()); long history = s.document.historyBytes();
      try (var edit = BackgroundNoiseEditTest.session(s, worker, ui)) {
        long version = edit.version(); var callback = deliver(worker, ui); callback.run();
        assertEquals(version, edit.version()); assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase()); assertNull(edit.preview()); assertFalse(edit.canStage());
        assertThrows(IllegalStateException.class, edit::stage); assertTrue(owner.valuesCurrent(s)); edit.acceptPrepared();
        var old = edit.preview(); assertTrue(edit.ready()); assertFalse(old.valid()); edit.choose(Resolution.INLINE_PARAMETER, null);
        version = edit.version(); callback = deliver(worker, ui); assertSame(old, edit.preview()); assertEquals(version, edit.version()); assertFalse(edit.canStage());
        edit.acceptPrepared(); var fresh = edit.preview(); callback.run(); edit.acceptPrepared(); assertSame(fresh, edit.preview()); assertTrue(edit.canStage());
        assertTrue(owner.valuesCurrent(s)); assertEquals(history, s.document.historyBytes()); edit.stage(); assertSame(n, s.draft); assertTrue(edit.closed());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(history, s.document.historyBytes()); assertEquals("-", s.seedInputs.text("seedOffset"));
        assertFalse(s.applyDraft()); s.seedInputs.edit(s, "seedOffset", "Seed", "0"); assertTrue(s.applyDraft());
        assertEquals(12, new NoiseCompiler(s.project(), 0).compile(n.uuid).sample(-3, 0, 7));
        var after = ProjectIO.JSON.valueToTree(s.project()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        s.document.redo(); assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void readyValidInvalidFatalAndNullPlansCanCloseWithoutAdoptionOrFailureFeedback() {
    for (int mode = 0; mode < 4; mode++) try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); int action = mode;
      var request = mode == 0 ? unused(s) : BackgroundNoiseEditTest.request(s); var owner = new ProjectFileOwner(s); int events = s.events.snapshot().size();
      try (var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), request, worker, ui::add, (p, n, r) -> {
        if (action == 2) throw new AssertionError("planner"); if (action == 3) return null; return NoiseAuthoring.plan(p, n, r);
      })) {
        var callback = deliver(worker, ui); assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase()); assertNull(edit.preview());
        edit.cancel(); callback.run(); edit.acceptPrepared(); assertTrue(edit.closed()); assertNull(edit.preview());
        assertTrue(owner.valuesCurrent(s)); assertEquals(events, s.events.snapshot().size());
      }
    }
  }

  @Test
  void anOlderReadyResolutionCannotReplaceTheLatestDesiredChoiceOrItsCandidate() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundNoiseEditTest.session(s, worker, ui)) {
        deliver(worker, ui); edit.acceptPrepared(); var old = edit.preview(); edit.choose(Resolution.INLINE_PARAMETER, null); var callback = deliver(worker, ui);
        var replacement = BackgroundNoiseEditTest.noise(s).sharedParameters.getLast().uuid; edit.choose(Resolution.REPLACE, replacement); long version = edit.version();
        callback.run(); edit.acceptPrepared(); assertEquals(version, edit.version()); assertSame(old, edit.preview()); assertFalse(edit.canStage());
        assertEquals(Resolution.REPLACE, edit.resolution()); assertEquals(replacement, edit.replacement()); deliver(worker, ui); edit.acceptPrepared();
        assertTrue(edit.canStage()); edit.stage(); assertEquals(replacement, BackgroundNoiseEditTest.noise(s).processing.getFirst().inputs.get("b").parameterUuid);
        assertEquals(2, s.project().noise2D.getFirst().sharedParameters.size());
      }
    }
  }

  @Test
  void readyProjectDraftRawKindSelectionIdentityAndLifetimeChangesCannotAdoptOldPlans() throws Exception {
    for (int mode = 0; mode < 8; mode++) try (var s = BackgroundNoiseEditTest.state()) {
      var path = directory.resolve("same-" + mode + ".json"); if (mode == 7) s.document.save(path);
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundNoiseEditTest.session(s, worker, ui)) {
        deliver(worker, ui);
        if (mode == 0) s.project().project.seed++; else if (mode == 1) BackgroundNoiseEditTest.noise(s).id = "new-private";
        else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+"); else if (mode == 3) s.selectedKind = "noise3D";
        else if (mode == 4) s.multiSelection.clear(); else if (mode == 5) BackgroundNoiseEditTest.noise(s).uuid = UUID.randomUUID();
        else if (mode == 6) s.newSession(); else assertTrue(s.document.open(path).isEmpty());
        var project = ProjectIO.JSON.valueToTree(s.project()); var draft = ProjectIO.JSON.valueToTree(s.draft); edit.acceptPrepared();
        assertEquals(NoiseEditSession.Phase.STALE, edit.phase()); assertFalse(edit.canStage()); assertFalse(edit.canRetry()); assertNull(edit.preview());
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project())); assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
      }
    }
  }

  @Test
  void unavailableOpeningRetiresReadyWorkAndRequiresCancelAndReopening() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s);
      try (var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, ui::add)) {
        deliver(worker, ui); edit.acceptPrepared(() -> false); assertEquals(NoiseEditSession.Phase.STALE, edit.phase()); assertTrue(worker.tasks.isEmpty());
        assertFalse(edit.canRetry()); assertTrue(owner.valuesCurrent(s)); edit.cancel();
      }
      try (var next = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, ui::add)) {
        deliver(worker, ui); next.acceptPrepared(); assertTrue(next.canStage()); assertTrue(owner.valuesCurrent(s));
      }
    }
  }

  @Test
  void acceptedPlannerFailureKeepsPriorFeedbackAndDesiredResolutionForExplicitRetry() {
    for (boolean nullPlan : new boolean[] {false, true}) try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var broken = new AtomicBoolean(false);
      try (var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), BackgroundNoiseEditTest.request(s), worker, ui::add, (p, n, r) -> {
        if (broken.get()) { if (nullPlan) return null; throw new AssertionError("planner"); } return NoiseAuthoring.plan(p, n, r);
      })) {
        deliver(worker, ui); edit.acceptPrepared(); var old = edit.preview(); broken.set(true); edit.choose(Resolution.INLINE_PARAMETER, null);
        var callback = deliver(worker, ui); assertSame(old, edit.preview()); assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase()); edit.acceptPrepared(); callback.run();
        assertTrue(edit.canRetry()); assertSame(old, edit.preview()); assertEquals(Resolution.INLINE_PARAMETER, edit.resolution()); assertTrue(worker.tasks.isEmpty());
        assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("NOISE_EDIT_PREVIEW_FAILED")).count());
        broken.set(false); edit.retry(); deliver(worker, ui); edit.acceptPrepared(); assertTrue(edit.canStage()); assertFalse(edit.closed());
      }
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffOrExecutorFaultCannotInstallAnUnacceptedPreview() {
    for (boolean executor : new boolean[] {false, true}) try (var s = BackgroundNoiseEditTest.state()) {
      var once = new AtomicBoolean(true); Executor worker = task -> { task.run(); if (executor && once.getAndSet(false)) throw new AssertionError("executor"); };
      try (var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, callback -> {
        callback.run(); if (!executor && once.getAndSet(false)) throw new AssertionError("handoff");
      })) {
        edit.acceptPrepared(); assertTrue(edit.canRetry()); assertNull(edit.preview()); assertFalse(edit.canStage());
        assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("NOISE_EDIT_PREVIEW_FAILED")).count());
        edit.retry(); edit.acceptPrepared(); assertTrue(edit.canStage());
      }
    }
  }

  @Test
  void presentationCallbackCannotAuthorizeTheInlineReadySuccessorItCreates() {
    try (var s = BackgroundNoiseEditTest.state(); var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), BackgroundNoiseEditTest.request(s), Runnable::run, Runnable::run)) {
      var owner = new ProjectFileOwner(s); edit.acceptPrepared(() -> { edit.choose(Resolution.INLINE_PARAMETER, null); return true; });
      assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase()); assertNull(edit.preview()); assertTrue(owner.valuesCurrent(s));
      edit.acceptPrepared(); assertTrue(edit.canStage()); assertTrue(owner.valuesCurrent(s));
    }
  }

  @Test
  void aThrowingPresentationCallbackCannotRetireItsReadySuccessor() {
    try (var s = BackgroundNoiseEditTest.state(); var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), BackgroundNoiseEditTest.request(s), Runnable::run, Runnable::run)) {
      int events = s.events.snapshot().size(); edit.acceptPrepared(() -> { edit.choose(Resolution.INLINE_PARAMETER, null); throw new IllegalStateException("presentation"); });
      assertEquals(NoiseEditSession.Phase.RUNNING, edit.phase()); assertNull(edit.preview()); assertEquals(events, s.events.snapshot().size());
      edit.acceptPrepared(); assertTrue(edit.canStage());
    }
  }

  @Test
  void duplicateAllocationIsOwnedOnceAcrossReadinessRepeatedDeliveryAndPrivateStage() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var n = BackgroundNoiseEditTest.noise(s); var original = n.sharedParameters.getFirst().uuid;
      try (var edit = new NoiseEditSession(s, n, Request.simple(Action.DUPLICATE_PARAMETER, original), worker, ui::add)) {
        var callback = deliver(worker, ui); assertNull(edit.preview()); edit.acceptPrepared(); var plan = edit.preview(); var selected = plan.selected();
        callback.run(); edit.acceptPrepared(); assertSame(plan, edit.preview()); assertNotEquals(original, selected); plan.candidate().sharedParameters.clear();
        assertEquals(selected, edit.stage()); assertEquals(3, n.sharedParameters.size()); assertEquals(original, n.sharedParameters.getFirst().uuid);
        assertEquals(2, s.project().noise2D.getFirst().sharedParameters.size()); assertThrows(IllegalStateException.class, edit::stage);
      }
    }
  }

  @Test
  void closedPredecessorCannotConsumeOrClearTheNextOpeningPreview() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var first = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, ui::add); var callback = deliver(worker, ui); first.close();
      try (var next = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, ui::add)) {
        callback.run(); next.acceptPrepared(); assertEquals(NoiseEditSession.Phase.RUNNING, next.phase()); assertNull(next.preview());
        deliver(worker, ui); callback.run(); next.acceptPrepared(); assertTrue(next.canStage()); assertTrue(first.closed());
      }
    }
  }

  @Test
  void acceptedPreviewStillRepeatsCompletePrivateSourceChecksAtStage() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = new NoiseEditSession(s, BackgroundNoiseEditTest.noise(s), unused(s), worker, ui::add)) {
        deliver(worker, ui); edit.acceptPrepared(); assertTrue(edit.canStage()); BackgroundNoiseEditTest.noise(s).sharedParameters.getFirst().value = ProjectIO.JSON.valueToTree(9);
        var changed = ProjectIO.JSON.valueToTree(s.draft); assertThrows(IllegalStateException.class, edit::stage);
        assertEquals(changed, ProjectIO.JSON.valueToTree(s.draft)); assertEquals(NoiseEditSession.Phase.STALE, edit.phase()); assertFalse(edit.canStage());
      }
    }
  }

  @Test
  void automaticInitialActionWaitsForCurrentCancellationThenStagesOnceBeforeThrowingFocusFeedback() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var n = BackgroundNoiseEditTest.noise(s); var owner = new ProjectFileOwner(s);
      try (var dialog = new NoiseEditDialog(s, n, unused(s), id -> fail("cancelled focus"), worker, ui::add, true)) {
        var shown = dialog.capture(); deliver(worker, ui); assertTrue(dialog.decide(shown, false, true, false, false)); assertTrue(owner.valuesCurrent(s));
      }
      var focus = new AtomicInteger(); var before = ProjectIO.JSON.valueToTree(s.project()); long history = s.document.historyBytes();
      try (var dialog = new NoiseEditDialog(s, n, unused(s), id -> { focus.incrementAndGet(); throw new AssertionError("focus"); }, worker, ui::add, true)) {
        var shown = dialog.capture(); deliver(worker, ui); assertTrue(dialog.decide(shown, false, false, false, false));
        assertEquals(1, focus.get()); assertEquals(1, n.sharedParameters.size()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(history, s.document.historyBytes());
        assertTrue(dialog.decide(shown, true, false, false, false)); assertEquals(1, focus.get());
        assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("NOISE_EDIT_FOCUS_FAILED")).count());
      }
    }
  }

  @Test
  void explicitResolutionNeedsItsAcceptedDisplayedPlanAndRejectsForeignOpeningOrHeldApply() {
    try (var s = BackgroundNoiseEditTest.state()) {
      var worker = new BackgroundNoiseEditTest.Workers(); var ui = new ArrayDeque<Runnable>(); var n = BackgroundNoiseEditTest.noise(s); var focus = new AtomicInteger();
      var first = new NoiseEditDialog(s, n, BackgroundNoiseEditTest.request(s), id -> fail("old focus"), worker, ui::add, true);
      var foreign = first.capture(); first.close();
      try (var dialog = new NoiseEditDialog(s, n, BackgroundNoiseEditTest.request(s), id -> focus.incrementAndGet(), worker, ui::add, true)) {
        assertFalse(dialog.decide(foreign, true, true, false, false)); var shown = dialog.capture(); deliver(worker, ui); dialog.decide(shown, false, false, false, false);
        assertTrue(dialog.session().ready()); assertFalse(dialog.session().canStage()); var old = dialog.capture(); dialog.choose(Resolution.INLINE_PARAMETER, null); deliver(worker, ui);
        assertFalse(dialog.decide(old, true, false, false, false)); assertEquals(NoiseEditSession.Phase.RUNNING, dialog.session().phase());
        var pending = dialog.capture(); assertFalse(dialog.decide(pending, true, false, false, false)); assertTrue(dialog.session().canStage()); assertEquals(0, focus.get());
        assertFalse(dialog.decide(pending, true, false, false, false)); assertTrue(dialog.decide(dialog.capture(), true, false, false, false));
        assertEquals(1, focus.get()); assertEquals(1, n.sharedParameters.size()); assertEquals(2, s.project().noise2D.getFirst().sharedParameters.size());
      }
    }
  }
}
