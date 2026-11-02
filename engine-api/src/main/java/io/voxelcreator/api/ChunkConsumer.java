package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ChunkConsumer {
  /**
   * Called once per delivery on a region coordinator. Return a non-null stage acknowledging the
   * delivery; CompletableFuture conversion is optional. The engine only observes the host stage,
   * never completes or cancels it, and never retries consumer side effects automatically.
   */
  CompletionStage<Void> accept(VoxelChunk completed);
}
