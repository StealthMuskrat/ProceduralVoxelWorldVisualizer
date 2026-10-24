package io.voxelcreator.api;

public record EngineMetrics(
    int workers,
    int active,
    int queued,
    long completed,
    long generationNanos,
    long cacheBytes,
    long stagingBytes,
    long memoryBudget,
    long collectionBytes) {
  public EngineMetrics(
      int workers,
      int active,
      int queued,
      long completed,
      long generationNanos,
      long cacheBytes,
      long stagingBytes,
      long memoryBudget) {
    this(
        workers,
        active,
        queued,
        completed,
        generationNanos,
        cacheBytes,
        stagingBytes,
        memoryBudget,
        0);
  }
}
