package io.voxelcreator.storage;

public record RegionBounds(BlockPos min, BlockPos max) {
  public RegionBounds {
    if (min == null || max == null || min.x() > max.x() || min.y() > max.y() || min.z() > max.z())
      throw new IllegalArgumentException("Inclusive bounds require min <= max");
  }

  public java.util.Optional<RegionBounds> clipY(int minY, int maxY) {
    int lo = Math.max(min.y(), minY), hi = Math.min(max.y(), maxY);
    return lo > hi
        ? java.util.Optional.empty()
        : java.util.Optional.of(
            new RegionBounds(
                new BlockPos(min.x(), lo, min.z()), new BlockPos(max.x(), hi, max.z())));
  }

  public long chunkCount(int size) {
    VoxelChunk.validateSize(size);
    long x = (long) Math.floorDiv(max.x(), size) - Math.floorDiv(min.x(), size) + 1,
        y = (long) Math.floorDiv(max.y(), size) - Math.floorDiv(min.y(), size) + 1,
        z = (long) Math.floorDiv(max.z(), size) - Math.floorDiv(min.z(), size) + 1;
    try {
      return Math.multiplyExact(Math.multiplyExact(x, y), z);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "REGION_TOO_LARGE: chunk count exceeds signed 64-bit capacity", overflow);
    }
  }

  public boolean contains(BlockPos p) {
    return p.x() >= min.x()
        && p.x() <= max.x()
        && p.y() >= min.y()
        && p.y() <= max.y()
        && p.z() >= min.z()
        && p.z() <= max.z();
  }
}
