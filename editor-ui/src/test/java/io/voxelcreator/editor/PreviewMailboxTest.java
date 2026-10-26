package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class PreviewMailboxTest {
  @Test
  void latestVersionOwnsSlotEvenAfterItWasConsumed() {
    var box = new PreviewMailbox<String>();
    assertTrue(box.publish(0, "initial"));
    assertEquals("initial", box.take());
    assertNull(box.take());
    box.advance(1);
    assertFalse(box.publish(0, "obsolete success"));
    assertFalse(box.publish(0, "obsolete failure"));
    assertNull(box.take());
    assertTrue(box.publish(1, "newest"));
    assertEquals("newest", box.take());
    assertFalse(box.publish(0, "late"));
    assertNull(box.take());
  }

  @Test
  void advancingDropsPendingPayloadAndCancelsPriorTokens() {
    var box = new PreviewMailbox<Object>();
    var token = box.token(0);
    box.publish(0, new Object());
    box.advance(1);
    assertTrue(token.isCancelled());
    assertNull(box.take());
    assertFalse(box.token(1).isCancelled());
    assertThrows(CancellationException.class, token::throwIfCancelled);
    assertThrows(IllegalArgumentException.class, () -> box.advance(1));
    assertThrows(IllegalArgumentException.class, () -> box.advance(0));
  }

  @Test
  void closureClearsSlotAndPreventsLatePublicationAndNewWork() {
    var box = new PreviewMailbox<String>();
    var token = box.token(0);
    box.publish(0, "ready");
    box.close();
    assertNull(box.take());
    assertFalse(box.publish(0, "late"));
    assertTrue(token.isCancelled());
    assertTrue(box.token(0).isCancelled());
    box.close();
    box.advance(2);
    assertFalse(box.publish(2, "late"));
  }

  @Test
  void staleWorkerCannotReplacePendingCurrentPayload() throws Exception {
    var box = new PreviewMailbox<String>();
    try (var workers = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old =
          workers.submit(
              () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return box.publish(0, "old");
              });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        box.advance(1);
        box.publish(1, "current");
      } finally {
        release.countDown();
      }
      assertFalse(old.get(5, TimeUnit.SECONDS));
      assertEquals("current", box.take());
    }
  }

  @Test
  void cancellationSurvivesASamplerClearingThreadInterrupts() throws Exception {
    var box = new PreviewMailbox<String>();
    var token = box.token(0);
    try (var rows = new NoiseImageRows();
        var workers = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var old =
          workers.submit(
              () ->
                  rows.sample(
                      "source",
                      0,
                      (x, y, z) -> {
                        entered.countDown();
                        try {
                          assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException ignored) {
                        }
                        Thread.interrupted();
                        return x + z;
                      },
                      4,
                      0,
                      0,
                      1,
                      1 << 20,
                      false,
                      token));
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        box.advance(1);
      } finally {
        release.countDown();
      }
      var failed = assertThrows(ExecutionException.class, () -> old.get(5, TimeUnit.SECONDS));
      assertInstanceOf(CancellationException.class, failed.getCause());
      assertEquals(0, rows.memory().rows());
      var newest =
          rows.sample("source", 1, (x, y, z) -> x + z, 4, 1, 0, 1, 1 << 20, false, box.token(1));
      assertEquals(-3, newest.value(0, 0));
    }
  }

  @Test
  void closeDuringWorkCancelsBeforeAnyRowCanBeAdmitted() {
    var box = new PreviewMailbox<String>();
    try (var rows = new NoiseImageRows()) {
      var token = box.token(0);
      assertThrows(
          CancellationException.class,
          () ->
              rows.sample(
                  "source",
                  0,
                  (x, y, z) -> {
                    box.close();
                    return 1;
                  },
                  4,
                  0,
                  0,
                  1,
                  1 << 20,
                  false,
                  token));
      assertEquals(0, rows.memory().rows());
      assertFalse(box.publish(0, "late"));
    }
  }

  @Test
  void explicitTokenCancellationDoesNotCancelIndependentCurrentRequest() {
    var box = new PreviewMailbox<String>();
    var first = box.token(0);
    var second = box.token(0);
    first.cancel();
    assertTrue(first.isCancelled());
    assertFalse(second.isCancelled());
    box.advance(1);
    assertTrue(second.isCancelled());
    assertFalse(box.token(1).isCancelled());
  }
}
