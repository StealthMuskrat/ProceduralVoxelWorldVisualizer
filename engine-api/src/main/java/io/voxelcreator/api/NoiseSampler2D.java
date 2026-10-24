package io.voxelcreator.api;

@FunctionalInterface
public interface NoiseSampler2D {
  double sample(double x, double z);

  default void sampleGrid(
      double originX, double originZ, int width, int depth, double step, double[] destination) {
    if (width < 0
        || depth < 0
        || (long) width * depth > destination.length
        || !Double.isFinite(step))
      throw new IllegalArgumentException("Grid dimensions/step/destination");
    for (int z = 0; z < depth; z++)
      for (int x = 0; x < width; x++)
        destination[z * width + x] = sample(originX + x * step, originZ + z * step);
  }
}
