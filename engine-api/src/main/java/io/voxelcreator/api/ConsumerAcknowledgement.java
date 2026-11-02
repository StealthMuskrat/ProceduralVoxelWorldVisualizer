package io.voxelcreator.api;

import java.util.concurrent.*;

/** Observes host-owned delivery stages without completing or cancelling them. */
final class ConsumerAcknowledgement {
  private ConsumerAcknowledgement() {}

  static void await(CompletionStage<Void> stage, CancellationToken token) {
    var acknowledgement = observe(stage);
    while (!acknowledgement.isDone()) {
      token.throwIfCancelled();
      try {
        acknowledgement.get(50, TimeUnit.MILLISECONDS);
      } catch (TimeoutException ignored) {
      } catch (ExecutionException failed) {
        // Join below gives the same cause path for an already failed or asynchronously failed ack.
        break;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Region delivery wait interrupted");
      }
    }
    acknowledgement.join();
  }

  private static CompletableFuture<Void> observe(CompletionStage<Void> stage) {
    if (stage == null) throw new IllegalStateException("ChunkConsumer returned null stage");
    try {
      var future = stage.toCompletableFuture();
      if (future == null)
        throw new IllegalStateException("ChunkConsumer stage returned null future");
      return future;
    } catch (UnsupportedOperationException unsupported) {
      // CompletionStage need not support CompletableFuture conversion. Observe completion rather
      // than requiring a particular host stage implementation.
      var observed = new CompletableFuture<Void>();
      stage.whenComplete(
          (value, failure) -> {
            if (failure == null) observed.complete(null);
            else observed.completeExceptionally(failure);
          });
      return observed;
    }
  }
}
