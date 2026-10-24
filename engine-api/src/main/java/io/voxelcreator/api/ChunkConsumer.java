package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ChunkConsumer {
  CompletionStage<Void> accept(VoxelChunk completed);
}
