package io.voxelcreator.storage;

import java.util.Objects;

public record ChunkPos(int x, int y, int z) implements Comparable<ChunkPos> {
  /**
   * Maps signed block coordinates through the supported root grid using floor division.
   *
   * @throws IllegalArgumentException if size is not 2,4,8,16,32,64,128,256 (checked first)
   * @throws NullPointerException if p is null and size is supported
   */
  public static ChunkPos fromBlock(BlockPos p, int size) {
    VoxelChunk.validateSize(size);
    Objects.requireNonNull(p);
    return new ChunkPos(
        Math.floorDiv(p.x(), size), Math.floorDiv(p.y(), size), Math.floorDiv(p.z(), size));
  }

  @Override
  public int compareTo(ChunkPos p) {
    int c = Integer.compare(x, p.x);
    if (c == 0) c = Integer.compare(y, p.y);
    return c == 0 ? Integer.compare(z, p.z) : c;
  }
}
