package io.voxelcreator.api;

/**
 * Successful prefix samples remain written; the failed sample and remaining cells are untouched.
 */
public final class BatchSamplingException extends RuntimeException {
  public final long sampleIndex;
  public final double x, y, z;

  public BatchSamplingException(long index, double x, double y, double z, RuntimeException cause) {
    super("Sample " + index + " at " + x + "," + y + "," + z + ": " + cause.getMessage(), cause);
    sampleIndex = index;
    this.x = x;
    this.y = y;
    this.z = z;
  }
}
