package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.*;
import io.voxelcreator.model.ProjectData.Noise;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** Deferred current-control acceptance for read-only committed-project diagnostics. */
class ReadyValidationTest {
  static final Diagnostic BEFORE = Diagnostic.error("PRIVATE", "/project/seed", "private feedback");
  static final Diagnostic RESULT = Diagnostic.error("RESULT", "/project/maxY", "committed feedback");

  static EditorState state() {
    var s = new EditorState(new UserConfig(), false);
    assertTrue(s.change(p -> {
      var n = new Noise(); n.id = "field"; n.algorithm.key = "CONSTANT";
      n.algorithm.parameters.clear(); n.algorithm.parameters.put("value", .125); p.noise2D.add(n);
    }));
    s.select("noise2D", s.project().noise2D.getFirst().uuid);
    ((Noise) s.draft).id = "private"; s.seedInputs.edit(s, "seedOffset", "Seed", "-");
    s.issues = List.of(BEFORE); s.validationScope = "Rejected edit";
    return s;
  }

  static ProjectValidationSession session(EditorState s, Queue<Runnable> work, Queue<Runnable> ui,
      Function<ProjectData, List<Diagnostic>> validator, Consumer<String> errors) {
    return new ProjectValidationSession(s, work::add, ui::add, validator, errors);
  }

