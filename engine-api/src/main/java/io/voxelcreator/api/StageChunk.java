package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;

/** Exact immutable snapshots surrounding one stage; changes include removal to EMPTY. */
public record StageChunk(GenerationStage stage, VoxelChunk before, VoxelChunk after) {
  public StageChunk {
    if (!before.position().equals(after.position()) || before.size() != after.size())
      throw new IllegalArgumentException("Stage snapshot coordinates must match");
  }

  public boolean changed(int x, int y, int z) {
    return before.getVoxelTypeId(x, y, z) != after.getVoxelTypeId(x, y, z);
  }
}
