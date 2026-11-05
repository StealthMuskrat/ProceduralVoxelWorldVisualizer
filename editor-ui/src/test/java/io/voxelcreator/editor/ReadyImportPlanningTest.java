package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred current-control acceptance of choice/reload previews, distinct from final Import. */
class ReadyImportPlanningTest {
  static Runnable deliver(ImportPreviewPlanningTest.Workers worker, Queue<Runnable> ui) {
    worker.run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void readinessKeepsTheOldRowsAndRequestUntilOneAcceptedUnacknowledgedPlan() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview(); edit.markShown(old);
      var owner = new ProjectFileOwner(s); var before = ProjectIO.JSON.valueToTree(s.project()); long bytes = s.document.historyBytes();
      edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); var request = edit.planningToken();
      var callback = deliver(worker, ui); callback.run(); edit.markShown(old);
      assertSame(request, edit.planningToken()); assertTrue(edit.planning()); assertSame(old, edit.preview());
      assertFalse(edit.canApply()); assertFalse(edit.canRetryPlan()); assertEquals(ImportEditSession.Result.NOT_REVIEWED, edit.apply());
      assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes());
      edit.acceptPlanning(); var fresh = edit.preview(); callback.run(); edit.acceptPlanning();
      assertSame(fresh, edit.preview()); assertNotSame(old, fresh); assertFalse(edit.planning()); assertFalse(edit.canApply());
      assertEquals(old.rows().get(1).targetUuid(), fresh.rows().get(1).targetUuid());
      edit.markShown(old); assertFalse(edit.canApply()); edit.markShown(fresh); assertTrue(edit.canApply());
      assertEquals(ImportEditSession.Result.STARTED, edit.apply()); worker.run(); ui.remove().run(); edit.acceptPrepared();
      assertEquals(3, s.project().noise2D.size()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo(); assertEquals(3, s.project().noise2D.size());
    }
  }

  @Test
  void readySuccessAndCapturedPlannerFailureCanCloseWithoutReplacingRowsOrReporting() {
    for (boolean failure : new boolean[] {false, true}) try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview();
      if (failure) {
        // Deliberately malformed raw destination: its graph traversal fails inside the worker.
        s.project().noise2D.add(null); edit.reload();
      } else edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE);
      var owner = new ProjectFileOwner(s); int events = s.events.snapshot().size(); var callback = deliver(worker, ui);
      assertTrue(edit.planning()); assertSame(old, edit.preview()); assertEquals(events, s.events.snapshot().size());
      edit.cancel(); callback.run(); edit.acceptPlanning();
      assertTrue(edit.closed()); assertFalse(edit.planning()); assertSame(old, edit.preview());
      assertTrue(owner.valuesCurrent(s)); assertEquals(events, s.events.snapshot().size());
    }
  }

  @Test
  void acceptedPlannerFailureKeepsRowsAndChoicesForExplicitCorrectedReload() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview();
      s.project().noise2D.add(null); edit.reload(); var callback = deliver(worker, ui);
      assertTrue(edit.planning()); assertSame(old, edit.preview()); edit.acceptPlanning(); callback.run();
      assertSame(old, edit.preview()); assertTrue(edit.canRetryPlan()); assertFalse(edit.canApply());
      assertTrue(edit.planningMessage().contains("Preview rebuild failed"));
      assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("IMPORT_PREVIEW_FAILED")).count());
      s.project().noise2D.removeLast(); edit.retryPlan(); deliver(worker, ui); edit.acceptPlanning();
      assertNotSame(old, edit.preview()); assertFalse(edit.canApply()); edit.markShown(edit.preview()); assertTrue(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void aReadyChoiceCannotOverwriteNewDesiredChoicesOrRegenerateTheirCopyAllocation() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview();
      var shared = old.rows().getFirst().uuid(); var added = old.rows().get(1).uuid();
      edit.choose(shared, ResourceImport.Choice.REUSE); var callback = deliver(worker, ui);
      edit.choose(added, ResourceImport.Choice.SKIP); var request = edit.planningToken(); callback.run(); edit.acceptPlanning();
      assertSame(request, edit.planningToken()); assertSame(old, edit.preview()); assertTrue(edit.planning());
      assertEquals(ResourceImport.Choice.REUSE, edit.choice(shared)); assertEquals(ResourceImport.Choice.SKIP, edit.choice(added));
      deliver(worker, ui); edit.acceptPlanning(); assertEquals("SKIP (including dependent closure)", edit.preview().rows().get(1).resolution());
      edit.choose(added, ResourceImport.Choice.COPY); deliver(worker, ui); edit.acceptPlanning();
      assertEquals(old.rows().get(1).targetUuid(), edit.preview().rows().get(1).targetUuid()); assertFalse(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void readyReloadRetainsChoicesButInstallsNewCopyTargetsOnlyAfterAcceptanceAndDisplay() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui);
      edit.choose(ImportPreviewPlanningTest.id(edit), ResourceImport.Choice.REUSE); deliver(worker, ui); edit.acceptPlanning();
      var old = edit.preview(); edit.markShown(old); assertTrue(s.change(p -> p.project.seed = 18)); edit.reload();
      var callback = deliver(worker, ui); assertSame(old, edit.preview()); assertFalse(edit.canApply());
      edit.acceptPlanning(); var fresh = edit.preview(); callback.run();
      assertNotEquals(old.rows().get(1).targetUuid(), fresh.rows().get(1).targetUuid());
      assertEquals(18, fresh.candidate().project.seed); assertEquals(ResourceImport.Choice.REUSE, edit.choice(fresh.rows().getFirst().uuid()));
      edit.markShown(old); assertFalse(edit.canApply()); edit.markShown(fresh); assertTrue(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void readyCommittedPrivateRawSelectionAndLifetimeChangesPreserveNewerValues() {
    for (int mode = 0; mode < 5; mode++) try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview();
      edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); deliver(worker, ui);
      if (mode == 0) s.project().project.seed++; else if (mode == 1) ((Noise) s.draft).id = "new-private";
      else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
      else if (mode == 3) s.multiSelection.clear(); else s.newSession();
      var owner = new ProjectFileOwner(s); edit.acceptPlanning();
      assertSame(old, edit.preview()); assertFalse(edit.planning()); assertFalse(edit.canApply()); assertTrue(owner.valuesCurrent(s)); edit.cancel();
    }
  }

  @Test
  void unavailableOpeningRetiresReadyWorkAndRequiresAnExplicitPreviewRetry() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var worker = new ImportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ImportPreviewPlanningTest.session(s, worker, ui); var old = edit.preview();
      edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); deliver(worker, ui); edit.acceptPlanning(() -> false);
      assertSame(old, edit.preview()); assertTrue(edit.canRetryPlan()); assertTrue(worker.tasks.isEmpty());
      edit.retryPlan(); deliver(worker, ui); edit.acceptPlanning(); assertNotSame(old, edit.preview()); assertFalse(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void blockerCannotInstallOrRetireTheReadySuccessorItCreates() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var replace = new AtomicBoolean(false); var holder = new ImportEditSession[1];
      var edit = new ImportEditSession(s, ImportPreviewPlanningTest.incoming(), Runnable::run, Runnable::run, () -> {
        if (replace.getAndSet(false)) holder[0].choose(holder[0].preview().rows().get(1).uuid(), ResourceImport.Choice.SKIP);
        return false;
      });
      holder[0] = edit; var old = edit.preview(); edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE);
      replace.set(true); edit.acceptPlanning(); assertTrue(edit.planning()); assertSame(old, edit.preview());
      edit.acceptPlanning(); assertFalse(edit.planning()); assertNotSame(old, edit.preview()); assertFalse(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void presentationCallbackCannotAuthorizeTheReadySuccessorItCreates() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var edit = new ImportEditSession(s, ImportPreviewPlanningTest.incoming(), Runnable::run, Runnable::run, () -> false);
      var old = edit.preview(); edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); var once = new AtomicBoolean(true);
      edit.acceptPlanning(() -> {
        if (once.getAndSet(false)) edit.choose(old.rows().get(1).uuid(), ResourceImport.Choice.SKIP); return true;
      });
      assertTrue(edit.planning()); assertSame(old, edit.preview()); edit.acceptPlanning(); assertNotSame(old, edit.preview()); edit.cancel();
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureRetainsOldRowsAndExplicitRetry() {
    try (var s = ImportPreviewPlanningTest.state()) {
      var fail = new AtomicBoolean(true);
      var edit = new ImportEditSession(s, ImportPreviewPlanningTest.incoming(), Runnable::run, callback -> {
        callback.run(); if (fail.getAndSet(false)) throw new AssertionError("late handoff");
      }, () -> false);
      var old = edit.preview(); edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); edit.acceptPlanning();
      assertSame(old, edit.preview()); assertTrue(edit.canRetryPlan());
      assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("IMPORT_PREVIEW_FAILED")).count());
      edit.retryPlan(); edit.acceptPlanning(); assertNotSame(old, edit.preview()); assertFalse(edit.canApply()); edit.cancel();
    }
  }

  @Test
  void closedOpeningCannotConsumeItsReadyReplacementReview() {
    try (var s = ImportPreviewPlanningTest.state();
        var panel = new ResourceImportPanel(s, () -> false, () -> {}, Runnable::run, Runnable::run)) {
      assertTrue(panel.open(ImportPreviewPlanningTest.incoming())); var first = panel.session();
      first.choose(ImportPreviewPlanningTest.id(first), ResourceImport.Choice.REUSE);
      var shown = panel.capture(1); var request = first.planningToken(); panel.close();
      assertTrue(panel.open(ImportPreviewPlanningTest.incoming())); var next = panel.session(); var plan = next.preview();
      next.choose(plan.rows().getFirst().uuid(), ResourceImport.Choice.REUSE); panel.acceptPlanning(shown, request);
      assertSame(plan, next.preview()); assertTrue(next.planning());
      panel.acceptPlanning(panel.capture(2), next.planningToken()); assertNotSame(plan, next.preview()); assertFalse(next.canApply());
    }
  }

  @Test
  void aChoiceMadeInTheCurrentFrameCannotAcceptItsInlineResultAsTheOldShownWork() {
    try (var s = ImportPreviewPlanningTest.state();
        var panel = new ResourceImportPanel(s, () -> false, () -> {}, Runnable::run, Runnable::run)) {
      assertTrue(panel.open(ImportPreviewPlanningTest.incoming())); var edit = panel.session(); var old = edit.preview();
      var shown = panel.capture(1); var request = edit.planningToken(); edit.choose(old.rows().getFirst().uuid(), ResourceImport.Choice.REUSE);
      panel.acceptPlanning(shown, request); assertTrue(edit.planning()); assertSame(old, edit.preview());
      panel.acceptPlanning(panel.capture(2), edit.planningToken()); assertFalse(edit.planning()); assertNotSame(old, edit.preview());
      assertFalse(edit.canApply());
    }
  }
}
