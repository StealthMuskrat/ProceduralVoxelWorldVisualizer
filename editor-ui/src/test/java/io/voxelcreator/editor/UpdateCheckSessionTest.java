package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred manual request publication/link ownership with controlled worker and UI queues. */
class UpdateCheckSessionTest {
  private static final String ENDPOINT = "https://updates.example.test/meta.json",
      ORIGIN = "https://updates.example.test",
      LINK = "https://releases.example.test/1.0.0";
  private final UserConfig user = configured();
  private final Deque<Runnable> workers = new ArrayDeque<>(), ui = new ArrayDeque<>();
  private final List<String> errors = new ArrayList<>();

  private static UserConfig configured() {
    var user = new UserConfig();
    user.performance.updateEndpoint = ENDPOINT;
    user.performance.trustedOrigins =
        new ArrayList<>(List.of(ORIGIN, "https://releases.example.test"));
    return user;
  }

  private UpdateCheckSession session(UpdateCheckSession.Fetcher fetcher) {
    return new UpdateCheckSession(user, "1.0.0", workers::add, ui::add, fetcher, errors::add);
  }

  private void deliver() {
    workers.removeFirst().run();
    while (!ui.isEmpty()) ui.removeFirst().run();
  }

  @Test
  void repeatedClicksShareOneRequestAndPublicationWaitsForTheUiQueue() {
    var calls = new AtomicInteger();
    try (var session =
        session(
            (endpoint, origins) -> {
              calls.incrementAndGet();
              assertEquals(ENDPOINT, endpoint);
              assertEquals(user.performance.trustedOrigins, origins);
              assertThrows(UnsupportedOperationException.class, origins::clear);
              return new UpdateChecker.Release("1.0.1", LINK);
            })) {
      assertTrue(session.start());
      assertFalse(session.start());
      assertEquals(1, workers.size());
      workers.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertNull(session.view().result());
      ui.removeFirst().run();
      assertEquals(UpdateCheckSession.Comparison.NEWER, session.view().result().comparison());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void cancellationAfterWorkerCompletionDiscardsItsQueuedResultAndNewRequestCanSucceed() {
    try (var session = session((e, o) -> new UpdateChecker.Release("1.0.1", LINK))) {
      session.start();
      workers.removeFirst().run();
      session.cancel();
      assertTrue(session.start());
      ui.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertNull(session.view().result());
      deliver();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
    }
  }

  @Test
  void changedAppliedValuesInvalidateRunningAndReadyResultsWhileOtherPreferencesDoNot() {
    try (var session = session((e, o) -> new UpdateChecker.Release("1.0.0+build.42", LINK))) {
      session.start();
      user.appearance.theme = "Light";
      user.workspace.width = 1234;
      deliver();
      var result = session.view().result();
      assertEquals(UpdateCheckSession.Comparison.CURRENT, result.comparison());
      user.performance.trustedOrigins.add("https://other.example.test");
      assertEquals(UpdateCheckSession.Phase.STALE, session.view().phase());
      assertThrows(
          IllegalStateException.class,
          () -> session.open(result, uri -> fail("Opened stale link")));
      session.start();
      workers.removeFirst().run();
      user.performance.updateEndpoint = ENDPOINT + "?next=1";
      ui.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.STALE, session.view().phase());
      assertNull(session.view().result());
    }
  }

  @Test
  void closeConsumesOwnershipAndQueuedCompletionCannotResurrectTheChecker() {
    var session = session((e, o) -> new UpdateChecker.Release("1.0.0", LINK));
    session.start();
    workers.removeFirst().run();
    session.close();
    ui.removeFirst().run();
    session.close();
    assertEquals(UpdateCheckSession.Phase.CLOSED, session.view().phase());
    assertNull(session.view().result());
    assertFalse(session.start());
  }

  @Test
  void failureAndExecutorRejectionAreBoundedRetryableAndNeverExposeALink() {
    var calls = new AtomicInteger();
    try (var session =
        session(
            (e, o) -> {
              if (calls.incrementAndGet() == 1) throw new IOException();
              return new UpdateChecker.Release("0.9.0", LINK);
            })) {
      session.start();
      deliver();
      assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
      assertTrue(session.view().message().contains("IOException"));
      assertNull(session.view().result());
      assertEquals(1, errors.size());
      session.start();
      deliver();
      assertEquals(UpdateCheckSession.Comparison.OLDER, session.view().result().comparison());
    }
    try (var rejected =
        new UpdateCheckSession(
            user,
            "1.0.0",
            task -> {
              throw new RejectedExecutionException("x".repeat(10000));
            },
            ui::add,
            (e, o) -> {
              fail("Rejected request ran");
              return null;
            },
            errors::add)) {
      assertFalse(rejected.start());
      assertEquals(UpdateCheckSession.Phase.FAILED, rejected.view().phase());
      assertTrue(rejected.view().message().length() < 1100);
    }
  }

  @Test
  void invalidConfigurationAndInvalidWorkerResultsNeverReachReadyOrLinkActions() {
    for (var release :
        List.of(
            new UpdateChecker.Release("01.0.0", LINK),
            new UpdateChecker.Release("1.0.0", "https://unapproved.example.test/release"),
            new UpdateChecker.Release("1.0.0", "http://releases.example.test/release"),
            new UpdateChecker.Release(
                "1.0.0", "https://releases.example.test/" + "x".repeat(4096)))) {
      try (var session = session((e, o) -> release)) {
        session.start();
        deliver();
        assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
        assertNull(session.view().result());
      }
    }
    user.performance.updateEndpoint = "";
    try (var session =
        session(
            (e, o) -> {
              fail("Unconfigured request ran");
              return null;
            })) {
      assertFalse(session.start());
      assertEquals(0, workers.size());
    }
  }

  @Test
  void explicitOpenAndCopyRequireTheExactReviewedResultAndFailuresKeepItRetryable()
      throws Exception {
    try (var session = session((e, o) -> new UpdateChecker.Release("1.0.1", LINK))) {
      session.start();
      deliver();
      var reviewed = session.view().result();
      var equal = new UpdateCheckSession.Result(reviewed.release(), reviewed.comparison());
      assertThrows(
          IllegalStateException.class,
          () -> session.open(equal, uri -> fail("Opened unreviewed result")));
      assertThrows(
          IOException.class,
          () ->
              session.open(
                  reviewed,
                  uri -> {
                    throw new IOException("browser fixture");
                  }));
      assertSame(reviewed, session.view().result());
      var opened = new ArrayList<String>();
      session.open(reviewed, uri -> opened.add(uri.toString()));
      session.copy(reviewed, opened::add);
      assertEquals(List.of(LINK, LINK), opened);
      session.start();
      assertThrows(
          IllegalStateException.class,
          () -> session.copy(reviewed, text -> fail("Copied older result")));
    }
  }

  @Test
  void cancellationBeforeQueuedExecutionDoesNotCallTheFetcherAndFreshRequestsDoNotAutoRetry() {
    var calls = new AtomicInteger();
    try (var session =
        session(
            (e, o) -> {
              calls.incrementAndGet();
              return new UpdateChecker.Release("1.0.0", LINK);
            })) {
      session.start();
      session.cancel();
      workers.removeFirst().run();
      assertEquals(0, calls.get());
      assertTrue(ui.isEmpty());
      for (int i = 0; i < 5; i++) session.view();
      assertTrue(workers.isEmpty());
      assertEquals(UpdateCheckSession.Phase.CANCELLED, session.view().phase());
    }
  }

  @Test
  void reentrantOpenIsBlockedAndBrowserCallbacksCannotReviveClosedResults() throws Exception {
    try (var session = session((e, o) -> new UpdateChecker.Release("1.0.0", LINK))) {
      session.start();
      deliver();
      var reviewed = session.view().result();
      session.open(
          reviewed,
          uri -> {
            assertThrows(
                IllegalStateException.class,
                () -> session.open(reviewed, nested -> fail("Reentrant browser open")));
            session.close();
          });
      assertEquals(UpdateCheckSession.Phase.CLOSED, session.view().phase());
      assertNull(session.view().result());
    }
  }

  @Test
  void presentationTokenTracksRequestsWithoutReplacingPublicationOrExactResultAuthority() {
    try (var session = session((e, o) -> new UpdateChecker.Release("1.0.1", LINK))) {
      var idle = session.requestToken();
      assertTrue(session.start());
      var first = session.requestToken();
      assertNotSame(idle, first);
      assertFalse(session.start());
      assertSame(first, session.requestToken());
      deliver();
      var reviewed = session.view().result();
      assertSame(first, session.requestToken());
      assertTrue(session.start());
      assertNotSame(first, session.requestToken());
      deliver();
      assertEquals(reviewed, session.view().result());
      assertNotSame(reviewed, session.view().result());
      assertThrows(
          IllegalStateException.class,
          () -> session.copy(reviewed, text -> fail("Old review copied")));
      session.cancel();
      var cancelled = session.requestToken();
      session.cancel();
      assertSame(cancelled, session.requestToken());
      session.close();
      assertNotSame(cancelled, session.requestToken());
      var closed = session.requestToken();
      session.close();
      assertSame(closed, session.requestToken());
    }
  }
}
