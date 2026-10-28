package io.voxelcreator.render;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Display-only light projection and bounded depth-map admission. No native dependency. */
public final class DirectionalShadow {
  private DirectionalShadow() {}

  public record Bounds(
      double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    public Bounds {
      if (!Double.isFinite(minX)
          || !Double.isFinite(minY)
          || !Double.isFinite(minZ)
          || !Double.isFinite(maxX)
          || !Double.isFinite(maxY)
          || !Double.isFinite(maxZ)
          || minX > maxX
          || minY > maxY
          || minZ > maxZ
          || Math.max(
                  Math.max(Math.abs(minX), Math.abs(maxX)),
                  Math.max(Math.abs(minY), Math.abs(maxY)))
              > 100_000_000
          || Math.max(Math.abs(minZ), Math.abs(maxZ)) > 100_000_000
          || maxX - minX > 10_000_000
          || maxY - minY > 10_000_000
          || maxZ - minZ > 10_000_000) throw new IllegalArgumentException("Invalid shadow bounds");
    }

    public Bounds union(Bounds other) {
      return new Bounds(
          Math.min(minX, other.minX),
          Math.min(minY, other.minY),
          Math.min(minZ, other.minZ),
          Math.max(maxX, other.maxX),
          Math.max(maxY, other.maxY),
          Math.max(maxZ, other.maxZ));
    }
  }

  public record Projection(Matrix4f matrix, float bias, double worldUnitsPerTexel, Bounds bounds) {
    public Projection {
      matrix = new Matrix4f(matrix);
    }

    @Override
    public Matrix4f matrix() {
      return new Matrix4f(matrix);
    }
  }

  public static boolean validResolution(int value) {
    return value == 512 || value == 1024 || value == 2048;
  }

  /** Four bytes per depth texel is conservative accounting, not measured physical residency. */
  public static long bytes(int resolution) {
    if (resolution == 0) return 0;
    if (!validResolution(resolution))
      throw new IllegalArgumentException("Shadow resolution must be 512, 1024 or 2048");
    return 4L * resolution * resolution;
  }

  /**
   * Leave at least half of the configured GPU budget for geometry. Zero means no admissible map.
   */
  public static int resolution(int requested, long gpuBudget) {
    bytes(requested);
    int result = requested;
    while (result >= 512 && bytes(result) > gpuBudget / 2) result /= 2;
    return result >= 512 ? result : 0;
  }

  public static int arenaBytes(int gpuBudget, boolean enabled, int requested) {
    if (gpuBudget < 0) throw new IllegalArgumentException("Negative GPU budget");
    return gpuBudget - (int) (enabled ? bytes(resolution(requested, gpuBudget)) : 0);
  }

  public static Bounds bounds(Mesh mesh) {
    if (mesh.points() || mesh.vertexCount() == 0) return null;
    double x = Double.POSITIVE_INFINITY, y = x, z = x, a = -x, b = -x, c = -x;
    for (int i = 0; i < mesh.vertexCount() * 7; i += 7) {
      var v = mesh.vertices();
      if (!Float.isFinite(v[i]) || !Float.isFinite(v[i + 1]) || !Float.isFinite(v[i + 2]))
        return null;
      x = Math.min(x, v[i]);
      y = Math.min(y, v[i + 1]);
      z = Math.min(z, v[i + 2]);
      a = Math.max(a, v[i]);
      b = Math.max(b, v[i + 1]);
      c = Math.max(c, v[i + 2]);
    }
    try {
      return new Bounds(x, y, z, a, b, c);
    } catch (IllegalArgumentException outside) {
      return null;
    }
  }

  /** Fit every caster/receiver corner, with a margin for light-space texel snapping and PCF. */
  public static Projection projection(Bounds bounds, int resolution) {
    bytes(resolution);
    if (resolution == 0) throw new IllegalArgumentException("No shadow map");
    double cx = bounds.minX * .5 + bounds.maxX * .5,
        cy = bounds.minY * .5 + bounds.maxY * .5,
        cz = bounds.minZ * .5 + bounds.maxZ * .5;
    double radius =
        .5
            * Math.sqrt(
                Math.pow(bounds.maxX - bounds.minX, 2)
                    + Math.pow(bounds.maxY - bounds.minY, 2)
                    + Math.pow(bounds.maxZ - bounds.minZ, 2));
    radius = Math.max(1, radius + Math.max(1, radius * .02));
    double texel = 2 * radius / resolution, distance = 2 * radius;
    var light = new Vector3d(.4, 1, .3).normalize();
    var view =
        new Matrix4d()
            .lookAt(light.x * distance, light.y * distance, light.z * distance, 0, 0, 0, 0, 1, 0);
    var center = view.transformDirection(new Vector3d(cx, cy, cz));
    view.m30(-Math.rint(center.x / texel) * texel)
        .m31(-Math.rint(center.y / texel) * texel)
        .m32(-center.z - distance);
    var matrix =
        new Matrix4f(
            new Matrix4d()
                .ortho(-radius, radius, -radius, radius, radius * .5, radius * 3.5)
                .mul(view));
    return new Projection(matrix, 1f / resolution, texel, bounds);
  }
}
