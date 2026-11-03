package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.model.ProjectData;
import io.voxelcreator.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Deferred public-future mutations against actual admitted/shared chunk requests. */
class ChunkSubscriberOwnershipTest {
  private WorldContext world(NoiseGeneratorEngine engine) {
    return engine.openWorld(
        new CompiledProject(new ProjectData()),
        new WorldOptions(null, 2, WorldOptions.CachePolicy.NO_CACHE, 0));
  }

  private record HeldWorker(CountDownLatch release) implements AutoCloseable {
    public void close() {
      release.countDown();
    }
  }

  private HeldWorker hold(NoiseGeneratorEngine engine) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    engine.workers.execute(
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
          }
        });
    assertTrue(entered.await(3, TimeUnit.SECONDS));
    return new HeldWorker(release);
  }

  @Test
  void blockedCallbackCanAdmitASuccessorAtTheFullSubscriberLimit() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var held = hold(engine);
        var publishers = Executors.newVirtualThreadPerTaskExecutor()) {
      var position = new ChunkPos(0, 0, 0);
      var requests = new ArrayList<CompletableFuture<VoxelChunk>>();
      for (int i = 0; i < 8192; i++) requests.add(world.generateChunkAsync(position));
      assertThrows(RejectedExecutionException.class, () -> world.generateChunkAsync(position));
      var first = requests.getFirst();
      var entered = new CountDownLatch(1);
      var release = new CountDownLatch(1);
      var successor = new AtomicReference<CompletableFuture<VoxelChunk>>();
      var callbackFailure = new AtomicReference<Throwable>();
      first.whenComplete(
          (value, failure) -> {
            try {
              successor.set(world.generateChunkAsync(position));
            } catch (Throwable rejected) {
              callbackFailure.set(rejected);
            } finally {
              entered.countDown();
            }
            try {
              release.await();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
      var supplied = VoxelChunk.uniform(position, 2, 42);
      var publication = publishers.submit(() -> first.complete(supplied));
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertNull(callbackFailure.get());
        assertNotNull(successor.get());
        assertTrue(first.isDone());
        assertSame(supplied, first.join());
        assertFalse(publication.isDone());
        assertEquals(1, world.inFlightChunks());
        successor.get().cancel(false);
        var replacement = world.generateChunkAsync(position);
        replacement.cancel(false);
        for (int i = 1; i < requests.size(); i++) requests.get(i).cancel(false);
        assertEquals(0, world.inFlightChunks());
        assertEquals(0, engine.metrics().queued());
        assertFalse(publication.isDone());
      } finally {
        release.countDown();
        for (var request : requests) request.cancel(false);
        if (successor.get() != null) successor.get().cancel(false);
      }
      assertTrue(publication.get(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void everyTerminalMutationRetiresTheLastQueuedSubscriberBeforeCallbacks() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var held = hold(engine)) {
      var position = new ChunkPos(0, 0, 0);
      var supplied = VoxelChunk.uniform(position, 2, 42);
      List<Consumer<CompletableFuture<VoxelChunk>>> operations =
          List.of(
              f -> f.complete(supplied),
              f -> f.completeExceptionally(new IllegalStateException("caller")),
              f -> f.cancel(false),
              f -> f.obtrudeValue(supplied),
              f -> f.obtrudeException(new IllegalStateException("caller")),
              f -> f.completeAsync(() -> supplied, Runnable::run),
              f ->
                  f.completeAsync(
                      () -> {
                        throw new IllegalStateException("supplier");
                      },
                      Runnable::run),
              f -> f.orTimeout(0, TimeUnit.NANOSECONDS),
              f -> f.completeOnTimeout(supplied, 0, TimeUnit.NANOSECONDS));
      for (var operation : operations) {
        var future = world.generateChunkAsync(position);
        assertEquals(1, world.inFlightChunks());
        assertEquals(1, engine.metrics().queued());
        var retired =
            future.handle(
                (value, failure) -> world.inFlightChunks() == 0 && engine.metrics().queued() == 0);
        operation.accept(future);
        assertTrue(retired.get(3, TimeUnit.SECONDS));
      }
      assertEquals(0, engine.metrics().completed());
    }
  }

  @Test
  void callerSuppliedSnapshotsDoNotReplaceSharedGenerationOrPopulateTheCache() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world =
            engine.openWorld(
                new CompiledProject(new ProjectData()),
                new WorldOptions(
                    null, 2, WorldOptions.CachePolicy.BOUNDED_MEMORY_CACHE, 1L << 20));
        var held = hold(engine)) {
      var position = new ChunkPos(0, 0, 0);
      var first = world.generateChunkAsync(position);
      var peer = world.generateChunkAsync(position);
      var supplied = VoxelChunk.uniform(position, 2, 42);
      assertTrue(first.complete(supplied));
      assertFalse(peer.isDone());
      assertEquals(1, world.inFlightChunks());
      held.close();
      var generated = peer.get(3, TimeUnit.SECONDS);
      assertTrue(generated.isEmpty());
      assertNotSame(supplied, generated);
      assertSame(supplied, first.join());
      assertTrue(world.generateChunkAsync(position).get(3, TimeUnit.SECONDS).isEmpty());
      assertEquals(1, engine.metrics().completed());
    }
  }

  @Test
  void timedReadsAndCopiedObserverMutationDoNotWithdrawTheSubscriber() throws Exception {
    try (var engine = new NoiseGeneratorEngine(1, 16, 8L << 20);
        var world = world(engine);
        var held = hold(engine)) {
      var future = world.generateChunkAsync(new ChunkPos(0, 0, 0));
      assertThrows(TimeoutException.class, () -> future.get(0, TimeUnit.NANOSECONDS));
      future.copy().cancel(false);
      future.thenApply(value -> value).complete(null);
      assertFalse(future.isDone());
      assertEquals(1, world.inFlightChunks());
      assertEquals(1, engine.metrics().queued());
      future.cancel(false);
      assertEquals(0, world.inFlightChunks());
      assertEquals(0, engine.metrics().queued());
    }
  }
}
