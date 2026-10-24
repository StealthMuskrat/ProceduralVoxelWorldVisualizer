package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FairWorkQueueTest {
  private record Work(Object scope, GenerationPriority priority, String label)
      implements FairWorkQueue.Work {
    public void run() {}
  }

  @Test
  void normalScopesTakeRoundRobinTurnsAndKeepTheirOwnFifo() {
    var queue = new FairWorkQueue(16);
    Object a = new Object(), b = new Object();
    queue.add(new Work(a, GenerationPriority.NORMAL, "a1"));
    queue.add(new Work(a, GenerationPriority.NORMAL, "a2"));
    queue.add(new Work(a, GenerationPriority.NORMAL, "a3"));
    queue.add(new Work(b, GenerationPriority.NORMAL, "b1"));
    queue.add(new Work(b, GenerationPriority.NORMAL, "b2"));
    var labels = new ArrayList<String>();
    while (!queue.isEmpty()) labels.add(((Work) queue.remove()).label());
    assertEquals(List.of("a1", "b1", "a2", "b2", "a3"), labels);
    assertEquals(5, queue.statistics().dequeued());
    assertEquals(0, queue.statistics().queuedScopes());
  }

  @Test
  void weightedLanesDoNotStarveNormalOrBackgroundWork() {
    var queue = new FairWorkQueue(24);
    Object owner = new Object();
    for (var p : GenerationPriority.values())
      for (int i = 0; i < 7; i++) queue.add(new Work(owner, p, p.name() + i));
    var served = new ArrayList<GenerationPriority>();
    for (int i = 0; i < 7; i++) served.add(((Work) queue.remove()).priority());
    assertEquals(
        List.of(
            GenerationPriority.INTERACTIVE,
            GenerationPriority.INTERACTIVE,
            GenerationPriority.NORMAL,
            GenerationPriority.INTERACTIVE,
            GenerationPriority.BACKGROUND,
            GenerationPriority.INTERACTIVE,
            GenerationPriority.NORMAL),
        served);
    var stats = queue.statistics();
    assertEquals(3, stats.byPriority().get(GenerationPriority.INTERACTIVE));
    assertEquals(5, stats.byPriority().get(GenerationPriority.NORMAL));
    assertEquals(6, stats.byPriority().get(GenerationPriority.BACKGROUND));
    assertThrows(UnsupportedOperationException.class, () -> stats.byPriority().clear());
  }

  @Test
  void promotionMovesOneQueuedJobAndPreservesCapacityAndIdentity() {
    var queue = new FairWorkQueue(3);
    Object owner = new Object();
    var background = new Work(owner, GenerationPriority.BACKGROUND, "promote");
    queue.add(new Work(owner, GenerationPriority.NORMAL, "normal"));
    queue.add(background);
    assertTrue(queue.promote(background, GenerationPriority.INTERACTIVE));
    assertSame(background, queue.peek());
    assertSame(background, queue.remove());
    assertEquals(2, queue.remainingCapacity());
    assertEquals(1, queue.statistics().promotions());
    assertFalse(queue.promote(background, GenerationPriority.INTERACTIVE));
    assertEquals("normal", ((Work) queue.remove()).label());
  }

  @Test
  void removalReleasesCapacityAndDuplicateRunnablesAreSupported() {
    var queue = new FairWorkQueue(2);
    Runnable work = () -> {};
    queue.add(work);
    queue.add(work);
    assertFalse(queue.offer(() -> {}));
    assertEquals(1, queue.statistics().rejected());
    assertTrue(queue.remove(work));
    assertEquals(1, queue.size());
    assertSame(work, queue.remove());
    assertTrue(queue.isEmpty());
    assertEquals(1, queue.statistics().removed());
  }

  @Test
  void blockingOperationsTimeoutWakeAndDrainWithoutLeakingBuckets() throws Exception {
    var queue = new FairWorkQueue(1);
    Runnable first = () -> {}, second = () -> {};
    queue.put(first);
    assertFalse(queue.offer(second, 1, TimeUnit.MILLISECONDS));
    try (var worker = Executors.newSingleThreadExecutor()) {
      var entered = new CountDownLatch(1);
      var added =
          worker.submit(
              () -> {
                entered.countDown();
                queue.put(second);
                return true;
              });
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      assertSame(first, queue.take());
      assertTrue(added.get(2, TimeUnit.SECONDS));
      assertSame(second, queue.take());
    }
    assertNull(queue.poll(1, TimeUnit.MILLISECONDS));
    queue.add(first);
    var out = new ArrayList<Runnable>();
    assertEquals(1, queue.drainTo(out));
    assertEquals(List.of(first), out);
    assertEquals(0, queue.statistics().queuedScopes());
    assertThrows(IllegalArgumentException.class, () -> queue.drainTo(queue));
  }
}
