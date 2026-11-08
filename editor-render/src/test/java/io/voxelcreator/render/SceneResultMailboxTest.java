package io.voxelcreator.render;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Deferred CPU-only handoff ownership; no GL context or native operations. */
class SceneResultMailboxTest {
  @Test
  void fifoDeliveryAndClosureKeepPreviouslyDeliveredSnapshotsIndependent() {
    var mailbox = new SceneResultMailbox<Object>();
    var chunk = VoxelChunk.uniform(new ChunkPos(-1, 0, 1), 2, 1); var pending = new Object();
    assertNull(mailbox.poll()); assertTrue(mailbox.offer(chunk)); assertTrue(mailbox.offer(pending));
    assertSame(chunk, mailbox.poll()); mailbox.close(); mailbox.close();
    assertNull(mailbox.poll()); assertFalse(mailbox.offer(pending)); assertNull(mailbox.poll());
    assertThrows(NullPointerException.class, () -> mailbox.offer(null));
    for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) assertEquals(1, chunk.getVoxelTypeId(x, y, z));
  }

  @Test
  void preparedReceiptsOfferedAfterClosureAreRejectedWithoutReadingTheirPayload() throws Exception {
    var mailbox = new SceneResultMailbox<Object>(); var entered = new CountDownLatch(3); var release = new CountDownLatch(1);
    var reads = new AtomicInteger();
    Object hostile = new AssertionError() { public String toString() { reads.incrementAndGet(); throw new AssertionError("text reader"); } };
    var payloads = List.of(VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 1), new Object(), hostile);
    try (var workers = Executors.newFixedThreadPool(3)) {
      var results = new ArrayList<Future<Boolean>>();
      for (var payload : payloads) results.add(workers.submit(() -> {
        entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Prepared receipt not released");
        return mailbox.offer(payload);
      }));
      try { assertTrue(entered.await(3, TimeUnit.SECONDS)); mailbox.close(); assertNull(mailbox.poll()); }
      finally { release.countDown(); }
      for (var result : results) assertFalse(result.get(5, TimeUnit.SECONDS));
      assertNull(mailbox.poll()); assertEquals(0, reads.get());
    }
  }

  @Test
  void racingPublishersAndClosersLeaveNoReceiptAfterCloseReturns() throws Exception {
    try (var workers = Executors.newFixedThreadPool(5)) {
      for (int round = 0; round < 32; round++) {
        var mailbox = new SceneResultMailbox<Object>(); var start = new CountDownLatch(1);
        var publishers = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 4; i++) publishers.add(workers.submit(() -> {
          if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Publisher not released");
          return mailbox.offer(new Object());
        }));
        var closer = workers.submit(() -> {
          if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Closer not released");
          mailbox.close(); mailbox.close(); return true;
        });
        start.countDown(); assertTrue(closer.get(5, TimeUnit.SECONDS));
        for (var publisher : publishers) publisher.get(5, TimeUnit.SECONDS); // Either side may win the handoff.
        assertFalse(mailbox.offer(new Object())); assertNull(mailbox.poll());
      }
    }
  }
}
