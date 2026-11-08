package io.voxelcreator.render;

import java.util.Objects;

/** Owned input preparation before native state, retirement or display metadata is touched. */
final class ViewportUploadData {
  private ViewportUploadData() {}
  static int[] image(int width, int height, int[] rgba) {
    Objects.requireNonNull(rgba);
    if (width < 1 || height < 1 || (long) width * height != rgba.length)
      throw new IllegalArgumentException("Image dimensions/pixel count");
    return rgba.clone();
  }
  static Mesh mesh(Mesh mesh) {
    Objects.requireNonNull(mesh);
    float[] vertices = Objects.requireNonNull(mesh.vertices());
    if (vertices.length % 7 != 0)
      throw new IllegalArgumentException("Mesh requires seven floats per vertex");
    return new Mesh(vertices.clone(), mesh.points());
  }
}
