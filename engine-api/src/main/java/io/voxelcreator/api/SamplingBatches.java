package io.voxelcreator.api;

import java.util.*;
import java.util.concurrent.CancellationException;

/** Shared primitive traversal and preflight validation; does not allocate per sample. */
final class SamplingBatches {
  private SamplingBatches() {}

  private static void axis(double origin, double step, int count) {
    if (!Double.isFinite(origin)
        || !Double.isFinite(step)
        || count > 0 && !Double.isFinite(origin + (count - 1) * step))
      throw new IllegalArgumentException(
          "Finite sampling origin, step and final coordinate required");
  }

  private static void layout(
      int width, int rows, int planes, long rowStride, long planeStride, int offset, int length) {
    if (width < 0
        || rows < 0
        || planes < 0
        || rowStride < 0
        || planeStride < 0
        || offset < 0
        || offset > length) throw new IllegalArgumentException("Grid dimensions/strides/offset");
    if (width == 0 || rows == 0 || planes == 0) return;
    try {
      long count = Math.multiplyExact(Math.multiplyExact((long) width, rows), planes);
      long rowExtent = Math.addExact(Math.multiplyExact((long) rows - 1, rowStride), width);
      long extent = Math.addExact(Math.multiplyExact((long) planes - 1, planeStride), rowExtent);
      if (rowStride < width
          || planes > 1 && planeStride < rowExtent
          || count > length - offset
          || extent > length - offset)
        throw new IllegalArgumentException("Grid destination capacity or overlapping strides");
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException("Grid count/address overflow", overflow);
    }
  }

  private static double finite(double value) {
    if (!Double.isFinite(value)) throw new ArithmeticException("Nonfinite sampler result");
    return value;
  }

  static void grid2(
      NoiseSampler2D sampler,
      double ox,
      double oz,
      int width,
      int depth,
      double sx,
      double sz,
      double[] destination,
      int offset,
      long rowStride,
      CancellationToken token) {
    Objects.requireNonNull(destination);
    Objects.requireNonNull(token);
    layout(width, depth, 1, rowStride, 0, offset, destination.length);
    axis(ox, sx, width);
    axis(oz, sz, depth);
    token.throwIfCancelled();
    if (width == 0 || depth == 0) return;
    long index = 0;
    for (int z = 0; z < depth; z++) {
      token.throwIfCancelled();
      double wz = oz + z * sz;
      long row = offset + z * rowStride;
      for (int x = 0; x < width; x++, index++) {
        if ((index & 255) == 0) token.throwIfCancelled();
        double wx = ox + x * sx;
        try {
          destination[(int) (row + x)] = finite(sampler.sample(wx, wz));
        } catch (RuntimeException failure) {
          if (failure instanceof CancellationException) throw failure;
          throw new BatchSamplingException(index, wx, 0, wz, failure);
        }
      }
    }
    token.throwIfCancelled();
  }

  static void grid3(
      NoiseSampler3D sampler,
      double ox,
      double oy,
      double oz,
      int width,
      int height,
      int depth,
      double sx,
      double sy,
      double sz,
      double[] destination,
      int offset,
      long rowStride,
      long planeStride,
      CancellationToken token) {
    Objects.requireNonNull(destination);
    Objects.requireNonNull(token);
    layout(width, depth, height, rowStride, planeStride, offset, destination.length);
    axis(ox, sx, width);
    axis(oy, sy, height);
    axis(oz, sz, depth);
    token.throwIfCancelled();
    if (width == 0 || height == 0 || depth == 0) return;
    long index = 0;
    for (int y = 0; y < height; y++)
      for (int z = 0; z < depth; z++) {
        token.throwIfCancelled();
        double wy = oy + y * sy, wz = oz + z * sz;
        long row = offset + y * planeStride + z * rowStride;
        for (int x = 0; x < width; x++, index++) {
          if ((index & 255) == 0) token.throwIfCancelled();
          double wx = ox + x * sx;
          try {
            destination[(int) (row + x)] = finite(sampler.sample(wx, wy, wz));
          } catch (RuntimeException failure) {
            if (failure instanceof CancellationException) throw failure;
            throw new BatchSamplingException(index, wx, wy, wz, failure);
          }
        }
      }
    token.throwIfCancelled();
  }

  private static void points(
      int count,
      int sourceOffset,
      int destinationOffset,
      double[] destination,
      double[]... coordinates) {
    Objects.requireNonNull(destination);
    if (count < 0
        || sourceOffset < 0
        || destinationOffset < 0
        || destinationOffset > destination.length
        || count > destination.length - destinationOffset)
      throw new IllegalArgumentException("Point count/destination offset/capacity");
    for (var array : coordinates) {
      Objects.requireNonNull(array);
      if (sourceOffset > array.length || count > array.length - sourceOffset)
        throw new IllegalArgumentException("Coordinate array capacity");
      if (count > 0
          && array == destination
          && (long) sourceOffset + count > destinationOffset
          && (long) destinationOffset + count > sourceOffset)
        throw new IllegalArgumentException("Coordinate/destination ranges overlap");
    }
  }

  static void points2(
      NoiseSampler2D sampler,
      double[] xs,
      double[] zs,
      int sourceOffset,
      int count,
      double[] destination,
      int destinationOffset,
      CancellationToken token) {
    Objects.requireNonNull(token);
    points(count, sourceOffset, destinationOffset, destination, xs, zs);
    for (int i = 0; i < count; i++) {
      if ((i & 255) == 0) token.throwIfCancelled();
      if (!Double.isFinite(xs[sourceOffset + i]) || !Double.isFinite(zs[sourceOffset + i]))
        throw new IllegalArgumentException("Nonfinite point coordinates at " + i);
    }
    token.throwIfCancelled();
    for (int i = 0; i < count; i++) {
      if ((i & 255) == 0) token.throwIfCancelled();
      double x = xs[sourceOffset + i], z = zs[sourceOffset + i];
      try {
        destination[destinationOffset + i] = finite(sampler.sample(x, z));
      } catch (RuntimeException failure) {
        if (failure instanceof CancellationException) throw failure;
        throw new BatchSamplingException(i, x, 0, z, failure);
      }
    }
    token.throwIfCancelled();
  }

  static void points3(
      NoiseSampler3D sampler,
      double[] xs,
      double[] ys,
      double[] zs,
      int sourceOffset,
      int count,
      double[] destination,
      int destinationOffset,
      CancellationToken token) {
    Objects.requireNonNull(token);
    points(count, sourceOffset, destinationOffset, destination, xs, ys, zs);
    for (int i = 0; i < count; i++) {
      if ((i & 255) == 0) token.throwIfCancelled();
      if (!Double.isFinite(xs[sourceOffset + i])
          || !Double.isFinite(ys[sourceOffset + i])
          || !Double.isFinite(zs[sourceOffset + i]))
        throw new IllegalArgumentException("Nonfinite point coordinates at " + i);
    }
    token.throwIfCancelled();
    for (int i = 0; i < count; i++) {
      if ((i & 255) == 0) token.throwIfCancelled();
      double x = xs[sourceOffset + i], y = ys[sourceOffset + i], z = zs[sourceOffset + i];
      try {
        destination[destinationOffset + i] = finite(sampler.sample(x, y, z));
      } catch (RuntimeException failure) {
        if (failure instanceof CancellationException) throw failure;
        throw new BatchSamplingException(i, x, y, z, failure);
      }
    }
    token.throwIfCancelled();
  }
}
