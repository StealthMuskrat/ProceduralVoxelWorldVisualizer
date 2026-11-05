package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectIO;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred ready-result cancellation and exact manual release-link ownership. */
class ReadyUpdateCheckTest {
  static final String ENDPOINT = "https://updates.example.test/meta.json";
  static final String LINK = "https://releases.example.test/ready";
  static final UpdateChecker.Release RELEASE = new UpdateChecker.Release("1.0.1", LINK);

  static UserConfig configured() {
    var user = new UserConfig(); user.performance.updateEndpoint = ENDPOINT;
    user.performance.trustedOrigins = new ArrayList<>(List.of("https://updates.example.test", "https://releases.example.test"));
    return user;
  }

  static UpdateCheckSession session(UserConfig user, Queue<Runnable> workers, Queue<Runnable> ui,
      UpdateCheckSession.Fetcher fetcher, List<String> errors) {
    return new UpdateCheckSession(user, "1.0.0", workers::add, ui::add, fetcher, errors::add);
  }

  static Runnable deliver(Queue<Runnable> workers, Queue<Runnable> ui) {
    workers.remove().run(); var callback = ui.remove(); callback.run(); return callback;
  }

  @Test
  void deliveryKeepsCancelTokenAndNoLinkUntilOneExplicitOwnerAcceptance() {
    var user = configured(); var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>();
    var errors = new ArrayList<String>(); var calls = new AtomicInteger();
    try (var check = session(user, work, ui, (e, o) -> { calls.incrementAndGet(); return RELEASE; }, errors)) {
      check.start(); var token = check.requestToken(); var callback = deliver(work, ui); callback.run();
      assertSame(token, check.requestToken()); assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase());
      assertNull(check.view().result()); assertFalse(check.start());
      assertThrows(IllegalStateException.class, () -> check.open(null, uri -> fail("Opened pending result")));
      assertThrows(IllegalStateException.class, () -> check.copy(null, text -> fail("Copied pending result")));
      check.acceptPrepared(); var reviewed = check.view().result(); callback.run(); check.acceptPrepared();
      assertSame(reviewed, check.view().result()); assertSame(token, check.requestToken());
      assertEquals(UpdateCheckSession.Comparison.NEWER, reviewed.comparison()); assertEquals(1, calls.get()); assertTrue(errors.isEmpty());
    }
  }

  @Test
  void readySuccessFailureMalformedAndNullMetadataRemainCancellableBeforeFeedback() {
    for (int mode = 0; mode < 4; mode++) {
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
      int action = mode;
      try (var check = session(configured(), work, ui, (e, o) -> {
        if (action == 1) throw new AssertionError("worker");
        if (action == 2) return new UpdateChecker.Release("invalid", LINK);
        return action == 3 ? null : RELEASE;
      }, errors)) {
        check.start(); var callback = deliver(work, ui); assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase());
        assertTrue(errors.isEmpty()); check.cancel(); callback.run(); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.CANCELLED, check.view().phase()); assertNull(check.view().result()); assertTrue(errors.isEmpty());
      }
    }
  }

  @Test
  void readyEndpointAndOrderedAllowlistChangesRetireOldMetadataWithoutRetry() {
    for (int mode = 0; mode < 3; mode++) {
      var user = configured(); var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
      try (var check = session(user, work, ui, (e, o) -> RELEASE, errors)) {
        check.start(); deliver(work, ui);
        if (mode == 0) user.performance.updateEndpoint += "?new=1";
        else if (mode == 1) Collections.reverse(user.performance.trustedOrigins);
        else user.performance.trustedOrigins.add("https://other.example.test");
        var preferences = ProjectIO.JSON.valueToTree(user); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.STALE, check.view().phase()); assertNull(check.view().result());
        assertEquals(preferences, ProjectIO.JSON.valueToTree(user)); assertTrue(work.isEmpty()); assertTrue(errors.isEmpty());
      }
    }
  }

  @Test
  void equalAppliedValuesAndUnrelatedPrivateProjectPreferencesRemainIndependent() {
    try (var s = ReadyValidationTest.state()) {
      var configured = configured(); s.user.performance.updateEndpoint = configured.performance.updateEndpoint;
      s.user.performance.trustedOrigins = configured.performance.trustedOrigins;
      var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
      try (var check = session(s.user, work, ui, (e, o) -> RELEASE, errors)) {
        var project = ProjectIO.JSON.valueToTree(s.project()); var draft = s.draft; var values = ProjectIO.JSON.valueToTree(draft);
        long revision = s.revision, bytes = s.document.historyBytes(), raw = s.seedInputs.version(s); var engine = s.engine;
        check.start(); deliver(work, ui);
        s.user.performance.trustedOrigins = new ArrayList<>(s.user.performance.trustedOrigins);
        s.user.appearance.theme = "Light"; s.user.workspace.width = 1234;
        var user = ProjectIO.JSON.valueToTree(s.user); check.acceptPrepared();
        assertEquals(UpdateCheckSession.Phase.READY, check.view().phase()); assertEquals(project, ProjectIO.JSON.valueToTree(s.project()));
        assertSame(draft, s.draft); assertEquals(values, ProjectIO.JSON.valueToTree(s.draft)); assertEquals(raw, s.seedInputs.version(s));
        assertEquals(user, ProjectIO.JSON.valueToTree(s.user)); assertEquals(revision, s.revision);
        assertEquals(bytes, s.document.historyBytes()); assertSame(engine, s.engine); assertTrue(errors.isEmpty());
      }
    }
  }

  @Test
  void acceptedReadyFailureRemainsBoundedAndRetryDoesNotStartUntilRequested() {
    var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
    var fail = new AtomicBoolean(true); var calls = new AtomicInteger();
    try (var check = session(configured(), work, ui, (e, o) -> {
      calls.incrementAndGet(); if (fail.get()) throw new AssertionError("x".repeat(10000)); return RELEASE;
    }, errors)) {
      check.start(); deliver(work, ui); assertTrue(errors.isEmpty()); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase()); assertEquals(1, errors.size());
      assertTrue(check.view().message().length() < 2200); assertNull(check.view().result());
      for (int i = 0; i < 8; i++) check.acceptPrepared(); assertEquals(1, calls.get()); assertTrue(work.isEmpty());
      fail.set(false); check.start(); deliver(work, ui); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.READY, check.view().phase()); assertEquals(2, calls.get());
    }
  }

  @Test
  void inlineReadinessFollowedByHandoffFailureCannotPublishBeforeAcceptance() {
    var errors = new ArrayList<String>();
    try (var check = new UpdateCheckSession(configured(), "1.0.0", Runnable::run,
        callback -> { callback.run(); throw new AssertionError("handoff"); }, (e, o) -> RELEASE, errors::add)) {
      check.start(); check.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase());
      assertNull(check.view().result()); assertEquals(1, errors.size()); assertTrue(errors.getFirst().contains("handoff"));
    }
  }

  @Test
  void inlineReadinessFollowedByExecutorFailureCannotExposeAnUnacceptedLink() {
    var errors = new ArrayList<String>();
    try (var check = new UpdateCheckSession(configured(), "1.0.0", task -> { task.run(); throw new AssertionError("executor"); },
        Runnable::run, (e, o) -> RELEASE, errors::add)) {
      assertFalse(check.start()); check.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.FAILED, check.view().phase());
      assertNull(check.view().result()); assertEquals(1, errors.size()); assertTrue(errors.getFirst().contains("executor"));
    }
  }

  @Test
  void failureReporterCanPrepareAReadySuccessorWithoutOldAcceptanceConsumingIt() {
    var first = new AtomicBoolean(true); var errors = new ArrayList<String>(); var owner = new UpdateCheckSession[1];
    try (var check = new UpdateCheckSession(configured(), "1.0.0", Runnable::run, Runnable::run, (e, o) -> {
      if (first.getAndSet(false)) throw new AssertionError("old worker"); return RELEASE;
    }, text -> { errors.add(text); owner[0].start(); })) {
      owner[0] = check; check.start(); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase()); assertNull(check.view().result());
      assertEquals(1, errors.size()); assertFalse(check.view().message().contains("old worker"));
      check.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.READY, check.view().phase());
    }
  }

  @Test
  void oldReadyDeliveryCannotConsumeAQueuedRetryOfTheSameSession() {
    var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
    try (var check = session(configured(), work, ui, (e, o) -> RELEASE, errors)) {
      check.start(); var old = deliver(work, ui); var retired = check.requestToken(); check.cancel(); check.start();
      assertNotSame(retired, check.requestToken()); old.run(); check.acceptPrepared();
      assertEquals(UpdateCheckSession.Phase.RUNNING, check.view().phase()); assertNull(check.view().result());
      deliver(work, ui); check.acceptPrepared(); old.run(); assertEquals(UpdateCheckSession.Phase.READY, check.view().phase());
    }
  }

  @Test
  void closedReadyPredecessorCannotPublishIntoANewChecker() {
    var user = configured(); var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
    var first = session(user, work, ui, (e, o) -> RELEASE, errors); first.start(); var old = deliver(work, ui); first.close();
    try (var next = session(user, work, ui, (e, o) -> RELEASE, errors)) {
      next.start(); old.run(); next.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.CLOSED, first.view().phase());
      assertEquals(UpdateCheckSession.Phase.RUNNING, next.view().phase()); assertNull(next.view().result());
      deliver(work, ui); next.acceptPrepared(); assertEquals(UpdateCheckSession.Phase.READY, next.view().phase());
    }
  }

  @Test
  void equalSuccessorMetadataStillRequiresTheExactNewReviewedResultForLinkActions() throws Exception {
    var work = new ArrayDeque<Runnable>(); var ui = new ArrayDeque<Runnable>(); var errors = new ArrayList<String>();
    var opened = new ArrayList<String>(); var copied = new ArrayList<String>();
    try (var check = session(configured(), work, ui, (e, o) -> RELEASE, errors)) {
      check.start(); deliver(work, ui); check.acceptPrepared(); var first = check.view().result();
      check.start(); deliver(work, ui);
      assertThrows(IllegalStateException.class, () -> check.open(first, uri -> opened.add(uri.toString())));
      assertThrows(IllegalStateException.class, () -> check.copy(first, copied::add)); check.acceptPrepared();
      var next = check.view().result(); assertEquals(first, next); assertNotSame(first, next);
      assertThrows(IllegalStateException.class, () -> check.open(first, uri -> opened.add(uri.toString())));
      assertThrows(IllegalStateException.class, () -> check.copy(first, copied::add));
      check.open(next, uri -> opened.add(uri.toString())); check.copy(next, copied::add);
      assertEquals(List.of(LINK), opened); assertEquals(List.of(LINK), copied); assertTrue(errors.isEmpty());
    }
  }
}
