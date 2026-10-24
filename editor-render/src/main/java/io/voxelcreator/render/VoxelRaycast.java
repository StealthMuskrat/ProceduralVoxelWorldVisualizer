package io.voxelcreator.render;

import io.voxelcreator.storage.BlockPos;
import java.util.Optional;
import org.joml.Vector3d;

/** Exact Amanatides-Woo voxel traversal. Edge/corner ties advance all tied axes. */
public final class VoxelRaycast {
  @FunctionalInterface
  public interface Cells {
    int type(int x, int y, int z);
  }

  public static Optional<BlockPos> firstOccupied(Camera.Ray ray, double maxDistance, Cells cells) {
    Vector3d o = ray.origin(), d = new Vector3d(ray.direction());
    if (!Double.isFinite(maxDistance)
        || maxDistance < 0
        || !o.isFinite()
        || !d.isFinite()
        || d.lengthSquared() == 0)
      throw new IllegalArgumentException("Finite ray and nonnegative distance required");
    d.normalize();
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
