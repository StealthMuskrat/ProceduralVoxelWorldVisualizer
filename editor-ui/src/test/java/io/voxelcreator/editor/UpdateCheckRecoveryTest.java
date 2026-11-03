package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

/** Deferred finite boundary faults, UI-only failure recovery and exact request retirement. */
class UpdateCheckRecoveryTest {
  private static final String ENDPOINT = "https://updates.example.test/meta.json";
  private static final UpdateChecker.Release RELEASE =
      new UpdateChecker.Release("1.0.1", "https://releases.example.test/info");
  private final Deque<Runnable> workers = new ArrayDeque<>(), ui = new ArrayDeque<>();
  private final List<String> errors = new ArrayList<>();

  private UserConfig configured() {
    var user = new UserConfig();
    user.performance.updateEndpoint = ENDPOINT;
    user.performance.trustedOrigins =
        new ArrayList<>(List.of("https://updates.example.test", "https://releases.example.test"));
    return user;
  }

  private void deliver() {
    workers.removeFirst().run();
    while (!ui.isEmpty()) ui.removeFirst().run();
  }

  @Test
  void fetcherErrorsWaitForUiPublicationAndRemainExplicitlyRetryable() {
    var calls = new AtomicInteger();
    var error = new AssertionError("fetch-error");
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            workers::add,
            ui::add,
            (e, o) -> {
              if (calls.incrementAndGet() == 1) throw error;
              return RELEASE;
            },
            errors::add)) {
      assertTrue(session.start());
      workers.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertTrue(errors.isEmpty());
      ui.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
      assertTrue(session.view().message().contains("fetch-error"));
      assertNull(session.view().result());
      assertEquals(1, errors.size());
      for (int i = 0; i < 5; i++) session.view();
      assertTrue(workers.isEmpty());
      assertEquals(0, error.getSuppressed().length);
      assertTrue(session.start());
      deliver();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      assertEquals(2, calls.get());
    }
  }

  @Test
  void schedulerErrorsRetireTheRequestBeforeExposingRetry() {
    var reject = new AtomicBoolean(true);
    var calls = new AtomicInteger();
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            task -> {
              if (reject.get()) throw new AssertionError("scheduler-error");
              workers.add(task);
            },
            ui::add,
            (e, o) -> {
              calls.incrementAndGet();
              return RELEASE;
            },
            errors::add)) {
      var before = session.requestToken();
      assertFalse(session.start());
      assertNotSame(before, session.requestToken());
      assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
      assertTrue(session.view().message().contains("scheduler-error"));
      assertTrue(workers.isEmpty());
      assertEquals(0, calls.get());
      reject.set(false);
      assertTrue(session.start());
      deliver();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
    }
  }

  @Test
  void rejectedUiHandoffsAreRecoveredOnlyWhenTheUiObservesTheFailedTask() throws Exception {
    for (Throwable rejection :
        new Throwable[] {
          new IllegalStateException("queue-runtime"), new AssertionError("queue-error")
        }) {
      var reject = new AtomicBoolean(true);
      errors.clear();
      try (var session =
              new UpdateCheckSession(
                  configured(),
                  "1.0.0",
                  workers::add,
                  action -> {
                    if (reject.get()) {
                      if (rejection instanceof Error error) throw error;
                      throw (RuntimeException) rejection;
                    }
                    ui.add(action);
                  },
                  (e, o) -> RELEASE,
                  errors::add);
          var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
        session.start();
        var request = session.requestToken();
        tasks.submit(workers.removeFirst()).get(3, TimeUnit.SECONDS);
        assertTrue(errors.isEmpty(), "Worker called a UI error sink");
        assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
        assertNotSame(request, session.requestToken());
        assertTrue(session.view().message().contains(rejection.getMessage()));
        assertNull(session.view().result());
        assertEquals(1, errors.size());
        reject.set(false);
        assertTrue(session.start());
        deliver();
        assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      }
    }
  }

  @Test
  void enqueueThenRejectCannotPublishAnOldSuccessOrReplaceItsSuccessor() {
    var reject = new AtomicBoolean(true);
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            workers::add,
            action -> {
              ui.add(action);
              if (reject.get()) throw new AssertionError("after-enqueue");
            },
            (e, o) -> RELEASE,
            errors::add)) {
      session.start();
      workers.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
      reject.set(false);
      session.start();
      ui.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertNull(session.view().result());
      deliver();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      assertEquals(1, errors.size());
    }
  }

  @Test
  void cancelledStaleAndClosedOwnersDiscardFailedHandoffsWithoutReportingOldFailures() {
    for (var phase :
        List.of(
            UpdateCheckSession.Phase.CANCELLED,
            UpdateCheckSession.Phase.STALE,
            UpdateCheckSession.Phase.CLOSED)) {
      var user = configured();
      errors.clear();
      try (var session =
          new UpdateCheckSession(
              user,
              "1.0.0",
              workers::add,
              action -> {
                throw new AssertionError("retired-handoff");
              },
              (e, o) -> RELEASE,
              errors::add)) {
        session.start();
        workers.removeFirst().run();
        if (phase == UpdateCheckSession.Phase.CANCELLED) session.cancel();
        else if (phase == UpdateCheckSession.Phase.CLOSED) session.close();
        else user.performance.updateEndpoint = ENDPOINT + "?changed=1";
        assertEquals(phase, session.view().phase());
        assertNull(session.view().result());
        assertTrue(errors.isEmpty());
      }
    }
  }

  @Test
  void errorReporterFaultsPreserveThePrimaryFailureAndKeepFeedbackBounded() {
    for (boolean fatal : new boolean[] {false, true}) {
      var notifications = new AtomicInteger();
      var primary = new AssertionError("primary-" + "x".repeat(10000));
      try (var session =
          new UpdateCheckSession(
              configured(),
              "1.0.0",
              workers::add,
              ui::add,
              (e, o) -> {
                throw primary;
              },
              message -> {
                notifications.incrementAndGet();
                if (fatal) throw new AssertionError("reporter-" + "y".repeat(10000));
                throw new IllegalStateException("reporter-runtime");
              })) {
        session.start();
        assertDoesNotThrow(this::deliver);
        assertEquals(UpdateCheckSession.Phase.FAILED, session.view().phase());
        assertTrue(session.view().message().contains("primary-"));
        assertTrue(session.view().message().contains("Error notification failed: reporter-"));
        assertTrue(session.view().message().length() < 2200);
        assertEquals(1, notifications.get());
        assertEquals(0, primary.getSuppressed().length);
      }
    }
  }

  @Test
  void reentrantReporterReplacementCannotReceiveItsPredecessorsFailureFeedback() {
    var owner = new AtomicReference<UpdateCheckSession>();
    var calls = new AtomicInteger();
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            workers::add,
            ui::add,
            (e, o) -> {
              if (calls.incrementAndGet() == 1) throw new AssertionError("old-fetch");
              return RELEASE;
            },
            message -> {
              assertTrue(owner.get().start());
              throw new AssertionError("old-reporter");
            })) {
      owner.set(session);
      session.start();
      deliver();
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertFalse(session.view().message().contains("old-"));
      deliver();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      assertEquals(2, calls.get());
    }
  }

  @Test
  void aTaskCancelledByItsExecutorCannotRemainRunningOrStartAnImplicitRetry() {
    var calls = new AtomicInteger();
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            workers::add,
            ui::add,
            (e, o) -> {
              calls.incrementAndGet();
              return RELEASE;
            },
            errors::add)) {
      session.start();
      var work = workers.removeFirst();
      assertTrue(((Future<?>) work).cancel(false));
      work.run();
      assertEquals(UpdateCheckSession.Phase.CANCELLED, session.view().phase());
      assertNull(session.view().result());
      assertEquals(0, calls.get());
      assertTrue(errors.isEmpty());
      assertTrue(ui.isEmpty());
      assertTrue(workers.isEmpty());
    }
  }

  @Test
  void unreadableFailureMessagesUseABoundedNonemptyClassFallback() {
    var unreadable =
        new AssertionError() {
          @Override
          public String getMessage() {
            throw new AssertionError("description-fault");
          }
        };
    assertEquals(unreadable.getClass().getName(), UpdateCheckSession.describe(unreadable));
    assertTrue(UpdateCheckSession.describe(new AssertionError("x".repeat(10000))).length() <= 1024);
    assertTrue(
        UpdateCheckSession.report(
                message -> {
                  throw unreadable;
                },
                "primary")
            .startsWith("primary\nError notification failed: "));
  }

  @Test
  void obsoleteExecutorFaultAfterReentrantReplacementCannotCancelTheNewRequest() {
    var owner = new AtomicReference<UpdateCheckSession>();
    var submissions = new AtomicInteger();
    var fetches = new AtomicInteger();
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            task -> {
              if (submissions.incrementAndGet() == 1) {
                task.run();
                throw new AssertionError("obsolete-executor");
              }
              workers.add(task);
            },
            Runnable::run,
            (e, o) -> {
              if (fetches.incrementAndGet() == 1) throw new AssertionError("first-fetch");
              return RELEASE;
            },
            message -> {
              errors.add(message);
              assertTrue(owner.get().start());
            })) {
      owner.set(session);
      assertFalse(session.start());
      assertEquals(UpdateCheckSession.Phase.RUNNING, session.view().phase());
      assertTrue(session.view().message().contains("Checking"));
      workers.removeFirst().run();
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      assertEquals(2, fetches.get());
      assertEquals(1, errors.size());
      assertFalse(errors.getFirst().contains("obsolete-executor"));
    }
  }

  @Test
  void aPosterFaultAfterSuccessfulUiPublicationCannotRollBackTheReviewedResult() {
    try (var session =
        new UpdateCheckSession(
            configured(),
            "1.0.0",
            workers::add,
            action -> {
              action.run();
              throw new AssertionError("after-publication");
            },
            (e, o) -> RELEASE,
            errors::add)) {
      session.start();
      workers.removeFirst().run();
      var reviewed = session.view().result();
      assertNotNull(reviewed);
      assertEquals(UpdateCheckSession.Phase.READY, session.view().phase());
      assertSame(reviewed, session.view().result());
      assertTrue(errors.isEmpty());
      assertTrue(workers.isEmpty());
    }
  }

  @Test
  void executorRejectionAfterInlineTerminalPublicationCannotReplaceItsResultOrReportTwice() {
    for (boolean failedFetch : new boolean[] {false, true}) {
      errors.clear();
      try (var session =
          new UpdateCheckSession(
              configured(),
              "1.0.0",
              task -> {
                task.run();
                throw new AssertionError("after-execution");
              },
              Runnable::run,
              (e, o) -> {
                if (failedFetch) throw new AssertionError("published-fetch-failure");
                return RELEASE;
              },
              errors::add)) {
        assertFalse(session.start());
        assertEquals(
            failedFetch ? UpdateCheckSession.Phase.FAILED : UpdateCheckSession.Phase.READY,
            session.view().phase());
        assertEquals(failedFetch ? 1 : 0, errors.size());
        if (failedFetch) assertTrue(errors.getFirst().contains("published-fetch-failure"));
        else assertNotNull(session.view().result());
        assertFalse(session.view().message().contains("after-execution"));
      }
    }
  }
}
