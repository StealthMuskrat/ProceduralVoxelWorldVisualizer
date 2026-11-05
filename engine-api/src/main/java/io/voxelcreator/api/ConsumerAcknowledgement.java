package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;
import java.util.concurrent.*;

/** Observes host-owned delivery stages without completing or cancelling them. */
final class ConsumerAcknowledgement {
  private ConsumerAcknowledgement() {}

  /** Give synchronous host faults the same delivery-policy path as failed stages. */
  static void deliver(ChunkConsumer consumer, VoxelChunk chunk, CancellationToken token) {
    try {
      await(consumer.accept(chunk), token);
    } catch (Throwable failure) {
      // Keep cancellation/runtime control flow intact. Errors (or sneaky checked host faults)
      // otherwise bypass the per-chunk Exception handler, unlike a failed acknowledgement.
      // An explicit wrapper message avoids invoking host toString/getMessage during construction.
      throw failure instanceof RuntimeException runtime
          ? runtime
          : new CompletionException(null, failure);
    }
  }

  static void await(CompletionStage<Void> stage, CancellationToken token) {
    var observation = observe(stage);
    var acknowledgement = observation.outcome;
    boolean refreshed = false;
    while (!acknowledgement.isDone()) {
      token.throwIfCancelled();
      // A later host callback can block the completing thread before our first handler runs.
      // A completed converted future permits one immediate reobservation on this coordinator.
      if (!refreshed && observation.source != null && observation.source.isDone()) {
        refreshed = true;
        register(observation.source, acknowledgement);
      }
      try {
        acknowledgement.get(50, TimeUnit.MILLISECONDS);
      } catch (TimeoutException ignored) {
      } catch (ExecutionException failed) {
        // Only engine-owned normal outcome values are published to this private future.
        break;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CancellationException("Region delivery wait interrupted");
      }
    }
    var failure = acknowledgement.join().failure;
    if (failure instanceof CancellationException cancelled) throw cancelled;
    if (failure instanceof CompletionException completed) throw completed;
    if (failure != null) throw new CompletionException(null, failure);
  }

  private record Outcome(Throwable failure) {}
  private record Observation(CompletableFuture<Void> source, CompletableFuture<Outcome> outcome) {}

  private static Observation observe(CompletionStage<Void> stage) {
    if (stage == null) throw new IllegalStateException("ChunkConsumer returned null stage");
    CompletableFuture<Void> source;
    try {
      source = stage.toCompletableFuture();
      if (source == null)
        throw new IllegalStateException("ChunkConsumer stage returned null future");
    } catch (UnsupportedOperationException unsupported) {
      source = null;
    }
    var outcome = new CompletableFuture<Outcome>();
    register(source == null ? stage : source, outcome);
    return new Observation(source, outcome);
  }

  private static void register(CompletionStage<Void> source, CompletableFuture<Outcome> outcome) {
    // handle maps failures to normal values: neither host get/join nor a dependent failed-stage
    // wrapper may call an arbitrary Throwable's text methods before the delivery policy sees it.
    var registered = source.handle((value, failure) -> {
      outcome.complete(new Outcome(failure));
      return null;
    });
    if (registered == null)
      throw new IllegalStateException("ChunkConsumer stage returned null observation");
  }
}
