package io.voxelcreator.render;

import io.voxelcreator.storage.BlockPos;
import java.util.Objects;
import java.util.Optional;
import org.joml.Vector3d;

/** Exact Amanatides-Woo voxel traversal. Edge/corner ties advance all tied axes. */
public final class VoxelRaycast {
  @FunctionalInterface
  public interface Cells {
    int type(int x, int y, int z);
  }

  /** Inclusive integer cell bounds of a displayed fixed preview. */
  public record Bounds(BlockPos min, BlockPos max) {
    public Bounds {
      Objects.requireNonNull(min);
      Objects.requireNonNull(max);
      if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
        throw new IllegalArgumentException("Ordered picking bounds required");
    }

    public static Bounds cube(Vector3d origin, int side) {
      if (origin == null || !origin.isFinite() || side < 1 || side > 256)
        throw new IllegalArgumentException("Invalid fixed picking region");
      double[] a = {origin.x, origin.y, origin.z};
      int[] lo = new int[3], hi = new int[3];
      for (int i = 0; i < 3; i++) {
        if (a[i] != Math.rint(a[i])
            || a[i] < Integer.MIN_VALUE
            || a[i] > Integer.MAX_VALUE
            || a[i] + side - 1 > Integer.MAX_VALUE)
          throw new IllegalArgumentException("Fixed picking region exceeds block coordinates");
        lo[i] = (int) a[i];
        hi[i] = (int) ((long) lo[i] + side - 1);
      }
      return new Bounds(new BlockPos(lo[0], lo[1], lo[2]), new BlockPos(hi[0], hi[1], hi[2]));
    }

    public boolean contains(int x, int y, int z) {
      return x >= min.x()
          && x <= max.x()
          && y >= min.y()
          && y <= max.y()
          && z >= min.z()
          && z <= max.z();
    }

    public Cells restrict(Cells cells) {
      Objects.requireNonNull(cells);
      return (x, y, z) -> contains(x, y, z) ? cells.type(x, y, z) : 0;
    }
  }

  public static Optional<BlockPos> firstOccupied(Camera.Ray ray, double maxDistance, Cells cells) {
    Vector3d o = ray.origin(), d = new Vector3d(ray.direction());
    if (!Double.isFinite(maxDistance)
        || maxDistance < 0
        || !o.isFinite()
        || !d.isFinite()
        || d.x == 0 && d.y == 0 && d.z == 0)
      throw new IllegalArgumentException("Finite ray and nonnegative distance required");
    Objects.requireNonNull(cells);
    double scale = Math.max(Math.abs(d.x), Math.max(Math.abs(d.y), Math.abs(d.z)));
    d.set(d.x / scale, d.y / scale, d.z / scale).normalize();
    if (Math.floor(o.x) < Integer.MIN_VALUE
        || Math.floor(o.x) > Integer.MAX_VALUE
        || Math.floor(o.y) < Integer.MIN_VALUE
        || Math.floor(o.y) > Integer.MAX_VALUE
        || Math.floor(o.z) < Integer.MIN_VALUE
        || Math.floor(o.z) > Integer.MAX_VALUE)
      throw new IllegalArgumentException("Ray origin exceeds block coordinates");
    int x = (int) Math.floor(o.x), y = (int) Math.floor(o.y), z = (int) Math.floor(o.z);
    int sx = Double.compare(d.x, 0), sy = Double.compare(d.y, 0), sz = Double.compare(d.z, 0);
    double dx = d.x == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d.x),
        dy = d.y == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d.y),
        dz = d.z == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d.z);
    double tx = d.x == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1.0 : x) - o.x) / d.x,
        ty = d.y == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1.0 : y) - o.y) / d.y,
        tz = d.z == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1.0 : z) - o.z) / d.z;
    double travelled = 0;
    while (travelled <= maxDistance) {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      if (cells.type(x, y, z) > 0) return Optional.of(new BlockPos(x, y, z));
      double next = Math.min(tx, Math.min(ty, tz));
      if (next == Double.POSITIVE_INFINITY || next > maxDistance) break;
      if (tx == next && (sx > 0 && x == Integer.MAX_VALUE || sx < 0 && x == Integer.MIN_VALUE)
          || ty == next && (sy > 0 && y == Integer.MAX_VALUE || sy < 0 && y == Integer.MIN_VALUE)
          || tz == next && (sz > 0 && z == Integer.MAX_VALUE || sz < 0 && z == Integer.MIN_VALUE))
        break;
      if (tx == next) {
        x += sx;
        tx += dx;
      }
      if (ty == next) {
        y += sy;
        ty += dy;
      }
      if (tz == next) {
        z += sz;
        tz += dz;
      }
      travelled = next;
    }
    return Optional.empty();
  }
}
