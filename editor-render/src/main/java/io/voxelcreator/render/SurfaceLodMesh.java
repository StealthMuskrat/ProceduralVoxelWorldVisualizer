package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import java.util.Objects;

/** Coarse scalar interiors with the same canonical unit-triangle faces at every display level. */
public final class SurfaceLodMesh {
  private SurfaceLodMesh() {}

  private static int step(int size, int level) {
    if (size < 2
        || size > 256
        || Integer.bitCount(size) != 1
        || level < 0
        || level > Integer.numberOfTrailingZeros(size))
      throw new IllegalArgumentException("Surface LOD size/level");
    return 1 << level;
  }

  public static long maximumMeshBytes(int size, int level) {
    int step = step(size, level);
    // 6 faces * 2 unit triangles * 2 crossing triangles * 3 vertices * 7 floats * 4 bytes.
    return level == 0 ? IsosurfaceMesh.maximumMeshBytes(size) : 2016L * size * size * size / step;
  }

  public static long workingBytes(int size, int level) {
    long maximum = maximumMeshBytes(size, level), side = size + 1L;
    return 3 * maximum + 8 * side * side * side + 65536;
  }

  public static Mesh build(
      int size,
      int level,
      MeshBuilder.Scalar field,
      double threshold,
      CancellationToken token,
      long meshBytes) {
    int step = step(size, level);
    if (level == 0) return IsosurfaceMesh.build(size, field, threshold, token, meshBytes);
    Objects.requireNonNull(field);
    Objects.requireNonNull(token);
    if (!Double.isFinite(threshold) || meshBytes < 0)
      throw new IllegalArgumentException(
          "Finite surface threshold and nonnegative mesh budget required");
    token.throwIfCancelled();
    int side = size + 1;
    double[] samples = new double[side * side * side];
    for (int z = 0; z <= size; z++)
      for (int y = 0; y <= size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x <= size; x++) {
          double value = field.sample(x, y, z);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException("Nonfinite surface LOD sample");
          samples[(z * side + y) * side + x] = value;
        }
      }
    var output = new IsosurfaceMesh.Output(Math.min(meshBytes, maximumMeshBytes(size, level)));
    double[][] p = new double[4][3];
    double[] values = new double[4];
    int[] inside = new int[4], outside = new int[4];
    float[][] edges = new float[4][3];
    int[] base = new int[3];
    for (int z = 0; z < size; z += step)
      for (int y = 0; y < size; y += step)
        for (int x = 0; x < size; x += step) {
          token.throwIfCancelled();
          base[0] = x;
          base[1] = y;
          base[2] = z;
          for (int axis = 0; axis < 3; axis++) p[0][axis] = base[axis] + step / 2;
          values[0] = sample(samples, side, p[0]);
          // Coning every canonical unit face triangle to one interior grid node partitions the
          // cube.
          // Adjacent cubes/chunks keep identical face triangulation even at different coarse
          // levels.
          for (int axis = 0; axis < 3; axis++) {
            int u = (axis + 1) % 3, v = (axis + 2) % 3;
            for (int end = 0; end <= 1; end++)
              for (int j = 0; j < step; j++) {
                token.throwIfCancelled();
                for (int i = 0; i < step; i++) {
                  for (int a = 1; a <= 3; a++) {
                    p[a][axis] = base[axis] + end * step;
                    p[a][u] = base[u] + i;
                    p[a][v] = base[v] + j;
                  }
                  p[2][u]++;
                  p[3][u]++;
                  p[3][v]++;
                  for (int a = 1; a <= 3; a++) values[a] = sample(samples, side, p[a]);
                  tetrahedron(output, p, values, threshold, inside, outside, edges);
                  p[2][v]++;
                  p[3][u]--;
                  values[2] = sample(samples, side, p[2]);
                  values[3] = sample(samples, side, p[3]);
                  tetrahedron(output, p, values, threshold, inside, outside, edges);
                }
              }
          }
        }
    token.throwIfCancelled();
    return output.finish();
  }

  private static double sample(double[] samples, int side, double[] p) {
    return samples[((int) p[2] * side + (int) p[1]) * side + (int) p[0]];
  }

  private static void tetrahedron(
      IsosurfaceMesh.Output output,
      double[][] p,
      double[] values,
      double threshold,
      int[] inside,
      int[] outside,
      float[][] edges) {
    int ni = 0, no = 0;
    for (int i = 0; i < 4; i++)
      if (values[i] >= threshold) inside[ni++] = i;
      else outside[no++] = i;
    if (ni == 0 || no == 0) return;
    double dx = 0, dy = 0, dz = 0;
    for (int i = 0; i < no; i++) {
      dx += p[outside[i]][0] / no;
      dy += p[outside[i]][1] / no;
      dz += p[outside[i]][2] / no;
    }
    for (int i = 0; i < ni; i++) {
      dx -= p[inside[i]][0] / ni;
      dy -= p[inside[i]][1] / ni;
      dz -= p[inside[i]][2] / ni;
    }
    if (ni == 1 || no == 1) {
      int one = ni == 1 ? inside[0] : outside[0];
      int[] many = ni == 1 ? outside : inside;
      for (int i = 0; i < 3; i++) edge(edges[i], one, many[i], p, values, threshold);
      output.triangle(edges[0], edges[1], edges[2], dx, dy, dz);
    } else {
      edge(edges[0], inside[0], outside[0], p, values, threshold);
      edge(edges[1], inside[0], outside[1], p, values, threshold);
      edge(edges[2], inside[1], outside[0], p, values, threshold);
      edge(edges[3], inside[1], outside[1], p, values, threshold);
      output.triangle(edges[0], edges[1], edges[2], dx, dy, dz);
      output.triangle(edges[2], edges[1], edges[3], dx, dy, dz);
    }
  }

  private static void edge(
      float[] out, int a, int b, double[][] positions, double[] values, double threshold) {
    var p = positions[a];
    var q = positions[b];
    if (p[0] > q[0] || p[0] == q[0] && (p[1] > q[1] || p[1] == q[1] && p[2] > q[2])) {
      int swap = a;
      a = b;
      b = swap;
      p = positions[a];
      q = positions[b];
    }
    double va = values[a], vb = values[b], numerator = threshold - va, denominator = vb - va;
    double t;
    if (Double.isFinite(numerator) && Double.isFinite(denominator) && denominator != 0)
      t = numerator / denominator;
    else {
      double scale = Math.max(Math.abs(threshold), Math.max(Math.abs(va), Math.abs(vb)));
      t = (threshold / scale - va / scale) / (vb / scale - va / scale);
    }
    t = Math.max(0, Math.min(1, t));
    for (int axis = 0; axis < 3; axis++) out[axis] = (float) (p[axis] + t * (q[axis] - p[axis]));
  }
}
