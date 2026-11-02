package io.voxelcreator.api;

@FunctionalInterface
public interface NoiseSampler3D {
  double sample(double x, double y, double z);

  default void sampleGrid(
      double ox,
      double oy,
      double oz,
      int width,
      int height,
      int depth,
      double step,
      double[] destination) {
    sampleGrid(
        ox,
        oy,
        oz,
        width,
        height,
        depth,
        step,
        step,
        step,
        destination,
        0,
        width,
        (long) width * depth,
        new CancellationToken());
  }

  /**
   * X-fast Z rows in Y planes: index = offset + y*planeStride + z*rowStride + x. Each axis passes
   * its origin unchanged at index zero and at every index when its step is either signed zero,
   * preserving origin bits. Other coordinates use origin + index * step, with separate
   * multiplication and addition. Counts, addresses and coordinates preflight before writes; padding
   * is preserved.
   */
  default void sampleGrid(
      double ox,
      double oy,
      double oz,
      int width,
      int height,
      int depth,
      double stepX,
      double stepY,
      double stepZ,
      double[] destination,
      int offset,
      long rowStride,
      long planeStride,
      CancellationToken token) {
    SamplingBatches.grid3(
        this,
        ox,
        oy,
        oz,
        width,
        height,
        depth,
        stepX,
        stepY,
        stepZ,
        destination,
        offset,
        rowStride,
        planeStride,
        token);
  }

  default void samplePoints(
      double[] xs,
      double[] ys,
      double[] zs,
      int sourceOffset,
      int count,
      double[] destination,
      int destinationOffset,
      CancellationToken token) {
    SamplingBatches.points3(
        this, xs, ys, zs, sourceOffset, count, destination, destinationOffset, token);
  }
}
