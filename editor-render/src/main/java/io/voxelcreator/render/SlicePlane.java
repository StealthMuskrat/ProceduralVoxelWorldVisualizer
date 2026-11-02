package io.voxelcreator.render;

/** Orthogonal image-to-world coordinate mapping. Image rows advance along the second plane axis. */
public enum SlicePlane {
  X("Y/Z"),
  Y("X/Z"),
  Z("X/Y");
  private final String axes;

  SlicePlane(String axes) {
    this.axes = axes;
  }

  public String axes() {
    return axes;
  }

  public int width(GridExtent extent) {
    return this == X ? extent.height() : extent.width();
  }

  public int height(GridExtent extent) {
    return this == Z ? extent.height() : extent.depth();
  }

  /** Only the active plane is sampled: width * height cells, not a padded cube. */
  public GridExtent imageExtent(GridExtent extent) {
    return new GridExtent(width(extent), 1, height(extent));
  }

  public double x(double u, double v, double fixed) {
    return this == X ? fixed : u;
  }

  public double y(double u, double v, double fixed) {
    return this == Y ? fixed : this == X ? u : v;
  }

  public double z(double u, double v, double fixed) {
    return this == Z ? fixed : v;
  }
}
