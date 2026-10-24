package io.voxelcreator.render;

import java.util.*;

/**
 * Display-only greedy surface extraction. Internal occupied faces are hidden across type
 * boundaries.
 */
public final class MeshBuilder {
  @FunctionalInterface
  public interface Cells {
    int get(int x, int y, int z);
  }

  private float[] vertices = new float[4096];
  private int count = 0;

  private void vertex(float x, float y, float z, float nx, float ny, float nz, int type) {
    if (count + 7 > vertices.length) vertices = Arrays.copyOf(vertices, vertices.length * 2);
    vertices[count++] = x;
    vertices[count++] = y;
    vertices[count++] = z;
    vertices[count++] = nx;
    vertices[count++] = ny;
    vertices[count++] = nz;
    vertices[count++] = type;
  }

  public static Mesh greedy(int size, Cells cells) {
    if (size < 1 || size > 256) throw new IllegalArgumentException("Preview size 1..256");
    var mesh = new MeshBuilder();
    int[] mask = new int[size * size];
    int[] pos = new int[3], du = new int[3], dv = new int[3];
    for (int d = 0; d < 3; d++) {
      int u = (d + 1) % 3, v = (d + 2) % 3;
      for (int slice = 0; slice <= size; slice++) {
        if (Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException();
        for (int j = 0; j < size; j++)
          for (int i = 0; i < size; i++) {
            pos[d] = slice;
            pos[u] = i;
            pos[v] = j;
            int b = slice == size ? 0 : cells.get(pos[0], pos[1], pos[2]);
            pos[d] = slice - 1;
            int a = slice == 0 ? 0 : cells.get(pos[0], pos[1], pos[2]);
            mask[j * size + i] = a > 0 && b == 0 ? a : b > 0 && a == 0 ? -b : 0;
          }
        for (int j = 0; j < size; j++)
          for (int i = 0; i < size; ) {
            int material = mask[j * size + i];
            if (material == 0) {
              i++;
              continue;
            }
            int w = 1;
            while (i + w < size && mask[j * size + i + w] == material) w++;
            int h = 1;
            outer:
            while (j + h < size) {
              for (int k = 0; k < w; k++) if (mask[(j + h) * size + i + k] != material) break outer;
              h++;
            }
            Arrays.fill(pos, 0);
            Arrays.fill(du, 0);
            Arrays.fill(dv, 0);
            pos[d] = slice;
            pos[u] = i;
            pos[v] = j;
            du[u] = w;
            dv[v] = h;
            float nx = d == 0 ? Math.signum(material) : 0,
                ny = d == 1 ? Math.signum(material) : 0,
                nz = d == 2 ? Math.signum(material) : 0;
            int type = Math.abs(material);
            mesh.quad(pos, du, dv, nx, ny, nz, type, material < 0);
            for (int y = 0; y < h; y++)
              Arrays.fill(mask, (j + y) * size + i, (j + y) * size + i + w, 0);
            i += w;
          }
      }
    }
    return new Mesh(Arrays.copyOf(mesh.vertices, mesh.count), false);
  }

  private void quad(
      int[] p, int[] u, int[] v, float nx, float ny, float nz, int type, boolean reverse) {
    int[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    int[] order = reverse ? new int[] {0, 2, 1, 0, 3, 2} : new int[] {0, 1, 2, 0, 2, 3};
    for (int c : order) {
      int a = corners[c][0], b = corners[c][1];
      vertex(
          p[0] + a * u[0] + b * v[0],
          p[1] + a * u[1] + b * v[1],
          p[2] + a * u[2] + b * v[2],
          nx,
          ny,
          nz,
          type);
    }
  }

  public static Mesh points(int size, Cells cells) {
    var b = new MeshBuilder();
    for (int y = 0; y < size; y++)
      for (int z = 0; z < size; z++)
        for (int x = 0; x < size; x++) {
          int t = cells.get(x, y, z);
          if (t > 0) b.vertex(x + .5f, y + .5f, z + .5f, 0, 1, 0, t);
        }
    return new Mesh(Arrays.copyOf(b.vertices, b.count), true);
  }

  @FunctionalInterface
  public interface Scalar {
    double sample(double x, double y, double z);
  }

  private static final int[][] CORNERS = {
    {0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
  };
  private static final int[][] TETS = {
    {0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6}, {0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}
  };

  /** Marching tetrahedra with a fixed six-tetrahedron decomposition shared by neighboring cubes. */
  public static Mesh isosurface(int size, Scalar sample, double threshold) {
    var b = new MeshBuilder();
    double[][] values = new double[size + 1][(size + 1) * (size + 1)];
    for (int y = 0; y <= size; y++)
      for (int z = 0; z <= size; z++)
        for (int x = 0; x <= size; x++) values[y][z * (size + 1) + x] = sample.sample(x, y, z);
    for (int y = 0; y < size; y++)
      for (int z = 0; z < size; z++)
        for (int x = 0; x < size; x++)
          for (var tet : TETS) {
            var inside = new ArrayList<Integer>();
            var outside = new ArrayList<Integer>();
            for (int i : tet) {
              int[] c = CORNERS[i];
              (values[y + c[1]][(z + c[2]) * (size + 1) + x + c[0]] >= threshold ? inside : outside)
                  .add(i);
            }
            if (inside.isEmpty() || outside.isEmpty()) continue;
            var pts = new ArrayList<float[]>();
            if (inside.size() == 1 || inside.size() == 3) {
              var one = inside.size() == 1 ? inside : outside;
              var many = inside.size() == 1 ? outside : inside;
              for (int v : many) pts.add(edge(x, y, z, one.getFirst(), v, values, size, threshold));
              b.triangle(pts.get(0), pts.get(1), pts.get(2), 128);
            } else {
              int a = inside.get(0), c = inside.get(1), d = outside.get(0), e = outside.get(1);
              float[] p0 = edge(x, y, z, a, d, values, size, threshold),
                  p1 = edge(x, y, z, a, e, values, size, threshold),
                  p2 = edge(x, y, z, c, d, values, size, threshold),
                  p3 = edge(x, y, z, c, e, values, size, threshold);
              b.triangle(p0, p1, p2, 128);
              b.triangle(p2, p1, p3, 128);
            }
          }
    return new Mesh(Arrays.copyOf(b.vertices, b.count), false);
  }

  private static float[] edge(
      int x, int y, int z, int a, int b, double[][] values, int n, double threshold) {
    int[] p = CORNERS[a], q = CORNERS[b];
    double va = values[y + p[1]][(z + p[2]) * (n + 1) + x + p[0]],
        vb = values[y + q[1]][(z + q[2]) * (n + 1) + x + q[0]],
        t = va == vb ? .5 : (threshold - va) / (vb - va);
    return new float[] {
      (float) (x + p[0] + t * (q[0] - p[0])),
      (float) (y + p[1] + t * (q[1] - p[1])),
      (float) (z + p[2] + t * (q[2] - p[2]))
    };
  }

  private void triangle(float[] a, float[] b, float[] c, int type) {
    float ux = b[0] - a[0],
        uy = b[1] - a[1],
        uz = b[2] - a[2],
        vx = c[0] - a[0],
        vy = c[1] - a[1],
        vz = c[2] - a[2],
        nx = uy * vz - uz * vy,
        ny = uz * vx - ux * vz,
        nz = ux * vy - uy * vx,
        len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (len == 0) return;
    for (float[] p : List.of(a, b, c)) vertex(p[0], p[1], p[2], nx / len, ny / len, nz / len, type);
  }
}
