package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred ready-staging decisions before replacing a reviewed resource-package destination. */
class ReadyResourceExportTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }

  static ExportEditSession reviewed(EditorState s) {
    var source = new ExportEditSession(s, Set.of(s.selected)); source.markShown(source.preview()); return source;
  }

  static ResourcePackageFiles files(AtomicInteger writes, AtomicInteger replacements, AtomicInteger deletions) {
    return new ResourcePackageFiles(new ResourcePackageFiles.Storage() {
      public void write(Path temp, byte[] bytes) throws IOException { Files.write(temp, bytes); writes.incrementAndGet(); }
      public void replace(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); replacements.incrementAndGet();
      }
      public void delete(Path temp) throws IOException { Files.deleteIfExists(temp); deletions.incrementAndGet(); }
    });
  }

  Path target(String name) throws IOException { var p = directory.resolve(name); Files.writeString(p, "prior"); return p; }

  void noTemporaryFiles() throws IOException {
    try (var paths = Files.walk(directory)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().startsWith(".voxel-package-"))); }
  }

  static Runnable deliver(Workers worker, Queue<Runnable> ui) {
    worker.run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void deliveryKeepsPriorBytesAndCancelIdentityUntilOneAcceptedReplacementWithoutExtraReview() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var preview = source.preview(); var target = target("资源 package.json");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var writes = new AtomicInteger();
      var replacements = new AtomicInteger(); var deletions = new AtomicInteger(); var files = files(writes, replacements, deletions);
      try (var writer = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        var owner = new ProjectFileOwner(s); long bytes = s.document.historyBytes(); var user = ProjectIO.JSON.valueToTree(s.user);
        writer.start(target.toString(), source, preview, () -> true); var request = writer.requestToken(); var callback = deliver(worker, ui); callback.run();
        assertSame(request, writer.requestToken()); assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase());
        assertEquals("prior", Files.readString(target)); assertFalse(source.closed()); assertTrue(source.canExport()); assertEquals(1, writes.get());
        assertEquals(0, replacements.get()); assertEquals(0, deletions.get()); assertTrue(owner.valuesCurrent(s));
        writer.acceptPrepared(); callback.run(); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); assertTrue(source.closed());
        assertEquals("field", files.read(target).noise2D.getFirst().id); assertEquals(1, replacements.get()); assertEquals(0, deletions.get());
        assertTrue(owner.valuesCurrent(s)); assertEquals(bytes, s.document.historyBytes()); assertEquals(user, ProjectIO.JSON.valueToTree(s.user)); noTemporaryFiles();
      }
    }
  }

  @Test
  void readySuccessFailureFatalNullAndCancellationCanRetireWithoutReplacementOrPrimaryFeedback() throws Exception {
    for (int mode = 0; mode < 5; mode++) try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("cancel-" + mode); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      var writes = new AtomicInteger(); var replacements = new AtomicInteger(); var deletions = new AtomicInteger(); var files = files(writes, replacements, deletions); int action = mode;
      try (var writer = new ResourceExportWriter(s, worker, ui::add, (path, payload, active) -> {
        if (action == 1) throw new IOException("preparation"); if (action == 2) throw new AssertionError("preparation");
        if (action == 3) return null; if (action == 4) throw new CancellationException("preparation"); return files.prepare(path, payload, active);
      }, () -> false)) {
        int events = s.events.snapshot().size(); var owner = new ProjectFileOwner(s);
        writer.start(target.toString(), source, source.preview(), () -> true); var callback = deliver(worker, ui);
        assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase()); assertEquals(events, s.events.snapshot().size());
        writer.cancel(); callback.run(); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.CANCELLED, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertTrue(source.canExport()); assertTrue(owner.valuesCurrent(s)); assertEquals(0, replacements.get());
        assertEquals(mode == 0 ? 1 : 0, deletions.get()); assertEquals(events, s.events.snapshot().size()); noTemporaryFiles();
      }
    }
  }

  @Test
  void equivalentButDifferentDisplayedPathRetiresStagingAndNeedsExplicitRestaging() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("path.json"); String raw = directory.resolve(".").resolve("path.json").toString();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var files = new ResourcePackageFiles();
      try (var writer = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        writer.start(raw, source, source.preview(), () -> true); deliver(worker, ui); writer.acceptPrepared(target.toString(), () -> true);
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertTrue(worker.tasks.isEmpty()); assertTrue(source.canExport()); noTemporaryFiles();
        writer.start(target.toString(), source, source.preview(), () -> true); deliver(worker, ui); writer.acceptPrepared(target.toString(), () -> true);
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); noTemporaryFiles();
      }
    }
  }

  @Test
  void unavailableOpeningDisposesReadyStagingAndRetainsTheReviewedSourceForExplicitRetry() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("opening.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var files = new ResourcePackageFiles();
      try (var writer = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        writer.start(target.toString(), source, source.preview(), () -> true); deliver(worker, ui); writer.acceptPrepared(target.toString(), () -> false);
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertTrue(source.canExport()); assertTrue(worker.tasks.isEmpty()); noTemporaryFiles();
        writer.start(target.toString(), source, source.preview(), () -> true); deliver(worker, ui); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); noTemporaryFiles();
      }
    }
  }

  @Test
  void readyCommittedPrivateRawSelectionLifetimeAndPreviewChangesCannotReplaceTheDestination() throws Exception {
    for (int mode = 0; mode < 6; mode++) try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("stale-" + mode); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var files = new ResourcePackageFiles();
      try (var writer = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        writer.start(target.toString(), source, source.preview(), () -> true); deliver(worker, ui);
        if (mode == 0) s.project().project.seed++; else if (mode == 1) ((Noise) s.draft).id = "new-private";
        else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+");
        else if (mode == 3) s.multiSelection.clear(); else if (mode == 4) s.newSession(); else source.select(Set.of());
        var owner = new ProjectFileOwner(s); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertFalse(source.closed()); assertTrue(owner.valuesCurrent(s)); noTemporaryFiles();
      }
    }
  }

  @Test
  void aSaveAsAfterReadinessCannotTurnTheActiveProjectIntoAnExportPackage() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("became-project.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var files = new ResourcePackageFiles();
      try (var writer = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        writer.start(target.toString(), source, source.preview(), () -> true); deliver(worker, ui);
        s.document.save(target); var bytes = Files.readAllBytes(target); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.STALE, writer.view().phase()); assertArrayEquals(bytes, Files.readAllBytes(target));
        assertTrue(new ProjectIO().load(target).success()); assertFalse(source.closed()); noTemporaryFiles();
      }
    }
  }

  @Test
  void acceptedPreparationFailureKeepsTheExactReviewForOneExplicitRestagedRetry() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var preview = source.preview(); var target = target("retry.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      var files = new ResourcePackageFiles(); var fail = new AtomicBoolean(true);
      try (var writer = new ResourceExportWriter(s, worker, ui::add, (path, payload, active) -> {
        if (fail.getAndSet(false)) throw new IOException("preparation"); return files.prepare(path, payload, active);
      }, () -> false)) {
        writer.start(target.toString(), source, preview, () -> true); var callback = deliver(worker, ui); writer.acceptPrepared(); callback.run();
        assertEquals(ResourceExportWriter.Phase.FAILED, writer.view().phase()); assertSame(preview, source.preview()); assertTrue(source.canExport());
        assertEquals("prior", Files.readString(target)); assertTrue(worker.tasks.isEmpty());
        assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("FILE_EXPORT_PREPARATION_FAILED")).count());
        writer.start(target.toString(), source, preview, () -> true); deliver(worker, ui); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); assertTrue(source.closed()); noTemporaryFiles();
      }
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureCleansStagingBeforeAnyReplacement() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("handoff.json"); var files = new ResourcePackageFiles(); var once = new AtomicBoolean(true);
      try (var writer = new ResourceExportWriter(s, Runnable::run, callback -> {
        callback.run(); if (once.getAndSet(false)) throw new AssertionError("late handoff");
      }, files::prepare, () -> false)) {
        writer.start(target.toString(), source, source.preview(), () -> true); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.FAILED, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertTrue(source.canExport()); noTemporaryFiles();
        writer.start(target.toString(), source, source.preview(), () -> true); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); noTemporaryFiles();
      }
    }
  }

  @Test
  void blockerCannotPublishOrDisposeTheReadySuccessorItCreates() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("blocker.json"); var writes = new AtomicInteger(); var replacements = new AtomicInteger(); var deletions = new AtomicInteger();
      var files = files(writes, replacements, deletions); var replace = new AtomicBoolean(false); var holder = new ResourceExportWriter[1];
      try (var writer = new ResourceExportWriter(s, Runnable::run, Runnable::run, files::prepare, () -> {
        if (replace.getAndSet(false)) { holder[0].cancel(); holder[0].start(target.toString(), source, source.preview(), () -> true); } return false;
      })) {
        holder[0] = writer; writer.start(target.toString(), source, source.preview(), () -> true); replace.set(true); writer.acceptPrepared();
        assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase()); assertEquals("prior", Files.readString(target));
        assertEquals(2, writes.get()); assertEquals(1, deletions.get()); assertEquals(0, replacements.get());
        writer.acceptPrepared(); assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase());
        assertEquals(1, replacements.get()); assertEquals(1, deletions.get()); noTemporaryFiles();
      }
    }
  }

  @Test
  void presentationCallbackCannotAuthorizeTheReadySuccessorItCreates() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("shown.json"); var files = new ResourcePackageFiles(); var once = new AtomicBoolean(true);
      try (var writer = new ResourceExportWriter(s, Runnable::run, Runnable::run, files::prepare, () -> false)) {
        writer.start(target.toString(), source, source.preview(), () -> true); writer.acceptPrepared(target.toString(), () -> {
          if (once.getAndSet(false)) { writer.cancel(); writer.start(target.toString(), source, source.preview(), () -> true); } return true;
        });
        assertEquals(ResourceExportWriter.Phase.RUNNING, writer.view().phase()); assertEquals("prior", Files.readString(target));
        writer.acceptPrepared(); assertEquals(ResourceExportWriter.Phase.READY, writer.view().phase()); noTemporaryFiles();
      }
    }
  }

  @Test
  void aClosedPredecessorCannotConsumeTheSuccessorStagingOrItsReviewedSource() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var source = reviewed(s); var target = target("closed.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var files = new ResourcePackageFiles();
      var first = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false);
      first.start(target.toString(), source, source.preview(), () -> true); var old = deliver(worker, ui); first.close();
      try (var next = new ResourceExportWriter(s, worker, ui::add, files::prepare, () -> false)) {
        next.start(target.toString(), source, source.preview(), () -> true); old.run(); next.acceptPrepared();
        assertFalse(source.closed()); assertEquals("prior", Files.readString(target));
        deliver(worker, ui); old.run(); next.acceptPrepared(); assertTrue(source.closed());
        assertEquals(ResourceExportWriter.Phase.CLOSED, first.view().phase()); noTemporaryFiles();
      }
    }
  }

  @Test
  void pathSubmitCannotPublishItsInlineStagingAsThePreviouslyShownWork() throws Exception {
    try (var s = ReadyValidationTest.state(); var properties = new PropertyPanel(s); var resources = new ResourcePanel(s, properties);
        var imports = new ResourceImportPanel(s, () -> false, () -> {})) {
      s.document.save(directory.resolve("document.pvproj.json")); var target = ResourcePackageFiles.suggestedLocation(s.document.path()); Files.writeString(target, "prior");
      var files = new ResourcePackageFiles();
      try (var dialog = new ResourcePackageDialog(s, resources, imports, () -> false, files, Runnable::run, Runnable::run,
          (path, destination) -> ImportEditSession.prepare(destination, new ProjectData()))) {
        assertTrue(dialog.open("Export")); var source = resources.exportView().session(); source.markShown(source.preview());
        var shown = dialog.capture(1); var request = dialog.exportRequestToken(); dialog.dispatch(shown, ResourcePackageDialog.Command.SUBMIT, 1);
        dialog.acceptExport(shown, request); assertTrue(dialog.pending()); assertTrue(dialog.exportRunning()); assertEquals("prior", Files.readString(target));
        dialog.acceptExport(dialog.capture(2), dialog.exportRequestToken()); assertFalse(dialog.pending()); assertTrue(source.closed());
        assertEquals("field", files.read(target).noise2D.getFirst().id); noTemporaryFiles();
      }
    }
  }
}
