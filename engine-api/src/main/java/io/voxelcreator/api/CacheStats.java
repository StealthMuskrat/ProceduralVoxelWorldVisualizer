package io.voxelcreator.api;

public record CacheStats(
    long hits,
    long misses,
    long chunks,
    long bytes,
    long pinnedChunks,
    long automaticEvictions,
    long retentionRejected) {
  public CacheStats(long hits, long misses, long chunks, long bytes, long pinnedChunks) {
    this(hits, misses, chunks, bytes, pinnedChunks, 0, 0);
  }
}
