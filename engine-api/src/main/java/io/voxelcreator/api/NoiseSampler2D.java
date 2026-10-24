package io.voxelcreator.api;

@FunctionalInterface
public interface NoiseSampler2D {
  double sample(double x, double z);

  default void sampleGrid(
      double originX, double originZ, int width, int depth, double step, double[] destination) {
    sampleGrid(
        originX, originZ, width, depth, step, step, destination, 0, width, new CancellationToken());
  }

  /**
   * X-fast rows of Z; padding is preserved. Counts, addresses and coordinates preflight before
   * writes.
   */
  default void sampleGrid(
      double originX,
      double originZ,
      int width,
      int depth,
      double stepX,
      double stepZ,
      double[] destination,
      int offset,
      long rowStride,
      CancellationToken token) {
    SamplingBatches.grid2(
        this, originX, originZ, width, depth, stepX, stepZ, destination, offset, rowStride, token);
  }

  default void samplePoints(
      double[] xs,
      double[] zs,
      int sourceOffset,
      int count,
      double[] destination,
      int destinationOffset,
      CancellationToken token) {
    SamplingBatches.points2(
        this, xs, zs, sourceOffset, count, destination, destinationOffset, token);
  }
}
