package io.voxelcreator.render;

/** Cell counts of a display grid; each axis is independently bounded before allocation. */
public record GridExtent(int width, int height, int depth) {
  public GridExtent {
    if (width < 1 || width > 256 || height < 1 || height > 256 || depth < 1 || depth > 256)
      throw new IllegalArgumentException("Preview axes must be 1..256 cells");
  }

  public static GridExtent cube(int side) {
    return new GridExtent(side, side, side);
  }

  public int cells() {
    return width * height * depth;
  }

  public long corners() {
    return (width + 1L) * (height + 1L) * (depth + 1L);
  }

  public int maximum() {
    return Math.max(width, Math.max(height, depth));
  }
}
