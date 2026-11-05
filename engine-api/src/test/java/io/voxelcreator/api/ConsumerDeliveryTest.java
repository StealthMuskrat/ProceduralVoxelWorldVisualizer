package io.voxelcreator.api;

import static org.junit.jupiter.api.Assertions.*;

import io.voxelcreator.storage.*;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Deferred host invocation/observation parity without an engine or native code. */
class ConsumerDeliveryTest {
  private static final VoxelChunk CHUNK = VoxelChunk.uniform(new ChunkPos(0, 0, 0), 2, 7);

  @SuppressWarnings("unchecked")
  private CompletionStage<Void> hostile(Error failure, boolean conversion) {
    return (CompletionStage<Void>)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {CompletionStage.class},
            (proxy, method, args) -> {
              if (method.getName().equals("toCompletableFuture")) {
                if (conversion) throw failure;
                throw new UnsupportedOperationException("Observe instead");
              }
              if (method.getName().equals("handle")) throw failure;
              throw new AssertionError("Unexpected host method " + method.getName());
            });
  }

  @Test
  void runtimeAndCancellationExceptionsRetainTheirOriginalControlFlow() {
    for (var failure :
        new RuntimeException[] {
          new IllegalStateException("host"), new CancellationException("host")
        }) {
      assertSame(
          failure,
          assertThrows(
              RuntimeException.class,
              () ->
                  ConsumerAcknowledgement.deliver(
                      chunk -> {
                        throw failure;
                      },
                      CHUNK,
                      new CancellationToken())));
      assertEquals(0, failure.getSuppressed().length);
    }
  }

  @Test
  void synchronousErrorsAndFailedFutureErrorsHaveTheSameUnmodifiedCause() {
    var failure = new AssertionError("host-error");
    ChunkConsumer direct =
        chunk -> {
          assertSame(CHUNK, chunk);
          throw failure;
        };
    var failed = CompletableFuture.<Void>failedFuture(failure);
    for (var consumer : new ChunkConsumer[] {direct, chunk -> failed}) {
      var observed =
          assertThrows(
              CompletionException.class,
              () -> ConsumerAcknowledgement.deliver(consumer, CHUNK, new CancellationToken()));
      assertSame(failure, observed.getCause());
      assertEquals(0, failure.getSuppressed().length);
    }
    assertTrue(failed.isCompletedExceptionally());
    assertFalse(failed.isCancelled());
    assertEquals(7, CHUNK.getVoxelTypeId(0, 0, 0));
  }

  @Test
  void conversionAndObserverRegistrationErrorsBecomeDeliveryFailures() {
    var failure = new AssertionError("stage-error");
    for (boolean conversion : new boolean[] {true, false}) {
      var stage = hostile(failure, conversion);
      assertSame(
          failure,
          assertThrows(
                  CompletionException.class,
                  () ->
                      ConsumerAcknowledgement.deliver(
                          chunk -> stage, CHUNK, new CancellationToken()))
              .getCause());
    }
    assertEquals(0, failure.getSuppressed().length);
  }

  @Test
  void hostFutureReadsAreAvoidedAndPendingStatusFaultsUseTheDeliveryBoundary() {
    var failure = new AssertionError("future-read-error");
    var timed =
        new CompletableFuture<Void>() {
          @Override
          public Void get(long timeout, TimeUnit unit) {
            throw failure;
          }
        };
    var joined =
        new CompletableFuture<Void>() {
          @Override
          public Void join() {
            throw failure;
          }
        };
    var polled =
        new CompletableFuture<Void>() {
          @Override
          public boolean isDone() {
            throw failure;
          }
        };
    for (CompletableFuture<Void> stage : List.of(timed, joined, polled)) {
      stage.complete(null);
      assertDoesNotThrow(() -> ConsumerAcknowledgement.deliver(chunk -> stage, CHUNK, new CancellationToken()));
      assertFalse(stage.isCancelled());
    }
    var pending = new CompletableFuture<Void>() {
      @Override public boolean isDone() { throw failure; }
    };
    assertSame(failure, assertThrows(CompletionException.class,
        () -> ConsumerAcknowledgement.deliver(chunk -> pending, CHUNK, new CancellationToken())).getCause());
    assertFalse(pending.isCancelled());
    pending.complete(null);
  }

  @Test
  void asynchronouslyFailedAcknowledgementPreservesItsErrorAndHostOwnership() throws Exception {
    var entered = new CountDownLatch(1);
    var failure = new AssertionError("delayed-error");
    var host =
        new CompletableFuture<Void>() {
          @Override
          public <U> CompletableFuture<U> handle(java.util.function.BiFunction<? super Void, Throwable, ? extends U> handler) {
            var result = super.handle(handler);
            entered.countDown();
            return result;
          }
        };
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var delivery =
          tasks.submit(
              () -> {
                try {
                  ConsumerAcknowledgement.deliver(chunk -> host, CHUNK, new CancellationToken());
                  return null;
                } catch (CompletionException observed) {
                  return observed.getCause();
                }
              });
      try {
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        assertFalse(host.isDone());
        host.completeExceptionally(failure);
        assertSame(failure, delivery.get(3, TimeUnit.SECONDS));
        assertFalse(host.isCancelled());
      } finally {
        host.complete(null);
      }
    }
  }

  @Test
  void observationRegistrationFaultDoesNotRewriteAnAlreadySuccessfulHostStage() {
    var failure = new AssertionError("after-register");
    var host = CompletableFuture.<Void>completedFuture(null);
    @SuppressWarnings("unchecked")
    var stage =
        (CompletionStage<Void>)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {CompletionStage.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("toCompletableFuture"))
                    throw new UnsupportedOperationException();
                  if (method.getName().equals("handle")) {
                    try {
                      method.invoke(host, args);
                    } catch (InvocationTargetException wrapped) {
                      throw wrapped.getCause();
                    }
                    throw failure;
                  }
                  throw new AssertionError("Unexpected host method " + method.getName());
                });
    assertSame(
        failure,
        assertThrows(
                CompletionException.class,
                () ->
                    ConsumerAcknowledgement.deliver(chunk -> stage, CHUNK, new CancellationToken()))
            .getCause());
    assertNull(host.join());
    assertFalse(host.isCompletedExceptionally());
  }
}
