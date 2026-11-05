package io.voxelcreator.editor;

import static io.voxelcreator.editor.ProjectFileWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deferred background Save and transition authority; no independently scheduled test is run. */
class BackgroundProjectSaveTest {
  @TempDir Path directory;

  private static final class Workers implements Executor {
    final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    public void execute(Runnable task) {
      tasks.add(task);
    }

    void run() {
      tasks.removeFirst().run();
    }
  }

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    assertTrue(
        state.change(
            p -> {
              p.project.seed = 41;
              var n = new Noise();
              n.id = "owned";
              n.algorithm.key = "CONSTANT";
              n.algorithm.parameters.clear();
              n.algorithm.parameters.put("value", .25);
              p.noise2D.add(n);
            }));
    state.select("noise2D", state.project().noise2D.getFirst().uuid);
    ((Noise) state.draft).id = "unapplied";
    return state;
  }

  private ProjectFileWorkflow workflow(EditorState state, Executor workers, Consumer<Runnable> ui) {
    return new ProjectFileWorkflow(state, () -> {}, () -> {}, () -> false, workers, ui);
  }

  private void transition(ProjectFileWorkflow workflow, Intent intent) {
    workflow.request(intent);
    workflow.decideDraft(DraftDecision.DISCARD);
    assertEquals(Phase.GUARD, workflow.phase());
    workflow.decide(Decision.SAVE);
    assertEquals(Phase.SAVE, workflow.phase());
  }

  private List<Path> temporaryFiles() throws Exception {
    try (var paths = Files.list(directory)) {
      return paths.filter(p -> p.getFileName().toString().startsWith(".voxel-save-")).toList();
    }
  }

  @Test
  void saveAsPreparesWithoutPublishingThenUpdatesOnlyPathAndCheckpoint() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      var active = state.project();
      var draft = state.draft;
      var token = state.document.sessionToken();
      long revision = state.revision, history = state.document.historyBytes();
      var path = directory.resolve("save-as.json");
      Files.writeString(path, "previous destination");
      files.save(true);
      files.submit(path.toString());
      assertEquals(Phase.SAVING, files.phase());
      workers.run();
      assertEquals("previous destination", Files.readString(path));
      assertEquals(1, temporaryFiles().size());
      assertNull(state.document.path());
      assertTrue(state.document.dirty());
      long readyVersion = files.saveVersion();
      ui.removeFirst().run();
      assertEquals(Phase.SAVING, files.phase());
      assertEquals(readyVersion, files.saveVersion());
      assertEquals("previous destination", Files.readString(path));
      assertNull(state.document.path());
      assertTrue(state.document.dirty());
      files.acceptSave();
      assertEquals(Phase.IDLE, files.phase());
      assertSame(active, state.project());
      assertSame(draft, state.draft);
      assertSame(token, state.document.sessionToken());
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertFalse(state.document.dirty());
      assertEquals(path, state.document.path());
      assertEquals("owned", new ProjectIO().load(path).project().noise2D.getFirst().id);
      assertEquals("unapplied", ((Noise) state.draft).id);
      assertTrue(temporaryFiles().isEmpty());
    }
  }

  @Test
  void namedSaveStartsBackgroundPreparationWithoutAPathDialog() throws Exception {
    try (var state = state()) {
      var path = directory.resolve("named.json");
      state.document.save(path);
      state.change(p -> p.project.seed = 42);
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      files.save(false);
      assertEquals(Phase.SAVING, files.phase());
      workers.run();
      assertEquals(41, new ProjectIO().load(path).project().project.seed);
      ui.removeFirst().run();
      files.acceptSave();
      assertEquals(42, new ProjectIO().load(path).project().project.seed);
      assertFalse(state.document.dirty());
    }
  }

  @Test
  void saveBeforeEachTransitionContinuesOnlyAfterSuccessfulUiPublication() throws Exception {
    for (var intent : Intent.values()) {
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        int[] replacements = {0}, exits = {0};
        var files =
            new ProjectFileWorkflow(
                state, () -> replacements[0]++, () -> exits[0]++, () -> false, workers, ui::add);
        transition(files, intent);
        var path = directory.resolve(intent + ".json");
        files.submit(path.toString());
        workers.run();
        assertEquals(Phase.SAVING, files.phase());
        assertArrayEquals(new int[] {0}, replacements);
        assertArrayEquals(new int[] {0}, exits);
        ui.removeFirst().run();
        files.acceptSave();
        assertEquals(41, new ProjectIO().load(path).project().project.seed);
        assertEquals(intent == Intent.OPEN ? Phase.OPEN : Phase.IDLE, files.phase());
        assertEquals(intent == Intent.NEW ? 1 : 0, replacements[0]);
        assertEquals(intent == Intent.EXIT ? 1 : 0, exits[0]);
      }
    }
  }

  @Test
  void cancellationAfterStagingDiscardsFileAndEveryDestructiveTransition() throws Exception {
    for (var intent : Intent.values()) {
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var files =
            new ProjectFileWorkflow(
                state,
                () -> fail("replacement"),
                () -> fail("exit"),
                () -> false,
                workers,
                ui::add);
        transition(files, intent);
        var path = directory.resolve(intent + "-cancel.json");
        Files.writeString(path, "previous destination");
        files.submit(path.toString());
        workers.run();
        files.cancel();
        ui.removeFirst().run();
        files.acceptSave();
        assertEquals("previous destination", Files.readString(path));
        assertTrue(temporaryFiles().isEmpty());
        assertEquals(Phase.IDLE, files.phase());
        assertEquals(41, state.project().project.seed);
        assertTrue(state.document.dirty());
      }
    }
  }

  @Test
  void cancellationBeforeWorkerAdmissionCreatesNoTemporaryFile() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      var path = directory.resolve("queued.json");
      files.save(true);
      files.submit(path.toString());
      files.cancel();
      workers.run();
      assertFalse(Files.exists(path));
      assertTrue(temporaryFiles().isEmpty());
      assertTrue(ui.isEmpty());
    }
  }

  @Test
  void validationFailureRetrySavesWithoutResumingTheAbortedTransition() throws Exception {
    for (var intent : Intent.values()) {
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var files =
            new ProjectFileWorkflow(
                state,
                () -> fail("replacement"),
                () -> fail("exit"),
                () -> false,
                workers,
                ui::add);
        state.document.setValidator(
            p -> {
              throw new AssertionError("compiler fault");
            });
        transition(files, intent);
        var path = directory.resolve(intent + "-retry.json");
        files.submit(path.toString());
        workers.run();
        ui.removeFirst().run();
        files.acceptSave();
        assertEquals(Phase.SAVE, files.phase());
        assertFalse(files.error().isEmpty());
        assertFalse(Files.exists(path));
        state.document.setValidator(NoiseCompiler::validate);
        files.submit(path.toString());
        workers.run();
        ui.removeFirst().run();
        files.acceptSave();
        assertEquals(Phase.IDLE, files.phase());
        assertEquals(41, state.project().project.seed);
        assertFalse(state.document.dirty());
      }
    }
  }

  @Test
  void sameRevisionRawMutationPreservesDestinationAndCleansStaging() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      var path = directory.resolve("raw.json");
      Files.writeString(path, "previous destination");
      files.save(true);
      files.submit(path.toString());
      workers.run();
      state.project().project.seed = 42;
      ui.removeFirst().run();
      files.acceptSave();
      assertEquals("previous destination", Files.readString(path));
      assertEquals(Phase.IDLE, files.phase());
      assertEquals(42, state.project().project.seed);
      assertTrue(temporaryFiles().isEmpty());
    }
  }

  @Test
  void privateChangeOrChangedPolicyCannotReplaceTheDestination() throws Exception {
    for (boolean policy : List.of(false, true)) {
      try (var state = state()) {
        var workers = new Workers();
        var ui = new ArrayDeque<Runnable>();
        var files = workflow(state, workers, ui::add);
        var path = directory.resolve("changed-" + policy + ".json");
        Files.writeString(path, "previous destination");
        files.save(true);
        files.submit(path.toString());
        workers.run();
        if (policy) state.document.setValidator(p -> List.of());
        else ((Noise) state.draft).id = "later private value";
        ui.removeFirst().run();
        files.acceptSave();
        assertEquals("previous destination", Files.readString(path));
        assertEquals(policy ? Phase.SAVE : Phase.IDLE, files.phase());
        assertEquals(policy ? "unapplied" : "later private value", ((Noise) state.draft).id);
        assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void rejectedWorkerOrUiHandoffIsRetryableAndCannotLeakAStagedFile() throws Exception {
    for (boolean scheduler : List.of(false, true)) {
      try (var state = state()) {
        var workers = new Workers();
        Executor executor =
            scheduler
                ? task -> {
                  throw new RejectedExecutionException("busy");
                }
                : workers;
        var files =
            workflow(
                state,
                executor,
                task -> {
                  throw new AssertionError("handoff failed");
                });
        var path = directory.resolve("handoff-" + scheduler + ".json");
        files.save(true);
        files.submit(path.toString());
        if (!scheduler) workers.run();
        assertEquals(Phase.SAVE, files.phase());
        assertFalse(Files.exists(path));
        assertTrue(temporaryFiles().isEmpty());
      }
    }
  }

  @Test
  void shutdownRetiresQueuedPublicationAndDeletesItsUnpublishedSibling() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      var path = directory.resolve("close.json");
      files.save(true);
      files.submit(path.toString());
      workers.run();
      files.close();
      ui.removeFirst().run();
      files.acceptSave();
      assertEquals(Phase.IDLE, files.phase());
      assertFalse(Files.exists(path));
      assertTrue(temporaryFiles().isEmpty());
      assertTrue(state.document.dirty());
    }
  }

  @Test
  void aCancelledPredecessorCannotConsumeTheNextSavePayload() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files = workflow(state, workers, ui::add);
      var old = directory.resolve("old.json");
      var next = directory.resolve("next.json");
      files.save(true);
      files.submit(old.toString());
      workers.run();
      files.cancel();
      files.save(true);
      files.submit(next.toString());
      workers.run();
      ui.removeFirst().run();
      files.acceptSave();
      assertEquals(Phase.SAVING, files.phase());
      assertFalse(Files.exists(old));
      ui.removeFirst().run();
      files.acceptSave();
      assertTrue(Files.exists(next));
      assertEquals(next, state.document.path());
      assertTrue(temporaryFiles().isEmpty());
    }
  }

  @Test
  void failedAtomicReplacementRetainsTheFileAndAbortsThePendingExit() throws Exception {
    try (var state = state()) {
      var workers = new Workers();
      var ui = new ArrayDeque<Runnable>();
      var files =
          new ProjectFileWorkflow(
              state, () -> fail("replacement"), () -> fail("exit"), () -> false, workers, ui::add);
      transition(files, Intent.EXIT);
      var target = directory.resolve("blocked");
      Files.createDirectory(target);
      Files.writeString(target.resolve("sentinel"), "retained");
      files.submit(target.toString());
      workers.run();
      ui.removeFirst().run();
      files.acceptSave();
      assertEquals(Phase.SAVE, files.phase());
      assertEquals("retained", Files.readString(target.resolve("sentinel")));
      assertTrue(temporaryFiles().isEmpty());
      assertTrue(state.document.dirty());
      assertEquals(41, state.project().project.seed);
    }
  }
}
