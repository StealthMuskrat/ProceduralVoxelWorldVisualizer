package io.voxelcreator.api;

/**
 * Successful prefix samples remain written; the failed sample and remaining cells are untouched.
 * Cause text is captured once, bounded to 2,048 UTF-16 units without splitting surrogate pairs.
 * Unreadable/null/blank text uses the cause class name; the original cause is retained unchanged.
 */
public final class BatchSamplingException extends RuntimeException {
  public final long sampleIndex;
  public final double x, y, z;

  public BatchSamplingException(long index, double x, double y, double z, RuntimeException cause) {
    super("Sample " + index + " at " + x + "," + y + "," + z + ": "
        + FailureDetails.describe(java.util.Objects.requireNonNull(cause)), cause);
    sampleIndex = index;
    this.x = x;
    this.y = y;
    this.z = z;
  }
}
