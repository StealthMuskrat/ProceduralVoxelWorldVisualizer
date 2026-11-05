package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred current-saving decisions before replacement/checkpoint publication and continuation. */
class ReadyProjectSaveTest {
  @TempDir Path directory;

  static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    public void execute(Runnable task) { tasks.add(task); }
    void run() { tasks.remove().run(); }
  }

  Path target(String name) throws Exception { var path = directory.resolve(name); Files.writeString(path, "prior"); return path; }

  List<Path> temporaryFiles() throws Exception {
    try (var paths = Files.walk(directory)) { return paths.filter(p -> p.getFileName().toString().startsWith(".voxel-save-")).toList(); }
  }

  static void save(ProjectFileWorkflow files, String path) { files.save(true); assertEquals(Phase.SAVE, files.phase()); files.submit(path); }

  static void transition(ProjectFileWorkflow files, Intent intent, String path) {
    files.request(intent); if (files.phase() == Phase.DRAFT) files.decideDraft(DraftDecision.DISCARD);
    assertEquals(Phase.GUARD, files.phase()); files.decide(Decision.SAVE);
    if (files.phase() == Phase.SAVE) files.submit(path); else assertEquals(Phase.SAVING, files.phase());
  }

  static Runnable deliver(Workers worker, Queue<Runnable> ui) { worker.run(); var callback = ui.remove(); callback.run(); return callback; }

  @Test
  void readinessKeepsPriorBytesCheckpointHistoryPrivateInputsAndAttemptUntilOneAcceptedSave() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      assertTrue(s.change(p -> p.project.seed = 7)); s.document.undo();
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var path = target("资源 project.json"); var validations = new AtomicInteger();
      s.document.setValidator(p -> { validations.incrementAndGet(); return NoiseCompiler.validate(p); });
      var owner = new ProjectFileOwner(s); var project = s.project(); var lifetime = s.document.sessionToken(); var draft = s.draft;
      var user = ProjectIO.JSON.valueToTree(s.user); long bytes = s.document.historyBytes(); boolean undo = s.document.canUndo(), redo = s.document.canRedo();
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        save(files, path.toString()); long version = files.saveVersion(), request = files.requestVersion(); var callback = deliver(worker, ui); callback.run();
        assertEquals(Phase.SAVING, files.phase()); assertEquals(version, files.saveVersion()); assertEquals(request, files.requestVersion()); assertTrue(files.error().isEmpty());
        assertEquals("prior", Files.readString(path)); assertNull(s.document.path()); assertTrue(s.document.dirty()); assertTrue(owner.valuesCurrent(s));
        assertEquals(1, temporaryFiles().size()); assertEquals(1, validations.get()); files.acceptSave(); var saved = Files.readAllBytes(path); callback.run(); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertSame(project, s.project()); assertSame(lifetime, s.document.sessionToken()); assertSame(draft, s.draft);
        assertEquals(path, s.document.path()); assertFalse(s.document.dirty()); assertEquals(bytes, s.document.historyBytes());
        assertEquals(undo, s.document.canUndo()); assertEquals(redo, s.document.canRedo()); assertEquals(user, ProjectIO.JSON.valueToTree(s.user)); assertEquals(1, validations.get());
        assertEquals(ProjectIO.JSON.valueToTree(s.project()), ProjectIO.JSON.valueToTree(new ProjectIO().load(path).project()));
        assertArrayEquals(saved, Files.readAllBytes(path)); assertTrue(temporaryFiles().isEmpty());
        s.document.redo(); assertTrue(s.document.dirty()); s.document.undo(); assertFalse(s.document.dirty());
      }
    }
  }

  @Test
  void readySuccessRejectedAndFatalResultsCanCancelEverySaveIntentWithoutFeedbackOrContinuation() throws Exception {
    for (int intent = 0; intent < 4; intent++) for (int mode = 0; mode < 3; mode++) try (var s = ReadyValidationTest.state()) {
      var path = target("cancel-" + intent + "-" + mode + ".json");
      if (mode == 1) s.document.setValidator(p -> List.of(Diagnostic.error("BAD", "/", "rejected")));
      if (mode == 2) s.document.setValidator(p -> { throw new AssertionError("validator"); });
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s); int events = s.events.snapshot().size(); long history = s.document.historyBytes();
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        if (intent == 0) save(files, path.toString()); else transition(files, Intent.values()[intent - 1], path.toString());
        var callback = deliver(worker, ui); assertEquals(Phase.SAVING, files.phase()); assertTrue(files.error().isEmpty());
        files.cancel(); callback.run(); files.acceptSave(); assertEquals(Phase.IDLE, files.phase()); assertEquals("prior", Files.readString(path));
        assertTrue(owner.valuesCurrent(s)); assertEquals(history, s.document.historyBytes()); assertEquals(events, s.events.snapshot().size()); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void equivalentButDifferentDisplayedPathDisposesStagingAndAbortsTheOldTransition() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var path = target("path.json"); String raw = directory.resolve(".").resolve("path.json").toString(); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        var owner = new ProjectFileOwner(s); transition(files, Intent.NEW, raw); deliver(worker, ui); files.acceptSave(path.toString(), () -> true);
        assertEquals(Phase.IDLE, files.phase()); assertTrue(owner.valuesCurrent(s)); assertEquals("prior", Files.readString(path)); assertTrue(temporaryFiles().isEmpty()); assertTrue(worker.tasks.isEmpty());
        save(files, path.toString()); deliver(worker, ui); files.acceptSave(); assertEquals(Phase.IDLE, files.phase()); assertFalse(s.document.dirty()); assertNotNull(s.draft);
      }
    }
  }

  @Test
  void unavailableSavingOpeningRetainsTheOwnerAndPriorFileUntilExplicitRestaging() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var path = target("opening.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s);
      try (var files = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, worker, ui::add)) {
        save(files, path.toString()); deliver(worker, ui); files.acceptSave(path.toString(), () -> false);
        assertEquals(Phase.IDLE, files.phase()); assertTrue(owner.valuesCurrent(s)); assertEquals("prior", Files.readString(path)); assertTrue(temporaryFiles().isEmpty());
        assertTrue(worker.tasks.isEmpty()); save(files, path.toString()); deliver(worker, ui); files.acceptSave(); assertFalse(s.document.dirty());
      }
    }
  }

  @Test
  void readySourcePrivateRawSelectionLifetimeCheckpointAndPolicyChangesCannotReplacePriorBytes() throws Exception {
    for (int mode = 0; mode < 7; mode++) try (var s = ReadyValidationTest.state()) {
      var path = target("stale-" + mode + ".json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        save(files, path.toString()); deliver(worker, ui);
        if (mode == 0) s.project().project.seed++; else if (mode == 1) ((Noise) s.draft).id = "new-private";
        else if (mode == 2) s.seedInputs.edit(s, "seedOffset", "Seed", "+"); else if (mode == 3) s.multiSelection.clear();
        else if (mode == 4) s.newSession(); else if (mode == 5) s.document.save(directory.resolve("new-checkpoint.json")); else s.document.setValidator(p -> List.of());
        var owner = new ProjectFileOwner(s); files.acceptSave(); assertEquals(mode == 6 ? Phase.SAVE : Phase.IDLE, files.phase());
        assertTrue(owner.valuesCurrent(s)); assertEquals("prior", Files.readString(path)); assertTrue(temporaryFiles().isEmpty()); assertTrue(worker.tasks.isEmpty());
      }
    }
  }

  @Test
  void acceptedPreparationFailureRetriesOnlyTheSaveAndNeverTheAbortedNewOpenOrExit() throws Exception {
    for (var intent : Intent.values()) try (var s = ReadyValidationTest.state()) {
      var path = target("failure-" + intent + ".json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s);
      s.document.setValidator(p -> { throw new AssertionError("validator"); });
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        transition(files, intent, path.toString()); var callback = deliver(worker, ui); files.acceptSave(); callback.run();
        assertEquals(Phase.SAVE, files.phase()); assertTrue(owner.valuesCurrent(s)); assertEquals(path.toString(), files.savingLocation());
        assertEquals("prior", Files.readString(path)); assertTrue(worker.tasks.isEmpty()); assertTrue(temporaryFiles().isEmpty());
        assertEquals(1, s.events.snapshot().stream().filter(e -> e.code().equals("PROJECT_SAVE_FAILED")).count());
        s.document.setValidator(NoiseCompiler::validate); files.submit(path.toString()); deliver(worker, ui); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertFalse(s.document.dirty()); assertNotNull(s.draft); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void namedSaveAlsoKeepsThePriorCheckpointUntilTheCurrentSavingDecisionAccepts() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var path = directory.resolve("named.json"); s.document.save(path); var before = Files.readAllBytes(path); assertTrue(s.change(p -> p.project.seed = 43));
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var draft = s.draft; long history = s.document.historyBytes();
      try (var files = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, worker, ui::add)) {
        files.save(false); assertEquals(Phase.SAVING, files.phase()); var callback = deliver(worker, ui);
        assertArrayEquals(before, Files.readAllBytes(path)); assertTrue(s.document.dirty()); assertSame(draft, s.draft);
        files.acceptSave(path.toString(), () -> true); callback.run(); assertEquals(43, new ProjectIO().load(path).project().project.seed);
        assertFalse(s.document.dirty()); assertSame(draft, s.draft); assertEquals(history, s.document.historyBytes()); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void transitionBlockersAreRepeatedAfterPresentationWhileStandaloneSaveStaysIndependent() throws Exception {
    for (boolean required : new boolean[] {false, true}) try (var s = ReadyValidationTest.state()) {
      var path = target("block-" + required + ".json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var blocked = new AtomicBoolean(false);
      var owner = new ProjectFileOwner(s);
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), blocked::get, worker, ui::add)) {
        if (required) transition(files, Intent.NEW, path.toString()); else save(files, path.toString());
        deliver(worker, ui); files.acceptSave(path.toString(), () -> { blocked.set(true); return true; });
        assertEquals(Phase.IDLE, files.phase()); assertTrue(temporaryFiles().isEmpty());
        if (required) { assertEquals("prior", Files.readString(path)); assertTrue(owner.valuesCurrent(s)); }
        else { assertFalse(s.document.dirty()); assertNotNull(s.draft); }
      }
    }
  }

  @Test
  void blockerCannotPublishOrDisposeTheInlineReadySuccessorSaveItCreates() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var old = target("blocker-old.json"); var next = target("blocker-next.json"); var replace = new AtomicBoolean(false); var holder = new ProjectFileWorkflow[1];
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> {
        if (replace.getAndSet(false)) { holder[0].cancel(); save(holder[0], next.toString()); } return false;
      }, Runnable::run, Runnable::run)) {
        holder[0] = files; transition(files, Intent.NEW, old.toString()); replace.set(true); files.acceptSave();
        assertEquals(Phase.SAVING, files.phase()); assertEquals("prior", Files.readString(old)); assertEquals("prior", Files.readString(next)); assertEquals(1, temporaryFiles().size());
        files.acceptSave(); assertEquals(Phase.IDLE, files.phase()); assertEquals(next, s.document.path()); assertEquals("prior", Files.readString(old)); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void presentationCallbackCannotAuthorizeTheInlineReadySuccessorSaveItCreates() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var old = target("shown-old.json"); var next = target("shown-next.json"); var once = new AtomicBoolean(true);
      try (var files = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, Runnable::run, Runnable::run)) {
        save(files, old.toString()); files.acceptSave(old.toString(), () -> { if (once.getAndSet(false)) { files.cancel(); save(files, next.toString()); } return true; });
        assertEquals(Phase.SAVING, files.phase()); assertEquals("prior", Files.readString(old)); assertEquals(1, temporaryFiles().size());
        files.acceptSave(); assertEquals(next, s.document.path()); assertEquals("prior", Files.readString(old)); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void inlineHandoffAndExecutorFaultsAfterReadinessCannotPublishUnacceptedStaging() throws Exception {
    for (boolean executor : new boolean[] {false, true}) try (var s = ReadyValidationTest.state()) {
      var path = target("late-" + executor + ".json"); var once = new AtomicBoolean(true); var owner = new ProjectFileOwner(s);
      Executor worker = task -> { task.run(); if (executor && once.getAndSet(false)) throw new AssertionError("executor"); };
      try (var files = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, worker, callback -> {
        callback.run(); if (!executor && once.getAndSet(false)) throw new AssertionError("handoff");
      })) {
        save(files, path.toString()); files.acceptSave(); assertEquals(Phase.SAVE, files.phase()); assertTrue(owner.valuesCurrent(s));
        assertEquals("prior", Files.readString(path)); assertTrue(temporaryFiles().isEmpty()); files.submit(path.toString()); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertFalse(s.document.dirty()); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void closedPredecessorCannotConsumeOrDisposeTheNextSavePayload() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var old = target("closed-old.json"); var next = target("closed-next.json"); var worker = new Workers(); var ui = new ArrayDeque<Runnable>();
      var first = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, worker, ui::add); save(first, old.toString()); var callback = deliver(worker, ui); first.close();
      try (var files = new ProjectFileWorkflow(s, () -> {}, () -> {}, () -> false, worker, ui::add)) {
        save(files, next.toString()); callback.run(); files.acceptSave(); assertEquals(Phase.SAVING, files.phase()); assertEquals("prior", Files.readString(next));
        deliver(worker, ui); callback.run(); files.acceptSave(); assertEquals(next, s.document.path()); assertEquals("prior", Files.readString(old)); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void consumedSaveBeforeNewCanAdmitAReadySuccessorEvenWhenItsReplacementRefreshThrows() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var old = target("new-old.json"); var next = target("new-next.json"); var holder = new ProjectFileWorkflow[1]; var replacements = new AtomicInteger(); var before = ProjectIO.JSON.valueToTree(s.project());
      try (var files = new ProjectFileWorkflow(s, () -> {
        replacements.incrementAndGet(); save(holder[0], next.toString()); throw new AssertionError("refresh");
      }, () -> fail("exit"), () -> false, Runnable::run, Runnable::run)) {
        holder[0] = files; transition(files, Intent.NEW, old.toString()); files.acceptSave();
        assertEquals(Phase.SAVING, files.phase()); assertTrue(files.error().isEmpty()); assertEquals(1, replacements.get()); assertTrue(s.project().noise2D.isEmpty());
        assertEquals(before, ProjectIO.JSON.valueToTree(new ProjectIO().load(old).project())); assertEquals("prior", Files.readString(next));
        files.acceptSave(); assertEquals(Phase.IDLE, files.phase()); assertEquals(next, s.document.path()); assertEquals(1, replacements.get()); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void acceptedAtomicReplacementFailureDisposesItsStagingAndOnlyExplicitSaveRetryCanPublish() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var path = directory.resolve("blocked"); Files.createDirectory(path); var sentinel = path.resolve("sentinel"); Files.writeString(sentinel, "keep");
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var owner = new ProjectFileOwner(s);
      try (var files = new ProjectFileWorkflow(s, () -> fail("replacement"), () -> fail("exit"), () -> false, worker, ui::add)) {
        transition(files, Intent.EXIT, path.toString()); deliver(worker, ui); files.acceptSave(); assertEquals(Phase.SAVE, files.phase());
        assertEquals("keep", Files.readString(sentinel)); assertTrue(owner.valuesCurrent(s)); assertTrue(temporaryFiles().isEmpty()); assertTrue(worker.tasks.isEmpty());
        Files.delete(sentinel); Files.delete(path); Files.writeString(path, "prior"); files.submit(path.toString()); deliver(worker, ui); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertFalse(s.document.dirty()); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void immediateNamedSaveFailureRetainsItsRetryDestinationWithoutUsingTheSuppliedOpenFilename() throws Exception {
    try (var s = ReadyValidationTest.state()) {
      var named = directory.resolve("named-before-open.json"); s.document.save(named); assertTrue(s.change(p -> p.project.seed = 43));
      var incoming = directory.resolve("incoming.json"); var candidate = new ProjectData(); candidate.project.seed = 99; new ProjectIO().save(incoming, candidate); var bytes = Files.readAllBytes(incoming);
      var worker = new Workers(); var ui = new ArrayDeque<Runnable>(); var reject = new AtomicBoolean(true);
      var dialog = ProjectFileDialog.background(s, () -> fail("replacement"), () -> fail("exit"), () -> false,
          task -> { if (reject.get()) throw new RejectedExecutionException("scheduler"); worker.execute(task); }, ui::add);
      try (var files = dialog.workflow) {
        assertTrue(dialog.openLocation(incoming.toString())); files.decideDraft(DraftDecision.DISCARD); files.decide(Decision.SAVE);
        assertEquals(Phase.SAVE, files.phase()); assertEquals(named.toString(), files.savingLocation()); assertEquals(incoming.toString(), dialog.probePath());
        assertTrue(temporaryFiles().isEmpty()); reject.set(false); files.submit(files.savingLocation()); deliver(worker, ui); files.acceptSave();
        assertEquals(Phase.IDLE, files.phase()); assertEquals(named, s.document.path()); assertEquals(43, new ProjectIO().load(named).project().project.seed);
        assertArrayEquals(bytes, Files.readAllBytes(incoming)); assertNotNull(s.draft); assertTrue(temporaryFiles().isEmpty());
      }
    }
  }
}
