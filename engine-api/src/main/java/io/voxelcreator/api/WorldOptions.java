package io.voxelcreator.api;

import io.voxelcreator.storage.VoxelChunk;

public record WorldOptions(
    Long seedOverride, int rootChunkSize, CachePolicy cachePolicy, long cacheBytes) {
  public enum CachePolicy {
    NO_CACHE,
    MEMORY_CACHE,
    BOUNDED_MEMORY_CACHE,
    USER_MANAGED
  }

  public WorldOptions {
    VoxelChunk.validateSize(rootChunkSize);
    if (cachePolicy == null || cacheBytes < 0)
      throw new IllegalArgumentException("Cache policy/budget");
  }

  public static WorldOptions defaults() {
    return new WorldOptions(null, 64, CachePolicy.BOUNDED_MEMORY_CACHE, 128L * 1024 * 1024);
  }
}
