package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred ready-frame Import ownership; private review and committed resources stay distinct. */
class ReadyImportApplyTest {
  @TempDir Path directory;

  @Test
  void deliveryKeepsStableAttemptAndCopyAllocationUntilOneAcceptedImport() {
    try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false);
      var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
      var privateValues = ProjectIO.JSON.valueToTree(draft); var user = ProjectIO.JSON.valueToTree(s.user);
      var plan = edit.preview(); var target = plan.rows().getFirst().targetUuid();
      long history = s.document.historyBytes(), raw = s.seedInputs.version(s);
      edit.apply(); long work = edit.applyVersion(); worker.run(); var delivered = ui.remove(); delivered.run(); delivered.run();
      assertTrue(edit.busy()); assertFalse(edit.closed()); assertEquals(work, edit.applyVersion());
      assertEquals(ImportEditSession.ApplyPhase.RUNNING, edit.applyPhase()); assertSame(plan, edit.preview());
      assertSame(draft, s.draft); assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(history, s.document.historyBytes());
      edit.acceptPrepared(); delivered.run(); edit.acceptPrepared();
      assertTrue(edit.closed()); assertEquals(ImportEditSession.ApplyPhase.APPLIED, edit.applyPhase());
      assertEquals(target, s.project().noise2D.getLast().uuid); assertSame(draft, s.draft);
      assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft)); assertEquals(raw, s.seedInputs.version(s));
      assertEquals(user, ProjectIO.JSON.valueToTree(s.user));
      var after = ProjectIO.JSON.valueToTree(s.project()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      s.document.redo(); assertEquals(after, ProjectIO.JSON.valueToTree(s.project()));
    }
  }

  @Test
  void readyCancelRetainsExactPrivateInputAndBothHistoryStacks() {
    try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false); var owner = new ProjectFileOwner(s);
      long bytes = s.document.historyBytes(); edit.apply(); worker.run(); var delivered = ui.remove(); delivered.run();
      edit.cancel(); delivered.run(); edit.acceptPrepared();
      assertTrue(edit.closed()); assertFalse(edit.busy()); assertEquals(ImportEditSession.ApplyPhase.CANCELLED, edit.applyPhase());
      assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes()); assertFalse(s.document.canRedo());
    }
  }

  @Test
  void readyRejectedAndFatalResultsCanBeCancelledBeforeInstallingFeedback() {
    for (boolean fatal : new boolean[] {false, true}) try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      s.document.setValidator(p -> {
        if (fatal) throw new AssertionError("worker");
        return List.of(Diagnostic.error("REJECT", "/", "rejected"));
      });
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false);
      var issues = s.issues; var scope = s.validationScope; long events = s.events.snapshot().size();
      edit.apply(); worker.run(); ui.remove().run(); assertTrue(edit.busy()); assertEquals(0, edit.issueCount());
      assertEquals(ImportEditSession.ApplyPhase.RUNNING, edit.applyPhase());
      edit.cancel(); edit.acceptPrepared(); assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
      assertEquals(events, s.events.snapshot().size()); assertEquals(2, s.project().noise2D.size());
    }
  }

  @Test
  void acceptedRejectionRetainsImmutableFeedbackAndTheReviewedCopyForExplicitRetry() {
    try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var diagnostics = new ArrayList<Diagnostic>(); diagnostics.add(Diagnostic.error("REJECT", "/", "rejected"));
      s.document.setValidator(p -> diagnostics);
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false); var plan = edit.preview();
      var target = plan.rows().getFirst().targetUuid(); var issues = s.issues;
      edit.apply(); worker.run(); ui.remove().run(); assertSame(issues, s.issues); edit.acceptPrepared();
      assertEquals(ImportEditSession.ApplyPhase.REJECTED, edit.applyPhase()); diagnostics.clear();
      assertEquals(1, edit.issueCount()); assertEquals(1, edit.applyIssues().size()); assertEquals(1, s.issues.size());
      assertTrue(edit.canApply()); assertSame(plan, edit.preview());
      edit.apply(); worker.run(); ui.remove().run(); edit.acceptPrepared();
      assertTrue(edit.closed()); assertEquals(target, s.project().noise2D.getLast().uuid);
    }
  }

  @Test
  void readySourceCheckpointPolicyAndHistoryChangesCannotPublishOldImport() throws Exception {
    for (int mode = 0; mode < 4; mode++) try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var path = directory.resolve("import.json"); s.document.save(path);
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false);
      edit.apply(); worker.run(); ui.remove().run();
      if (mode == 0) s.document.save(path); else if (mode == 1) s.document.setValidator(NoiseCompiler::validate);
      else if (mode == 2) s.project().project.seed++; else { s.document.undo(); s.document.redo(); }
      var owner = new ProjectFileOwner(s); edit.acceptPrepared();
      assertFalse(edit.busy()); assertFalse(edit.closed()); assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
      assertTrue(owner.valuesCurrent(s)); assertFalse(edit.canApply());
    }
  }

  @Test
  void readyPrivateRawSelectionAndKindChangesPreserveTheirNewerAuthority() {
    for (int mode = 0; mode < 4; mode++) try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false); edit.apply(); worker.run(); ui.remove().run();
      if (mode == 0) ((Noise) s.draft).id = "new-private";
      else if (mode == 1) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
      else if (mode == 2) s.select("noise2D", s.project().noise2D.getLast().uuid); else s.selectedKind = "noise3D";
      var owner = new ProjectFileOwner(s); String kind = s.selectedKind; edit.acceptPrepared();
      assertFalse(edit.busy()); assertEquals(ImportEditSession.ApplyPhase.STALE, edit.applyPhase());
      assertTrue(owner.valuesCurrent(s)); assertEquals(kind, s.selectedKind); assertEquals(2, s.project().noise2D.size());
    }
  }

  @Test
  void readyDiagnosticIdentityContentsAndScopeCannotOverwriteNewerFeedback() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var shared = new ArrayList<Diagnostic>(); s.issues = shared;
      var edit = BackgroundImportApplyTest.session(s, worker, ui, () -> false); edit.apply(); worker.run(); ui.remove().run();
      var newer = Diagnostic.error("NEW", "/", "newer");
      if (mode == 0) s.issues = List.of(newer); else if (mode == 1) shared.add(newer); else s.validationScope = "newer";
      var issues = s.issues; var scope = s.validationScope; edit.acceptPrepared();
      assertFalse(edit.busy()); assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertEquals(2, s.project().noise2D.size());
    }
  }

  @Test
  void finalBlockerCanReplaceTheReviewWithoutAuthorizingItsReadySuccessor() {
    try (var s = BackgroundImportApplyTest.state()) {
      var next = new AtomicReference<Runnable>();
      var panel = new ResourceImportPanel(s, () -> { var action = next.getAndSet(null); if (action != null) action.run(); return false; },
          () -> {}, Runnable::run, Runnable::run);
      assertTrue(panel.open(BackgroundImportApplyTest.incoming())); var first = panel.session();
      first.markShown(first.preview()); first.apply();
      next.set(() -> {
        panel.cancel(); assertTrue(panel.open(BackgroundImportApplyTest.incoming()));
        var successor = panel.session(); successor.markShown(successor.preview()); successor.apply();
      });
      first.acceptPrepared(); var successor = panel.session();
      assertNotSame(first, successor); assertEquals(ImportEditSession.ApplyPhase.CANCELLED, first.applyPhase());
      assertTrue(successor.busy()); assertEquals(2, s.project().noise2D.size());
      successor.acceptPrepared(); assertTrue(successor.closed()); assertEquals(3, s.project().noise2D.size()); panel.close();
    }
  }

  @Test
  void readyPredecessorDeliveryCannotPopulateOrConsumeANewReview() {
    try (var s = BackgroundImportApplyTest.state()) {
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var first = BackgroundImportApplyTest.session(s, worker, ui, () -> false);
      first.apply(); worker.run(); var old = ui.remove(); old.run(); first.cancel();
      var successor = BackgroundImportApplyTest.session(s, worker, ui, () -> false); var plan = successor.preview();
      successor.apply(); old.run(); successor.acceptPrepared();
      assertTrue(successor.busy()); assertSame(plan, successor.preview()); assertEquals(2, s.project().noise2D.size());
      worker.run(); ui.remove().run(); old.run(); successor.acceptPrepared(); assertTrue(successor.closed());
    }
  }

  @Test
  void readyAllSkipNoopConsumesOnceWithoutDroppingRedoOrPrivateInput() {
    try (var s = BackgroundImportApplyTest.state()) {
      s.change(p -> p.project.rootOrder.put("noise2D", p.noise2D.stream().map(n -> n.uuid).toList()));
      s.change(p -> p.project.seed++); s.document.undo(); s.revision++; s.refreshDraft();
      ((Noise) s.draft).id = "private"; s.seedInputs.edit(s, "seedOffset", "Seed", "-");
      var worker = new BackgroundImportApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var incoming = BackgroundImportApplyTest.incoming();
      var edit = new ImportEditSession(s, incoming, worker, ui::add, () -> false);
      edit.choose(incoming.noise2D.getFirst().uuid, ResourceImport.Choice.SKIP); worker.run(); ui.remove().run(); edit.acceptPlanning();
      edit.markShown(edit.preview()); var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
      long bytes = s.document.historyBytes(), raw = s.seedInputs.version(s);
      edit.apply(); worker.run(); var delivered = ui.remove(); delivered.run();
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); edit.acceptPrepared(); delivered.run(); edit.acceptPrepared();
      assertTrue(edit.closed()); assertTrue(s.document.canRedo()); assertEquals(bytes, s.document.historyBytes());
      assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertSame(draft, s.draft); assertEquals(raw, s.seedInputs.version(s));
    }
  }
}
