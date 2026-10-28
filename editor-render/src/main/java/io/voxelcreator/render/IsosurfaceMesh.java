package io.voxelcreator.render;

import io.voxelcreator.api.CancellationToken;
import java.util.*;

/** Bounded display-only marching tetrahedra. Positive/equal samples are inside. */
public final class IsosurfaceMesh {
  public static final long DEFAULT_MESH_BYTES = 256L << 20;
  private static final int[][] CORNERS = {
    {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
    {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
  };
  private static final int[][] TETS = {
    {0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6},
    {0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}
  };

  private IsosurfaceMesh() {}

  private static void size(int size) {
    if (size < 1 || size > 256) throw new IllegalArgumentException("Isosurface size 1..256");
  }

  /** Six tetrahedra, two triangles each, three vertices of seven floats per triangle. */
  public static long maximumMeshBytes(int size) {
    size(size);
    return 1008L * size * size * size;
  }

  /** Includes geometric growth/final trimming, the sampled corner grid and fixed scratch. */
  public static long workingBytes(int size) {
    size(size);
    long side = size + 1L;
    return 3 * maximumMeshBytes(size) + 8 * side * side * side + 65536;
  }

  public static Mesh build(
      int size,
      MeshBuilder.Scalar field,
      double threshold,
      CancellationToken token,
      long meshBytes) {
    size(size);
    Objects.requireNonNull(field);
    Objects.requireNonNull(token);
    if (!Double.isFinite(threshold) || meshBytes < 0)
      throw new IllegalArgumentException(
          "Finite isosurface threshold and nonnegative mesh budget required");
    token.throwIfCancelled();
    int side = size + 1;
    double[] samples = new double[side * side * side];
    for (int z = 0; z <= size; z++)
      for (int y = 0; y <= size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x <= size; x++) {
          double value = field.sample(x, y, z);
          if (!Double.isFinite(value))
            throw new IllegalArgumentException(
                "Nonfinite isosurface sample at " + x + "," + y + "," + z);
          samples[(z * side + y) * side + x] = value;
        }
      }
    var output = new Output(Math.min(meshBytes, maximumMeshBytes(size)));
    int[] inside = new int[4], outside = new int[4];
    double[] values = new double[8];
    float[][] points = new float[4][3];
    for (int z = 0; z < size; z++)
      for (int y = 0; y < size; y++) {
        token.throwIfCancelled();
        for (int x = 0; x < size; x++) {
          for (int i = 0; i < 8; i++) {
            var c = CORNERS[i];
            values[i] = samples[((z + c[2]) * side + y + c[1]) * side + x + c[0]];
          }
          for (var tet : TETS) {
            int ni = 0, no = 0;
            for (int corner : tet)
              if (values[corner] >= threshold) inside[ni++] = corner;
              else outside[no++] = corner;
            if (ni == 0 || no == 0) continue;
            // Every crossing triangle faces from the positive region toward the negative region.
            double dx = 0, dy = 0, dz = 0;
            for (int i = 0; i < no; i++) {
              var c = CORNERS[outside[i]];
              dx += c[0] / (double) no;
              dy += c[1] / (double) no;
              dz += c[2] / (double) no;
            }
            for (int i = 0; i < ni; i++) {
              var c = CORNERS[inside[i]];
              dx -= c[0] / (double) ni;
              dy -= c[1] / (double) ni;
              dz -= c[2] / (double) ni;
            }
            if (ni == 1 || no == 1) {
              int one = ni == 1 ? inside[0] : outside[0];
              int[] many = ni == 1 ? outside : inside;
              for (int i = 0; i < 3; i++) edge(points[i], x, y, z, one, many[i], values, threshold);
              output.triangle(points[0], points[1], points[2], dx, dy, dz);
            } else {
              edge(points[0], x, y, z, inside[0], outside[0], values, threshold);
              edge(points[1], x, y, z, inside[0], outside[1], values, threshold);
              edge(points[2], x, y, z, inside[1], outside[0], values, threshold);
              edge(points[3], x, y, z, inside[1], outside[1], values, threshold);
              output.triangle(points[0], points[1], points[2], dx, dy, dz);
              output.triangle(points[2], points[1], points[3], dx, dy, dz);
            }
          }
        }
      }
    token.throwIfCancelled();
    return output.finish();
  }

  private static void edge(
      float[] out, int x, int y, int z, int a, int b, double[] values, double threshold) {
    var p = CORNERS[a];
    var q = CORNERS[b];
    // A shared edge must interpolate from the same endpoint in every tetrahedron/cube.
    if (p[0] > q[0] || p[0] == q[0] && (p[1] > q[1] || p[1] == q[1] && p[2] > q[2])) {
      int swap = a;
      a = b;
      b = swap;
      p = CORNERS[a];
      q = CORNERS[b];
    }
    double va = values[a], vb = values[b];
    double numerator = threshold - va, denominator = vb - va;
    double t;
    if (Double.isFinite(numerator) && Double.isFinite(denominator) && denominator != 0)
      t = numerator / denominator;
    else {
      double scale = Math.max(Math.abs(threshold), Math.max(Math.abs(va), Math.abs(vb)));
      t = (threshold / scale - va / scale) / (vb / scale - va / scale);
    }
    t = Math.max(0, Math.min(1, t));
    out[0] = (float) (x + p[0] + t * (q[0] - p[0]));
    out[1] = (float) (y + p[1] + t * (q[1] - p[1]));
    out[2] = (float) (z + p[2] + t * (q[2] - p[2]));
  }

  static final class Output {
    private final int limit;
    private float[] vertices;
    private int count;

    Output(long bytes) {
      limit = (int) (Math.min(Integer.MAX_VALUE - 8L, bytes / 4) / 21 * 21);
      vertices = new float[Math.min(4095, limit)];
    }

    void triangle(float[] a, float[] b, float[] c, double dx, double dy, double dz) {
      double ux = b[0] - (double) a[0], uy = b[1] - (double) a[1], uz = b[2] - (double) a[2];
      double vx = c[0] - (double) a[0], vy = c[1] - (double) a[1], vz = c[2] - (double) a[2];
      double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      double length = Math.hypot(Math.hypot(nx, ny), nz);
      if (length == 0) return;
      if (nx * dx + ny * dy + nz * dz < 0) {
        var swap = b;
        b = c;
        c = swap;
        nx = -nx;
        ny = -ny;
        nz = -nz;
      }
      if (count > limit - 21)
        throw new IllegalStateException("Isosurface mesh exceeds output byte budget");
      if (count + 21 > vertices.length)
        vertices =
            Arrays.copyOf(
                vertices, (int) Math.min(limit, Math.max(count + 21L, 2L * vertices.length)));
      vertex(a, nx / length, ny / length, nz / length);
      vertex(b, nx / length, ny / length, nz / length);
      vertex(c, nx / length, ny / length, nz / length);
    }

    private void vertex(float[] p, double nx, double ny, double nz) {
      vertices[count++] = p[0];
      vertices[count++] = p[1];
      vertices[count++] = p[2];
      vertices[count++] = (float) nx;
      vertices[count++] = (float) ny;
      vertices[count++] = (float) nz;
      vertices[count++] = 128;
    }

    Mesh finish() {
      return new Mesh(Arrays.copyOf(vertices, count), false);
    }
  }
}
