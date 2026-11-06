package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.voxelcreator.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred cancellation/capture ownership; update fetchers are local fixtures, never transport. */
class ManualCheckRetirementTest {
  private static final UpdateChecker.Release RELEASE =
      new UpdateChecker.Release("1.0.1", "https://releases.example.test/info");

  @Test
  void cancellationCannotRelabelOrEraseAReadySuccessor() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        assertTrue(h.start());
        var successor = new AtomicReference<Object>();
        h.install(
            () -> {
              assertTrue(h.start());
              successor.set(h.token());
            },
            null);
        h.cancel();
        assertSame(successor.get(), h.token());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void stalenessCannotOverwriteANewerSourceChecksPhaseMessageOrReceipt() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        assertTrue(h.start());
        h.install(() -> assertTrue(h.start()), null);
        if (update)
          h.state.user.performance.updateEndpoint = "https://updates.example.test/new.json";
        else h.state.revision++;
        h.checkpoint();
        assertEquals("RUNNING", h.phase());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void terminalFailureCannotDescribeOrReportAgainstACancellationCreatedSuccessor() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        var reads = new AtomicInteger();
        var failure =
            new AssertionError() {
              public String getMessage() {
                reads.incrementAndGet();
                return "obsolete task";
              }
            };
        assertTrue(h.start());
        h.install(() -> assertTrue(h.start()), failure);
        assertEquals("RUNNING", h.phase());
        assertEquals(0, reads.get());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void startCannotAdoptTheIdentityReservedByItsRetirementCallback() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        assertTrue(h.start());
        h.cancel();
        var successor = new AtomicReference<Object>();
        h.install(
            () -> {
              assertTrue(h.start());
              successor.set(h.token());
            },
            null);
        assertFalse(h.start());
        assertSame(successor.get(), h.token());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void finalCloseRejectsAdmissionAndCancellationCannotOverwriteCallbackClosure() {
    for (boolean update : List.of(false, true))
      for (boolean closeDuringCancel : List.of(false, true))
        try (var h = new Harness(update)) {
          assertTrue(h.start());
          h.install(
              () -> {
                if (closeDuringCancel) h.closeOwner();
                else assertFalse(h.start());
              },
              null);
          if (closeDuringCancel) h.cancel();
          else h.closeOwner();
          assertEquals("CLOSED", h.phase());
          assertTrue(h.message().toLowerCase(Locale.ROOT).contains("closed"));
          assertFalse(h.start());
          h.accept();
          assertEquals("CLOSED", h.phase());
          assertTrue(h.errors.isEmpty());
          h.unchanged();
        }
  }

  @Test
  void cancellationFaultPropagatesAfterDetachingWorkAndLeavesAnExplicitRetry() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        var failure = new AssertionError("cancel");
        assertTrue(h.start());
        h.install(() -> { throw failure; }, null);
        assertSame(failure, assertThrows(AssertionError.class, h::cancel));
        assertEquals("CANCELLED", h.phase());
        assertTrue(h.start());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void executorFailureCannotRetireOrReportAReplacementStartedByItsCleanup() {
    for (boolean update : List.of(false, true))
      try (var h = new Harness(update)) {
        var successor = new AtomicReference<Object>();
        h.admission =
            work -> {
              work.run();
              h.admission = Runnable::run;
              h.install(
                  () -> {
                    assertTrue(h.start());
                    successor.set(h.token());
                  },
                  null);
              throw new AssertionError("retired executor admission");
            };
        assertFalse(h.start());
        assertSame(successor.get(), h.token());
        h.pending();
        h.accept();
        h.published();
      }
  }

  @Test
  void sourceCaptureCannotInstallOverACallbackCreatedRequestOrClosedOwner() {
    for (boolean update : List.of(false, true))
      for (boolean close : List.of(false, true))
        try (var h = new Harness(update)) {
          var called = new AtomicBoolean();
          Runnable callback =
              () -> {
                assertTrue(called.compareAndSet(false, true));
                if (close) h.closeOwner();
                else assertTrue(h.start());
              };
          if (update)
            h.state.user.performance.trustedOrigins =
                captured(h.state.user.performance.trustedOrigins, callback);
          else h.state.project().noise2D = captured(h.state.project().noise2D, callback);
          assertFalse(h.start());
          assertTrue(called.get());
          if (close) {
            assertEquals("CLOSED", h.phase());
            assertTrue(h.errors.isEmpty());
            h.unchanged();
          } else {
            h.pending();
            h.accept();
            h.published();
          }
        }
  }

  private static <T> List<T> captured(List<T> values, Runnable callback) {
    var called = new AtomicBoolean();
    return new AbstractList<>() {
      public int size() {
        if (called.compareAndSet(false, true)) callback.run();
        return values.size();
      }

      public T get(int index) {
        return values.get(index);
      }
    };
  }

