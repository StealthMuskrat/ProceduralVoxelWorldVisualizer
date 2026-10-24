package io.voxelcreator.render;

import io.voxelcreator.storage.ChunkPos;
import org.joml.Vector3d;

public final class LodSelector {
  private LodSelector() {}

  public static double distance(ChunkPos pos, int size, Vector3d eye) {
    double sum = 0;
    double[] camera = {eye.x, eye.y, eye.z};
    int[] coordinate = {pos.x(), pos.y(), pos.z()};
    for (int axis = 0; axis < 3; axis++) {
      double lo = (double) coordinate[axis] * size,
          delta = Math.max(0, Math.max(lo - camera[axis], camera[axis] - (lo + size)));
      sum += delta * delta;
    }
    return StrictMath.sqrt(sum);
  }

  public static int choose(
      ChunkPos pos, int size, Vector3d eye, int previous, int start, int quality, int bias) {
    int max = Integer.numberOfTrailingZeros(size);
    double distance = distance(pos, size, eye) / size;
    int wanted = 0;
    while (wanted < max
        && distance
            >= Math.max(1, start) * StrictMath.pow(2, wanted) * StrictMath.pow(2, quality - 1))
      wanted++;
    wanted = Math.min(max, wanted + (distance >= start ? bias : 0));
    if (previous >= 0 && wanted != previous) {
      if (wanted > previous
          && distance
              < Math.max(1, start)
                  * StrictMath.pow(2, previous)
                  * StrictMath.pow(2, quality - 1)
                  * 1.15) return previous;
      if (wanted < previous
          && previous > 0
          && distance
              > Math.max(1, start)
                  * StrictMath.pow(2, previous - 1)
                  * StrictMath.pow(2, quality - 1)
                  * .85) return previous;
    }
    return wanted;
  }
}
