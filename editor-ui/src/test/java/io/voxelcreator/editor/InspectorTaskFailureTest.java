package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred terminal worker/handoff observation without granting success publication authority. */
class InspectorTaskFailureTest {
  private static final BlockPos POINT = new BlockPos(7, 0, 3);

  @Test
  void unfinishedWorkIsNeverReadAndDoesNotBlockTheUiPoll() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      task.attach(
          request,
          new FutureTask<>(() -> 1) {
            public Integer get() {
              throw new AssertionError("Unfinished future read");
            }
          });
      assertNull(task.failure());
      assertTrue(task.pending());
      assertSame(request, task.request());
    }
  }

  @Test
  void normalWorkerCompletionStillWaitsForItsOwnedUiCallback() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      var future = new FutureTask<>(() -> 1);
      task.attach(request, future);
      future.run();
      assertNull(task.failure());
      assertTrue(task.pending());
      assertEquals(POINT, task.coordinate());
      assertTrue(task.finishSuccessful(request));
      assertFalse(task.pending());
    }
  }

  @Test
  void runtimeAndErrorCausesRetainIdentityAndTheExactPendingTarget() {
    for (Throwable failure :
        List.of(new AssertionError("worker"), new IllegalStateException("worker")))
      try (var task = new InspectorTask()) {
        var request = new InspectorRequests().begin(1, false);
        task.begin(request, POINT);
        task.attach(request, CompletableFuture.failedFuture(failure));
        assertSame(failure, task.failure());
        assertTrue(task.pending());
        assertSame(request, task.request());
        assertEquals(POINT, task.coordinate());
        assertEquals(0, failure.getSuppressed().length);
        assertTrue(task.finish(request));
        assertNull(task.failure());
      }
  }

  @Test
  void externallyCancelledFutureCanRetireWithoutAnyUiCallback() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, true);
      task.begin(request, null);
      var future = new FutureTask<>(() -> 1);
      task.attach(request, future);
      future.cancel(false);
      assertInstanceOf(CancellationException.class, task.failure());
      assertTrue(task.finish(request));
      assertNull(task.failure());
    }
  }

  @Test
  void successorNeverObservesAPredecessorsErrorOrAcceptsItsLateAttachment() {
    try (var task = new InspectorTask()) {
      var gate = new InspectorRequests();
      var old = gate.begin(1, false);
      task.begin(old, POINT);
      var failed = CompletableFuture.failedFuture(new AssertionError("obsolete"));
      task.attach(old, failed);
      var next = gate.begin(1, false);
      task.begin(next, new BlockPos(2, 0, 3));
      task.attach(next, new FutureTask<>(() -> 1));
      task.attach(old, failed);
      assertNull(task.failure());
      assertFalse(task.finish(old));
      assertSame(next, task.request());
      assertEquals(new BlockPos(2, 0, 3), task.coordinate());
    }
  }

  @Test
  void terminalIsDoneOrGetErrorsAreObservedWithoutEscapingTheWorkspace() {
    for (boolean status : new boolean[] {false, true})
      try (var task = new InspectorTask()) {
        var failure = new AssertionError("future-method");
        var request = new InspectorRequests().begin(1, false);
        task.begin(request, POINT);
        task.attach(
            request,
            new FutureTask<>(() -> 1) {
              public boolean isDone() {
                if (status) throw failure;
                return true;
              }

              public Integer get() {
                throw failure;
              }
            });
        assertSame(failure, task.failure());
        assertTrue(task.finish(request));
        assertNull(task.failure());
      }
  }

  @Test
  void interruptedTerminalReadPreservesTheUiThreadInterruptFlag() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      task.attach(
          request,
          new FutureTask<>(() -> 1) {
            public boolean isDone() {
              return true;
            }

            public Integer get() throws InterruptedException {
              throw new InterruptedException("read");
            }
          });
      assertInstanceOf(InterruptedException.class, task.failure());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void enqueueThenFailCannotPublishAfterTerminalFailureRetiresTheRequest() {
    try (var task = new InspectorTask()) {
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      var ui = new ArrayDeque<Runnable>();
      var published = new AtomicInteger();
      var failure = new AssertionError("handoff");
      var future =
          new FutureTask<Void>(
              () -> {
                ui.add(
                    () -> {
                      if (task.finishSuccessful(request)) published.incrementAndGet();
                    });
                throw failure;
              });
      task.attach(request, future);
      future.run();
      ui.remove().run();
      assertEquals(0, published.get());
      assertTrue(task.pending());
      assertSame(failure, task.failure());
      assertTrue(task.finish(request));
      assertEquals(0, published.get());
    }
  }

  @Test
  void closingAndCancellationDiscardTerminalFailuresAndLateCallbacks() {
    for (boolean closed : new boolean[] {false, true}) {
      var task = new InspectorTask();
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      task.attach(request, CompletableFuture.failedFuture(new AssertionError("obsolete")));
      if (closed) task.close();
      else task.cancel();
      assertNull(task.failure());
      assertFalse(task.finish(request));
      assertFalse(task.pending());
      task.close();
    }
  }

  @Test
  void unreadableBlankOrOversizedMessagesHaveBoundedUsableFallbacks() {
    var unreadable =
        new AssertionError() {
          public String getMessage() {
            throw new AssertionError("message reader");
          }
        };
    assertEquals(unreadable.getClass().getName(), InspectorTask.describe(unreadable));
    assertEquals("AssertionError", InspectorTask.describe(new AssertionError(" ")));
    assertTrue(InspectorTask.describe(new AssertionError("x".repeat(10000))).length() <= 2049);
    assertEquals(0, unreadable.getSuppressed().length);
  }
}
