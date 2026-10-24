package io.voxelcreator.api;

public record EngineMetrics(
    int workers,
    int active,
    int queued,
    long completed,
    long generationNanos,
    long cacheBytes,
    long stagingBytes,
    long memoryBudget) {}
