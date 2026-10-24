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
    if (width < 0
        || height < 0
        || depth < 0
        || (long) width * height * depth > destination.length
        || !Double.isFinite(step))
      throw new IllegalArgumentException("Grid dimensions/step/destination");
    for (int y = 0; y < height; y++)
      for (int z = 0; z < depth; z++)
        for (int x = 0; x < width; x++)
          destination[(y * depth + z) * width + x] =
              sample(ox + x * step, oy + y * step, oz + z * step);
  }
}
