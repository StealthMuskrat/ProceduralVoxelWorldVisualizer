package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred current-selection/host decisions before installing export catalog and closure results. */
class ReadyExportPlanningTest {
  @TempDir Path directory;

  static Runnable deliver(ExportPreviewPlanningTest.Workers worker, Queue<Runnable> ui) {
    worker.run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void initialReadinessKeepsCatalogAndIncludedPrivateUntilOneUnacknowledgedInstallation() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var owner = new ProjectFileOwner(s); long bytes = s.document.historyBytes(); var user = ProjectIO.JSON.valueToTree(s.user);
      var edit = ExportPreviewPlanningTest.edit(s, worker, ui); var request = edit.planningToken(); var callback = deliver(worker, ui); callback.run();
      assertSame(request, edit.planningToken()); assertTrue(edit.planning()); assertNull(edit.catalog()); assertNull(edit.preview());
      assertEquals(0, edit.preparations()); assertFalse(edit.choicesReady()); assertFalse(edit.canExport()); assertFalse(edit.canRetryPlan());
      assertTrue(owner.valuesCurrent(s)); edit.acceptPlanning(); var preview = edit.preview(); var catalog = edit.catalog();
      callback.run(); edit.acceptPlanning(); assertSame(preview, edit.preview()); assertSame(catalog, edit.catalog()); assertEquals(1, edit.preparations());
      assertEquals(List.of("base", "derived"), preview.resources().noise2D.stream().map(n -> n.id).toList());
      assertFalse(edit.canExport()); assertThrows(IllegalStateException.class, () -> edit.exportSnapshot(preview));
      edit.markShown(preview); assertTrue(edit.canExport()); preview.resources().noise2D.clear(); assertEquals(2, edit.exportSnapshot(preview).noise2D.size());
      assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes()); assertEquals(user, ProjectIO.JSON.valueToTree(s.user)); edit.cancel();
    }
  }

  @Test
  void readySelectionKeepsOldIncludedAndCatalogUntilAcceptanceThenNeedsFreshDisplay() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>(); var edit = ExportPreviewPlanningTest.edit(s, worker, ui);
      deliver(worker, ui); edit.acceptPlanning(); var old = edit.preview(); var catalog = edit.catalog(); edit.markShown(old);
      edit.select(ExportPreviewPlanningTest.base(s)); var request = edit.planningToken(); var callback = deliver(worker, ui); edit.markShown(old);
      assertSame(old, edit.preview()); assertSame(catalog, edit.catalog()); assertSame(request, edit.planningToken()); assertFalse(edit.canExport());
      assertEquals(1, edit.preparations()); edit.acceptPlanning(); var fresh = edit.preview(); callback.run(); edit.acceptPlanning();
      assertSame(catalog, edit.catalog()); assertSame(fresh, edit.preview()); assertNotSame(old, fresh); assertEquals(2, edit.preparations());
      assertEquals(1, fresh.resources().noise2D.size()); assertEquals(2, old.resources().noise2D.size());
      edit.markShown(old); assertFalse(edit.canExport()); edit.markShown(fresh); assertTrue(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void readyInitialSelectionReloadAndCapturedCatalogFailureCanCloseWithoutInstallingOrReporting() {
    for (int mode = 0; mode < 4; mode++) try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      if (mode == 3) s.project().project.rootOrder = null; // Deliberate worker-only catalog failure.
      var edit = ExportPreviewPlanningTest.edit(s, worker, ui);
      if (mode == 1 || mode == 2) {
        deliver(worker, ui); edit.acceptPlanning();
        if (mode == 1) edit.select(ExportPreviewPlanningTest.base(s));
        else { assertTrue(s.change(p -> p.project.seed = 18)); edit.reload(); }
      }
      var old = edit.preview(); var catalog = edit.catalog(); var owner = new ProjectFileOwner(s); int events = s.events.snapshot().size();
      var callback = deliver(worker, ui); assertTrue(edit.planning()); assertSame(old, edit.preview()); assertSame(catalog, edit.catalog());
      assertEquals(events, s.events.snapshot().size()); edit.cancel(); callback.run(); edit.acceptPlanning();
      assertTrue(edit.closed()); assertFalse(edit.planning()); assertSame(old, edit.preview()); assertSame(catalog, edit.catalog());
      assertTrue(owner.valuesCurrent(s)); assertEquals(events, s.events.snapshot().size());
    }
  }

  @Test
  void oldReadySelectionsCannotOverwriteTheLatestDesiredSetOrItsCachedCatalog() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>(); var edit = ExportPreviewPlanningTest.edit(s, worker, ui);
      deliver(worker, ui); edit.acceptPlanning(); var old = edit.preview(); var catalog = edit.catalog();
      edit.select(ExportPreviewPlanningTest.base(s)); var callback = deliver(worker, ui); edit.select(Set.of());
      var request = edit.planningToken(); callback.run(); edit.acceptPlanning(); assertSame(old, edit.preview()); assertSame(request, edit.planningToken());
      assertTrue(edit.selected().isEmpty()); deliver(worker, ui); edit.acceptPlanning();
      assertSame(catalog, edit.catalog()); assertTrue(edit.preview().included().isEmpty()); assertFalse(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void missingSelectionsStayPrivateUntilAcceptedAndRequireExplicitRemovalAndNewIncludedReview() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>(); var missing = UUID.randomUUID();
      var edit = new ExportEditSession(s, Set.of(missing), worker, ui::add, () -> false); deliver(worker, ui);
      assertNull(edit.catalog()); assertEquals(0, edit.unavailableCount()); assertEquals(Set.of(missing), edit.selected());
      edit.acceptPlanning(); assertNotNull(edit.catalog()); assertEquals(1, edit.unavailableCount()); assertNull(edit.preview()); assertFalse(edit.canExport());
      edit.select(Set.of()); deliver(worker, ui); edit.acceptPlanning(); assertEquals(0, edit.unavailableCount()); assertFalse(edit.canExport());
      edit.select(ExportPreviewPlanningTest.derived(s)); deliver(worker, ui); edit.acceptPlanning(); assertFalse(edit.canExport());
      edit.markShown(edit.preview()); assertTrue(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void readyCommittedPrivateRawSelectionLifetimeAndSavedPathChangesPreserveNewerState() throws Exception {
    for (int mode = 0; mode < 7; mode++) try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>(); var edit = ExportPreviewPlanningTest.edit(s, worker, ui);
      deliver(worker, ui);
      if (mode == 0) s.project().project.seed++; else if (mode == 1) ((Noise) s.draft).id = "new-private";
      else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+"); else if (mode == 3) s.multiSelection.clear();
      else if (mode == 4) s.newSession(); else if (mode == 5) s.document.save(directory.resolve("saved-" + mode + ".json"));
      else { s.project().project.seed++; edit.select(ExportPreviewPlanningTest.base(s)); deliver(worker, ui); }
      var owner = new ProjectFileOwner(s); edit.acceptPlanning();
      assertNull(edit.catalog()); assertNull(edit.preview()); assertFalse(edit.planning()); assertFalse(edit.canExport()); assertTrue(owner.valuesCurrent(s));
      if (mode == 6) { assertFalse(edit.current()); assertFalse(edit.canRetryPlan()); }
      edit.cancel();
    }
  }

  @Test
  void acceptedCatalogFailureRetainsSelectionAndRequiresAnExplicitCorrectedReload() {
    try (var s = ExportPreviewPlanningTest.state()) {
      s.project().project.rootOrder = null; var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>();
      var edit = ExportPreviewPlanningTest.edit(s, worker, ui); var callback = deliver(worker, ui); assertTrue(edit.planning());
      edit.acceptPlanning(); callback.run(); assertTrue(edit.canRetryPlan()); assertNull(edit.catalog()); assertEquals(ExportPreviewPlanningTest.derived(s), edit.selected());
      assertTrue(edit.planningMessage().contains("Export preview preparation failed")); assertTrue(worker.tasks.isEmpty());
      assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("EXPORT_PREVIEW_FAILED")).count());
      s.project().project.rootOrder = new LinkedHashMap<>(); edit.reload(); deliver(worker, ui); edit.acceptPlanning();
      assertEquals(2, edit.catalog().size()); assertFalse(edit.canExport()); edit.markShown(edit.preview()); assertTrue(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void unavailableOpeningRetiresReadyResultsUntilAnExplicitPreviewRetry() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var worker = new ExportPreviewPlanningTest.Workers(); var ui = new ArrayDeque<Runnable>(); var edit = ExportPreviewPlanningTest.edit(s, worker, ui);
      deliver(worker, ui); edit.acceptPlanning(() -> false); assertNull(edit.catalog()); assertNull(edit.preview()); assertTrue(edit.canRetryPlan());
      assertTrue(worker.tasks.isEmpty()); edit.retryPlan(); deliver(worker, ui); edit.acceptPlanning(); assertNotNull(edit.catalog()); assertFalse(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void blockerCannotInstallOrRetireItsInlineReadySuccessor() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var replace = new AtomicBoolean(false); var holder = new ExportEditSession[1];
      var edit = new ExportEditSession(s, ExportPreviewPlanningTest.derived(s), Runnable::run, Runnable::run, () -> {
        if (replace.getAndSet(false)) holder[0].select(ExportPreviewPlanningTest.base(s)); return false;
      });
      holder[0] = edit; replace.set(true); edit.acceptPlanning(); assertTrue(edit.planning()); assertNull(edit.catalog());
      assertEquals(ExportPreviewPlanningTest.base(s), edit.selected()); edit.acceptPlanning();
      assertEquals(1, edit.preview().resources().noise2D.size()); assertFalse(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void presentationCallbackCannotAuthorizeItsInlineReadySuccessor() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var edit = new ExportEditSession(s, ExportPreviewPlanningTest.derived(s), Runnable::run, Runnable::run, () -> false); var once = new AtomicBoolean(true);
      edit.acceptPlanning(() -> { if (once.getAndSet(false)) edit.select(ExportPreviewPlanningTest.base(s)); return true; });
      assertTrue(edit.planning()); assertNull(edit.catalog()); edit.acceptPlanning();
      assertEquals(1, edit.preview().resources().noise2D.size()); assertFalse(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureCannotInstallAnUnacceptedCatalog() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var once = new AtomicBoolean(true); var edit = new ExportEditSession(s, ExportPreviewPlanningTest.derived(s), Runnable::run, callback -> {
        callback.run(); if (once.getAndSet(false)) throw new AssertionError("late handoff");
      }, () -> false);
      edit.acceptPlanning(); assertNull(edit.catalog()); assertNull(edit.preview()); assertTrue(edit.canRetryPlan());
      assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("EXPORT_PREVIEW_FAILED")).count());
      edit.retryPlan(); edit.acceptPlanning(); assertNotNull(edit.catalog()); assertFalse(edit.canExport()); edit.cancel();
    }
  }

  @Test
  void panelRepeatsDesiredSelectionAfterPresentationCallbacksBeforeInstalling() {
    try (var s = ExportPreviewPlanningTest.state()) {
      var selected = new LinkedHashSet<>(ExportPreviewPlanningTest.derived(s));
      var panel = new ResourceExportPanel(s, selected, Runnable::run, Runnable::run, () -> false); panel.open();
      var edit = panel.session(); var owner = new ProjectFileOwner(s);
      panel.acceptPlanning(edit, edit.planningToken(), () -> {
        selected.clear(); selected.addAll(ExportPreviewPlanningTest.base(s)); return true;
      });
      assertNull(edit.catalog()); assertTrue(edit.canRetryPlan()); assertEquals(ExportPreviewPlanningTest.base(s), selected);
      edit.select(selected); panel.acceptPlanning(edit, edit.planningToken(), () -> true);
      assertEquals(1, edit.preview().resources().noise2D.size()); assertFalse(panel.canExport()); assertTrue(owner.valuesCurrent(s)); panel.reset();
    }
  }

  @Test
  void currentPanelSelectionAndOpeningCannotAcceptInlineResultsAsOlderShownWork() {
    try (var s = ExportPreviewPlanningTest.state(); var properties = new PropertyPanel(s);
        var resources = new ResourcePanel(s, properties, Runnable::run, Runnable::run, () -> false);
        var imports = new ResourceImportPanel(s, () -> false, () -> {});
        var dialog = new ResourcePackageDialog(s, resources, imports, () -> false)) {
      assertTrue(dialog.open("Export")); var first = resources.exportView().session(); var shown = dialog.capture(1); var request = first.planningToken();
      resources.exportSelection.clear(); resources.exportSelection.addAll(ExportPreviewPlanningTest.base(s));
      dialog.acceptExportPlanning(shown, request); assertNull(first.catalog()); assertTrue(first.planning());
      first.select(resources.exportSelection); dialog.acceptExportPlanning(shown, request); assertNull(first.catalog()); assertTrue(first.planning());
      dialog.acceptExportPlanning(dialog.capture(2), first.planningToken()); assertNotNull(first.catalog()); assertEquals(1, first.preview().resources().noise2D.size());
      assertFalse(resources.canExport()); dialog.cancel(); assertTrue(first.closed()); assertTrue(dialog.open("Export"));
      var successor = resources.exportView().session(); dialog.acceptExportPlanning(shown, request); assertNull(successor.catalog()); assertTrue(successor.planning());
      dialog.acceptExportPlanning(dialog.capture(3), successor.planningToken()); assertNotNull(successor.catalog()); assertFalse(resources.canExport());
    }
  }
}