  @Test
  void appliedTrustReadCannotStaleAReplacementOrReopenAClosedChecker() {
    for (boolean close : List.of(false, true))
      try (var h = new Harness(true)) {
        assertTrue(h.start());
        var origins = new ArrayList<>(h.state.user.performance.trustedOrigins);
        origins.add("https://other.example.test");
        h.state.user.performance.trustedOrigins = origins;
        h.checkpoint();
        h.state.user.performance.trustedOrigins =
            captured(
                origins,
                () -> {
                  if (close) h.closeOwner();
                  else {
                    h.cancel();
                    assertTrue(h.start());
                  }
                });
        if (close) {
          assertEquals("CLOSED", h.phase());
          assertFalse(h.start());
          h.unchanged();
        } else {
          h.pending();
          h.accept();
          h.published();
        }
      }
  }

  private static final class Harness implements AutoCloseable {
    final EditorState state = ReadyValidationTest.state();
    final List<String> errors = new ArrayList<>();
    final Object owner;
    Consumer<Runnable> admission = Runnable::run;
    JsonNode project, preferences;
    Object draft;
    List<Diagnostic> issues;
    long revision, history;
    boolean dirty;

    Harness(boolean update) {
      state.user.performance.updateEndpoint = "https://updates.example.test/meta.json";
      state.user.performance.trustedOrigins =
          new ArrayList<>(List.of("https://updates.example.test", "https://releases.example.test"));
      Executor executor = work -> admission.accept(work);
      owner =
          update
              ? new UpdateCheckSession(
                  state.user, "1.0.0", executor, Runnable::run, (e, o) -> RELEASE, errors::add)
              : new ProjectValidationSession(
                  state,
                  executor,
                  Runnable::run,
                  p -> List.of(ReadyValidationTest.RESULT),
                  errors::add);
      checkpoint();
    }

    void checkpoint() {
      project = ProjectIO.JSON.valueToTree(state.project());
      preferences = ProjectIO.JSON.valueToTree(state.user);
      draft = state.draft;
      issues = state.issues;
      revision = state.revision;
      history = state.document.historyBytes();
      dirty = state.document.dirty();
    }

    boolean start() {
      return owner instanceof UpdateCheckSession check
          ? check.start()
          : ((ProjectValidationSession) owner).start();
    }

    Object token() {
      return owner instanceof UpdateCheckSession check
          ? check.requestToken()
          : ((ProjectValidationSession) owner).requestToken();
    }

    String phase() {
      return owner instanceof UpdateCheckSession check
          ? check.view().phase().name()
          : ((ProjectValidationSession) owner).view().phase().name();
    }

    String message() {
      return owner instanceof UpdateCheckSession check
          ? check.view().message()
          : ((ProjectValidationSession) owner).view().message();
    }

    void cancel() {
      if (owner instanceof UpdateCheckSession check) check.cancel();
      else ((ProjectValidationSession) owner).cancel();
    }

    void accept() {
      if (owner instanceof UpdateCheckSession check) check.acceptPrepared();
      else ((ProjectValidationSession) owner).acceptPrepared();
    }

    void pending() {
      assertEquals("RUNNING", phase());
      assertTrue(
          message().startsWith(owner instanceof UpdateCheckSession ? "Checking" : "Validating"));
      if (owner instanceof UpdateCheckSession check) assertNull(check.view().result());
      assertSame(issues, state.issues);
      assertTrue(errors.isEmpty());
      unchanged();
    }

    void published() {
      assertEquals("READY", phase());
      if (owner instanceof UpdateCheckSession check) {
        assertSame(RELEASE, check.view().result().release());
        assertSame(issues, state.issues);
      } else assertEquals(List.of(ReadyValidationTest.RESULT), state.issues);
      assertTrue(errors.isEmpty());
      unchanged();
    }

    void unchanged() {
      assertEquals(project, ProjectIO.JSON.valueToTree(state.project()));
      assertEquals(preferences, ProjectIO.JSON.valueToTree(state.user));
      assertSame(draft, state.draft);
      assertEquals("-", state.seedInputs.text("seedOffset"));
      assertEquals(revision, state.revision);
      assertEquals(history, state.document.historyBytes());
      assertEquals(dirty, state.document.dirty());
    }

    /** Replace only the task boundary after explicitly cancelling the original computation. */
    void install(Runnable cancellation, Error observation) {
      try {
        var field = owner.getClass().getDeclaredField("task");
        field.setAccessible(true);
        var previous = (Future<?>) field.get(owner);
        if (previous != null) previous.cancel(false);
        field.set(
            owner,
            new FutureTask<Void>(() -> null) {
              public boolean isDone() {
                if (observation != null) throw observation;
                return super.isDone();
              }

              public boolean cancel(boolean interrupt) {
                boolean cancelled = super.cancel(interrupt);
                cancellation.run();
                return cancelled;
              }
            });
      } catch (ReflectiveOperationException failure) {
        throw new AssertionError(failure);
      }
    }

    void closeOwner() {
      if (owner instanceof UpdateCheckSession check) check.close();
      else ((ProjectValidationSession) owner).close();
    }

    public void close() {
      closeOwner();
      state.close();
    }
  }
}
