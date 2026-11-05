package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred acceptance definitions for the member transaction and private curve staging. */
class ReadyChildApplyTest {
  @TempDir Path directory;

  @Test
  void memberDeliveryRetainsCancelAuthorityAndPublishesExactlyOnceAfterAcceptance() {
    for (String kind : List.of("set", "group")) try (var s = BackgroundMembershipTest.state(kind)) {
      var worker = new BackgroundMembershipTest.Workers();
      var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var parent = s.draft;
        long raw = s.seedInputs.version(s), history = s.document.historyBytes();
        edit.apply(s); long work = edit.workVersion(); worker.run(); var delivery = ui.remove();
        delivery.run(); delivery.run();
        assertTrue(edit.applying()); assertFalse(edit.closed()); assertEquals(work, edit.workVersion());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(history, s.document.historyBytes());
        assertSame(parent, s.draft); assertTrue(edit.acceptPrepared());
        delivery.run(); assertFalse(edit.acceptPrepared());
        assertSame(parent, s.draft); assertEquals(raw, s.seedInputs.version(s));
        assertEquals(" private-id ", ((Membership) parent).id); assertEquals("-", s.seedInputs.text("seedOffset"));
        s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void readyMemberCancelCloseAndSuspensionRetainParentInputWithoutPublication() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var parent = s.draft; var values = edit.values();
        edit.apply(s); worker.run(); var delivery = ui.remove(); delivery.run();
        if (mode == 0) edit.cancel(); else if (mode == 1) edit.close(); else edit.suspendApply();
        delivery.run(); assertFalse(edit.acceptPrepared()); assertFalse(edit.applying());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertSame(parent, s.draft);
        assertEquals(values, edit.values()); assertEquals(mode != 2, edit.closed());
        if (mode == 2) {
          edit.apply(s); worker.run(); ui.remove().run(); assertTrue(edit.acceptPrepared());
        }
      }
    }
  }

  @Test
  void readyMemberChoiceEditThenRestoreRequiresANewExplicitApply() {
    try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var values = edit.values();
        edit.apply(s); worker.run(); var delivery = ui.remove(); delivery.run(); long work = edit.workVersion();
        var id = BackgroundMembershipTest.type(s, 0); edit.set(id, false); edit.set(id, true);
        assertEquals(values, edit.values()); assertNotEquals(work, edit.workVersion());
        delivery.run(); assertFalse(edit.acceptPrepared()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        edit.apply(s); worker.run(); ui.remove().run(); assertTrue(edit.acceptPrepared());
      }
    }
  }

  @Test
  void newerMemberDiagnosticIdentityContentsAndScopeWinAfterDelivery() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var shared = new ArrayList<Diagnostic>(); s.issues = shared;
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        edit.apply(s); worker.run(); ui.remove().run();
        var newer = Diagnostic.error("NEW", "/", "newer");
        if (mode == 0) s.issues = List.of(newer); else if (mode == 1) shared.add(newer); else s.validationScope = "newer";
        var issues = s.issues; var scope = s.validationScope;
        assertFalse(edit.acceptPrepared()); assertFalse(edit.applying());
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
        assertEquals(List.of(BackgroundMembershipTest.type(s, 1)), s.project().voxelSets.getFirst().voxelTypeUuids);
      }
    }
  }

  @Test
  void readyMemberSourceCheckpointPolicyAndPrivateChangesRejectOldAuthority() throws Exception {
    for (int mode = 0; mode < 5; mode++) try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var path = directory.resolve("members.json"); s.document.save(path);
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        edit.apply(s); worker.run(); ui.remove().run();
        if (mode == 0) s.document.save(path);
        else if (mode == 1) s.document.setValidator(NoiseCompiler::validate);
        else if (mode == 2) ((Membership) s.draft).id = "later private";
        else if (mode == 3) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else s.project().project.seed++;
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = ProjectIO.JSON.valueToTree(s.draft);
        assertFalse(edit.acceptPrepared()); assertFalse(edit.applying());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(draft, ProjectIO.JSON.valueToTree(s.draft));
      }
    }
  }

  @Test
  void readyMemberRejectionAndWorkerFailureCanBeRetiredWithoutInstallingFeedback() {
    for (boolean fatal : new boolean[] {false, true}) try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      s.document.setValidator(p -> {
        if (fatal) throw new AssertionError("worker");
        return List.of(Diagnostic.error("REJECT", "/", "rejected"));
      });
      try (var edit = BackgroundMembershipTest.session(s, worker, ui)) {
        var issues = s.issues; var scope = s.validationScope;
        edit.apply(s); worker.run(); ui.remove().run(); assertTrue(edit.applying());
        assertTrue(edit.applyFailure().isEmpty()); assertEquals(0, edit.issueCount());
        edit.suspendApply(); assertFalse(edit.acceptPrepared());
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
        s.document.setValidator(NoiseCompiler::validate);
        edit.apply(s); worker.run(); ui.remove().run(); assertTrue(edit.acceptPrepared());
      }
    }
  }

  @Test
  void closedMemberPredecessorCannotPopulateOrConsumeSuccessorPreparation() {
    try (var s = BackgroundMembershipTest.state("set")) {
      var worker = new BackgroundMembershipTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var first = BackgroundMembershipTest.session(s, worker, ui)) {
        first.apply(s); worker.run(); var old = ui.remove(); old.run(); first.cancel();
        try (var next = BackgroundMembershipTest.session(s, worker, ui)) {
          next.apply(s); old.run(); assertFalse(next.acceptPrepared()); assertTrue(next.applying());
          worker.run(); ui.remove().run(); old.run(); assertTrue(next.acceptPrepared());
        }
      }
    }
  }

  @Test
  void curveReadyDeliveryRetainsWorkVersionAndStagesOnlyOnce() {
    try (var s = BackgroundCurveApplyTest.state()) {
      var local = BackgroundCurveApplyTest.curve(s); local.points.getLast().y = 3;
      var worker = new BackgroundCurveApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var accepted = new AtomicInteger();
      try (var edit = BackgroundCurveApplyTest.session(s, local, worker, ui, accepted::incrementAndGet)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); edit.apply(local); long work = edit.version();
        worker.run(); var delivery = ui.remove(); delivery.run(); delivery.run();
        assertTrue(edit.ready()); assertTrue(edit.running()); assertEquals(work, edit.version());
        assertEquals(1, BackgroundCurveApplyTest.curve(s).points.getLast().y);
        assertTrue(edit.accept()); delivery.run(); assertFalse(edit.accept()); assertEquals(1, accepted.get());
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void readyCurveWorkerFailureWaitsForAcceptanceAndCanBeCancelledBeforeFeedback() {
    for (boolean cancel : new boolean[] {false, true}) {
      var worker = new BackgroundCurveApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var broken = new AtomicBoolean(true); var local = new Curve(); var stages = new AtomicInteger();
      try (var edit = new CurveApplySession(() -> {}, worker, ui::add, () -> true, () -> local,
          value -> stages.incrementAndGet(), input -> {
            if (broken.get()) throw new AssertionError("curve worker");
            return CurveDraftEdit.prepare(input);
          })) {
        edit.apply(local); worker.run(); ui.remove().run(); assertTrue(edit.ready()); assertTrue(edit.error().isEmpty());
        if (cancel) edit.cancelApply();
        assertFalse(edit.accept()); assertEquals(cancel, edit.error().isEmpty()); assertEquals(0, stages.get());
        broken.set(false); edit.apply(local); worker.run(); ui.remove().run(); assertTrue(edit.accept()); assertEquals(1, stages.get());
      }
    }
  }

  @Test
  void curveAcceptanceCannotConsumeAReadySuccessorStartedByAnOwnershipCallback() {
    var local = new Curve(); var replace = new AtomicBoolean(false); var stages = new AtomicInteger();
    var holder = new CurveApplySession[1];
    try (var edit = new CurveApplySession(() -> {}, Runnable::run, Runnable::run, () -> {
      if (replace.getAndSet(false)) { holder[0].cancelApply(); holder[0].apply(local); }
      return true;
    }, () -> local, value -> stages.incrementAndGet(), CurveDraftEdit::prepare)) {
      holder[0] = edit; edit.apply(local); assertTrue(edit.ready()); replace.set(true);
      assertFalse(edit.accept()); assertTrue(edit.ready()); assertEquals(0, stages.get());
      assertTrue(edit.accept()); assertEquals(1, stages.get());
    }
  }

  @Test
  void retiredReadyCurveDeliveryCannotConsumeAnUndeliveredSuccessor() {
    try (var s = BackgroundCurveApplyTest.state()) {
      var local = BackgroundCurveApplyTest.curve(s); local.points.getLast().y = 3;
      var worker = new BackgroundCurveApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundCurveApplyTest.session(s, local, worker, ui, () -> {})) {
        edit.apply(local); worker.run(); var old = ui.remove(); old.run(); edit.cancelApply();
        edit.apply(local); old.run(); assertFalse(edit.accept()); assertTrue(edit.running());
        worker.run(); ui.remove().run(); old.run(); assertTrue(edit.accept());
        assertEquals(3, BackgroundCurveApplyTest.curve(s).points.getLast().y);
      }
    }
  }

  @Test
  void suspendingReadyCurveKeepsExactLocalInputForExplicitRetryAndPreservesRawProperties() {
    try (var s = BackgroundCurveApplyTest.state()) {
      var local = BackgroundCurveApplyTest.curve(s); local.points.getLast().y = 7;
      var worker = new BackgroundCurveApplyTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var edit = BackgroundCurveApplyTest.session(s, local, worker, ui, () -> {})) {
        var parent = s.draft; var before = ProjectIO.JSON.valueToTree(s.project());
        s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        edit.apply(local); worker.run(); ui.remove().run(); edit.cancelApply();
        assertFalse(edit.accept()); assertEquals(7, local.points.getLast().y); assertSame(parent, s.draft);
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals("-", s.seedInputs.text("seedOffset"));
        edit.apply(local); worker.run(); ui.remove().run(); assertTrue(edit.accept());
        assertSame(parent, s.draft); assertEquals("-", s.seedInputs.text("seedOffset"));
      }
    }
  }
}
