package io.voxelcreator.editor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OwnedPreviewMailboxTest {
  private static class Source implements AutoCloseable {
    final AtomicInteger closes = new AtomicInteger();

    public void close() {
      assertEquals(1, closes.incrementAndGet(), "Source closed twice");
    }
  }

  @Test
  void takeTransfersOwnershipWithoutClosingIt() {
    var box = new OwnedPreviewMailbox<Source>();
    long id = box.begin();
    var source = new Source();
    assertTrue(box.publish(id, source));
    assertTrue(box.pending());
    assertSame(source, box.take());
    box.close();
    assertEquals(0, source.closes.get());
    source.close();
    assertNull(box.take());
  }

  @Test
  void replacingAndInvalidatingCloseExactlyTheDiscardedSources() {
    try (var box = new OwnedPreviewMailbox<Source>()) {
      long id = box.begin();
      var first = new Source();
      var second = new Source();
      box.publish(id, first);
      box.publish(id, second);
      assertEquals(1, first.closes.get());
      box.invalidate();
      assertEquals(1, second.closes.get());
      assertFalse(box.current(id));
      assertFalse(box.pending());
      var stale = new Source();
      assertFalse(box.publish(id, stale));
      assertEquals(1, stale.closes.get());
    }
  }

  @Test
  void lateSourceCannotRefillConsumedCurrentSlot() {
    try (var box = new OwnedPreviewMailbox<Source>()) {
      long old = box.begin(), current = box.begin();
      var fresh = new Source();
      box.publish(current, fresh);
      assertSame(fresh, box.take());
      var late = new Source();
      assertFalse(box.publish(old, late));
      assertEquals(1, late.closes.get());
      assertNull(box.take());
      fresh.close();
    }
  }

  @Test
  void closureRejectsAndClosesLateSourcesWithoutAUiDrain() throws Exception {
    var box = new OwnedPreviewMailbox<Source>();
    long id = box.begin();
    try (var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var source = new Source();
      var result =
          worker.submit(
              () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return box.publish(id, source);
              });
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        box.close();
      } finally {
        release.countDown();
      }
      assertFalse(result.get(5, TimeUnit.SECONDS));
      assertEquals(1, source.closes.get());
      assertNull(box.take());
      assertThrows(IllegalStateException.class, box::begin);
    }
  }

  @Test
  void cleanupDoesNotHoldTheMailboxMonitor() throws Exception {
    var box = new OwnedPreviewMailbox<AutoCloseable>();
    long id = box.begin();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    box.publish(
        id,
        () -> {
          entered.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        });
    try (var worker = Executors.newSingleThreadExecutor()) {
      var disposing = worker.submit(box::invalidate);
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        long newer = box.begin();
        var fresh = new Source();
        assertTrue(box.publish(newer, fresh));
        assertSame(fresh, box.take());
        fresh.close();
      } finally {
        release.countDown();
      }
      disposing.get(5, TimeUnit.SECONDS);
    }
    box.close();
  }

  @Test
  void cleanupFailureDoesNotLoseNewerOwnedPayload() {
    var box = new OwnedPreviewMailbox<AutoCloseable>();
    long id = box.begin();
    box.publish(
        id,
        () -> {
          throw new IllegalStateException("Close failure");
        });
    var fresh = new Source();
    assertTrue(box.publish(id, fresh));
    assertSame(fresh, box.take());
    fresh.close();
    box.close();
  }

  @Test
  void repeatedClosureAndInvalidationDoNotDoubleClosePendingSource() {
    var box = new OwnedPreviewMailbox<Source>();
    long id = box.begin();
    var source = new Source();
    box.publish(id, source);
    box.close();
    box.close();
    box.invalidate();
    assertEquals(1, source.closes.get());
    assertFalse(box.current(id));
    var late = new Source();
    assertFalse(box.publish(id, late));
    assertEquals(1, late.closes.get());
  }
}