  static Runnable deliver(Queue<Runnable> work, Queue<Runnable> ui) {
    work.remove().run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void readinessKeepsCancelIdentityAndAllDomainsPrivateUntilOneAcceptance() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var validation = session(s, work, ui, p -> List.of(RESULT), text -> fail(text))) {
        var project = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft;
        var privateValues = ProjectIO.JSON.valueToTree(draft); var user = ProjectIO.JSON.valueToTree(s.user);
        var engine = s.engine; long revision = s.revision, bytes = s.document.historyBytes(), raw = s.seedInputs.version(s);
        validation.start(); var request = validation.requestToken(); var callback = deliver(work, ui); callback.run();
        assertEquals(ProjectValidationSession.Phase.RUNNING, validation.view().phase());
        assertSame(request, validation.requestToken()); assertEquals(List.of(BEFORE), s.issues);
        assertEquals("Rejected edit", s.validationScope); assertFalse(validation.start());
        validation.acceptPrepared(); callback.run(); validation.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.READY, validation.view().phase()); assertEquals(List.of(RESULT), s.issues);
        assertThrows(UnsupportedOperationException.class, () -> s.issues.clear());
        assertEquals(project, ProjectIO.JSON.valueToTree(s.project())); assertSame(draft, s.draft);
        assertEquals(privateValues, ProjectIO.JSON.valueToTree(s.draft)); assertEquals(raw, s.seedInputs.version(s));
        assertEquals(user, ProjectIO.JSON.valueToTree(s.user)); assertSame(engine, s.engine);
        assertEquals(revision, s.revision); assertEquals(bytes, s.document.historyBytes());
      }
    }
  }

  @Test
  void readySuccessAndFailureCanBeCancelledWithoutReplacingRowsOrReportingErrors() {
    for (boolean failure : new boolean[] {false, true}) try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var reports = new ArrayList<String>();
      try (var validation = session(s, work, ui, p -> {
        if (failure) throw new AssertionError("worker"); return List.of(RESULT);
      }, reports::add)) {
        var issues = s.issues; var scope = s.validationScope; validation.start(); var callback = deliver(work, ui);
        validation.cancel(); callback.run(); validation.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.CANCELLED, validation.view().phase());
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertTrue(reports.isEmpty());
      }
    }
  }

  @Test
  void deliveredDiagnosticsOwnTheirImmutableWorkerSnapshot() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var result = new ArrayList<Diagnostic>();
      result.add(RESULT);
      try (var validation = session(s, work, ui, p -> result, text -> fail(text))) {
        validation.start(); deliver(work, ui); result.clear(); result.add(BEFORE);
        validation.acceptPrepared(); assertEquals(List.of(RESULT), s.issues);
      }
    }
  }

  @Test
  void readyProjectAndDiagnosticChangesCannotBeOverwrittenByOldFeedback() {
    for (int mode = 0; mode < 6; mode++) try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      s.issues = new ArrayList<>(s.issues);
      try (var validation = session(s, work, ui, p -> List.of(RESULT), text -> fail(text))) {
        validation.start(); deliver(work, ui);
        if (mode == 0) s.project().project.seed++;
        else if (mode == 1) s.issues.add(RESULT);
        else if (mode == 2) s.issues = List.of(BEFORE, RESULT);
        else if (mode == 3) s.validationScope = "newer";
        else if (mode == 4) s.revision++; else s.newSession();
        var project = ProjectIO.JSON.valueToTree(s.project()); var issues = s.issues; var scope = s.validationScope;
        validation.acceptPrepared(); assertEquals(ProjectValidationSession.Phase.STALE, validation.view().phase());
        assertSame(issues, s.issues); assertEquals(scope, s.validationScope); assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
      }
    }
  }

  @Test
  void laterPrivateRawSelectionAndPreferencesDoNotInvalidateCommittedFeedback() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      try (var validation = session(s, work, ui, p -> List.of(RESULT), text -> fail(text))) {
        validation.start(); deliver(work, ui); ((Noise) s.draft).id = "later-private";
        s.seedInputs.edit(s, "seedOffset", "Seed", "+"); s.multiSelection.clear(); s.user.performance.engineMiB = 512;
        var draft = s.draft; var values = ProjectIO.JSON.valueToTree(draft); long raw = s.seedInputs.version(s);
        validation.acceptPrepared(); assertEquals(ProjectValidationSession.Phase.READY, validation.view().phase());
        assertEquals(List.of(RESULT), s.issues); assertSame(draft, s.draft); assertEquals(values, ProjectIO.JSON.valueToTree(s.draft));
        assertEquals(raw, s.seedInputs.version(s)); assertEquals(512, s.user.performance.engineMiB);
        assertEquals("field", s.project().noise2D.getFirst().id);
      }
    }
  }

  @Test
  void failureReporterCanStartAReadySuccessorWithoutOldAcceptanceConsumingIt() {
    try (var s = state()) {
      var first = new AtomicBoolean(true); var reports = new ArrayList<String>(); var holder = new ProjectValidationSession[1];
      try (var validation = new ProjectValidationSession(s, Runnable::run, Runnable::run, p -> {
        if (first.getAndSet(false)) throw new AssertionError("worker"); return List.of();
      }, text -> { reports.add(text); holder[0].start(); })) {
        holder[0] = validation; validation.start(); validation.acceptPrepared();
        assertEquals(1, reports.size()); assertEquals(ProjectValidationSession.Phase.RUNNING, validation.view().phase());
        assertEquals(List.of(BEFORE), s.issues); validation.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.READY, validation.view().phase()); assertTrue(s.issues.isEmpty());
      }
    }
  }

  @Test
  void closedReadyPredecessorCannotPopulateOrConsumeAnotherValidationOwner() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
      var first = session(s, work, ui, p -> List.of(RESULT), text -> fail(text));
      first.start(); var old = deliver(work, ui); first.close();
      try (var next = session(s, work, ui, p -> List.of(), text -> fail(text))) {
        next.start(); old.run(); next.acceptPrepared(); assertEquals(List.of(BEFORE), s.issues);
        assertEquals(ProjectValidationSession.Phase.RUNNING, next.view().phase());
        deliver(work, ui); old.run(); next.acceptPrepared(); assertTrue(s.issues.isEmpty());
      }
    }
  }

  @Test
  void explicitRetryRetiresReadyCancellationWithoutAcceptingAnOldDelivery() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var calls = new AtomicInteger();
      try (var validation = session(s, work, ui, p -> calls.incrementAndGet() == 1 ? List.of(RESULT) : List.of(), text -> fail(text))) {
        validation.start(); var old = deliver(work, ui); validation.cancel(); validation.start(); old.run();
        validation.acceptPrepared(); assertEquals(List.of(BEFORE), s.issues);
        deliver(work, ui); validation.acceptPrepared(); old.run(); assertTrue(s.issues.isEmpty());
        assertEquals(ProjectValidationSession.Phase.READY, validation.view().phase());
      }
    }
  }

  @Test
  void workerCancellationResultWaitsForOwnerAcceptanceAndDoesNotReportFailure() {
    try (var s = state()) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var reports = new ArrayList<String>();
      try (var validation = session(s, work, ui, p -> { throw new CancellationException("worker"); }, reports::add)) {
        validation.start(); deliver(work, ui); assertEquals(ProjectValidationSession.Phase.RUNNING, validation.view().phase());
        validation.acceptPrepared(); assertEquals(ProjectValidationSession.Phase.CANCELLED, validation.view().phase());
        assertEquals(List.of(BEFORE), s.issues); assertTrue(reports.isEmpty());
      }
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureCannotPublishAnUnacceptedResult() {
    try (var s = state()) {
      var reports = new ArrayList<String>();
      try (var validation = new ProjectValidationSession(s, Runnable::run,
          callback -> { callback.run(); throw new AssertionError("late handoff"); },
          p -> List.of(), reports::add)) {
        validation.start(); validation.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.FAILED, validation.view().phase());
        assertEquals(List.of(BEFORE), s.issues); assertEquals(1, reports.size());
      }
    }
  }

  @Test
  void inlineAcceptanceCannotPublishBeforeExecutorAdmissionReturnsSuccessfully() {
    try (var s = state()) {
      var holder = new ProjectValidationSession[1]; var reports = new ArrayList<String>();
      try (var validation = new ProjectValidationSession(s, task -> { task.run(); throw new AssertionError("late executor"); },
          callback -> { callback.run(); holder[0].acceptPrepared(); }, p -> List.of(), reports::add)) {
        holder[0] = validation; validation.start(); validation.acceptPrepared();
        assertEquals(ProjectValidationSession.Phase.FAILED, validation.view().phase());
        assertEquals(List.of(BEFORE), s.issues); assertEquals(1, reports.size());
        assertTrue(reports.getFirst().contains("late executor"));
      }
    }
  }
}
