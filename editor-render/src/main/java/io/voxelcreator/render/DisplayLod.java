package io.voxelcreator.render;

import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Lossy render-only aggregation. Exact one-cell boundary shells keep every chunk seam on LOD0 data.
 */
public final class DisplayLod {
  private final SceneChunk exact;
  private final int level, factor, side;
  private final int[] coarse;

  public DisplayLod(SceneChunk exact, int level) {
    if (level < 0 || level > Integer.numberOfTrailingZeros(exact.size()))
      throw new IllegalArgumentException("LOD level");
    this.exact = exact;
    this.level = level;
    factor = 1 << level;
    side = exact.size() / factor;
    coarse = level == 0 ? null : new int[side * side * side];
    if (coarse != null) build();
  }

  private void build() {
    if (exact.uniformType() >= 0) {
      Arrays.fill(coarse, exact.uniformType());
      return;
    }
    for (int z = 0; z < side; z++) {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      for (int y = 0; y < side; y++)
        for (int x = 0; x < side; x++) {
          var counts = new HashMap<Integer, Integer>();
          int occupied = 0;
          for (int dz = 0; dz < factor; dz++)
            for (int dy = 0; dy < factor; dy++)
              for (int dx = 0; dx < factor; dx++) {
                int type = exact.get(x * factor + dx, y * factor + dy, z * factor + dz);
                if (type > 0) {
                  occupied++;
                  counts.merge(type, 1, Integer::sum);
                }
              }
          int type = 0, best = 0;
          if (occupied * 2 >= factor * factor * factor)
            for (var e : counts.entrySet())
              if (e.getValue() > best || e.getValue() == best && e.getKey() < type) {
                type = e.getKey();
                best = e.getValue();
              }
          coarse[(z * side + y) * side + x] = type;
        }
    }
  }

  public int get(int x, int y, int z) {
    int n = exact.size();
    if (x < 0 || y < 0 || z < 0 || x >= n || y >= n || z >= n) return 0;
    if (coarse == null || x == 0 || y == 0 || z == 0 || x == n - 1 || y == n - 1 || z == n - 1)
      return exact.get(x, y, z);
    return coarse[((z / factor) * side + y / factor) * side + x / factor];
  }

  public int level() {
    return level;
  }

  public int factor() {
    return factor;
  }

  public long bytes() {
    return coarse == null ? 0 : 4L * coarse.length;
  }
}
