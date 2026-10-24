package io.voxelcreator.api;

public record CacheStats(long hits, long misses, long chunks, long bytes, long pinnedChunks) {}
