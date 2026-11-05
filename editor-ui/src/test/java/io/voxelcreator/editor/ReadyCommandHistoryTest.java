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

/** Deferred ready-frame ownership for browser commands and committed project history. */
class ReadyCommandHistoryTest {
  @TempDir Path directory;

  @Test
  void commandDeliveryKeepsShownWorkAndPrivateInputUntilOneCleanApplyOrDiscardAcceptance() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundResourceEditTest.state(mode != 0)) {
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<ResourceEditSession.Result>();
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, done)) {
        if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "-");
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        edit.request("Rename", BackgroundResourceEditTest.rename(s));
        if (mode != 0) edit.decide(mode == 1 ? SearchNavigation.Decision.APPLY : SearchNavigation.Decision.DISCARD);
        long work = edit.workVersion(); worker.run(); var delivery = ui.remove(); delivery.run(); delivery.run();
        assertTrue(edit.pending()); assertTrue(edit.preparing()); assertFalse(edit.busy());
        assertEquals(work, edit.workVersion()); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        assertSame(draft, s.draft); assertTrue(done.isEmpty());
        edit.acceptPrepared(); delivery.run(); edit.acceptPrepared();
        assertEquals(List.of(ResourceEditSession.Result.APPLIED), done); assertFalse(edit.pending());
        assertEquals("renamed", s.project().noise2D.getFirst().id);
        assertEquals(mode == 1 ? .625 : .125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void readyCommandCancelClearAndCloseNeverRunCompletionOrDisposePrivateInput() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundResourceEditTest.state(true)) {
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<ResourceEditSession.Result>();
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, done)) {
        var owner = new ProjectFileOwner(s); long bytes = s.document.historyBytes();
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); edit.decide(SearchNavigation.Decision.APPLY);
        worker.run(); var delivery = ui.remove(); delivery.run();
        if (mode == 0) edit.decide(SearchNavigation.Decision.CANCEL); else if (mode == 1) edit.clear(); else edit.close();
        delivery.run(); edit.acceptPrepared();
        assertFalse(edit.pending()); assertFalse(edit.preparing()); assertTrue(done.isEmpty());
        assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes());
      }
    }
  }

  @Test
  void readyCommandWorkerFailureAndRejectionCanBeCancelledBeforeFeedbackAndRetried() {
    for (boolean fatal : new boolean[] {false, true}) try (var s = BackgroundResourceEditTest.state(true)) {
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<ResourceEditSession.Result>();
      s.document.setValidator(p -> {
        if (fatal) throw new AssertionError("worker");
        return List.of(Diagnostic.error("REJECT", "/", "rejected"));
      });
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, done)) {
        var issues = s.issues; var scope = s.validationScope;
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); edit.decide(SearchNavigation.Decision.APPLY);
        worker.run(); ui.remove().run(); assertTrue(edit.preparing()); assertSame(issues, s.issues);
        edit.decide(SearchNavigation.Decision.CANCEL); edit.acceptPrepared();
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertTrue(done.isEmpty());
        s.document.setValidator(NoiseCompiler::validate);
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); edit.decide(SearchNavigation.Decision.APPLY);
        worker.run(); ui.remove().run(); edit.acceptPrepared(); assertEquals(List.of(ResourceEditSession.Result.APPLIED), done);
      }
    }
  }

  @Test
  void newerCommandDiagnosticIdentityContentsAndScopeSurviveReadyAcceptance() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundResourceEditTest.state(false)) {
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var shared = new ArrayList<Diagnostic>(); s.issues = shared;
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, new ArrayList<>())) {
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); worker.run(); ui.remove().run();
        var newer = Diagnostic.error("NEW", "/", "newer");
        if (mode == 0) s.issues = List.of(newer); else if (mode == 1) shared.add(newer); else s.validationScope = "newer";
        var issues = s.issues; var scope = s.validationScope; edit.acceptPrepared();
        assertFalse(edit.pending()); assertSame(issues, s.issues); assertEquals(scope, s.validationScope);
        assertEquals("a", s.project().noise2D.getFirst().id);
      }
    }
  }

  @Test
  void readyCommandRechecksSourceCheckpointPolicyPrivateAndRawAuthority() throws Exception {
    for (int mode = 0; mode < 5; mode++) try (var s = BackgroundResourceEditTest.state(true)) {
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var path = directory.resolve("command.json"); s.document.save(path);
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, new ArrayList<>())) {
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); edit.decide(SearchNavigation.Decision.APPLY);
        worker.run(); ui.remove().run();
        if (mode == 0) s.document.save(path); else if (mode == 1) s.document.setValidator(NoiseCompiler::validate);
        else if (mode == 2) ((Noise) s.draft).id = "new-private";
        else if (mode == 3) s.seedInputs.edit(s, "seedOffset", "Seed", "-"); else s.project().project.seed++;
        var owner = new ProjectFileOwner(s); edit.acceptPrepared();
        assertFalse(edit.pending()); assertTrue(owner.valuesCurrent(s));
      }
    }
  }

  @Test
  void commandBlockerCannotConsumeAReadySuccessorCreatedDuringOldAcceptance() {
    try (var s = BackgroundResourceEditTest.state(false)) {
      var replace = new AtomicBoolean(false); var holder = new ResourceEditSession[1];
      var done = new ArrayList<ResourceEditSession.Result>();
      try (var edit = new ResourceEditSession(s, () -> {}, Runnable::run, Runnable::run, () -> {
        if (replace.getAndSet(false)) {
          holder[0].clear(); holder[0].request("Successor", ResourceBrowserEdits.rename(s.selected, "successor"));
        }
        return false;
      }, done::add)) {
        holder[0] = edit; edit.request("Rename", BackgroundResourceEditTest.rename(s)); replace.set(true);
        edit.acceptPrepared(); assertTrue(edit.preparing()); assertTrue(done.isEmpty()); assertEquals("a", s.project().noise2D.getFirst().id);
        edit.acceptPrepared(); assertFalse(edit.pending()); assertEquals("successor", s.project().noise2D.getFirst().id);
        assertEquals(List.of(ResourceEditSession.Result.APPLIED), done);
      }
    }
  }

  @Test
  void staleCommandCompletionCannotAuthorizeAReadySuccessorFromTheOldAcceptance() {
    try (var s = BackgroundResourceEditTest.state(false)) {
      var holder = new ResourceEditSession[1]; var done = new ArrayList<ResourceEditSession.Result>();
      try (var edit = new ResourceEditSession(s, () -> {}, Runnable::run, Runnable::run, () -> false, result -> {
        done.add(result);
        if (result == ResourceEditSession.Result.UNAVAILABLE)
          holder[0].request("Successor", ResourceBrowserEdits.rename(s.selected, "successor"));
      })) {
        holder[0] = edit; edit.request("Rename", BackgroundResourceEditTest.rename(s));
        s.issues = List.of(Diagnostic.error("NEW", "/", "newer"));
        edit.acceptPrepared(); assertTrue(edit.preparing()); assertEquals("a", s.project().noise2D.getFirst().id);
        assertEquals(List.of(ResourceEditSession.Result.UNAVAILABLE), done);
        edit.acceptPrepared(); assertEquals("successor", s.project().noise2D.getFirst().id);
        assertEquals(List.of(ResourceEditSession.Result.UNAVAILABLE, ResourceEditSession.Result.APPLIED), done);
      }
    }
  }

  @Test
  void historyDeliveryKeepsStableCancelAuthorityAndOneUndoRedoStep() {
    for (boolean dirty : new boolean[] {false, true}) try (var s = BackgroundProjectHistoryTest.state(dirty)) {
      var worker = new BackgroundProjectHistoryTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var history = BackgroundProjectHistoryTest.history(s, worker, ui)) {
        var before = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft; long bytes = s.document.historyBytes();
        if (dirty) BackgroundProjectHistoryTest.confirm(history); else history.request(ProjectHistorySession.Direction.UNDO);
        long request = history.requestVersion(), work = history.workVersion();
        worker.run(); var delivery = ui.remove(); delivery.run(); delivery.run();
        assertTrue(history.preparing()); assertTrue(history.pending()); assertEquals(request, history.requestVersion());
        assertEquals(work, history.workVersion()); assertSame(draft, s.draft); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
        history.acceptPrepared(); delivery.run(); history.acceptPrepared();
        assertFalse(history.pending()); assertTrue(s.document.canRedo()); assertEquals(bytes, s.document.historyBytes());
        assertEquals(.125, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        history.request(ProjectHistorySession.Direction.REDO); worker.run(); ui.remove().run(); history.acceptPrepared();
        assertEquals(before, ProjectIO.JSON.valueToTree(s.project())); assertEquals(bytes, s.document.historyBytes());
      }
    }
  }

  @Test
  void readyHistoryCancelClearAndCloseRetainPrivateInputAndHistoryStacks() {
    for (int mode = 0; mode < 3; mode++) try (var s = BackgroundProjectHistoryTest.state(true)) {
      var worker = new BackgroundProjectHistoryTest.Workers(); var ui = new ArrayDeque<Runnable>();
      try (var history = BackgroundProjectHistoryTest.history(s, worker, ui)) {
        var owner = new ProjectFileOwner(s); long bytes = s.document.historyBytes();
        BackgroundProjectHistoryTest.confirm(history); worker.run(); var delivery = ui.remove(); delivery.run();
        if (mode == 0) history.decide(ProjectHistorySession.Decision.CANCEL); else if (mode == 1) history.clear(); else history.close();
        delivery.run(); history.acceptPrepared(); assertFalse(history.pending()); assertFalse(history.preparing());
        assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes()); assertFalse(s.document.canRedo());
      }
    }
  }

  @Test
  void readyHistoryRejectedAndFatalCandidatesCanBeCancelledBeforeFeedback() {
    for (boolean fatal : new boolean[] {false, true}) try (var s = BackgroundProjectHistoryTest.state(true)) {
      var worker = new BackgroundProjectHistoryTest.Workers(); var ui = new ArrayDeque<Runnable>();
      s.document.setValidator(p -> {
        if (fatal) throw new AssertionError("worker");
        return List.of(Diagnostic.error("REJECT", "/", "rejected"));
      });
      try (var history = BackgroundProjectHistoryTest.history(s, worker, ui)) {
        var issues = s.issues; var scope = s.validationScope;
        BackgroundProjectHistoryTest.confirm(history); worker.run(); ui.remove().run(); assertTrue(history.preparing());
        history.decide(ProjectHistorySession.Decision.CANCEL); history.acceptPrepared();
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertFalse(s.document.canRedo());
        s.document.setValidator(NoiseCompiler::validate);
        BackgroundProjectHistoryTest.confirm(history); worker.run(); ui.remove().run(); history.acceptPrepared(); assertTrue(s.document.canRedo());
      }
    }
  }

  @Test
  void readyHistoryRechecksSourceCheckpointPolicyPrivateAndRawAuthority() throws Exception {
    for (int mode = 0; mode < 5; mode++) try (var s = BackgroundProjectHistoryTest.state(true)) {
      var worker = new BackgroundProjectHistoryTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var path = directory.resolve("history.json"); s.document.save(path);
      try (var history = BackgroundProjectHistoryTest.history(s, worker, ui)) {
        BackgroundProjectHistoryTest.confirm(history); worker.run(); ui.remove().run();
        if (mode == 0) s.document.save(path); else if (mode == 1) s.document.setValidator(NoiseCompiler::validate);
        else if (mode == 2) ((Noise) s.draft).id = "new-private";
        else if (mode == 3) s.seedInputs.edit(s, "seedOffset", "Seed", "+"); else s.project().project.seed++;
        var owner = new ProjectFileOwner(s); history.acceptPrepared(); assertFalse(history.pending()); assertTrue(owner.valuesCurrent(s));
      }
    }
  }

  @Test
  void historyBlockerCannotConsumeAReadySuccessorCreatedDuringOldAcceptance() {
    try (var s = BackgroundProjectHistoryTest.state(false)) {
      var replace = new AtomicBoolean(false); var holder = new ProjectHistorySession[1]; var refreshes = new AtomicInteger();
      try (var history = new ProjectHistorySession(s, () -> {
        if (replace.getAndSet(false)) { holder[0].clear(); holder[0].request(ProjectHistorySession.Direction.UNDO); }
        return false;
      }, () -> { refreshes.incrementAndGet(); s.reconcileSelection(s.multiSelection, s.selected); }, Runnable::run, Runnable::run)) {
        holder[0] = history; history.request(ProjectHistorySession.Direction.UNDO); replace.set(true);
        history.acceptPrepared(); assertTrue(history.preparing()); assertEquals(0, refreshes.get());
        assertEquals(.625, s.project().noise2D.getFirst().algorithm.parameters.get("value"));
        history.acceptPrepared(); assertFalse(history.pending()); assertEquals(1, refreshes.get()); assertTrue(s.document.canRedo());
      }
    }
  }

  @Test
  void readyNoopCommandKeepsRedoAndRetiredDeliveryCannotConsumeNextRequest() {
    try (var s = BackgroundResourceEditTest.state(false)) {
      s.change(p -> p.project.seed++); s.document.undo(); s.revision++; s.refreshDraft();
      var worker = new BackgroundResourceEditTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var done = new ArrayList<ResourceEditSession.Result>();
      try (var edit = BackgroundResourceEditTest.session(s, worker, ui, done)) {
        long bytes = s.document.historyBytes(); edit.request("No op", p -> null);
        worker.run(); var old = ui.remove(); old.run(); edit.acceptPrepared();
        assertTrue(s.document.canRedo()); assertEquals(bytes, s.document.historyBytes());
        edit.request("Rename", BackgroundResourceEditTest.rename(s)); old.run(); edit.acceptPrepared(); assertTrue(edit.preparing());
        assertEquals("a", s.project().noise2D.getFirst().id);
        worker.run(); ui.remove().run(); edit.acceptPrepared(); assertEquals(2, done.size());
      }
    }
  }
}
