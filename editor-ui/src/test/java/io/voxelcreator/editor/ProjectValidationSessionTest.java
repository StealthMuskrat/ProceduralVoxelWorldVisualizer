package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.core.NoiseCompiler;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred snapshot, result-publication and failure-recovery contracts. */
class ProjectValidationSessionTest {
  private static final Diagnostic PRIVATE = Diagnostic.error("PRIVATE", "/project/seed", "draft");
  private static final Diagnostic RESULT = Diagnostic.error("RESULT", "/project/maxY", "snapshot");

  private EditorState state() {
    var state = new EditorState(new UserConfig(), false);
    state.issues = List.of(PRIVATE);
    state.validationScope = "Rejected edit";
    return state;
  }

  private ProjectValidationSession session(
      EditorState state,
      ArrayDeque<Runnable> work,
      ArrayDeque<Runnable> ui,
      Function<ProjectData, List<Diagnostic>> validator) {
    return new ProjectValidationSession(state, work::add, ui::add, validator, message -> {});
  }

  @Test
  void workerOwnsASnapshotAndOnlyUiPublicationReplacesDiagnostics() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var original = state.project();
      var values = ProjectIO.JSON.valueToTree(original);
      long revision = state.revision, history = state.document.historyBytes();
      try (var session =
          session(
              state,
              work,
              ui,
              snapshot -> {
                assertNotSame(original, snapshot);
                assertEquals(values, ProjectIO.JSON.valueToTree(snapshot));
                return new ArrayList<>(List.of(RESULT));
              })) {
        assertTrue(session.start());
        assertFalse(session.start());
        work.remove().run();
        assertEquals(ProjectValidationSession.Phase.RUNNING, session.view().phase());
        assertEquals(List.of(PRIVATE), state.issues);
        assertEquals("Rejected edit", state.validationScope);
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
        assertEquals(List.of(RESULT), state.issues);
        assertEquals("Committed project", state.validationScope);
        assertThrows(UnsupportedOperationException.class, () -> state.issues.clear());
        assertSame(original, state.project());
        assertEquals(values, ProjectIO.JSON.valueToTree(original));
        assertEquals(revision, state.revision);
        assertEquals(history, state.document.historyBytes());
        assertFalse(state.document.dirty());
      }
    }
  }

  @Test
  void committedEditsRetireQueuedValidationAndDoNotReplaceNewerIssues() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of(RESULT))) {
        session.start();
        work.remove().run();
        assertTrue(state.change(p -> p.project.seed = 7));
        var issues = state.issues;
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.STALE, session.view().phase());
        assertSame(issues, state.issues);
      }
    }
  }

  @Test
  void sameValueNewSessionCannotReceiveAnEarlierResult() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of(RESULT))) {
        session.start();
        work.remove().run();
        state.newSession();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.STALE, session.view().phase());
        assertTrue(state.issues.isEmpty());
      }
    }
  }

  @Test
  void newerPrivateDiagnosticPublicationRetiresTheOlderCommittedCheck() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of(RESULT))) {
        session.start();
        work.remove().run();
        state.issues = List.of(PRIVATE, PRIVATE);
        state.validationScope = "Rejected Apply";
        var current = state.issues;
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.STALE, session.view().phase());
        assertSame(current, state.issues);
      }
    }
  }

  @Test
  void unversionedModelOrMutableDiagnosticChangesAreCheckedBeforePublication() {
    for (boolean model : new boolean[] {false, true})
      try (var state = state()) {
        state.issues = new ArrayList<>(state.issues);
        var work = new ArrayDeque<Runnable>();
        var ui = new ArrayDeque<Runnable>();
        try (var session = session(state, work, ui, p -> List.of(RESULT))) {
          session.start();
          work.remove().run();
          if (model) state.project().project.seed = 23;
          else state.issues.add(RESULT);
          var current = List.copyOf(state.issues);
          ui.remove().run();
          assertEquals(ProjectValidationSession.Phase.STALE, session.view().phase());
          assertEquals(current, state.issues);
        }
      }
  }

  @Test
  void cancellationDropsQueuedWorkAndOldUiCallbacksCannotPublishIntoARetry() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of(RESULT))) {
        session.start();
        work.remove().run();
        var old = ui.remove();
        session.cancel();
        assertEquals(ProjectValidationSession.Phase.CANCELLED, session.view().phase());
        assertTrue(session.start());
        old.run();
        assertEquals(ProjectValidationSession.Phase.RUNNING, session.view().phase());
        work.remove().run();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
      }
    }
  }

  @Test
  void closureRejectsStartAndQueuedCallbacksLeaveOriginalDiagnosticsAlone() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var session = session(state, work, ui, p -> List.of(RESULT));
      session.start();
      work.remove().run();
      session.close();
      ui.remove().run();
      assertEquals(ProjectValidationSession.Phase.CLOSED, session.view().phase());
      assertFalse(session.start());
      assertEquals(List.of(PRIVATE), state.issues);
      assertDoesNotThrow(session::close);
    }
  }

  @Test
  void workerErrorsArePublishedOnlyOnUiAndReporterErrorsDoNotTrapRetry() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var reports = new ArrayList<String>();
      try (var session =
          new ProjectValidationSession(
              state,
              work::add,
              ui::add,
              p -> {
                throw new AssertionError("compile-failed");
              },
              message -> {
                reports.add(message);
                throw new AssertionError("reporter");
              })) {
        session.start();
        work.remove().run();
        assertTrue(reports.isEmpty());
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.FAILED, session.view().phase());
        assertEquals(1, reports.size());
        assertTrue(reports.getFirst().contains("compile-failed"));
        assertEquals(List.of(PRIVATE), state.issues);
        assertTrue(session.start());
      }
    }
  }

  @Test
  void schedulerRejectionAndExceptionalUiHandoffRemainRecoverable() {
    for (boolean scheduler : new boolean[] {false, true})
      try (var state = state()) {
        var work = new ArrayDeque<Runnable>();
        var reports = new ArrayList<String>();
        Executor executor =
            scheduler
                ? task -> {
                  throw new AssertionError("scheduler");
                }
                : work::add;
        try (var session =
            new ProjectValidationSession(
                state,
                executor,
                callback -> {
                  throw new AssertionError("handoff");
                },
                p -> List.of(),
                reports::add)) {
          session.start();
          if (!scheduler) work.remove().run();
          assertEquals(ProjectValidationSession.Phase.FAILED, session.view().phase());
          assertEquals(1, reports.size());
          assertEquals(List.of(PRIVATE), state.issues);
          session.view();
          assertEquals(1, reports.size());
        }
      }
  }

  @Test
  void enqueueThenRejectCannotPublishAnAbandonedResult() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session =
          new ProjectValidationSession(
              state,
              work::add,
              callback -> {
                ui.add(callback);
                throw new AssertionError("after-enqueue");
              },
              p -> List.of(RESULT),
              text -> {})) {
        session.start();
        work.remove().run();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.FAILED, session.view().phase());
        assertEquals(List.of(PRIVATE), state.issues);
      }
    }
  }

  @Test
  void successfulUiPublicationCannotBeReclassifiedByALaterExecutorFault() {
    try (var state = state();
        var session =
            new ProjectValidationSession(
                state,
                task -> {
                  task.run();
                  throw new AssertionError("late-executor");
                },
                Runnable::run,
                p -> List.of(),
                text -> {
                  throw new AssertionError("Unexpected report");
                })) {
      session.start();
      assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
      assertTrue(state.issues.isEmpty());
      assertFalse(Thread.currentThread().isInterrupted());
    }
  }

  @Test
  void privateDraftAndPreferenceChangesDoNotInvalidateCommittedSnapshotValidation() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, NoiseCompiler::validate)) {
        session.start();
        work.remove().run();
        state.draft = new ProjectData.Noise();
        state.user.performance.engineMiB = 512;
        var draft = state.draft;
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
        assertSame(draft, state.draft);
        assertEquals(512, state.user.performance.engineMiB);
        assertTrue(state.issues.isEmpty());
      }
    }
  }

  @Test
  void readyStatusBecomesStaleAfterLaterCommittedChanges() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of())) {
        session.start();
        work.remove().run();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
        state.change(p -> p.project.seed = 4);
        assertEquals(ProjectValidationSession.Phase.STALE, session.view().phase());
      }
    }
  }

  @Test
  void editorDrainObservesFailuresAndEditorClosureRetiresItsDefaultRequest() {
    try (var state = state()) {
      state.background.shutdownNow();
      assertFalse(state.committedValidation.start());
      assertEquals(ProjectValidationSession.Phase.FAILED, state.committedValidation.view().phase());
      state.close(false);
      assertEquals(ProjectValidationSession.Phase.CLOSED, state.committedValidation.view().phase());
      assertFalse(state.committedValidation.start());
    }
  }

  @Test
  void externallyCancelledWorkerIsObservedWithoutWaitingForAUiCallback() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      try (var session = session(state, work, ui, p -> List.of())) {
        session.start();
        assertTrue(((Future<?>) work.remove()).cancel(false));
        assertEquals(ProjectValidationSession.Phase.CANCELLED, session.view().phase());
        assertTrue(ui.isEmpty());
        assertEquals(List.of(PRIVATE), state.issues);
      }
    }
  }

  @Test
  void unreadableWorkerFailureAndReentrantReporterCannotRetireItsSuccessor() {
    try (var state = state()) {
      var work = new ArrayDeque<Runnable>();
      var ui = new ArrayDeque<Runnable>();
      var owner = new AtomicReference<ProjectValidationSession>();
      var calls = new AtomicInteger();
      var reports = new ArrayList<String>();
      try (var session =
          new ProjectValidationSession(
              state,
              work::add,
              ui::add,
              p -> {
                if (calls.incrementAndGet() == 1)
                  throw new AssertionError() {
                    public String getMessage() {
                      throw new AssertionError("unreadable");
                    }
                  };
                return List.of();
              },
              message -> {
                reports.add(message);
                owner.get().start();
                throw new AssertionError("reporter");
              })) {
        owner.set(session);
        session.start();
        work.remove().run();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.RUNNING, session.view().phase());
        assertEquals(1, reports.size());
        assertTrue(reports.getFirst().startsWith("Project validation failed: "));
        work.remove().run();
        ui.remove().run();
        assertEquals(ProjectValidationSession.Phase.READY, session.view().phase());
        assertTrue(state.issues.isEmpty());
      }
    }
  }
}
