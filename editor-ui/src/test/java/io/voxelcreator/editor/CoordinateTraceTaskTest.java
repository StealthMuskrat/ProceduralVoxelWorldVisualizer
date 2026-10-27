package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CoordinateTraceTaskTest {
  private static final BlockPos POINT = new BlockPos(-2, 3, 4);

  @Test
  void replacementCancelsQueuedWorkAndOnlyTheLatestTaskRuns() {
    var gate = new InspectorRequests();
    try (var task = new CoordinateTraceTask()) {
      var calls = new AtomicInteger();
      var first = gate.begin(1, false);
      task.begin(first, POINT);
      var obsolete = new FutureTask<>(() -> calls.addAndGet(100));
      task.attach(first, obsolete);
      var next = gate.begin(1, false);
      task.begin(next, new BlockPos(0, 0, 0));
      var latest = new FutureTask<>(() -> calls.incrementAndGet());
      task.attach(next, latest);
      obsolete.run();
      latest.run();
      assertTrue(obsolete.isCancelled());
      assertEquals(1, calls.get());
      assertEquals(new BlockPos(0, 0, 0), task.coordinate());
      assertFalse(task.finish(first));
      assertTrue(task.pending());
      assertTrue(task.finish(next));
      assertFalse(task.pending());
      assertFalse(latest.isCancelled());
    }
  }

  @Test
  void cancellationAndClosureRejectLateFutureAttachment() {
    for (boolean close : new boolean[] {false, true}) {
      var task = new CoordinateTraceTask();
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      if (close) task.close();
      else task.cancel();
      var late = new FutureTask<>(() -> fail("Late work ran"));
      task.attach(request, late);
      late.run();
      assertTrue(late.isCancelled());
      assertFalse(task.pending());
      assertFalse(task.finish(request));
      task.close();
    }
  }

  @Test
  void supersededAttachmentCannotReplaceTheCurrentOwnedFuture() {
    var gate = new InspectorRequests();
    try (var task = new CoordinateTraceTask()) {
      var first = gate.begin(1, false);
      task.begin(first, POINT);
      var next = gate.begin(1, false);
      task.begin(next, POINT);
      var current = new FutureTask<>(() -> 1);
      task.attach(next, current);
      var late = new FutureTask<>(() -> 2);
      task.attach(first, late);
      assertTrue(late.isCancelled());
      assertFalse(current.isCancelled());
      task.cancel();
      assertTrue(current.isCancelled());
    }
  }

  @Test
  void closingRequestsInterruptionOfRunningOwnedWork() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    try (var workers = Executors.newSingleThreadExecutor();
        var task = new CoordinateTraceTask()) {
      var request = new InspectorRequests().begin(1, false);
      task.begin(request, POINT);
      try {
        var future =
            workers.submit(
                () -> {
                  entered.countDown();
                  try {
                    release.await();
                  } catch (InterruptedException e) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                  }
                });
        task.attach(request, future);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        task.close();
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(future.isCancelled());
        assertThrows(IllegalStateException.class, () -> task.begin(request, POINT));
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void invalidRequestsCannotDiscardAnExistingPendingCoordinate() {
    var gate = new InspectorRequests();
    try (var task = new CoordinateTraceTask()) {
      var coordinate = gate.begin(1, false);
      task.begin(coordinate, POINT);
      assertThrows(IllegalArgumentException.class, () -> task.begin(gate.begin(1, true), POINT));
      assertThrows(NullPointerException.class, () -> task.begin(coordinate, null));
      assertEquals(coordinate, task.request());
      assertEquals(POINT, task.coordinate());
    }
  }
}
