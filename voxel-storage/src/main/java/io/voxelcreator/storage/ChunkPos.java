package io.voxelcreator.storage;

public record ChunkPos(int x, int y, int z) implements Comparable<ChunkPos> {
  public static ChunkPos fromBlock(BlockPos p, int size) {
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
