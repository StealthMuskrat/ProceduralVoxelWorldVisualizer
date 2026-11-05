package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred File Location cancellation and exact-path acceptance before private Import review. */
class ReadyPackageLoadTest {
  static final String SOURCE = "./资源 package.pvresources.json";

  static ProjectData incoming() {
    var p = new ProjectData(); p.noise2D.add(BackgroundImportApplyTest.noise("incoming", .75)); return p;
  }

  static ResourceImportLoader loader(EditorState s, Queue<Runnable> work, Queue<Runnable> ui,
      ResourceImportPanel panel, BooleanSupplier blocked) {
    return new ResourceImportLoader(s, work::add, ui::add,
        (path, destination) -> ImportEditSession.prepare(destination, incoming()), panel::openPrepared, blocked);
  }

  static Runnable deliver(Queue<Runnable> work, Queue<Runnable> ui) {
    work.remove().run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void readinessKeepsThePathDecisionPrivateUntilOneAcceptedUnreviewedWorkspace() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var loading = loader(s, work, ui, panel, () -> false)) {
        var owner = new ProjectFileOwner(s); var before = ProjectIO.JSON.valueToTree(s.project()); long bytes = s.document.historyBytes();
        loading.start(SOURCE); var token = loading.requestToken(); var callback = deliver(work, ui); callback.run();
        assertSame(token, loading.requestToken()); assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase());
        assertFalse(panel.pending()); assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes());
        loading.acceptPrepared(SOURCE, () -> true); callback.run(); loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.READY, loading.view().phase()); assertTrue(panel.pending());
        var edit = panel.session(); var plan = edit.preview(); assertSame(plan, edit.preview()); assertFalse(edit.canApply());
        assertTrue(owner.valuesCurrent(s)); edit.markShown(plan); assertEquals(ImportEditSession.Result.APPLIED, edit.apply());
        assertEquals(2, s.project().noise2D.size()); s.document.undo(); assertEquals(before, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void readySuccessFailureAndCancellationCanRetireWithoutOpeningReviewOrReportingFeedback() {
    for (int mode = 0; mode < 3; mode++) try (var s = ReadyValidationTest.state();
        var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); int action = mode;
      try (var loading = new ResourceImportLoader(s, work::add, ui::add, (path, destination) -> {
        if (action == 1) throw new AssertionError("worker");
        if (action == 2) throw new CancellationException("worker");
        return ImportEditSession.prepare(destination, incoming());
      }, panel::openPrepared, () -> false)) {
        var owner = new ProjectFileOwner(s); int events = s.events.snapshot().size();
        loading.start(SOURCE); var callback = deliver(work, ui); assertFalse(panel.pending());
        loading.cancel(); callback.run(); loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.CANCELLED, loading.view().phase()); assertFalse(panel.pending());
        assertTrue(owner.valuesCurrent(s)); assertEquals(events, s.events.snapshot().size());
      }
    }
  }

  @Test
  void equivalentPathSpellingStillNeedsAnExplicitCorrectedRequest() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var loading = loader(s, work, ui, panel, () -> false)) {
        loading.start(SOURCE); deliver(work, ui); loading.acceptPrepared("资源 package.pvresources.json", () -> true);
        assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase()); assertFalse(panel.pending()); assertTrue(work.isEmpty());
        loading.start("资源 package.pvresources.json"); deliver(work, ui);
        loading.acceptPrepared("资源 package.pvresources.json", () -> true); assertTrue(panel.pending());
      }
    }
  }

  @Test
  void unavailableCurrentOpeningRetiresReadyReviewUntilAFreshExplicitRequest() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var loading = loader(s, work, ui, panel, () -> false)) {
        loading.start(SOURCE); deliver(work, ui); loading.acceptPrepared(SOURCE, () -> false);
        assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase()); assertFalse(panel.pending());
        loading.start(SOURCE); deliver(work, ui); loading.acceptPrepared(SOURCE, () -> true); assertTrue(panel.pending());
      }
    }
  }

  @Test
  void readyProjectPrivateRawSelectionAndLifetimeChangesCannotInstallOldReview() {
    for (int mode = 0; mode < 5; mode++) try (var s = ReadyValidationTest.state();
        var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var loading = loader(s, work, ui, panel, () -> false)) {
        loading.start(SOURCE); deliver(work, ui);
        if (mode == 0) s.project().project.seed++;
        else if (mode == 1) ((Noise) s.draft).id = "new-private";
        else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else if (mode == 3) s.multiSelection.clear(); else s.newSession();
        var owner = new ProjectFileOwner(s); loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.STALE, loading.view().phase()); assertFalse(panel.pending()); assertTrue(owner.valuesCurrent(s));
      }
    }
  }

  @Test
  void reviewAdmissionRepeatsOpeningAuthorityBeforeConsumingPreparedValues() {
    try (var s = ReadyValidationTest.state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var shown = new AtomicBoolean(true);
      var captured = new AtomicReference<ImportEditSession.Prepared>();
      try (var panel = new ResourceImportPanel(s, () -> { shown.set(false); return false; }, () -> {});
          var loading = new ResourceImportLoader(s, work::add, ui::add, (path, destination) -> {
            var prepared = ImportEditSession.prepare(destination, incoming()); captured.set(prepared); return prepared;
          }, panel::openPrepared, () -> false)) {
        loading.start(SOURCE); deliver(work, ui); loading.acceptPrepared(SOURCE, shown::get);
        assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase()); assertFalse(panel.pending());
        var direct = new ImportEditSession(s, captured.get()); assertFalse(direct.canApply()); direct.cancel();
      }
    }
  }

  @Test
  void blockerCanStartAReadySuccessorWithoutOldAcceptanceInstallingEitherReview() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var replace = new AtomicBoolean(false); var holder = new ResourceImportLoader[1];
      try (var loading = new ResourceImportLoader(s, Runnable::run, Runnable::run,
          (path, destination) -> ImportEditSession.prepare(destination, incoming()), panel::openPrepared, () -> {
            if (replace.getAndSet(false)) { holder[0].cancel(); holder[0].start(SOURCE); } return false;
          })) {
        holder[0] = loading; loading.start(SOURCE); replace.set(true); loading.acceptPrepared();
        assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase()); assertFalse(panel.pending());
        loading.acceptPrepared(); assertEquals(ResourceImportLoader.Phase.READY, loading.view().phase()); assertTrue(panel.pending());
      }
    }
  }

  @Test
  void openingCallbackCannotAuthorizeTheReadySuccessorItCreates() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var once = new AtomicBoolean(true);
      try (var loading = new ResourceImportLoader(s, Runnable::run, Runnable::run,
          (path, destination) -> ImportEditSession.prepare(destination, incoming()), panel::openPrepared, () -> false)) {
        loading.start(SOURCE); loading.acceptPrepared(SOURCE, () -> {
          if (once.getAndSet(false)) { loading.cancel(); loading.start(SOURCE); } return true;
        });
        assertEquals(ResourceImportLoader.Phase.RUNNING, loading.view().phase()); assertFalse(panel.pending());
        loading.acceptPrepared(); assertTrue(panel.pending());
      }
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureCannotInstallAnUnacceptedReview() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      try (var loading = new ResourceImportLoader(s, Runnable::run,
          callback -> { callback.run(); throw new AssertionError("handoff"); },
          (path, destination) -> ImportEditSession.prepare(destination, incoming()), panel::openPrepared, () -> false)) {
        loading.start(SOURCE); loading.acceptPrepared(); assertEquals(ResourceImportLoader.Phase.FAILED, loading.view().phase());
        assertFalse(panel.pending()); assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("FILE_IMPORT_PREPARATION_FAILED")).count());
      }
    }
  }

  @Test
  void closedPredecessorCannotConsumeTheQueuedSuccessorReview() {
    try (var s = ReadyValidationTest.state(); var panel = new ResourceImportPanel(s, () -> false, () -> {})) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      var first = loader(s, work, ui, panel, () -> false); first.start(SOURCE); var old = deliver(work, ui); first.close();
      try (var next = loader(s, work, ui, panel, () -> false)) {
        next.start(SOURCE); old.run(); next.acceptPrepared(); assertFalse(panel.pending());
        deliver(work, ui); old.run(); next.acceptPrepared(); assertTrue(panel.pending());
        assertEquals(ResourceImportLoader.Phase.CLOSED, first.view().phase());
      }
    }
  }

  @Test
  void pathSubmitCannotAcceptItsInlinePreparationAsThePreviouslyShownWork() {
    try (var s = ReadyValidationTest.state(); var properties = new PropertyPanel(s);
        var resources = new ResourcePanel(s, properties); var panel = new ResourceImportPanel(s, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(s, resources, panel, () -> false, new ResourcePackageFiles(),
            Runnable::run, Runnable::run, (path, destination) -> ImportEditSession.prepare(destination, incoming()))) {
      assertTrue(dialog.open("Import")); var shown = dialog.capture(1); var request = dialog.importRequestToken();
      dialog.dispatch(shown, ResourcePackageDialog.Command.SUBMIT, 1); dialog.acceptImport(shown, request);
      assertTrue(dialog.pending()); assertTrue(dialog.importRunning()); assertFalse(panel.pending());
      dialog.acceptImport(dialog.capture(2), dialog.importRequestToken());
      assertFalse(dialog.pending()); assertTrue(panel.pending()); assertFalse(panel.session().canApply());
    }
  }
}
