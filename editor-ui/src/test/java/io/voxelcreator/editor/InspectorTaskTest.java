package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.BlockPos;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InspectorTaskTest {
  private static final BlockPos POINT = new BlockPos(-2, 3, 4);

  @Test
  void worldPicksOwnUnknownOrKnownCoordinatesAndCanReplaceCoordinateWork() {
    var gate = new InspectorRequests();
    try (var task = new InspectorTask()) {
      var coordinate = gate.begin(1, false);
      task.begin(coordinate, POINT);
      var old = new FutureTask<>(() -> 1);
      task.attach(coordinate, old);
      var pick = gate.begin(1, true);
      task.begin(pick, null);
      assertTrue(old.isCancelled());
      assertNull(task.coordinate());
      assertTrue(task.request().viewBound());
      var known = gate.begin(1, true);
      task.begin(known, POINT);
      assertFalse(task.finish(pick));
      assertEquals(POINT, task.coordinate());
      assertTrue(task.finish(known));
    }
  }

  @Test
  void aRunningPredecessorThatIgnoresInterruptionCannotFinishTheNewRequest() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var gate = new InspectorRequests();
    try (var workers = Executors.newSingleThreadExecutor();
        var task = new InspectorTask()) {
      var old = gate.begin(1, true);
      task.begin(old, null);
      try {
        task.attach(
            old,
            workers.submit(
                () -> {
                  entered.countDown();
                  while (release.getCount() != 0) {
                    try {
                      release.await();
                    } catch (InterruptedException ignored) {
                    }
                  }
                  done.countDown();
                }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        var next = gate.begin(1, false);
        task.begin(next, POINT);
        var future = new FutureTask<>(() -> 1);
        task.attach(next, future);
        release.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertFalse(task.finish(old));
        assertEquals(next, task.request());
        assertFalse(future.isCancelled());
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void replacementCancelsQueuedWorkAndOnlyTheLatestTaskRuns() {
    var gate = new InspectorRequests();
    try (var task = new InspectorTask()) {
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
      var task = new InspectorTask();
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
    try (var task = new InspectorTask()) {
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
        var task = new InspectorTask()) {
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
    try (var task = new InspectorTask()) {
      var coordinate = gate.begin(1, false);
      task.begin(coordinate, POINT);
      assertThrows(NullPointerException.class, () -> task.begin(null, POINT));
      assertThrows(NullPointerException.class, () -> task.begin(coordinate, null));
      assertEquals(coordinate, task.request());
      assertEquals(POINT, task.coordinate());
    }
  }
}
